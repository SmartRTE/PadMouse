package com.padmouse.mapper.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 绑定表的纯逻辑测试。 */
class BindingCfgTest {

    @Test
    fun defaultKeysAreUsedWhenNothingStored() {
        val cfg = BindingCfg()
        assertEquals(ActionId.CLICK.defaultKeys, cfg.keysOf(ActionId.CLICK))
        assertEquals(emptyList<Int>(), cfg.keysOf(ActionId.DOUBLE))
    }

    @Test
    fun withKeyAppendsWithoutDuplicating() {
        val cfg = BindingCfg()
        val a = cfg.withKey(ActionId.DOUBLE, 96)
        assertEquals(listOf(96), a.keysOf(ActionId.DOUBLE))
        val b = a.withKey(ActionId.DOUBLE, 96)
        assertEquals(listOf(96), b.keysOf(ActionId.DOUBLE))
    }

    @Test
    fun withoutKeyRemovesOnlyThatKey() {
        val cfg = BindingCfg().withKey(ActionId.DOUBLE, 96).withKey(ActionId.DOUBLE, 97)
        val after = cfg.withoutKey(ActionId.DOUBLE, 96)
        assertEquals(listOf(97), after.keysOf(ActionId.DOUBLE))
    }

    @Test
    fun clearedPersistsAnEmptyList() {
        val cfg = BindingCfg().cleared(ActionId.CLICK)
        assertEquals(emptyList<Int>(), cfg.keysOf(ActionId.CLICK))
        // 空列表必须被写进 JSON，否则"删空"会丢
        assertTrue(cfg.toJson().has("CLICK"))
        assertEquals(0, cfg.toJson().getJSONArray("CLICK").length())
    }

    @Test
    fun reverseIndexMapsKeyToItsAction() {
        val cfg = BindingCfg().cleared(ActionId.CLICK).withKey(ActionId.CLICK, 999)
        assertEquals(ActionId.CLICK, cfg.reverseIndex()[999])
    }

    @Test
    fun reverseIndexKeepsFirstActionOnConflict() {
        // CLICK 在枚举里排在 TOUCH_HOLD 之前，同一键绑两个功能时先到先得
        val cfg = BindingCfg().cleared(ActionId.CLICK).withKey(ActionId.CLICK, 999)
            .cleared(ActionId.TOUCH_HOLD).withKey(ActionId.TOUCH_HOLD, 999)
        assertEquals(ActionId.CLICK, cfg.reverseIndex()[999])
    }

    @Test
    fun roundTripsThroughJson() {
        val cfg = BindingCfg().cleared(ActionId.CLICK).withKey(ActionId.CLICK, 999)
        val back = BindingCfg.from(cfg.toJson())
        assertEquals(listOf(999), back.keysOf(ActionId.CLICK))
    }

    @Test
    fun fromNullJsonGivesDefaults() {
        assertNull(BindingCfg.from(null).keyMap["NOT_EXISTING"])
    }

    @Test
    fun conflictsForReportsOtherActionsUsingTheSameKey() {
        // 用一个默认绑定里不会出现的键码，让测试不依赖默认值
        val key = 999
        val cfg = BindingCfg().cleared(ActionId.CLICK).withKey(ActionId.CLICK, key)
        // CLICK 自己不算冲突
        assertTrue(cfg.conflictsFor(ActionId.CLICK, key).isEmpty())
        // 别的功能想绑同一个键 -> 报冲突
        val c = cfg.conflictsFor(ActionId.DOUBLE, key)
        assertEquals(1, c.size)
        assertEquals(ActionId.CLICK, c[0])
    }

    @Test
    fun defaultBindingsHaveNoAmbiguousKeysBetweenDifferentActions() {
        // 默认绑定里，同一个按键不应该同时属于两个功能
        // （反向索引只认枚举里靠前的那个，另一个会静默失效）
        val cfg = BindingCfg()
        val conflicts = ActionId.entries.flatMap { a ->
            cfg.keysOf(a).filter { k -> cfg.conflictsFor(a, k).isNotEmpty() }
                .map { k -> a.name + " <-> " + k }
        }
        assertTrue(
            "默认绑定不该有冲突，实际: " + conflicts,
            conflicts.isEmpty()
        )
    }

    @Test
    fun pauseKeySharedWithShortPressActionIsAllowed() {
        // START 同时是 PAUSE(长按) 与 CURSOR_TOGGLE(短按) —— 这是支持的用法
        val cfg = BindingCfg()
        assertEquals(ActionId.CURSOR_TOGGLE, cfg.shortPressActionFor(108))
        assertTrue(cfg.keysOf(ActionId.PAUSE).contains(108))
    }

    @Test
    fun shortPressActionForIgnoresPauseAction() {
        // START 同时是 PAUSE（长按）和 CURSOR_TOGGLE（短按）
        val cfg = BindingCfg()
        assertEquals(ActionId.CURSOR_TOGGLE, cfg.shortPressActionFor(108))
    }

    @Test
    fun shortPressActionForReturnsNullWhenOnlyPauseIsBound() {
        // 只绑了 PAUSE（长按语义）的键，没有短按动作
        val cfg = BindingCfg().cleared(ActionId.CURSOR_TOGGLE)
        assertNull(cfg.shortPressActionFor(108))
    }

    @Test
    fun pauseDoesNotCountAsConflict() {
        // PAUSE 与其它动作共用同一个键是支持���用法，不算冲突
        val cfg = BindingCfg()
        assertTrue(cfg.conflictsFor(ActionId.CURSOR_TOGGLE, 108).isEmpty())
    }
}