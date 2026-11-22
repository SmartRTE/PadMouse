package com.padmouse.mapper.model

import com.padmouse.mapper.core.InputMath
import org.json.JSONArray
import org.json.JSONObject

/**
 * 所有可调参数。全部暴露到 UI，用于适配不同设备手感。
 * 单位约定：比例用 (0..1] 表示与屏幕短边/长边的比值；时间用毫秒。
 */
data class CursorCfg(
    /** 光标最大速度 = maxSpeedRatio × min(屏宽,屏高) 像素/秒 */
    val maxSpeedRatio: Float = 1.2370807f,
    /** 径向死区 (0..0.4) */
    val deadzone: Float = 0.2f,
    /** 响应曲线指数：越大低速越细腻 */
    val gamma: Float = 2.1058679f,
    /** 速度平滑时间常数 (0..80ms)，越小越跟手但可能抖 */
    val smoothingMs: Float = 28f,
    /** 精准模式速度倍率 */
    val precisionMul: Float = 0.25f,
    /** 加速模式速度倍率 */
    val boostMul: Float = 2.617867f,
    /** 光标尺寸 dp（小屏可调小，避免遮挡） */
    val sizeDp: Float = 18.543291f,
    /** 光标颜色 ARGB */
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    /** 静止时淡出 */
    val autoFade: Boolean = true,
    /** 点击点相对箭头尖端的微调（px），用于校正主观手感 */
    val aimOffsetX: Float = 0f,
    val aimOffsetY: Float = 0f,
    /** 指针位置提示（红圈从大到小收拢）总开关 */
    val pulseEnabled: Boolean = true,
    /** 提示圈颜色 */
    val pulseColorArgb: Int = 0xFFFF3B30.toInt(),
    /** 提示圈起始半径 dp */
    val pulseMaxRadiusDp: Float = 190.3652f
) {
    fun toJson() = JSONObject().apply {
        put("maxSpeedRatio", maxSpeedRatio.toDouble())
        put("deadzone", deadzone.toDouble())
        put("gamma", gamma.toDouble())
        put("smoothingMs", smoothingMs.toDouble())
        put("precisionMul", precisionMul.toDouble())
        put("boostMul", boostMul.toDouble())
        put("sizeDp", sizeDp.toDouble())
        put("colorArgb", colorArgb)
        put("autoFade", autoFade)
        put("aimOffsetX", aimOffsetX.toDouble())
        put("aimOffsetY", aimOffsetY.toDouble())
        put("pulseEnabled", pulseEnabled)
        put("pulseColorArgb", pulseColorArgb)
        put("pulseMaxRadiusDp", pulseMaxRadiusDp.toDouble())
    }

    companion object {
        /**
         * 从 JSON 还原。所有数值都要做范围收敛：
         * 死区必须 < 1，否则 (1 - deadzone) 会除零，光标速度直接变 NaN。
         */
        fun from(o: JSONObject?): CursorCfg {
            val d = CursorCfg()
            if (o == null) return d
            return CursorCfg(
                maxSpeedRatio = o.optDouble("maxSpeedRatio", d.maxSpeedRatio.toDouble()).toFloat()
                    .coerceIn(0.05f, 5f),
                deadzone = o.optDouble("deadzone", d.deadzone.toDouble()).toFloat()
                    .coerceIn(0f, InputMath.MAX_DEADZONE),
                gamma = o.optDouble("gamma", d.gamma.toDouble()).toFloat()
                    .coerceIn(0.2f, 5f),
                smoothingMs = o.optDouble("smoothingMs", d.smoothingMs.toDouble()).toFloat()
                    .coerceIn(0f, 200f),
                precisionMul = o.optDouble("precisionMul", d.precisionMul.toDouble()).toFloat()
                    .coerceIn(0.05f, 1f),
                boostMul = o.optDouble("boostMul", d.boostMul.toDouble()).toFloat()
                    .coerceIn(1f, 10f),
                sizeDp = o.optDouble("sizeDp", d.sizeDp.toDouble()).toFloat()
                    .coerceIn(8f, 200f),
                colorArgb = o.optInt("colorArgb", d.colorArgb),
                autoFade = o.optBoolean("autoFade", d.autoFade),
                aimOffsetX = o.optDouble("aimOffsetX", 0.0).toFloat().coerceIn(-500f, 500f),
                aimOffsetY = o.optDouble("aimOffsetY", 0.0).toFloat().coerceIn(-500f, 500f),
                pulseEnabled = o.optBoolean("pulseEnabled", d.pulseEnabled),
                pulseColorArgb = o.optInt("pulseColorArgb", d.pulseColorArgb),
                pulseMaxRadiusDp = o.optDouble("pulseMaxRadiusDp", d.pulseMaxRadiusDp.toDouble()).toFloat()
                    .coerceIn(10f, 1000f)
            )
        }
    }
}

data class ScrollCfg(
    /** CURSOR = 在光标处滚，CENTER = 屏幕中心 */
    val anchor: String = "CURSOR",
    /** 每个滚动单元位移 = distanceRatio × 屏高 */
    val distanceRatio: Float = 0.10546035f,
    /** 每个滚动单元时长 */
    val unitMs: Long = 232L,
    /** 丝滑滚动（等速）：按目标速度反算单段位移/时长，段与段之间不留间隔 */
    val smooth: Boolean = true,
    /** 丝滑模式：满推时每秒滚动多少个「屏幕短边」 */
    val speedRatio: Float = 1f,
    /** 丝滑模式：单段基准时长（越短越跟手，越长则段间边界更少、更顺） */
    val chunkMs: Long = 300L,
    /** 允许水平滚动 */
    val horizontal: Boolean = true,
    /** 摇杆推量 → 滚动频率 的曲线指数 */
    val curve: Float = 1.6f,
    /** 滚动死区 */
    val deadzone: Float = 0.26908085f
) {
    fun toJson() = JSONObject().apply {
        put("anchor", anchor)
        put("distanceRatio", distanceRatio.toDouble())
        put("unitMs", unitMs)
        put("smooth", smooth)
        put("speedRatio", speedRatio.toDouble())
        put("chunkMs", chunkMs)
        put("horizontal", horizontal)
        put("curve", curve.toDouble())
        put("deadzone", deadzone.toDouble())
    }

    companion object {
        fun from(o: JSONObject?): ScrollCfg {
            val d = ScrollCfg()
            if (o == null) return d
            return ScrollCfg(
                anchor = o.optString("anchor", d.anchor)
                    .let { if (it == "CURSOR" || it == "CENTER") it else d.anchor },
                distanceRatio = o.optDouble("distanceRatio", d.distanceRatio.toDouble()).toFloat()
                    .coerceIn(0.02f, 1f),
                unitMs = o.optLong("unitMs", d.unitMs).coerceIn(50L, 1000L),
                smooth = o.optBoolean("smooth", d.smooth),
                speedRatio = o.optDouble("speedRatio", d.speedRatio.toDouble()).toFloat()
                    .coerceIn(0.1f, 8f),
                chunkMs = o.optLong("chunkMs", d.chunkMs).coerceIn(100L, 400L),
                horizontal = o.optBoolean("horizontal", d.horizontal),
                curve = o.optDouble("curve", d.curve.toDouble()).toFloat().coerceIn(0.2f, 5f),
                deadzone = o.optDouble("deadzone", d.deadzone.toDouble()).toFloat()
                    .coerceIn(0f, InputMath.MAX_DEADZONE)
            )
        }
    }
}

data class GestureCfg(
    val tapMs: Long = 45L,
    val longPressMs: Long = 650L,
    val doubleTapMs: Long = 120L,
    /** 滑动手势位移 = swipeDistanceRatio × 屏高 */
    val swipeDistanceRatio: Float = 0.32f,
    val swipeMs: Long = 200L,
    /** 十字键连发冷却（按住时重复触发滑动） */
    val hatRepeatMs: Long = 340L,
    /** 十字键当摇杆用时，从 0 加速到满速所需时间（数字量→模拟量斜坡） */
    val hatRampMs: Long = 80L,
    /** 「切换映射」需要按住多久才触发（短按不触发，可透传给前台 App） */
    val pauseHoldMs: Long = 700L,
    /**
     * 返回动作的实现方式：
     *  RELEASE = 临时移除焦点窗 → 注入全局 BACK → 收回焦点（默认，实测唯一稳定）
     *  GLOBAL  = performGlobalAction(BACK)，仅当焦点窗未抢焦点时有效
     *  GESTURE = 注入边缘返回手势（部分系统会忽略注入触摸）
     */
    val backMode: String = "RELEASE",
    /** 手势返回从哪一侧划入：LEFT / RIGHT */
    val backEdge: String = "LEFT"
) {
    fun toJson() = JSONObject().apply {
        put("tapMs", tapMs)
        put("longPressMs", longPressMs)
        put("doubleTapMs", doubleTapMs)
        put("swipeDistanceRatio", swipeDistanceRatio.toDouble())
        put("swipeMs", swipeMs)
        put("hatRepeatMs", hatRepeatMs)
        put("hatRampMs", hatRampMs)
        put("pauseHoldMs", pauseHoldMs)
        put("backMode", backMode)
        put("backEdge", backEdge)
    }

    companion object {
        fun from(o: JSONObject?): GestureCfg {
            val d = GestureCfg()
            if (o == null) return d
            return GestureCfg(
                tapMs = o.optLong("tapMs", d.tapMs).coerceIn(10L, 1000L),
                longPressMs = o.optLong("longPressMs", d.longPressMs).coerceIn(100L, 5000L),
                doubleTapMs = o.optLong("doubleTapMs", d.doubleTapMs).coerceIn(20L, 1000L),
                swipeDistanceRatio = o.optDouble("swipeDistanceRatio", d.swipeDistanceRatio.toDouble()).toFloat()
                    .coerceIn(0.02f, 1f),
                swipeMs = o.optLong("swipeMs", d.swipeMs).coerceIn(30L, 2000L),
                hatRepeatMs = o.optLong("hatRepeatMs", d.hatRepeatMs).coerceIn(60L, 3000L),
                hatRampMs = o.optLong("hatRampMs", d.hatRampMs).coerceIn(60L, 3000L),
                pauseHoldMs = o.optLong("pauseHoldMs", d.pauseHoldMs).coerceIn(200L, 5000L),
                backMode = o.optString("backMode", d.backMode)
                    .let { if (it == "RELEASE" || it == "GLOBAL" || it == "GESTURE") it else d.backMode },
                backEdge = o.optString("backEdge", d.backEdge)
                    .let { if (it == "LEFT" || it == "RIGHT") it else d.backEdge }
            )
        }
    }
}

/** 轴反转，适配不同人的操作习惯 */
data class InvertCfg(
    val leftX: Boolean = false,
    val leftY: Boolean = false,
    val rightX: Boolean = true,
    val rightY: Boolean = true,
    val hatX: Boolean = true,
    val hatY: Boolean = true
) {
    fun toJson() = JSONObject().apply {
        put("leftX", leftX)
        put("leftY", leftY)
        put("rightX", rightX)
        put("rightY", rightY)
        put("hatX", hatX)
        put("hatY", hatY)
    }

    companion object {
        fun from(o: JSONObject?) = InvertCfg().let { d ->
            if (o == null) d else InvertCfg(
                leftX = o.optBoolean("leftX", d.leftX),
                leftY = o.optBoolean("leftY", d.leftY),
                rightX = o.optBoolean("rightX", d.rightX),
                rightY = o.optBoolean("rightY", d.rightY),
                hatX = o.optBoolean("hatX", d.hatX),
                hatY = o.optBoolean("hatY", d.hatY)
            )
        }
    }
}

/**
 * 功能 → 按键 的绑定表。每个功能可绑定多个按键。
 * 存储时把所有功能都写进去（包含空列表），这样"删空"也能被持久化。
 */
data class BindingCfg(val keyMap: Map<String, List<Int>> = emptyMap()) {

    fun keysOf(action: ActionId): List<Int> = keyMap[action.name] ?: action.defaultKeys

    /**
     * 找出已经绑定了该按键的**其它**功能。
     *
     * reverseIndex 同一按键只认第一个功能（枚举顺序靠前的），后来者会静默失效。
     * 绑定前先查一下，UI 才能提示用户"这个键已经是 XX 了"。
     *
     * PAUSE（切换映射）不计入冲突：它由服务单独处理（长按），
     * 与其它动作共用同一个键是**支持的用法**（长按切换 / 短按做别的）。
     */
    fun conflictsFor(action: ActionId, key: Int): List<ActionId> {
        // PAUSE 是长按语义，与其它动作共用同一个键是**支持的用法**
        // （例：Start 短按 = 光标开关，长按 = 切换映射），所以两边都跳过。
        if (action == ActionId.PAUSE) return emptyList()
        val out = ArrayList<ActionId>()
        for (a in ActionId.entries) {
            if (a == action) continue
            if (a == ActionId.PAUSE) continue
            if (keysOf(a).contains(key)) out.add(a)
        }
        return out
    }

    /**
     * 该按键上的**短按**动作（不含 PAUSE）。
     *
     * 「切换映射」是长按语义：同一个键可以再绑一个普通动作，
     * 没到长按阈值就松开时执行它（例：Start 短按 = 光标显示/隐藏）。
     */
    fun shortPressActionFor(key: Int): ActionId? {
        for (a in ActionId.entries) {
            if (a == ActionId.PAUSE) continue
            if (keysOf(a).contains(key)) return a
        }
        return null
    }

    /** 反向索引：keyCode → 功能（同一按键只认第一个功能，避免歧义） */
    fun reverseIndex(): Map<Int, ActionId> {
        val map = LinkedHashMap<Int, ActionId>()
        for (a in ActionId.entries) {
            for (k in keysOf(a)) map.putIfAbsent(k, a)
        }
        return map
    }

    fun withKey(action: ActionId, key: Int): BindingCfg {
        val cur = keysOf(action)
        if (cur.contains(key)) return this
        return copy(keyMap = keyMap + (action.name to (cur + key)))
    }

    fun withoutKey(action: ActionId, key: Int): BindingCfg =
        copy(keyMap = keyMap + (action.name to keysOf(action).filter { it != key }))

    fun cleared(action: ActionId): BindingCfg = copy(keyMap = keyMap + (action.name to emptyList()))

    fun resetAll(): BindingCfg = copy(keyMap = emptyMap())

    fun toJson(): JSONObject = JSONObject().apply {
        for (a in ActionId.entries) {
            val arr = JSONArray()
            keysOf(a).forEach { arr.put(it) }
            put(a.name, arr)
        }
    }

    companion object {
        fun from(o: JSONObject?): BindingCfg {
            if (o == null) return BindingCfg()
            val map = LinkedHashMap<String, List<Int>>()
            for (a in ActionId.entries) {
                val arr = o.optJSONArray(a.name) ?: continue
                val list = ArrayList<Int>()
                for (i in 0 until arr.length()) list.add(arr.optInt(i))
                map[a.name] = list
            }
            return BindingCfg(map)
        }
    }
}

/** 应用分级：完全暂停（安全类）/ 键盘优先（聊天搜索类，让出焦点） */
data class AppLists(
    val blacklist: Set<String> = emptySet(),
    val keyboardPriority: Set<String> = emptySet()
) {
    fun toJson() = JSONObject().apply {
        put("blacklist", JSONArray(blacklist.toList()))
        put("keyboardPriority", JSONArray(keyboardPriority.toList()))
    }

    companion object {
        fun from(o: JSONObject?) = AppLists().let { d ->
            if (o == null) d else AppLists(
                blacklist = jsonArrayToStringSet(o.optJSONArray("blacklist")),
                keyboardPriority = jsonArrayToStringSet(o.optJSONArray("keyboardPriority"))
            )
        }
    }
}

private fun jsonArrayToStringSet(a: JSONArray?): Set<String> {
    if (a == null) return emptySet()
    val s = mutableSetOf<String>()
    for (i in 0 until a.length()) s.add(a.optString(i))
    return s
}

data class AppSettings(
    val cursor: CursorCfg = CursorCfg(),
    val scroll: ScrollCfg = ScrollCfg(),
    val gesture: GestureCfg = GestureCfg(),
    val invert: InvertCfg = InvertCfg(),
    val binding: BindingCfg = BindingCfg(),
    val lists: AppLists = AppLists(),
    /** 映射总开关（用户主动启用/暂停） */
    val mappingEnabled: Boolean = true,
    val hud: Boolean = true,
    /** 外观：SYSTEM 跟随系统 / LIGHT 浅色 / DARK 深色 */
    val themeMode: String = "SYSTEM",
    /** 允许在映射开启时使用输入法（键盘模式）：让出输入焦点以便打字，期间摇杆/滚动暂停、按键映射照常 */
    val allowImeWhileMapping: Boolean = true,
    /** 十字键 ⇄ 左摇杆 角色互换（给没有摇杆的小手柄用）：十字键驱动光标，左摇杆触发滑动 */
    val swapDpadStick: Boolean = false,
    /**
     * 用哪个摇杆移动光标：LEFT = 左摇杆（默认），RIGHT = 右摇杆。
     * 另一个摇杆自动改做滚动。
     *
     * 存在的意义：有些手柄（比如无线的）左手压着摇杆会抖，
     * 换成右手推右摇杆移光标更稳。
     */
    val cursorStick: String = "LEFT",
    /**
     * 简洁模式：隐藏教程性/诊断性的说明文字与次要状态行。
     * 默认开启 —— 普通用户只关心"能不能用"，不需要看一堆解释。
     * 在「调试」页可以关掉，看到完整界面。
     */
    val compactMode: Boolean = true
) {
    fun toJson(): String = JSONObject().apply {
        put("version", VERSION)
        put("cursor", cursor.toJson())
        put("scroll", scroll.toJson())
        put("gesture", gesture.toJson())
        put("invert", invert.toJson())
        put("binding", binding.toJson())
        put("lists", lists.toJson())
        put("mappingEnabled", mappingEnabled)
        put("hud", hud)
        put("themeMode", themeMode)
        put("allowImeWhileMapping", allowImeWhileMapping)
        put("swapDpadStick", swapDpadStick)
        put("cursorStick", cursorStick)
        put("compactMode", compactMode)
    }.toString()

    companion object {

        /**
         * 配置结构版本。唯一事实来源 —— ConfigIO 的信封 schema 也用这个值，
         * 避免出现两套版本号互相不认的情况。
         */
        /** 9：scroll 增加 smooth / speedRatio / chunkMs（丝滑滚动） */
        const val VERSION = 9

        private val THEME_MODES = setOf("SYSTEM", "LIGHT", "DARK")

        /** 移动光标用的摇杆：LEFT / RIGHT */
        val CURSOR_STICKS = setOf("LEFT", "RIGHT")

        /**
         * 严格解析：解析失败返回失败原因，不再静默吞异常。
         *
         * 缺字段一律用默认值 —— 那是旧版本配置的正常形态，不是错误。
         * 但 JSON 本身坏掉、或版本比当前新，必须让调用方知道。
         */
        fun parse(s: String?): Result<AppSettings> {
            if (s.isNullOrBlank()) return Result.success(AppSettings())
            return try {
                val o = JSONObject(s)
                val ver = o.optInt("version", 1)
                if (ver > VERSION) {
                    Result.failure(
                        IllegalArgumentException("配置版本 $ver 高于当前支持的 $VERSION，请先升级应用")
                    )
                } else {
                    Result.success(
                        AppSettings(
                            cursor = CursorCfg.from(o.optJSONObject("cursor")),
                            scroll = ScrollCfg.from(o.optJSONObject("scroll")),
                            gesture = GestureCfg.from(o.optJSONObject("gesture")),
                            invert = InvertCfg.from(o.optJSONObject("invert")),
                            binding = BindingCfg.from(o.optJSONObject("binding")),
                            lists = AppLists.from(o.optJSONObject("lists")),
                            mappingEnabled = o.optBoolean("mappingEnabled", true),
                            hud = o.optBoolean("hud", true),
                            themeMode = o.optString("themeMode", "SYSTEM")
                                .let { if (it in THEME_MODES) it else "SYSTEM" },
                            allowImeWhileMapping = o.optBoolean("allowImeWhileMapping", false),
                            swapDpadStick = o.optBoolean("swapDpadStick", false),
                            cursorStick = o.optString("cursorStick", "LEFT")
                                .let { if (it in CURSOR_STICKS) it else "LEFT" },
                            compactMode = o.optBoolean("compactMode", true)
                        )
                    )
                }
            } catch (t: Throwable) {
                Result.failure(
                    IllegalArgumentException(
                        "配置 JSON 解析失败：" + (t.message ?: t.javaClass.simpleName)
                    )
                )
            }
        }

        /**
         * 宽松解析：失败时回退到默认设置。
         * 只用于读取本地存储 —— 宁可退回默认值，也不能让一次坏文件把用户配置清零。
         */
        fun fromJson(s: String?): AppSettings = parse(s).getOrElse { AppSettings() }
    }
}
