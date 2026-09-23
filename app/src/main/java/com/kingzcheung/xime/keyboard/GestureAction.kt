package com.kingzcheung.xime.keyboard

import android.view.KeyEvent

/**
 * 手势动作执行上下文接口。
 *
 * 由 InputMethodService 实现并注入给 [GestureAction.execute]，封装所有执行手势动作所需的能力。
 * 枚举值通过此接口操作编辑器，不直接依赖 InputConnection，便于测试和替换。
 */
interface ActionExecutor {

    /**
     * 上屏指定文本。
     * @param text 要上屏的文本内容
     */
    fun commitText(text: String)

    /**
     * 执行系统编辑器菜单动作。
     * @param actionId Android 内置动作 ID，如 [android.R.id.selectAll]、[android.R.id.copy]、
     *                 [android.R.id.cut]、[android.R.id.paste]、[android.R.id.undo]
     */
    fun performEditorMenuAction(actionId: Int)

    /**
     * 发送按键事件（用于光标移动等操作）。
     * @param keyCode 按键码，如 [KeyEvent.KEYCODE_MOVE_HOME]、[KeyEvent.KEYCODE_MOVE_END]
     */
    fun sendKeyEvent(keyCode: Int)

    /**
     * 执行内置命令。
     * @param name 命令名，如 "clear_composition"（清空输入）
     */
    fun executeCommand(name: String)

    /** 重复上一次输入。 */
    fun repeatLastInput()

    /**
     * 派发功能键语义进服务层按键路由（ImeKeyRouter），复用其全部既有状态机
     * （组合态提交、候选选择、退格合并、T9 partial 等均在路由内处理）。
     * @param key 功能键字符串，如 "enter"、"space"、"delete"、"clear_all"
     */
    fun dispatchKey(key: String)
}

/**
 * 键盘手势动作枚举。
 *
 * 每个枚举值自包含执行逻辑（[execute]），零分支调度。
 * 新增动作只需添加一个枚举值 + 实现 execute() 即可。
 *
 * YAML 中的 action 字段值通过 [fromValue] 映射到枚举。
 */
enum class GestureAction(val value: String) {

    /** 上屏文本，value 为上屏内容。 */
    COMMIT("commit") {
        override fun execute(context: ActionExecutor, value: String) {
            context.commitText(value)
        }
    },

    /**
     * 提交给 rime 引擎：value 作为按键输入走引擎组合路径（与物理键盘敲键同一条
     * 路由，中文模式下字母进拼音组合、由候选选词上屏），不直接上屏。
     * tap 槽位省略 action 时的默认语义（tap 实际执行本就走按键路由）；
     * 也可显式配置在 swipe/长按上，与 [COMMIT]（即选即上屏）相对。
     * 键盘分发层将 tap 上的 SEND_RIME 与 COMMIT 同等处理（均走 onKeyPress），
     * 与旧配置行为完全一致。
     */
    SEND_RIME("send_rime") {
        override fun execute(context: ActionExecutor, value: String) {
            context.dispatchKey(value)
        }
    },

    /** 执行内置命令，value 为命令名（如 "clear_composition"）。 */
    COMMAND("command") {
        override fun execute(context: ActionExecutor, value: String) {
            context.executeCommand(value)
        }
    },

    /** 全选。 */
    SELECT_ALL("select_all") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.selectAll)
        }
    },

    /** 复制。 */
    COPY("copy") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.copy)
        }
    },

    /** 剪切。 */
    CUT("cut") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.cut)
        }
    },

    /** 粘贴。 */
    PASTE("paste") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.paste)
        }
    },

    /** 移动到行首。 */
    LINE_START("line_start") {
        override fun execute(context: ActionExecutor, value: String) {
            context.sendKeyEvent(KeyEvent.KEYCODE_MOVE_HOME)
        }
    },

    /** 移动到行尾。 */
    LINE_END("line_end") {
        override fun execute(context: ActionExecutor, value: String) {
            context.sendKeyEvent(KeyEvent.KEYCODE_MOVE_END)
        }
    },

    /** 撤销。 */
    UNDO("undo") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.undo)
        }
    },

    /** 仅显示，无操作。 */
    NONE("none") {
        override fun execute(context: ActionExecutor, value: String) { /* no-op */ }
    },

    /** 重复上一次输入。 */
    REPEAT("repeat") {
        override fun execute(context: ActionExecutor, value: String) {
            context.repeatLastInput()
        }
    },

    /** 切换键盘路由/面板（如打开 emoji、符号面板）。
     *  由 KeyboardView 拦截处理，[ActionExecutor] 层 no-op。 */
    SWITCH_ROUTE("switch_route") {
        override fun execute(context: ActionExecutor, value: String) { /* no-op, handled at UI layer */ }
    },

    /** 切换中/英文输入模式。
     *  UI 分发层优先处理（附带 resetShift 与 ascii 状态参数）；此处为兜底路径。 */
    TOGGLE_ASCII("toggle_ascii") {
        override fun execute(context: ActionExecutor, value: String) {
            context.dispatchKey("ime_switch")
        }
    },

    /** 删除/退格。UI 分发层优先处理；此处为兜底路径（复用退格合并状态机）。 */
    DELETE("delete") {
        override fun execute(context: ActionExecutor, value: String) {
            context.dispatchKey("delete")
        }
    },

    /** 切换符号键盘。纯 UI 层行为（KeyboardView 内部切换），ActionExecutor 层 no-op。 */
    TOGGLE_SYMBOLS("toggle_symbols") {
        override fun execute(context: ActionExecutor, value: String) { /* no-op, handled at UI layer */ }
    },

    /** 回车键语义（组合态提交编码 / 空闲态编辑器动作），复用服务层回车状态机。 */
    ENTER("enter") {
        override fun execute(context: ActionExecutor, value: String) {
            context.dispatchKey("enter")
        }
    },

    /** 空格键语义（组合态选首候选 / 空闲态上屏空格），复用服务层空格状态机。 */
    SPACE("space") {
        override fun execute(context: ActionExecutor, value: String) {
            context.dispatchKey("space")
        }
    },

    /** 上滑清空：输入态清输入态 / 空闲态清空全部已上屏（记录撤回），复用服务层逻辑。 */
    CLEAR_ALL("clear_all") {
        override fun execute(context: ActionExecutor, value: String) {
            context.dispatchKey("clear_all")
        }
    },

    /** 下滑撤回：恢复最近一次 clear_all 清空的内容，仅空闲态有效。 */
    UNDO_CLEAR("undo_clear") {
        override fun execute(context: ActionExecutor, value: String) {
            context.dispatchKey("undo_clear")
        }
    },

    /** 切换大小写状态。纯 UI 层行为（KeyboardViewModel.toggleShift），ActionExecutor 层 no-op。 */
    TOGGLE_SHIFT("toggle_shift") {
        override fun execute(context: ActionExecutor, value: String) { /* no-op, handled at UI layer */ }
    };

    /**
     * 执行本动作。
     * @param context 执行上下文，提供 InputConnection 等能力
     * @param value   动作参数（如 COMMIT 时的上屏文本、COMMAND 时的命令名）
     */
    abstract fun execute(context: ActionExecutor, value: String)

    companion object {
        private val map = entries.associateBy { it.value }

        /** 根据 YAML 字符串值查找对应的枚举，找不到返回 null。 */
        fun fromValue(value: String): GestureAction? = map[value]
    }
}
