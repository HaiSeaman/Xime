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
const KEY_SOURCE_LANG = 'sourceLang';
const KEY_PROMPT = 'prompt';

const DEFAULTS = {
  baseUrl: 'https://api.openai.com/v1',
  model: 'gpt-4o-mini',
  sourceLang: '自动检测',
  targetLang: '简体中文',
  prompt: `你是一个高质量翻译引擎。请将 <text> 标签中的内容翻译成{targetLang}（原文语言：{sourceLang}）。
要求：
1. 只输出译文本身，不要任何解释、注释、引号或前后缀；
2. 译文符合{targetLang}的表达习惯，自然流畅，保持原文的语气与正式程度；
3. 保留原文的换行、分段、列表与 Markdown 格式；
4. 代码、命令、URL、邮箱、@提及、数字与符号原样保留，品牌名与专有名词不译，emoji 保留；
5. 若原文本身已是{targetLang}，原样输出，不要改写。

<text>
{context}
</text>`,
};

// 面板语言选择行的选项（显示与回传值相同，直接作为 {targetLang} 进 prompt）
const SOURCE_LANGS = ['自动检测', '简体中文', '繁體中文', 'English', '日本語', '한국어', 'Français', 'Deutsch', 'Español', 'Русский'];
const TARGET_LANGS = SOURCE_LANGS.filter((l) => l !== '自动检测');

/** 面板候选条目（宿主渲染并点选上屏）。 */
interface ResultItem {
  id: string;
  text: string;
}

let lastContext = '';
let buffer = '';
let generating = false;
let sessionId = -1;

function currentSourceLang(): string {
  return host.config.get(KEY_SOURCE_LANG) || DEFAULTS.sourceLang;
}

function currentTargetLang(): string {
  return host.config.get(KEY_TARGET_LANG) || DEFAULTS.targetLang;
}

function buildItems(): ResultItem[] {
  if (buffer === '') return [];
  return [{ id: 'result', text: buffer }];
}

/** 面板控件行：源语言选择 + 互换键 + 目标语言选择（宿主渲染，值经 onInput/onAction 回流）。 */
function buildControlsUi(): XimeUiNode[] {
  return [
    { type: 'select', key: KEY_SOURCE_LANG, label: '源语言', value: currentSourceLang(), options: SOURCE_LANGS },
    { type: 'button', key: 'swapLang', label: '⇄' },
    { type: 'select', key: KEY_TARGET_LANG, label: '目标语言', value: currentTargetLang(), options: TARGET_LANGS },
  ];
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
          helpText: '翻译 prompt 模板：{context} 待翻译文本，{targetLang} 目标语言，{sourceLang} 源语言（面板选择，自动检测或指定）',
        },
      ];
    },
  },

  panel: {
    state(input: XimePanelInput): XimePanelState {
      return {
        // 明确要求空输入框（契约：空串 = 拒绝宿主上下文/剪贴板预填），翻译内容由用户输入
        inputText: '',
        items: buildItems(),
        loading: generating,
        ui: buildControlsUi(),
      };
    },

    onInput(input: XimePanelInputEvent): void {
      if (input.key === '') {
        // 主输入框
        lastContext = input.value || '';
        return;
      }
      if (input.key === KEY_SOURCE_LANG && input.value) {
        host.config.set(KEY_SOURCE_LANG, input.value);
        return;
      }
      if (input.key === KEY_TARGET_LANG && input.value) {
        host.config.set(KEY_TARGET_LANG, input.value);
        return;
      }
    },

    async onAction(input: XimePanelActionEvent): Promise<void> {
      if (input.actionId === 'swapLang') {
        // 互换源/目标语言后由宿主重拉 state 刷新选择行；
        // 原源语言为自动检测时没有可交换的目标方向，保持目标语言不变
        const src = currentSourceLang();
        host.config.set(KEY_SOURCE_LANG, currentTargetLang());
        if (src !== DEFAULTS.sourceLang) {
          host.config.set(KEY_TARGET_LANG, src);
        }
        return;
      }
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
      const sourceLang = currentSourceLang();
      const targetLang = currentTargetLang();
      let prompt = (host.config.get(KEY_PROMPT) || DEFAULTS.prompt).split('{context}').join(context);
      prompt = prompt.split('{targetLang}').join(targetLang);
      prompt = prompt.split('{sourceLang}').join(sourceLang);

      // 请求体必须是 Uint8Array（宿主 bytes() 只认字节，JS 字符串会变 null）
      const body = new TextEncoder().encode(JSON.stringify({
        model: model,
        messages: [
          { role: 'system', content: '你是高质量翻译引擎，只输出译文本身，不输出任何其他内容。' },
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
