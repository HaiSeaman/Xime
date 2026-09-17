package com.kingzcheung.xime.plugin.core.api

import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable

/**
 * 工具面板结果显示方式：宿主按插件元数据（manifest.capabilities.tool.display）决策
 * 结果交互（直接上屏 or 纯展示面板），插件侧不再返回。
 * manifest 取值小写：`direct` | `passive`（与 inputMode 风格一致）。
 */
enum class ToolResult {
    /** 结果生成结束直接上屏（如 AI 翻译）。 */
    DIRECT,

    /**
     * 纯展示面板（InfoPanel）：无输入框、无生成动作（enter 不触发 generate）、
     * 点击节点不上屏。插件通过 [ToolPanelState.ui] 声明式描述内容
     * （统一 [com.kingzcheung.xime.plugin.core.config.UiNode] 白名单节点）；
     * 多条候选通过 [ToolPanelState.items] 返回，宿主渲染为点击上屏条目
     * （替代原 SELECT 全屏结果页，如 AI 智能回复）。
     */
    PASSIVE,
}

/**
 * 工具面板状态（宿主渲染、插件给数据）。
 */
data class ToolPanelState(
    val inputText: String = "",
    val items: List<PluginResultItem> = emptyList(),
    /** 是否正在生成中（SSE 流式期间为 true，宿主据此展示 loading 并轮询刷新）。 */
    val loading: Boolean = false,
    /**
     * passive 纯展示节点树（声明式 UI，统一模型 [com.kingzcheung.xime.plugin.core.config.UiNode]）。
     * 面板展示白名单：SECTION / TEXT / METRIC / DIVIDER / BUTTON；
     * 未知类型节点由解析层丢弃，渲染层降级为文本兜底。
     */
    val ui: List<com.kingzcheung.xime.plugin.core.config.UiNode>? = null,
)

/**
 * 工具类插件（`type: tool`）契约：宿主渲染通用面板（ToolPanel/InfoPanel），插件提供数据与事件处理。
 *
 * ## 返回协议（宿主强制校验：非法数据将被丢弃并输出协议错误日志）
 *
 * [getPanelState] 必须返回 JS 对象，字段：
 * - `items`（必填，数组）：候选结果，每个元素 `{ id: string, text: string, insertText?: string, imageUrl?: string }`；
 *   `id` 必须非空且全表唯一，`text` 必须非空，否则该元素被宿主丢弃。
 *   多候选（多条回复）时建议 id 用序号；单条时可用固定值（如 `"result"`）。
 *   display=passive 时宿主将 items 渲染为 InfoPanel 内点击上屏条目；
 *   display=direct 时生成结束后 items[0] 直接上屏。
 * - `loading`（布尔）：生成期间为 true，宿主据此轮询刷新直至 false。
 * - `inputText`（字符串，可选）：面板输入框内容回显，缺省回退为宿主传入的输入。
 * - `ui`（数组，可选）：纯展示节点树，仅 `display: passive` 时由宿主渲染（InfoPanel）。
 *   节点 = 统一 [com.kingzcheung.xime.plugin.core.config.UiNode] 结构
 *   `{ type: string, key?, label?, value?, options?, ... }`，面板展示白名单：
 *   `section(label)` / `text(value, style?)` / `metric(label, value, unit?)` /
 *   `divider` / `button(key, label)`；未知 type 由解析层丢弃。
 *   旧字段名（title/content/actionId、action→button）解析层兼容。
 *
 * 传输方式（同步 HTTP / SSE 流式）与结果呈现由插件元数据声明，宿主只消费上述结构化数据。
 *
 * - [getPanelState]：返回面板状态（输入框内容 + 候选列表）
 * - [onPanelInput]：面板输入框内容变化通知
 * - [onPanelAction]：`generate` 触发生成（宿主保留 action id）
 * - [onPanelItemClick]：点候选 → 宿主上屏（host 负责选区替换/追加）
 */
interface ToolPlugin : IPluginEntryClass, IPluginConfigurable {
    fun getPanelState(inputText: String): ToolPanelState

    fun onPanelInput(text: String) {}

    fun onPanelAction(actionId: String) {}

    fun onPanelItemClick(itemId: String) {}
}