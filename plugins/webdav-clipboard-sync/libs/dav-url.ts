// WebDAV 剪贴板同步的 URL 组装（纯函数，rolldown 构建时内联进单文件 main.js）
//
// 远端剪贴板文件布局：{davUrl}/{remotePath}/clipboard/current.json
//   davUrl     = 服务器根（如 https://dav.jianguoyun.com/dav/）
//   remotePath = 远程目录（如 xime，留空为根目录）

/** 剪贴板文件名（相对 remotePath） */
export const CLIPBOARD_FILE = 'clipboard/current.json';

/** 去掉末尾斜杠（url 归一化） */
export function stripTrailingSlashes(value: string): string {
  return value.replace(/\/+$/, '');
}

/** 去掉首尾斜杠（remotePath 归一化） */
export function stripSlashes(value: string): string {
  return value.replace(/^\/+/, '').replace(/\/+$/, '');
}

// 远端剪贴板文件 URL：{davUrl}/{remotePath}/clipboard/current.json
// davUrl 为空返回 null（未配置服务器地址）
export function buildFileUrl(davUrl: string, remotePath: string): string | null {
  let base = stripTrailingSlashes(davUrl);
  if (base === '') return null;
  const path = stripSlashes(remotePath);
  if (path !== '') base = base + '/' + path;
  return base + '/' + CLIPBOARD_FILE;
}

// 远端剪贴板目录 URL（{davUrl}/{remotePath}/clipboard，用于连接测试的 PROPFIND 探测）
export function buildDirUrl(fileUrl: string | null): string | null {
  if (fileUrl === null) return null;
  return fileUrl.replace(/\/[^/]+$/, '');
}

// 从文件 URL 里解析出相对 davUrl 的目录层级（不含文件名），供逐级 MKCOL 创建。
// 从用户配置的 davUrl（通常已存在）之后开始创建，避免 MKCOL 服务器根/WebDAV 根被拒。
// 纯字符串前缀匹配，避免正则中 . - + 等特殊字符或前缀误匹配。
export function relativeDirParts(baseUrl: string, fileUrl: string): string[] {
  const basePath = stripTrailingSlashes(baseUrl.replace(/^https?:\/\/[^/]+/, ''));
  let dirPart = fileUrl.replace(/^https?:\/\/[^/]+/, '').replace(/\/[^/]+$/, '');
  if (basePath !== '' && dirPart.substring(0, basePath.length) === basePath) {
    dirPart = dirPart.substring(basePath.length);
  }
  return dirPart.replace(/^\/+/, '').split('/').filter((part) => part !== '');
}
