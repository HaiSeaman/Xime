// 火山引擎（火山方舟）WebSocket 流式语音识别（TypeScript 脚本插件）
//
// 职责划分：
//   JS    = 全部功能逻辑（连接时机、状态机、prebuffer、bigmodel_async 二进制协议组装/解析、结果上报）
//   宿主  = 仅提供通用原语：
//     host.ws         WebSocket 白名单（connect/sendText/sendBinary/close，事件走回调槽）
//     host.zlib       gzip / gunzip（火山二进制帧强制 gzip）
//     host.bin        uint32be / int32be（帧长度与序号字段）
//     host.asr.emit*  结果回传桥
//     host.config / host.uuid；JSON 用 JS 原生 JSON
//
// 协议细节与帧格式见 libs/protocol.ts。
// TS 范式：host.ws / host.zlib 为 async 服务（await；失败 throw XimeError，try/catch 后 emitError 上报）。

import {
  MSG_FULL_CLIENT_REQ,
  MSG_AUDIO_ONLY,
  MSG_SERVER_RESP,
  MSG_SERVER_ERROR,
  buildFrame,
  parseServerFrame,
  utf8Encode,
} from './libs/protocol';

const WS_URL = 'wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async';
const SAMPLE_RATE = 16000;

const KEY_API_KEY = 'apiKey';
const KEY_APP_KEY = 'appKey';
const KEY_ACCESS_KEY = 'accessKey';
const KEY_RESOURCE_ID = 'resourceId';
const DEFAULT_RESOURCE = 'volc.seedasr.sauc.duration';

let taskId = '';
let audioReady = false;
let seq = 1;
let prebuffer: Uint8Array[] = [];

function isConfigured(): boolean {
  const apiKey = host.config.get(KEY_API_KEY);
  if (apiKey !== null && apiKey !== undefined && apiKey !== '') return true;
  const appKey = host.config.get(KEY_APP_KEY);
  const accessKey = host.config.get(KEY_ACCESS_KEY);
  return appKey !== null && appKey !== undefined && appKey !== ''
    && accessKey !== null && accessKey !== undefined && accessKey !== '';
}

function getSettingsSchema(): XimeUiNode[] {
  return [
    {
      key: KEY_API_KEY,
      label: 'API Key',
      type: 'secret',
      placeholder: '输入火山方舟 API Key',
      helpText: '在火山引擎方舟平台申请开通流式语音识别后获取',
    },
    {
      key: KEY_APP_KEY,
      label: 'App Key（旧鉴权）',
      type: 'secret',
      required: false,
      placeholder: '旧版 App Key（可选）',
      section: '旧鉴权',
    },
    {
      key: KEY_ACCESS_KEY,
      label: 'Access Key（旧鉴权）',
      type: 'secret',
      required: false,
      placeholder: '旧版 Access Key（可选）',
      section: '旧鉴权',
    },
    {
      key: KEY_RESOURCE_ID,
      label: '模型资源 ID',
      type: 'text',
      defaultValue: DEFAULT_RESOURCE,
      placeholder: DEFAULT_RESOURCE,
      helpText: '默认流式识别 2.0（volc.seedasr.sauc.duration）；1.0 模型填 volc.bigasr.sauc.duration',
    },
  ];
}

async function configure(): Promise<boolean> {
  return true;
}

async function gzipOrNil(data: Uint8Array): Promise<Uint8Array | null> {
  try {
    return await host.zlib.gzip(data);
  } catch (e) {
    host.asr.emitError((e as Error).message);
    return null;
  }
}

// ================= 启动 =================

async function start(): Promise<boolean> {
  if (!isConfigured()) {
    host.asr.emitError('未配置 API Key，请在插件设置中填写');
    return false;
  }
  taskId = host.uuid();
  audioReady = false;
  seq = 1;
  prebuffer = [];

  const headers: Record<string, string> = {};
  const apiKey = host.config.get(KEY_API_KEY);
  if (apiKey !== null && apiKey !== undefined && apiKey !== '') {
    headers['X-Api-Key'] = apiKey;
  } else {
    headers['X-Api-App-Key'] = host.config.get(KEY_APP_KEY) ?? '';
    headers['X-Api-Access-Key'] = host.config.get(KEY_ACCESS_KEY) ?? '';
  }
  headers['X-Api-Resource-Id'] = host.config.get(KEY_RESOURCE_ID) || DEFAULT_RESOURCE;
  headers['X-Api-Request-Id'] = taskId;
  headers['X-Api-Connect-Id'] = host.uuid();
  headers['X-Api-Sequence'] = '-1';

  try {
    await host.ws.connect(WS_URL, headers);
  } catch (e) {
    host.asr.emitError((e as Error).message);
    return false;
  }
  return true;
}

// ================= WebSocket 事件（状态机） =================

async function onWsOpen(): Promise<void> {
  const full = JSON.stringify({
    user: { uid: host.config.get(KEY_APP_KEY) || 'xime' },
    audio: {
      format: 'pcm',
      codec: 'raw',
      rate: SAMPLE_RATE,
      bits: 16,
      channel: 1,
    },
    request: {
      model_name: 'bigmodel',
      enable_itn: true,
      enable_punc: true,
      enable_ddc: false,
      show_utterances: false,
      enable_nonstream: false,
    },
  });
  const gz = await gzipOrNil(utf8Encode(full));
  if (gz === null) {
    await host.ws.close();
    return;
  }
  try {
    await host.ws.sendBinary(buildFrame(MSG_FULL_CLIENT_REQ, seq, 0x1, 0x1, gz));
  } catch (e) {
    host.asr.emitError((e as Error).message);
  }
  seq = seq + 1;
  audioReady = true;
}

async function onWsBinary(frame: Uint8Array): Promise<void> {
  const parsed = await parseServerFrame(frame);
  if (parsed === null) return;

  if (parsed.msgType === MSG_SERVER_RESP) {
    const text = parsed.text || '';
    if (parsed.isLast) {
      host.asr.emitFinal(text);
      await host.ws.close();
    } else if (text !== '') {
      host.asr.emitPartial(text);
    }
  } else if (parsed.msgType === MSG_SERVER_ERROR) {
    host.asr.emitError('ASR 错误 ' + String(parsed.code || 0) + ': ' + (parsed.message || ''));
    await host.ws.close();
  }
}

function onWsError(msg: string): void {
  host.asr.emitError(msg);
}

function onWsClose(): void {
  taskId = '';
  audioReady = false;
  seq = 1;
  prebuffer = [];
}

// ================= 音频数据（主 App 每帧提交，JS 决策） =================

async function processAudioChunk(pcm: Uint8Array): Promise<void> {
  if (audioReady) {
    const gz = await gzipOrNil(pcm);
    if (gz !== null) {
      try {
        await host.ws.sendBinary(buildFrame(MSG_AUDIO_ONLY, seq, 0x0, 0x1, gz));
      } catch (e) {
        host.asr.emitError((e as Error).message);
      }
      seq = seq + 1;
    }
  } else {
    prebuffer.push(pcm);
    if (prebuffer.length > 300) prebuffer.shift();
  }
}

async function stop(): Promise<void> {
  if (taskId === '') return;
  for (const frame of prebuffer) {
    const gz = await gzipOrNil(frame);
    if (gz !== null) {
      try {
        await host.ws.sendBinary(buildFrame(MSG_AUDIO_ONLY, seq, 0x0, 0x1, gz));
      } catch (e) {
        host.asr.emitError((e as Error).message);
      }
      seq = seq + 1;
    }
  }
  prebuffer = [];
  // 最后一包标记：flags=0x3（NEG_WITH_SEQUENCE）且 seq 取负
  const lastGz = await gzipOrNil(utf8Encode(''));
  if (lastGz !== null) {
    try {
      await host.ws.sendBinary(buildFrame(MSG_AUDIO_ONLY, -seq, 0x0, 0x1, lastGz));
    } catch (e) {
      host.asr.emitError((e as Error).message);
    }
  }
}

async function cancel(): Promise<void> {
  await host.ws.close();
  taskId = '';
  audioReady = false;
  seq = 1;
  prebuffer = [];
}

const plugin = definePlugin({
  speech: {
    isConfigured,
    configure,
    feed: processAudioChunk,
    start,
    stop,
    cancel,
  },

  settings: {
    schema: getSettingsSchema,
  },

  ws: {
    onOpen: onWsOpen,
    onBinary: onWsBinary,
    onError: onWsError,
    onClose: onWsClose,
  },
});

export default plugin;
