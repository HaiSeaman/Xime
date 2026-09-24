package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction

/** 手势槽位。 */
enum class KeyGesture {
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,
}

/**
 * 单个动作定义（对应 YAML 中一个手势槽位的取值）。
 *
 * @param action 动作类型；null 表示无动作
 * @param value 动作参数（如 commit 的上屏文本、command 的命令名）
 * @param label 显示标签
 * @param labels 多行标签
 * @param icon 图标名（标签以 `@` 前缀书写时提取）
 * @param display 标签/气泡显示位置（key：画在键面；bubble：不画键面，留给气泡；both：都画）
 * @param bubble 运行时是否弹出该手势的内容气泡（与 display 无关，display 只管静态提示位置）
 * @param repeat 是否支持长按重复
 * @param sticky 是否粘滞（按下后锁定，如大小写锁定）
 */
class KeyAction(
    val action: GestureAction? = null,
    val value: String = "",
    val label: String = "",
    val labels: List<String> = emptyList(),
    val icon: String = "",
    val display: DisplayMode = DisplayMode.BOTH,
    val bubble: Boolean = true,
    val repeat: Boolean = false,
    val sticky: Boolean = false,
)

/**
 * 长按配置。
 *
 * 单动作对象 → [values] 仅一项；数组 → 气泡列表多项。
 *
 * @param display 显示模式（key：直接显示在按键上；bubble：气泡弹出）
 * @param values 长按候选动作
 * @param repeat 是否支持长按重复（单动作时有效）
 */
class LongPressAction(
    val display: DisplayMode = DisplayMode.KEY,
    val values: List<KeyAction> = emptyList(),
    val repeat: Boolean = false,
)

/**
 * 一个键的手势绑定表。
 *
 * @param composing 组合态手势覆盖（`when_composing`），为空则沿用各槽位默认
 * @param sticky 键级粘滞（按下锁定，如大小写）
 * @param width 布局宽度权重；null 时功能键用内置默认宽度、字母键为 1
 */
class KeyBinding(
    val tap: KeyAction? = null,
    val doubleTap: KeyAction? = null,
    val longPress: LongPressAction? = null,
    val swipeUp: KeyAction? = null,
    val swipeDown: KeyAction? = null,
    val swipeLeft: KeyAction? = null,
    val swipeRight: KeyAction? = null,
    val composing: KeyBinding? = null,
    val sticky: Boolean = false,
    val width: Float? = null,
) {
    /** 按槽位取动作；[KeyGesture.LONG_PRESS] 返回长按首个动作。 */
    fun gesture(gesture: KeyGesture): KeyAction? = when (gesture) {
        KeyGesture.TAP -> tap
        KeyGesture.DOUBLE_TAP -> doubleTap
        KeyGesture.SWIPE_UP -> swipeUp
        KeyGesture.SWIPE_DOWN -> swipeDown
        KeyGesture.SWIPE_LEFT -> swipeLeft
        KeyGesture.SWIPE_RIGHT -> swipeRight
        KeyGesture.LONG_PRESS -> longPress?.values?.firstOrNull()
    }
}