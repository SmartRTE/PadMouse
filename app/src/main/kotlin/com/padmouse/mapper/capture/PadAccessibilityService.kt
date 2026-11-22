package com.padmouse.mapper.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.padmouse.mapper.ProbeReceiver
import com.padmouse.mapper.core.BindingBus
import com.padmouse.mapper.core.LatencyStats
import com.padmouse.mapper.core.LivePad
import com.padmouse.mapper.core.Probe
import com.padmouse.mapper.core.SettingsStore
import com.padmouse.mapper.engine.CursorController
import com.padmouse.mapper.engine.GestureEngine
import com.padmouse.mapper.engine.MappingEngine
import com.padmouse.mapper.engine.ScrollDriver
import com.padmouse.mapper.engine.TouchDriver
import com.padmouse.mapper.inject.InjectKit
import com.padmouse.mapper.model.ActionId
import com.padmouse.mapper.model.prettyKeyName
import com.padmouse.mapper.overlay.OverlayManager
import com.padmouse.mapper.overlay.PadBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

class PadAccessibilityService : AccessibilityService(), InputManager.InputDeviceListener {

    companion object {
        /**
         * 短按去重窗口（毫秒）。
         * 必须小于人的最短连按间隔（~50ms），又要比双路径的到达间隔（几 ms）大。
         */
        private const val SHORT_PRESS_DEDUP_MS = 40L

        @Volatile
        var instance: PadAccessibilityService? = null
            private set

        /**
         * 服务状态的 UI 快照。
         *
         * 为什么要有这个：UI 需要显示"暂停/恢复映射""显示/隐藏光标"这类
         * 跟随服务内部状态的文字，但那些是普通字段，Compose 无法感知变化 ——
         * 靠定时轮询重读也不可靠（组合作用域的跳过优化会让读取根本不发生）。
         *
         * 改成服务主动把状态推进 StateFlow，UI 用 collectAsState 订阅：
         * Compose 会把它当作一等状态，变化必定触发重组。
         */
        private val _uiState = MutableStateFlow(ServiceUiState())
        val uiState: StateFlow<ServiceUiState> = _uiState

        /** 由服务在状态变化时调用 */
        internal fun publishUiState(s: ServiceUiState) {
            _uiState.value = s
        }

        val keyEventCount = AtomicInteger()
        val motionEventCount = AtomicInteger()
        val gestureOk = AtomicInteger()
        val gestureFail = AtomicInteger()

        /** 手势看门狗触发次数（系统回调丢失的次数；正常应长期为 0） */
        val gestureTimeout = AtomicInteger()

        /** 手势「派发 → 回调」耗时窗口（UI 显示 p50/p95） */
        val gestureLatency = LatencyStats(64)

        @Volatile
        var volumeTrigger: Boolean = true

        fun resetCounters() {
            keyEventCount.set(0)
            motionEventCount.set(0)
            gestureOk.set(0)
            gestureFail.set(0)
            gestureTimeout.set(0)
        }
    }

    lateinit var overlays: OverlayManager
        private set
    lateinit var cursorCtl: CursorController
        private set
    lateinit var gestures: GestureEngine
        private set
    lateinit var scrollDriver: ScrollDriver
        private set
    lateinit var touchDriver: TouchDriver
        private set
    lateinit var mapping: MappingEngine
        private set

    val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var paused: Boolean = false
        private set

    @Volatile
    var boostActive: Boolean = false
        private set

    @Volatile
    var cursorVisible: Boolean = true
        private set

    /** 上一次 applyState 时是否处于映射激活态，用于识别"刚切进来"那一刻 */
    private var lastAppliedActive = false

    @Volatile
    var foregroundPkg: String = ""
        private set

    @Volatile
    var blockedByForeground: Boolean = false
        private set

    @Volatile
    var keyboardMode: Boolean = false
        private set

    private var inputManager: InputManager? = null

    // ---------------------------------------------------------------- 锁屏自动暂停

    /** 锁屏时是否是本服务自己暂停的：只有这种情况解锁后才自动恢复 */
    @Volatile
    private var pausedByScreenOff = false

    /** 锁屏前的光标显示偏好，解锁后按原样还原 */
    @Volatile
    private var cursorVisibleBeforeScreenOff = true

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_USER_PRESENT -> onUnlocked("user present")
                // 没有锁屏密码的机器不会发 USER_PRESENT，这里兜底（等灭屏动画走完再判断）
                Intent.ACTION_SCREEN_ON -> mainHandler.postDelayed(unlockFallback, 800L)
            }
        }
    }

    private val unlockFallback = Runnable {
        val kg = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (kg?.isKeyguardLocked != true) onUnlocked("screen on fallback")
    }

    /**
     * 锁屏 / 熄屏：暂停映射并收掉所有悬浮窗。
     * 光标窗留在锁屏画面上会长时间停在同一点（烧屏隐患），所以这里无条件隐藏。
     */
    private fun onScreenOff() {
        if (!this::overlays.isInitialized) return
        hideAllOverlaysForScreenOff()
        if (pausedByScreenOff) return
        if (!isMappingActive()) {
            // 用户本来就手动暂停着：保持暂停，解锁后也不动
            Probe.log("SVC", "screen off (already inactive, overlays hidden)")
            return
        }
        cursorVisibleBeforeScreenOff = cursorVisible
        pausedByScreenOff = true
        paused = true
        applyState("screen off")
        Probe.log("SVC", "screen off -> auto paused (cursorVisibleBefore=$cursorVisibleBeforeScreenOff)")
    }

    private fun hideAllOverlaysForScreenOff() {
        runCatching {
            if (this::cursorCtl.isInitialized) cursorCtl.stop()
            if (this::scrollDriver.isInitialized) scrollDriver.stop()
            if (this::touchDriver.isInitialized) touchDriver.end()
            overlays.hideCaptor()
            overlays.hideCursor()
        }.onFailure { Probe.log("SVC", "hide overlays failed: $it") }
    }

    /** 解锁：只在锁屏时是我们暂停的情况下恢复，并把光标显示偏好还原 */
    private fun onUnlocked(reason: String) {
        if (!pausedByScreenOff) return
        pausedByScreenOff = false
        paused = false
        applyState("unlock: $reason")
        // applyState 在刚进入激活时会把 cursorVisible 打开，这里按锁屏前的偏好还原
        cursorVisible = cursorVisibleBeforeScreenOff
        if (this::overlays.isInitialized) {
            if (cursorVisible) overlays.showCursorIfNeeded() else overlays.hideCursor()
        }
        Probe.log("SVC", "unlocked ($reason) -> mapping restored, cursorVisible=$cursorVisible")
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val flags = serviceInfo?.flags ?: 0
        val filterKey = (flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        Probe.log(
            "SVC",
            "connected sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} " +
                "flags=0x${Integer.toHexString(flags)} filterKey=$filterKey"
        )

        SettingsStore.init(this)
        overlays = OverlayManager(this)
        cursorCtl = CursorController(overlays)
        gestures = GestureEngine(this)
        scrollDriver = ScrollDriver(
            gestures,
            anchorProvider = { anchorPoint() },
            screenSize = { overlays.screenSize() }
        )
        mapping = MappingEngine(this, gestures)
        touchDriver = TouchDriver(this) { aimPoint() ?: (0f to 0f) }
        // 初始化摇杆角色，避免第一次轴事件时被误判成"角色变了"
        lastStickRole = currentStickRole()

        PadBridge.onStick = { x, y -> onStick(x, y) }
        PadBridge.onRightStick = { x, y -> onRightStick(x, y) }
        PadBridge.onTriggers = { lt, rt -> onTriggers(lt, rt) }
        PadBridge.onHat = { hx, hy -> onHatInput(hx, hy) }
        PadBridge.onKey = { code, action, _ -> handleKey(code, action, 0) }

        inputManager = getSystemService(INPUT_SERVICE) as? InputManager
        inputManager?.registerInputDeviceListener(this, mainHandler)

        // 锁屏自动暂停：熄屏 / 解锁都靠这几个系统广播
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, screenFilter)
            }
            Probe.log("SVC", "screen state receiver registered")
        }.onFailure { Probe.log("SVC", "register screen receiver failed: $it") }

        createNotificationChannel()
        applyState("service connected")

        // 焦点自愈：只在服务生命周期内跑，1Hz 很便宜
        mainHandler.removeCallbacks(focusWatcher)
        mainHandler.postDelayed(focusWatcher, 1500L)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Probe.log("SVC", "onUnbind")
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        try {
            // 别让待触发的"长按切换"在服务销毁后突然生效
            mainHandler.removeCallbacks(pauseHoldRunnable)
            mainHandler.removeCallbacks(focusWatcher)
            focusMisses = 0
            if (this::gestures.isInitialized) gestures.shutdown()
            if (this::touchDriver.isInitialized) touchDriver.end()
            if (this::scrollDriver.isInitialized) scrollDriver.stop()
            if (this::cursorCtl.isInitialized) cursorCtl.stop()
            if (this::overlays.isInitialized) overlays.teardown()
            inputManager?.unregisterInputDeviceListener(this)
            mainHandler.removeCallbacks(unlockFallback)
            runCatching { unregisterReceiver(screenStateReceiver) }
        } catch (t: Throwable) {
            Probe.log("SVC", "cleanup error: $t")
        }
        if (instance === this) {
            instance = null
        }
        // 告诉 UI 服务已断开
        publishUiState(ServiceUiState(connected = false))
        // 进程被杀前把防抖中的设置立刻落盘，避免丢最后一次改动
        SettingsStore.flushPersist()
        Probe.log("SVC", "cleaned up")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (this::overlays.isInitialized) {
            overlays.refreshScreenSize()
            val (w, h) = overlays.screenSize()
            overlays.moveCursorTo(w / 2f, h / 2f)
            Probe.log("SVC", "config changed -> screen ${w}x$h, cursor recentered")
        }
    }

    // ------------------------------------------------ Captor 焦点自愈

    /**
     * 失焦自愈：手柄输入全靠 Captor 焦点窗。一旦焦点被通知栏 / 系统弹窗 / 别的悬浮窗抢走，
     * 摇杆就彻底无声，用户只能靠"切出去再回来"救回来。这里 1Hz 自检并重抢。
     *
     * 让出焦点是**预期**的几种情况要跳过：暂停/停用、键盘优先 App、输入法模式。
     */
    private var focusMisses = 0

    /** 重建次数与上次重建时间：防止某些 ROM 的 hasWindowFocus() 不可靠时每几秒 churn 一次 */
    private var focusRebuilds = 0
    private var lastFocusRebuildAt = 0L

    private fun canRebuildCaptor(): Boolean {
        if (focusRebuilds >= 5) return false
        return SystemClock.elapsedRealtime() - lastFocusRebuildAt >= 10_000L
    }

    private val focusWatcher = object : Runnable {
        override fun run() {
            try {
                watchdogCaptorFocus()
            } catch (t: Throwable) {
                logThrottled("focus-watch", "OVL", "焦点自检异常：" + t)
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun watchdogCaptorFocus() {
        if (!this::overlays.isInitialized) return
        // 这些情况焦点是主动让出去的，不能抢
        if (!isMappingActive() || keyboardMode || imeModeActive) {
            focusMisses = 0
            return
        }
        // 自己的设置界面在前台时也不要抢焦点：否则会把 UI 的点击/输入吃掉
        if (foregroundPkg == packageName) {
            focusMisses = 0
            return
        }
        when (overlays.captorFocusState()) {
            OverlayManager.CaptorFocus.FOCUSED -> {
                if (focusMisses > 0) {
                    focusMisses = 0
                    Probe.log("OVL", "Captor 焦点已恢复")
                }
                // 恢复正常 → 重建预算回满
                focusRebuilds = 0
            }

            OverlayManager.CaptorFocus.UNFOCUSED -> {
                focusMisses++
                overlays.reassertCaptorFocus()
                logThrottled(
                    "captor-unfocused", "OVL",
                    "Captor 失焦，已请求重抢焦点（连续 " + focusMisses + " 次）"
                )
                if (focusMisses >= 3 && canRebuildCaptor()) {
                    focusMisses = 0
                    focusRebuilds++
                    lastFocusRebuildAt = SystemClock.elapsedRealtime()
                    Probe.log("OVL", "Captor 连续失焦，重建焦点窗（第 " + focusRebuilds + " 次）")
                    overlays.rebuildCaptor()
                }
            }

            OverlayManager.CaptorFocus.MISSING -> {
                focusMisses++
                if (focusMisses >= 2 && canRebuildCaptor()) {
                    focusMisses = 0
                    focusRebuilds++
                    lastFocusRebuildAt = SystemClock.elapsedRealtime()
                    Probe.log("OVL", "Captor 窗口不见了，重建（第 " + focusRebuilds + " 次）")
                    overlays.showCaptor()
                }
            }
        }
    }

    override fun onInterrupt() {
        // 手势被系统打断时必须收起"按住"状态，否则目标 App 会一直停在按下
        if (this::touchDriver.isInitialized) touchDriver.end()
        Probe.log("SVC", "onInterrupt")
    }

    // ---------------------------------------------------------------- 无障碍事件

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                if (pkg != foregroundPkg && pkg != packageName) {
                    foregroundPkg = pkg
                    Probe.log("WIN", "foreground=$pkg")
                    applyState("window changed")
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val node = e.source
                if (node?.isEditable == true) {
                    Probe.log("EDIT", "可编辑控件获得焦点 pkg=${e.packageName} hint=${node.hintText}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 按键过滤（路线 A）

    override fun onKeyEvent(event: KeyEvent): Boolean {
        keyEventCount.incrementAndGet()
        Probe.log("KEYF") {
            "${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} " +
                "src=0x${Integer.toHexString(event.source)} scan=${event.scanCode} dev=${event.deviceId}"
        }
        return handleKey(event.keyCode, event.action, event.repeatCount)
    }

    /**
     * 按键分发主干（KeyFilter 与调试注入共用）。
     * 返回 true = 消费掉，不再下发给系统。
     */
    private fun doHandleKey(keyCode: Int, action: Int, repeatCount: Int = 0): Boolean {
        // 0) 先记一份原始按键状态：「状态」页的手柄图要用，与映射无关
        LivePad.onKey(keyCode, action)

        // 1) 录制模式：截获下一个按键并写入绑定，期间不执行任何动作
        if (BindingBus.isRecording) {
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                BindingBus.recordingAction?.let { bindKey(it, keyCode) }
            }
            return true
        }

        // 2) 切换映射键：长按触发，短按不消费（便于透传给前台 App）
        if (SettingsStore.current.binding.keysOf(ActionId.PAUSE).contains(keyCode)) {
            return handlePauseKey(keyCode, action, repeatCount)
        }

        if (!isMappingActive()) return false

        // 扳机按下会附带自动重复的 KEYCODE_BUTTON_L2/R2，忽略 repeat
        if (repeatCount > 0 && action == KeyEvent.ACTION_DOWN) return false

        val consumed = mapping.onKey(keyCode, action)
        // KEYMAP：记录每个按键最终映射成了什么。
        // release 版也会保留这个 tag —— 排查"某个键没反应"时最关键的信息。
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            val bound = SettingsStore.current.binding.shortPressActionFor(keyCode)
            val isPauseKey = SettingsStore.current.binding.keysOf(ActionId.PAUSE).contains(keyCode)
            val desc = when {
                isPauseKey && bound != null ->
                    prettyKeyName(keyCode) + " -> 长按切换映射 / 短按 " + bound.name
                isPauseKey -> prettyKeyName(keyCode) + " -> 长按切换映射"
                bound != null -> prettyKeyName(keyCode) + " -> " + bound.name
                else -> prettyKeyName(keyCode) + " -> 未绑定（透传给前台 App）"
            }
            Probe.log("KEYMAP", desc + (if (consumed) " [已消费]" else " [透传]"))
        }
        if (consumed) return true

        return false
    }

    // ------------------------------------------------ 切换映射：长按触发（到时立即切换）

    private var pauseDown = false
    private var pauseHoldFired = false
    private var pauseKeyCode = 0
    private var pauseHoldMsUsed = 0L

    /**
     * 短按去重：记录上一次已执行的短按（按键 + 时刻）。
     *
     * 为什么需要去重：同一个按键事件会**走两条路径**到达这里 ——
     *  1. KeyFilter 的 onKeyEvent（全局按键过滤）
     *  2. 焦点窗口 CaptureView.onKeyUp（摇杆/按键焦点窗）
     * 因为 handlePauseKey 始终返回 false（不消费，好让前台 App 也能收到这个键），
     * 两条路径都会执行到这里 —— 于是短按动作会被触发**两次**，
     * 表现为"光标闪一下又回到原状"。
     *
     * 两条路径的到达时间相差通常在几毫秒内，用一个短窗口去重即可。
     * 窗口取得比人的最短连按间隔（~50ms）小，所以不会误伤真实的快速连按。
     */
    private var lastShortPressKey = 0
    private var lastShortPressAt = 0L

    /** 按住达到阈值就切换，不等松手 */
    private val pauseHoldRunnable = Runnable {
        if (!pauseDown) return@Runnable
        pauseHoldFired = true
        togglePause("长按 ${prettyKeyName(pauseKeyCode)} ${pauseHoldMsUsed}ms")
    }

    /**
     * 「切换映射」= 按住达到阈值后**立即**切换（不等待松手）。
     *
     * 为什么不用"按下即切换"：那样这个键就被永久占用 —— 游戏里想用同一个键（例如 menu）
     * 做原生操作就永远发不出去。
     *
     * 现在的行为：
     *  - 按下：不消费事件（返回 false），启动一个 holdMs 的定时器
     *  - 按住到阈值：定时器触发 → 立刻切换映射（此时用户可能还按着）
     *  - 松手：取消定时器；已经切换过的不会重复切换
     *  - 短按（未到阈值就松开）：执行该键上绑定的**短按动作**（如果有），
     *    没有则什么都不做，按键照常透传给前台 App
     *
     * 短按动作的存在让"一个键两用"成为可能：默认 Start 短按 = 光标显示/隐藏，
     * 长按 = 切换映射。用户实测两者不冲突。
     *
     * 按键事件始终不消费 —— 这样暂停映射时前台 App 能收到完整的按下/抬起。
     */
    private fun handlePauseKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val holdMs = SettingsStore.current.gesture.pauseHoldMs.coerceAtLeast(200L)
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                // 只有"按下沿"才起定时器；同一次按压的第二个 DOWN（另一条路径）被忽略
                if (repeatCount == 0 && !pauseDown) {
                    pauseDown = true
                    pauseHoldFired = false
                    pauseKeyCode = keyCode
                    pauseHoldMsUsed = holdMs
                    mainHandler.removeCallbacks(pauseHoldRunnable)
                    mainHandler.postDelayed(pauseHoldRunnable, holdMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                // 必须用 pauseDown 判定"这是本次按压的第一个 UP"。
                // 否则长按之后的第二个 UP 会读到已重置的 pauseHoldFired=false，
                // 被误判成短按，把光标也给切换掉。
                if (!pauseDown) return false
                val fired = pauseHoldFired
                pauseDown = false
                pauseHoldFired = false
                mainHandler.removeCallbacks(pauseHoldRunnable)
                // 没触发长按 → 这次是短按，执行该键上的短按动作
                if (!fired) fireShortPress(keyCode)
            }
        }
        return false // 始终不消费，按键继续下发给前台 App
    }

    /**
     * 短按动作：在长按阈值内松开时触发。
     *
     * 只在映射**激活**时执行 —— 映射暂停时这个键要完整透传给前台 App，
     * 执行我们的动作反而会干扰游戏（且此时光标状态也没意义）。
     */
    private fun fireShortPress(keyCode: Int) {
        if (!isMappingActive()) return
        if (this::touchDriver.isInitialized && touchDriver.isActive) return
        val action = SettingsStore.current.binding.shortPressActionFor(keyCode) ?: return

        // 去重：同��按键在极短窗口内的重复投递（KeyFilter + 焦点窗双路径）只执行一次
        val now = SystemClock.elapsedRealtime()
        if (keyCode == lastShortPressKey && now - lastShortPressAt < SHORT_PRESS_DEDUP_MS) {
            Probe.log("SHORT", "${prettyKeyName(keyCode)} -> ${action.name} (dup, ignored)")
            return
        }
        lastShortPressKey = keyCode
        lastShortPressAt = now

        Probe.log("SHORT", "${prettyKeyName(keyCode)} -> ${action.name}")
        mapping.execute(action)
    }

    /**
     * 配置导入/替换后调用 —— 设置可能改了摇杆角色，
     * 要把旧角色的残留输入清掉。
     */
    fun onSettingsReplaced() {
        applyStickRoles()
        applyState("settings replaced")
    }

    /** 调试用：模拟一次按键，走与真实手柄完全相同的分发逻辑 */
    fun simulateKey(keyCode: Int, action: Int = KeyEvent.ACTION_DOWN) {
        Probe.log("SIM", "key=${prettyKeyName(keyCode)} action=$action")
        handleKey(keyCode, action, 0)
    }

    /**
     * 调试用：模拟**焦点窗口**路径的按键投递。
     *
     * 真实手柄的同一个按键会走两条路：KeyFilter 与焦点窗口。
     * 这个方法专门用来复现双路径，验证短按去重是否生效。
     */
    fun simulateKeyViaCaptor(keyCode: Int, action: Int = KeyEvent.ACTION_DOWN) {
        Probe.log("SIM-CAPTOR", "key=${prettyKeyName(keyCode)} action=$action")
        handleKey(keyCode, action, 0)
    }

    // ---------------------------------------------------------------- 手柄轴

    /**
     * 上一次处理轴事件时的摇杆角色。
     *
     * 用来**自动发现**角色变化 —— 设置可能从任何地方改
     * （UI 按钮、配置导入、其它进程），光靠 UI 主动回调不可靠。
     * 轴事件本身是高频的，正好可以顺便检查。
     */
    private var lastStickRole = -1

    /**
     * 把当前状态发布给 UI。
     *
     * 任何会影响界面文字的状态变化都要调这里 —— 否则 UI 显示会停在旧值。
     */
    private fun publishState() {
        publishUiState(
            ServiceUiState(
                connected = true,
                paused = paused,
                active = isMappingActive(),
                cursorVisible = cursorVisible,
                imeModeActive = imeModeActive,
                foregroundPkg = foregroundPkg,
                blockedByForeground = blockedByForeground,
                keyboardMode = keyboardMode,
                fps = if (this::cursorCtl.isInitialized) cursorCtl.fps else 0,
                speedMultiplier = if (this::cursorCtl.isInitialized) {
                    cursorCtl.currentMultiplier()
                } else 1f,
                captorShown = this::overlays.isInitialized && overlays.isCaptorShown,
                cursorOverlayShown = this::overlays.isInitialized && overlays.isCursorShown
            )
        )
    }

    /** 供 UI 在手动改了悬浮窗开关后，主动刷新一次状态快照 */
    fun refreshUiState() {
        if (this::overlays.isInitialized) publishState()
    }

    /**
     * 切换「移动光标的摇杆」设置后调用。
     *
     * 必须清掉两个驱动器的残留输入 —— 否则切换瞬间如果摇杆正被推着，
     * 旧角色的速度会一直留在那里（光标停不下来 / 滚动停不下来）。
     */
    fun applyStickRoles() {
        if (!this::cursorCtl.isInitialized) return
        // 硬复位：角色切换是明确的断点，速度要立刻归零，
        // 否则光标会按旧角色的输入继续飘一会儿
        cursorCtl.reset()
        if (this::scrollDriver.isInitialized) scrollDriver.stop()
        lastStickRole = currentStickRole()
        Probe.log(
            "STATE",
            "stick roles -> cursor=" + (if (rightStickDrivesCursor()) "RIGHT" else "LEFT")
        )
    }

    /** 当前摇杆角色：0=左移光标 1=右移光标 2=十字键移光标 */
    private fun currentStickRole(): Int {
        val s = SettingsStore.current
        return when {
            s.swapDpadStick -> 2
            s.cursorStick == "RIGHT" -> 1
            else -> 0
        }
    }

    /**
     * 在处理轴事件前检查角色有没有变。
     *
     * 角色变了就清掉残留输入 —— 否则旧角色的滚动/光标速度会一直挂着。
     */
    private fun ensureStickRoleCurrent() {
        val role = currentStickRole()
        if (role == lastStickRole) return
        lastStickRole = role
        if (!this::cursorCtl.isInitialized) return
        cursorCtl.reset()
        if (this::scrollDriver.isInitialized) scrollDriver.stop()
        Probe.log(
            "STATE",
            "stick role changed -> cursor=" + (if (role == 1) "RIGHT" else if (role == 2) "DPAD" else "LEFT")
        )
    }

    /**
     * 哪个摇杆驱动光标：true = 右摇杆。
     *
     * 选右摇杆移光标时，左摇杆自动改做滚动（反之亦然）。
     *
     * 注意：开启「十字键 ⇄ 左摇杆 互换」时光标已经归十字键了，
     * 此时这个设置不生效（十字键优先）。右摇杆仍然做滚动。
     */
    private fun rightStickDrivesCursor(): Boolean {
        val s = SettingsStore.current
        if (s.swapDpadStick) return false // 十字键已接管光标
        return s.cursorStick == "RIGHT"
    }

    // ------------------------------------------------ 输入入口兜底
    //
    // 摇杆 / 按键事件由系统调度进来。这里一旦抛异常，整条输入链就断了
    // （表现：摇杆按键全哑，但服务看起来还活着）。统一包一层：
    // 异常只记限流日志，不影响后续事件。

    fun onStick(lx0: Float, ly0: Float) {
        try {
            doOnStick(lx0, ly0)
        } catch (t: Throwable) {
            logThrottled("in-stick", "SVC", "onStick 异常：" + t)
        }
    }

    fun onRightStick(x0: Float, y0: Float) {
        try {
            doRightStick(x0, y0)
        } catch (t: Throwable) {
            logThrottled("in-rstick", "SVC", "onRightStick 异常：" + t)
        }
    }

    fun onHatInput(hx0: Float, hy0: Float) {
        try {
            doHatInput(hx0, hy0)
        } catch (t: Throwable) {
            logThrottled("in-hat", "SVC", "onHatInput 异常：" + t)
        }
    }

    private fun onTriggers(lt: Float, rt: Float) {
        try {
            doOnTriggers(lt, rt)
        } catch (t: Throwable) {
            logThrottled("in-trigger", "SVC", "onTriggers 异常：" + t)
        }
    }

    fun handleKey(keyCode: Int, action: Int, repeatCount: Int = 0): Boolean = try {
        doHandleKey(keyCode, action, repeatCount)
    } catch (t: Throwable) {
        logThrottled("in-key", "SVC", "handleKey 异常：" + t)
        false
    }

    /** 限流日志：同一 key 至少间隔 minInterval 才输出一次，避免异常刷屏 */
    private val throttledLogs = HashMap<String, Long>()

    private fun logThrottled(key: String, tag: String, msg: String, minInterval: Long = 2000L) {
        val now = SystemClock.elapsedRealtime()
        val last = throttledLogs[key] ?: 0L
        if (now - last >= minInterval) {
            throttledLogs[key] = now
            Probe.log(tag, msg)
        }
    }

    /** 左摇杆入口（含反转、互换处理）。调试注入也走这里，保证与真实手柄完全同路径 */
    private fun doOnStick(lx0: Float, ly0: Float) {
        // 预览要的是原始输入：先记一份物理值（此时还没做反转 / 互换）
        LivePad.setLeft(lx0, ly0)
        ensureStickRoleCurrent()
        val inv = SettingsStore.current.invert
        val swapped = SettingsStore.current.swapDpadStick
        val rightCursor = rightStickDrivesCursor()
        // 互换后反转开关也跟着换角色：此时"左摇杆"这组开关作用于十字键（光标）
        val invX = if (swapped) inv.hatX else inv.leftX
        val invY = if (swapped) inv.hatY else inv.leftY
        val x = if (invX) -lx0 else lx0
        val y = if (invY) -ly0 else ly0
        if (!isMappingActive()) return
        if (swapped) {
            // 互换：左摇杆改当十字键用 → 触发四向滑动
            mapping.onHat(x, y)
        } else if (rightCursor) {
            // 右摇杆移光标 → 左摇杆改做滚动
            if (touchActive) return
            scrollDriver.onStick(x, y)
        } else {
            cursorCtl.setStick(x, y)
        }
    }

    // ------------------------------------------------ 十字键 ⇄ 左摇杆 互换

    private var hatAnalogRunning = false
    private var hatDirX = 0f
    private var hatDirY = 0f
    private var hatStartAt = 0L

    private val hatAnalogTicker = object : Runnable {
        override fun run() {
            if (!hatAnalogRunning) return
            val ramp = SettingsStore.current.gesture.hatRampMs.coerceAtLeast(60L).toFloat()
            val t = ((SystemClock.elapsedRealtime() - hatStartAt) / ramp).coerceIn(0f, 1f)
            // 起步 0.22 倍速：轻点走一小步，按住逐渐加到满速
            val mag = 0.22f + 0.78f * t
            val vx = hatDirX * mag
            val vy = hatDirY * mag
            cursorCtl.setStick(vx, vy)
            mainHandler.postDelayed(this, 16L)
        }
    }

    /** 十字键是数字量（±1），当摇杆用必须做"按住逐渐加速"的斜坡，否则一推就是满速 */
    private fun driveHatAsStick(hx: Float, hy: Float) {
        val m = hypot(hx, hy)
        if (m < 0.5f) {
            stopHatAnalog()
            return
        }
        val nx = hx / m
        val ny = hy / m
        if (!hatAnalogRunning || nx != hatDirX || ny != hatDirY) {
            hatDirX = nx
            hatDirY = ny
            hatStartAt = SystemClock.elapsedRealtime()
        }
        if (!hatAnalogRunning) {
            hatAnalogRunning = true
            mainHandler.post(hatAnalogTicker)
        }
    }

    private fun stopHatAnalog() {
        if (!hatAnalogRunning) return
        hatAnalogRunning = false
        mainHandler.removeCallbacks(hatAnalogTicker)
        cursorCtl.setStick(0f, 0f)
    }

    /** 十字键原始输入入口：先按"物理控件"做反转，再按是否互换决定去向 */
    private fun doHatInput(hx0: Float, hy0: Float) {
        // 原始十字键值（预览用；此时还没做反转 / 互换）
        LivePad.setHat(hx0, hy0)
        val inv = SettingsStore.current.invert
        val swapped = SettingsStore.current.swapDpadStick
        // 互换后反转开关也跟着换角色：此时"十字键"这组开关作用于左摇杆（四向滑动）
        val invX = if (swapped) inv.leftX else inv.hatX
        val invY = if (swapped) inv.leftY else inv.hatY
        val hx = if (invX) -hx0 else hx0
        val hy = if (invY) -hy0 else hy0
        if (!isMappingActive()) return
        if (swapped) {
            driveHatAsStick(hx, hy)
        } else {
            mapping.onHat(hx, hy)
        }
    }

    private fun doRightStick(x0: Float, y0: Float) {
        // 预览要的是原始输入：先记一份物理值（未反转）
        LivePad.setRight(x0, y0)
        ensureStickRoleCurrent()
        val inv = SettingsStore.current.invert
        val x = if (inv.rightX) -x0 else x0
        val y = if (inv.rightY) -y0 else y0
        if (!isMappingActive()) return
        if (rightStickDrivesCursor()) {
            // 右摇杆驱动光标（反转仍按物理摇杆走，见 InvertCfg）
            cursorCtl.setStick(x, y)
        } else {
            if (touchActive) return // 触摸拖动期间不发滚动手势
            scrollDriver.onStick(x, y)
        }
    }

    // ------------------------------------------------ 输入法模式（让出焦点打字）

    /** 是否为「输入法模式」：让出输入焦点以便输入法可用，期间摇杆/滚动暂停，按键映射照常 */
    @Volatile
    var imeModeActive: Boolean = false
        private set

    private var imeWatchActive = false
    private var imeGoneSince = 0L

    private val imeWatcher = object : Runnable {
        override fun run() {
            if (!imeModeActive || !imeWatchActive) return
            val shown = isImeVisible()
            if (shown) {
                imeGoneSince = 0L
            } else if (imeGoneSince == 0L) {
                imeGoneSince = SystemClock.elapsedRealtime()
            }
            // 输入法收起 2.5s 后自动回到映射模式，避免用户"卡"在没摇杆的状态
            if (!shown && imeGoneSince > 0L && SystemClock.elapsedRealtime() - imeGoneSince > 2500L) {
                Probe.log("IME", "输入法已收起 2.5s，自动退出输入法模式")
                imeModeActive = false
                imeWatchActive = false
                applyState("ime auto exit")
                return
            }
            mainHandler.postDelayed(this, 700L)
        }
    }

    /** 输入法窗口是否可见（需要 flagRetrieveInteractiveWindows） */
    fun isImeVisible(): Boolean = try {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (t: Throwable) {
        false
    }

    /**
     * 切换输入法模式。
     *
     * 背景：摇杆数据只能来自"持有输入焦点的窗口"，而输入法只在"应用持焦点"时存活，
     * 两者互斥 —— 实测抢回焦点后输入法会被系统立刻收回。
     * 所以这里让出焦点，换取输入法可用；按键映射（全局按键过滤）不受影响。
     */
    fun toggleImeMode() {
        if (!SettingsStore.current.allowImeWhileMapping) {
            Probe.log("IME", "未开启「允许在映射开启时使用输入法」，忽略")
            return
        }
        imeModeActive = !imeModeActive
        Probe.log("IME", "输入法模式 -> $imeModeActive")
        if (imeModeActive) {
            cursorCtl.stop()
            scrollDriver.stop()
            if (this::touchDriver.isInitialized) touchDriver.end()
            overlays.hideCaptor()   // 关键：让出焦点
            overlays.showCursor()   // 光标留在原位，方便看位置
            overlays.setCursorImeMode(true)  // 描边转红：提醒"摇杆暂时移不动光标"
            imeWatchActive = true
            imeGoneSince = 0L
            mainHandler.removeCallbacks(imeWatcher)
            mainHandler.postDelayed(imeWatcher, 900L)
        } else {
            imeWatchActive = false
            mainHandler.removeCallbacks(imeWatcher)
            applyState("ime mode off")
        }
        updateNotification()
        publishState()
    }

    /** 触摸（按住跟随）是否进行中 */
    val touchActive: Boolean
        get() = this::touchDriver.isInitialized && touchDriver.isActive

    /** 按下（手指接触屏幕） */
    fun touchDown(): Boolean {
        if (!this::touchDriver.isInitialized) return false
        val (x, y) = aimPoint() ?: return false
        scrollDriver.stop()
        return touchDriver.start(x, y)
    }

    /** 松开（手指离开屏幕） */
    fun touchUp() {
        if (this::touchDriver.isInitialized) touchDriver.end()
    }

    /** 按键录制：把 keyCode 绑到 action 上 */
    private fun bindKey(action: ActionId, keyCode: Int) {
        // 同一个键绑到两个功能时，只有枚举里靠前的那个会生效（reverseIndex 只认第一个）。
        // 这里明确告诉用户冲突了，而不是让后者"看起来绑上了却没反应"。
        val conflicts = SettingsStore.current.binding.conflictsFor(action, keyCode)
        val conflictText = if (conflicts.isEmpty()) {
            ""
        } else {
            "（注意：${prettyKeyName(keyCode)} 已绑定 ${conflicts.joinToString { it.label }}，" +
                "同一按键只能触发一个功能）"
        }
        SettingsStore.update { s -> s.copy(binding = s.binding.withKey(action, keyCode)) }
        BindingBus.noteKey(keyCode, prettyKeyName(keyCode) + conflictText)
        Probe.log("BIND", "${action.name} += ${prettyKeyName(keyCode)}${conflictText}")
        mainHandler.postDelayed({ BindingBus.end() }, 600)
    }

    // ---------------- 系统动作 ----------------

    /**
     * 下拉通知栏。也不用 GLOBAL_ACTION_NOTIFICATIONS（实测在焦点窗存在时不生效），
     * 改为从屏幕顶部中心下拉的手势 —— 实测可用。
     */
    fun doNotif() {
        val (w, h) = overlays.screenSize()
        gestures.submit(
            "notif-gesture",
            InjectKit.swipe(w / 2f, 2f, w / 2f, (h * 0.55f).coerceAtLeast(400f), 320)
        )
        Probe.log("MAP", "notif gesture")
    }

    /** 轻提示：切换模式时让用户知道当前处于哪种状态 */
    private fun showToast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Probe.log("SVC", "toast failed: $t")
        }
    }

    fun global(action: Int) {
        val ok = performGlobalAction(action)
        Probe.log("MAP", "global($action) ok=$ok")
    }

    /**
     * 返回。
     *
     * 为什么不能直接 performGlobalAction(BACK)：它的实现是注入一个 KEYCODE_BACK
     * 按键事件，而按键只派发给**持焦点的窗口** —— 也就是我们的 Captor 焦点窗，
     * 事件会被它吃掉，前台 App 收不到。
     *
     * 三种实现（默认 RELEASE，实测唯一稳定可用）：
     *  - RELEASE：临时移除 Captor 焦点窗 → 注入全局 BACK → 收回焦点（~200ms）
     *  - GLOBAL ：直接全局 BACK。仅当焦点窗不存在时有效
     *  - GESTURE：注入边缘返回手势。实测本机（手势导航）系统会忽略注入的触摸，基本无效
     */
    fun doBack() {
        val g = SettingsStore.current.gesture
        when (g.backMode) {
            "GLOBAL" -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

            "GESTURE" -> {
                val (w, h) = overlays.screenSize()
                val y = h * 0.5f
                val fromRight = g.backEdge == "RIGHT"
                val x0 = if (fromRight) w - 1f else 1f
                val x1 = if (fromRight) w * 0.70f else w * 0.30f
                gestures.submit("back-gesture", InjectKit.swipe(x0, y, x1, y, 260))
                Probe.log("MAP", "back gesture ${g.backEdge} (${x0.toInt()} -> ${x1.toInt()})")
            }

            else -> {
                // RELEASE：释放焦点让 BACK 能派发给前台 App，随后收回焦点
                val hadCaptor = overlays.isCaptorShown
                Probe.log("MAP", "back via release (captor=$hadCaptor)")
                if (hadCaptor) overlays.hideCaptor()
                mainHandler.postDelayed({
                    global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    if (hadCaptor) {
                        mainHandler.postDelayed({
                            if (isMappingActive() && !keyboardMode) overlays.showCaptor()
                        }, 130)
                    }
                }, 70)
            }
        }
    }

    fun adjustVolume(delta: Int) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (delta > 0) android.media.AudioManager.ADJUST_RAISE
                else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            Probe.log("MAP", "volume ${if (delta > 0) "+" else "-"}")
        } catch (t: Throwable) {
            Probe.log("MAP", "volume failed: $t")
        }
    }

    /**
     * 指针位置提示（红圈收拢）。
     *
     * 如果光标当前是**隐藏**的，先把它显示出来 ——
     * 提示圈画在指针位置，指针不在就看不到任何提示，等于白按。
     * 隐藏时按下这个键 =&gt; 显示光标 + 显示提示圈。
     */
    fun pulseCursor(): Boolean {
        // 提示圈功能被关掉时：不做任何副作用，直接返回 false，
        // 由调用方决定怎么降级（避免"显示完光标又被降级逻辑关掉"）。
        if (!SettingsStore.current.cursor.pulseEnabled) return false
        if (!cursorVisible) {
            // 光标是隐藏的：先显示出来，提示圈才有落点
            setCursorVisible(true, showToastMsg = false)
            Probe.log("STATE", "pulse -> cursor was hidden, showing it")
        }
        return overlays.pulse()
    }

    /**
     * 设置光标显示状态。
     *
     * showToastMsg=false 时不弹 Toast（用于内部联动，比如提示圈把隐藏的光标带出来）。
     */
    private fun setCursorVisible(visible: Boolean, showToastMsg: Boolean = true) {
        cursorVisible = visible
        if (visible) overlays.showCursor() else overlays.hideCursor()
        Probe.log("STATE", "cursorVisible=$cursorVisible")
        if (showToastMsg) {
            showToast(if (visible) "已显示鼠标指针" else "已隐藏鼠标指针")
        }
        publishState()
    }

    /** 参数面板改光标大小/颜色后，实时应用 */
    fun refreshCursorStyle() {
        if (this::overlays.isInitialized) overlays.syncCursorStyle()
    }

    @Volatile
    private var lastLt = 0f

    @Volatile
    private var lastRt = 0f

    private fun doOnTriggers(lt: Float, rt: Float) {
        lastLt = lt
        lastRt = rt
        LivePad.setTriggers(lt, rt)
        if (!isMappingActive()) return
        applySpeedMultiplier()
    }

    /** RT 按住 = 精准模式；L3 按住 = 加速 */
    private fun applySpeedMultiplier() {
        val c = SettingsStore.current.cursor
        val mul = when {
            boostActive -> c.boostMul
            lastRt > 0.5f -> c.precisionMul
            else -> 1f
        }
        cursorCtl.setSpeedMultiplier(mul)
    }

    // ---------------------------------------------------------------- 状态控制

    fun isMappingActive(): Boolean = !paused && !blockedByForeground && SettingsStore.current.mappingEnabled

    /** 根据设置 + 前台 App 重新计算运行状态 */
    fun applyState(reason: String = "") {
        if (!this::overlays.isInitialized) return
        val s = SettingsStore.current
        val fg = foregroundPkg
        blockedByForeground = s.lists.blacklist.contains(fg)
        keyboardMode = s.lists.keyboardPriority.contains(fg)
        val active = isMappingActive()

        Probe.log(
            "STATE",
            "reason=$reason active=$active paused=$paused blocked=$blockedByForeground " +
                "kbMode=$keyboardMode fg=$fg"
        )

        if (!SettingsStore.current.swapDpadStick || !active) stopHatAnalog()
        // 停用时焦点窗已移除，不会再有输入；顺手清掉预览里残留的摇杆/按键状态
        if (!active) LivePad.reset()

        val entering = active && !lastAppliedActive
        lastAppliedActive = active

        if (active) {
            cursorCtl.start()
            // 刚切到映射模式那一刻立即显示指针，不必等第一次推摇杆；
            // 之后用户手动隐藏过就不再强行打开，避免每次切 App 都冒出来
            if (entering) cursorVisible = true
            overlays.syncCursorStyle()
            overlays.setCursorImeMode(imeModeActive)
            if (cursorVisible) overlays.showCursorIfNeeded()
            // 键盘优先的 App 上让出焦点，保证能打字；此时只能靠 KeyFilter 的按键操作
            if (keyboardMode || imeModeActive) overlays.hideCaptor() else overlays.showCaptor()
        } else {
            cursorCtl.stop()
            scrollDriver.stop()
            if (this::touchDriver.isInitialized) touchDriver.end()
            stopHatAnalog()
            // 进入暂停/停用状态时顺手退出输入法模式，避免 watcher 空转
            imeModeActive = false
            imeWatchActive = false
            mainHandler.removeCallbacks(imeWatcher)
            overlays.setCursorImeMode(false)
            overlays.hideCaptor()
            if (!keyboardMode) {
                // 暂停时收掉光标窗口，但**不要**改 cursorVisible ——
                // 它表示"用户的偏好"，恢复时还要按这个偏好还原。
                // 标志与实际显示状态的不一致只存在于暂停期间，是预期的。
                overlays.hideCursor()
            }
        }
        updateNotification()
        publishState()
    }

    fun setMappingEnabled(on: Boolean) {
        SettingsStore.update { it.copy(mappingEnabled = on) }
        paused = false
        showToast(if (on) "已切换到模拟鼠标模式" else "已切换到手柄模式")
        applyState("mapping=$on")
    }

    fun togglePause(from: String) {
        paused = !paused
        Probe.log("STATE", "togglePause($from) -> paused=$paused")
        showToast(if (paused) "已切换到手柄模式" else "已切换到模拟鼠标模式")
        applyState("togglePause")
    }

    fun setBoost(on: Boolean) {
        boostActive = on
        applySpeedMultiplier()
        publishState()
    }

    fun toggleCursorVisible() {
        setCursorVisible(!cursorVisible)
    }

    fun aimPoint(): Pair<Float, Float>? {
        val c = SettingsStore.current.cursor
        val base = overlays.cursorPosition() ?: run {
            val (w, h) = overlays.screenSize()
            w / 2f to h / 2f
        }
        return (base.first + c.aimOffsetX) to (base.second + c.aimOffsetY)
    }

    private fun anchorPoint(): Pair<Float, Float> {
        val s = SettingsStore.current.scroll
        val (w, h) = overlays.screenSize()
        return when (s.anchor) {
            "CURSOR" -> overlays.cursorPosition() ?: (w / 2f to h / 2f)
            else -> w / 2f to h / 2f
        }
    }

    // ---------------------------------------------------------------- 手势注入

    fun sendGesture(
        gesture: GestureDescription,
        label: String,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureOk.incrementAndGet()
                    Probe.log("GEST", "$label COMPLETED")
                    onDone?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureFail.incrementAndGet()
                    Probe.log("GEST", "$label CANCELLED")
                    onDone?.invoke(false)
                }
            },
            mainHandler
        )
        if (!ok) {
            Probe.log("GEST", "$label dispatch=false")
            onDone?.invoke(false)
        }
    }

    fun chainScroll(units: Int, centerX: Float, fromY: Float, toY: Float, unitMs: Long, label: String) {
        var i = 0
        fun next() {
            if (i >= units) {
                Probe.log("GEST", "$label chain finished ($units)")
                return
            }
            i++
            val idx = i
            sendGesture(InjectKit.swipe(centerX, fromY, centerX, toY, unitMs), "$label#$idx") { next() }
        }
        mainHandler.post { next() }
    }

    // ---------------------------------------------------------------- 输入设备

    override fun onInputDeviceAdded(deviceId: Int) {
        val d = inputManager?.getInputDevice(deviceId) ?: return
        Probe.log("DEV", "ADDED ${d.name} desc=${d.descriptor}")
        if ((d.sources and InputDevice.SOURCE_GAMEPAD) != 0) {
            mainHandler.post { applyState("gamepad added") }
            showToast("手柄已连接：" + d.name)
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        Probe.log("DEV", "REMOVED id=$deviceId")
        cursorCtl.setStick(0f, 0f)
        scrollDriver.stop()
        mapping.resetHat()
        LivePad.reset()
        // 手柄断开时如果"触摸（按住）"还按着，目标 App 会永远停在按下状态。
        // 必须在这里收敛出一次抬起，否则蓝牙掉线就再也松不开了。
        if (this::touchDriver.isInitialized) touchDriver.end()
        if (this::cursorCtl.isInitialized) cursorCtl.setSpeedMultiplier(1f)
        // 手柄全部拔掉才提示，避免多手柄切换时误报
        if (gamepadCount() == 0) showToast("手柄已断开")
    }

    /** 当前还连着几个手柄/摇杆类设备 */
    private fun gamepadCount(): Int = try {
        val ids = inputManager?.inputDeviceIds ?: IntArray(0)
        ids.count { id ->
            val d = inputManager?.getInputDevice(id) ?: return@count false
            (d.sources and InputDevice.SOURCE_GAMEPAD) != 0 ||
                (d.sources and InputDevice.SOURCE_JOYSTICK) != 0
        }
    } catch (t: Throwable) {
        0
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        Probe.log("DEV", "CHANGED id=$deviceId")
    }

    // ---------------------------------------------------------------- 通知

    private val CHANNEL_ID = "padmouse_status"
    private val NOTIF_ID = 1001

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "映射状态", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) }
                )
            }
        }
    }

    private fun notifyPendingIntent(cmd: String): PendingIntent {
        val i = Intent(this, ProbeReceiver::class.java).apply {
            action = "com.padmouse.probe.CMD"
            putExtra("cmd", cmd)
        }
        return PendingIntent.getBroadcast(
            this, cmd.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        try {
            val active = isMappingActive()
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val status = when {
                imeModeActive -> "输入法模式（摇杆暂停，按键可用）"
                paused -> "已暂停（按 Start 或点下方按钮恢复）"
                blockedByForeground -> "当前 App 已自动停用"
                keyboardMode -> "键盘优先模式（摇杆暂不可用）"
                active -> "运行中"
                else -> "已关闭"
            }
            val icon = android.graphics.drawable.Icon.createWithResource(
                this, android.R.drawable.ic_menu_compass
            )
            builder
                .setContentTitle("PadMouse 手柄映射")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    Notification.Action.Builder(
                        icon,
                        if (paused) "恢复" else "暂停",
                        notifyPendingIntent(if (paused) "resume" else "pause")
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(icon, "光标", notifyPendingIntent("cursor_toggle")).build()
                )
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, builder.build())
        } catch (t: Throwable) {
            Probe.log("SVC", "notification failed: $t")
        }
    }
}
