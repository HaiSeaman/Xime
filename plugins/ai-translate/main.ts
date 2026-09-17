// AI 翻译插件（TypeScript 源码，xime-plugin 编译为 QuickJS 单文件 main.js，SSE 流式）
//
// 职责划分：
//   插件  = prompt 模板组装（{context}/{targetLang}）+ 发起流式请求（await host.http.stream）+ 累积译文
//   宿主  = 通用工具面板 + 结果交互自适应（1 条自动上屏替换选区 / 多条候选选择）
//     host.http.stream   async SSE 流式（await 建立，失败 reject XimeError；事件经 plugin.sse.onData/onDone/onError 回调槽投递）
//     host.config        配置存储
//     JSON               原生 JSON.parse/JSON.stringify（沙箱无 host.json，解析失败抛异常需 try/catch）

const KEY_API_KEY = 'apiKey';
const KEY_BASE_URL = 'baseUrl';
const KEY_MODEL = 'model';
const KEY_TARGET_LANG = 'targetLang';
const KEY_PROMPT = 'prompt';

const DEFAULTS = {
  baseUrl: 'https://api.openai.com/v1',
  model: 'gpt-4o-mini',
  targetLang: '简体中文',
  prompt: '你是专业翻译。请把下面的内容翻译成{targetLang}，只输出译文，不要解释、不要引号。\n{context}',
};

/** 面板候选条目（宿主渲染并点选上屏）。 */
interface ResultItem {
  id: string;
  text: string;
}

let lastContext = '';
let buffer = '';
let generating = false;
let sessionId = -1;

function buildItems(): ResultItem[] {
  if (buffer === '') return [];
  return [{ id: 'result', text: buffer }];
}

// ================= 插件定义（宿主按扩展点路由调用） =================

const plugin = definePlugin({
  // ================= 配置 schema（与 manifest 一致，插件中心表单数据源） =================

  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          key: KEY_API_KEY,
          label: 'API Key',
          type: 'secret',
          placeholder: '输入 LLM API Key',
          helpText: 'OpenAI 兼容接口的 API Key',
        },
        {
          key: KEY_BASE_URL,
          label: '接口地址',
          type: 'text',
          defaultValue: DEFAULTS.baseUrl,
          helpText: 'OpenAI 兼容接口地址（/chat/completions 前缀），域名将自动获得联网授权',
        },
        {
          key: KEY_MODEL,
          label: '模型',
          type: 'text',
          defaultValue: DEFAULTS.model,
        },
        {
          key: KEY_TARGET_LANG,
          label: '目标语言',
          type: 'text',
          defaultValue: DEFAULTS.targetLang,
          helpText: '翻译目标语言（如 简体中文 / English / 日本語）',
        },
        {
          key: KEY_PROMPT,
          label: '翻译模板',
          type: 'textarea',
          defaultValue: DEFAULTS.prompt,
          helpText: '翻译 prompt 模板，{context} 替换为待翻译文本，{targetLang} 替换为目标语言',
        },
      ];
    },
  },

  panel: {
    state(input: XimePanelInput): XimePanelState {
      return {
        inputText: input.inputText,
        items: buildItems(),
        loading: generating,
      };
    },

    onInput(input: XimePanelInputEvent): void {
      lastContext = input.value || '';
    },

    async onAction(input: XimePanelActionEvent): Promise<void> {
      if (input.actionId !== 'generate') return;
      const context = lastContext;
      if (context === '') {
        host.logError('请先输入待翻译内容');
        return;
      }
      if (generating) return;

      const apiKey = host.config.get(KEY_API_KEY) || '';
      if (apiKey === '') {
        host.logError('AI 翻译未配置 API Key');
        return;
      }

      buffer = '';
      generating = true;

      let baseUrl = host.config.get(KEY_BASE_URL) || DEFAULTS.baseUrl;
      baseUrl = baseUrl.replace(/\/+$/, '');
      const model = host.config.get(KEY_MODEL) || DEFAULTS.model;
      const targetLang = host.config.get(KEY_TARGET_LANG) || DEFAULTS.targetLang;
      let prompt = (host.config.get(KEY_PROMPT) || DEFAULTS.prompt).split('{context}').join(context);
      prompt = prompt.split('{targetLang}').join(targetLang);

      // 请求体必须是 Uint8Array（宿主 bytes() 只认字节，JS 字符串会变 null）
      const body = new TextEncoder().encode(JSON.stringify({
        model: model,
        messages: [
          { role: 'system', content: '你是专业翻译，只输出译文。' },
          { role: 'user', content: prompt },
        ],
        temperature: 0.3,
        stream: true,
        // qwen3 等推理模型默认把输出放进 reasoning_content（content 为空），
        // 关闭思考模式让译文直接走 content；非推理模型（如 gpt-4o-mini）会忽略此字段
        enable_thinking: false,
      }));
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + apiKey,
      };
      const url = baseUrl + '/chat/completions';

      // SSE 流式：await 建立，resolve 会话 id（回调槽 sse.onData/onDone/onError 携带该 id）；失败 reject XimeError
      try {
        sessionId = await host.http.stream(url, headers, 0, 'POST', body);
      } catch (e) {
        generating = false;
        host.logError('AI 流式连接被拒绝: ' + ((e as Error).message || '未知错误'));
      }
    },

    onItemClick(_input: XimePanelItemClickEvent): void {
      // 上屏由宿主完成（选区替换）
    },
  },

  // ---- SSE 回调槽（宿主投递；形参 id 与模块态 sessionId 同名会遮蔽，统一用 sid） ----

  sse: {
    onData(_sid: number, text: string): void {
      if (text === undefined || text === null || text === '' || text === '[DONE]') return;
      // 原生 JSON.parse 非法输入抛异常（原 host.json.decode 返回 nil）
      let data: unknown = null;
      try {
        data = JSON.parse(text);
      } catch (e) {
        return;
      }
      if (data === null || typeof data !== 'object') return;
      const choices = (data as Record<string, unknown>).choices;
      if (!Array.isArray(choices) || choices.length === 0) return;
      const delta = (choices[0] as { delta?: { content?: unknown } }).delta;
      if (delta !== undefined && delta !== null && delta.content !== undefined && delta.content !== null) {
        buffer = buffer + String(delta.content);
      }
    },

    onDone(_sid: number, fullText: string): void {
      if (fullText !== undefined && fullText !== null && fullText !== '' && buffer === '') {
        buffer = fullText;
      }
      generating = false;
      sessionId = -1;
    },

    onError(_sid: number, message: string): void {
      host.logError('AI 流式请求失败: ' + (message !== null && message !== undefined ? message : '未知错误'));
      generating = false;
      sessionId = -1;
    },
  },
});

export default plugin;
