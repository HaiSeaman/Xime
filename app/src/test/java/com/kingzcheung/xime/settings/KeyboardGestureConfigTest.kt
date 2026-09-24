package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGestureConfigTest {

    // ── 槽位默认动作 ──

    @Test
    fun `tap 字符串简写默认为 send_rime`() {
        val kc = parse("q: { tap: \"q\" }")["q"]!!
        assertEquals("q", kc.tap!!.label)
        assertEquals(GestureAction.SEND_RIME, kc.tap!!.action)
        assertEquals("q", kc.tap!!.value)
    }

    @Test
    fun `swipe 字符串简写默认为 commit`() {
        val kc = parse("a: { tap: \"a\", swipe_up: \"!\", swipe_left: \"?\" }")["a"]!!
        assertEquals(GestureAction.COMMIT, kc.swipeUp!!.action)
        assertEquals("!", kc.swipeUp!!.value)
        assertEquals(GestureAction.COMMIT, kc.swipeLeft!!.action)
        assertEquals("?", kc.swipeLeft!!.value)
    }

    @Test
    fun `左右滑对象命令动作解析`() {
        val kc = parse("""
            delete:
              swipe_left: { action: "command", value: "clear_composition" }
              swipe_right: { action: "command", value: "clear_composition" }
        """.trimIndent())["delete"]!!
        assertEquals(GestureAction.COMMAND, kc.swipeLeft!!.action)
        assertEquals("clear_composition", kc.swipeLeft!!.value)
        assertEquals(GestureAction.COMMAND, kc.swipeRight!!.action)
        assertEquals("clear_composition", kc.swipeRight!!.value)
    }

    @Test
    fun `double_tap 字符串简写默认为 commit`() {
        val kc = parse("a: { tap: \"a\", double_tap: \"A\" }")["a"]!!
        assertEquals(GestureAction.COMMIT, kc.doubleTap!!.action)
        assertEquals("A", kc.doubleTap!!.value)
    }

    // ── 对象格式 ──

    @Test
    fun `对象格式指定 action copy`() {
        val su = parse("""
            c:
              swipe_up: { label: "复制", action: "copy" }
        """.trimIndent())["c"]!!.swipeUp!!
        assertEquals("复制", su.label)
        assertEquals(GestureAction.COPY, su.action)
    }

    @Test
    fun `对象格式省略 action 时取槽位默认`() {
        val tap = parse("""
            "comma":
              tap: { label: "，", value: "," }
        """.trimIndent())["comma"]!!.tap!!
        assertEquals(GestureAction.SEND_RIME, tap.action)
        assertEquals(",", tap.value)
    }

    @Test
    fun `action null 表示无动作`() {
        val sd = parse("s: { swipe_down: { label: \"\", action: null } }")["s"]!!.swipeDown!!
        assertNull(sd.action)
    }

    @Test
    fun `未知 action 不生效`() {
        val sd = parse("s: { swipe_up: { label: \"x\", action: \"no_such\" } }")["s"]!!.swipeUp!!
        assertNull(sd.action)
    }

    // ── display ──

    @Test
    fun `字符串简写 display 默认 both 对象默认 key`() {
        val kc = parse("a: { tap: \"a\", swipe_up: \"@\", swipe_down: { label: \"@\", action: \"commit\" } }")["a"]!!
        assertEquals(DisplayMode.BOTH, kc.swipeUp!!.display)
        assertEquals(DisplayMode.KEY, kc.swipeDown!!.display)
    }

    @Test
    fun `display bubble 解析`() {
        val sd = parse("a: { swipe_down: { label: \"@\", action: \"commit\", display: \"bubble\" } }")["a"]!!.swipeDown!!
        assertEquals(DisplayMode.BUBBLE, sd.display)
    }

    @Test
    fun `bubble 独立于 display 解析`() {
        val a = parse("""a: { swipe_up: { value: "1", display: "key", bubble: false } }""")["a"]!!.swipeUp!!
        assertEquals(DisplayMode.KEY, a.display)
        assertFalse(a.bubble)
        val b = parse("""b: { swipe_up: { value: "2", display: "bubble" } }""")["b"]!!.swipeUp!!
        assertEquals(DisplayMode.BUBBLE, b.display)
        assertTrue(b.bubble)
    }

    // ── icon ──

    @Test
    fun `label 以 @ 开头提取 icon 且 label 置空`() {
        val tap = parse("k: { tap: { label: \"@language\", action: \"toggle_ascii\" } }")["k"]!!.tap!!
        assertEquals("", tap.label)
        assertEquals("language", tap.icon)
    }

    @Test
    fun `字符串简写 @label 也提取 icon`() {
        val tap = parse("k: { tap: \"@language\" }")["k"]!!.tap!!
        assertEquals("", tap.label)
        assertEquals("language", tap.icon)
        assertEquals("@language", tap.value)
    }

    // ── long_press ──

    @Test
    fun `long_press 缺省 display 为 bubble`() {
        val lp = parse("""a: { long_press: { values: ["a", "A", "à"] } }""")["a"]!!.longPress!!
        assertEquals(DisplayMode.BUBBLE, lp.display)
        assertEquals(3, lp.values.size)
        assertEquals("a", lp.values[0].value)
        assertEquals(GestureAction.COMMIT, lp.values[0].action)
        assertEquals("à", lp.values[2].value)
    }

    @Test
    fun `long_press display key 关闭气泡`() {
        val lp = parse("""q: { long_press: { display: "key", values: ["q", "Q"] } }""")["q"]!!.longPress!!
        assertEquals(DisplayMode.KEY, lp.display)
        assertEquals(2, lp.values.size)
    }

    @Test
    fun `long_press 单动作重复写成单元素列表`() {
        val lp = parse("delete: { long_press: { values: [{ action: \"delete\", repeat: true }] } }")["delete"]!!.longPress!!
        assertEquals(1, lp.values.size)
        assertEquals(GestureAction.DELETE, lp.values[0].action)
        assertTrue(lp.values[0].repeat)
        assertTrue(lp.repeat)
    }

    @Test
    fun `long_press 多值冒泡`() {
        val lp = parse("m: { long_press: { values: [{ label: \"number\", action: \"command\", value: \"mode_change_number\" }, { label: \"symbol\", action: \"command\", value: \"mode_change_common_symbol\" }] } }")["m"]!!.longPress!!
        assertEquals(2, lp.values.size)
        assertEquals("number", lp.values[0].label)
        assertEquals("mode_change_number", lp.values[0].value)
    }

    @Test
    fun `long_press 数组简写已不再支持`() {
        val lp = parse("""a: { long_press: ["a", "A"] }""")["a"]!!.longPress
        assertNull(lp)
    }

    // ── when_composing / sticky ──

    @Test
    fun `when_composing 组合态覆盖解析`() {
        val kc = parse("""
            return:
              tap: "enter"
              when_composing:
                tap: { action: "command", value: "commit_raw" }
        """.trimIndent())["return"]!!
        assertEquals(GestureAction.SEND_RIME, kc.tap!!.action)
        assertNotNull(kc.composing)
        assertEquals(GestureAction.COMMAND, kc.composing!!.tap!!.action)
        assertEquals("commit_raw", kc.composing!!.tap!!.value)
    }

    @Test
    fun `键级 sticky 解析`() {
        val kc = parse("shift: { tap: \"shift\", sticky: true }")["shift"]!!
        assertTrue(kc.sticky)
    }

    @Test
    fun `键级 width 解析`() {
        val withWidth = parse("""enter: { tap: "enter", width: 1.2 }""")["enter"]!!
        assertEquals(1.2f, withWidth.width!!, 0.001f)
        assertNull(parse("""enter: { tap: "enter" }""")["enter"]!!.width)
    }

    // ── actions 预设 ──

    @Test
    fun `use 引用动作预设`() {
        val presets = KeysConfigHelper.parseKeyboardActionsYamlText(
            """
            keyboard:
              actions:
                switch_num: { action: "command", value: "mode_change_number" }
            """.trimIndent()
        )
        assertEquals("mode_change_number", presets["switch_num"]!!.value)
        val kc = parse("m: { tap: { use: \"switch_num\" } }", presets = presets)["m"]!!
        assertEquals(GestureAction.COMMAND, kc.tap!!.action)
        assertEquals("mode_change_number", kc.tap!!.value)
    }

    @Test
    fun `use 引用未知预设不生效`() {
        val kc = parse("m: { tap: { use: \"missing\" } }")["m"]!!
        assertNull(kc.tap!!.action)
    }

    // ── section 独立性 ──

    @Test
    fun `qwerty 与 qwerty_en 独立读取`() {
        val yaml = """
            keyboard:
              qwerty:
                keys:
                  earth: { tap: { label: "英", action: "toggle_ascii" } }
              qwerty_en:
                keys:
                  earth: { tap: { label: "中", action: "toggle_ascii" } }
        """.trimIndent()
        val zh = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty")!!
        val en = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty_en")!!
        assertEquals("英", zh["earth"]!!.tap!!.label)
        assertEquals("中", en["earth"]!!.tap!!.label)
        assertNotEquals(zh["earth"]!!.tap!!.label, en["earth"]!!.tap!!.label)
    }

    @Test
    fun `缺失键不影响其它键`() {
        val yaml = """
            keyboard:
              qwerty:
                keys:
                  q: { tap: "q" }
                  w: { tap: "w" }
        """.trimIndent()
        val zh = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty")!!
        assertEquals(2, zh.size)
        assertNotNull(zh["w"])
        assertNull(zh["z"])
    }

    // ── 辅助 ──

    private fun parse(
        keysFragment: String,
        section: String = "qwerty",
        presets: Map<String, KeyAction> = emptyMap(),
    ): Map<String, KeyBinding> {
        val indented = keysFragment.lines().joinToString("\n") { if (it.isBlank()) it else "      $it" }
        val yaml = "keyboard:\n  $section:\n    keys:\n$indented"
        return KeysConfigHelper.parseKeyboardYamlSection(yaml, section, presets) ?: emptyMap()
    }
}