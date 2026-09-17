# xipm — Xime 插件工具链

Xime 输入法插件开发 CLI（Rust 实现）。负责 TypeScript 编译、xipk 打包、插件骨架生成与清单校验。

- **插件源码**：TypeScript（`main.ts` + 可选 `libs/*.ts`，相对 import 自动内联）
- **编译产物**：IIFE 单文件 `main.js`（无顶层 import/export，QuickJS 脚本模式直接执行）
- **插件包**：`<name>-<version>.xipk`（zip，Deflate 级别 9；宿主安装解压到 `files/plugins/<id>/`）
- **编译产物不进入仓库**：由 `xipm build` 输出到 `build/plugin-js/`（gitignore）

## 安装 / 构建 CLI

```bash
# 方式一：开发运行（无需安装）
cd tools/xime-plugin
cargo run -- --help

# 方式二：构建二进制（推荐，命令名 xipm）
cargo build --release
./target/release/xipm --help
# 提示：若设置了 CARGO_TARGET_DIR，二进制位于 $CARGO_TARGET_DIR/release/xipm
```

## 最常用命令

```bash
# 仓库根一条命令：编译全部插件 + 打包 xipk（输出 build/plugin-release/*.xipk）
xipm pack

# 等价：编译全部插件（产物 build/plugin-js/，供测试/开发），不打包
xipm build
```

（零参数时自动识别：当前目录不是插件但存在 `plugins/` → 批量模式；
在插件目录内零参数运行 → 单插件模式）

## 快速开始（3 分钟）

```bash
# 1. 创建插件骨架（main.ts + manifest.json + xime-plugin.d.ts + tsconfig.json + resources/）
cd tools/xime-plugin
cargo run -- init my-plugin --type tool --parent /tmp/demo

# 2. 编辑 /tmp/demo/my-plugin/main.ts（契约见骨架内 xime-plugin.d.ts）

# 3. 编译（多文件 import 内联 → 单文件 main.js）
cargo run -- build /tmp/demo/my-plugin --out /tmp/demo/out

# 4. 校验清单
cargo run -- check /tmp/demo/my-plugin

# 5. 打包 xipk（内部会先编译一遍）
cargo run -- pack /tmp/demo/my-plugin --out /tmp/demo/out --release-dir /tmp/demo/release
# → /tmp/demo/release/my-plugin-0.1.0.xipk
```

## 命令参考

### `xipm build [DIR]`

编译插件：`main.ts`（多文件 import 内联）→ IIFE 单文件 `main.js`，并复制 `manifest.json` 与 `resources/`。

```bash
xipm build <插件目录> --out <输出根>          # 单插件 → <输出根>/<插件目录名>/
xipm build --all --plugins-dir plugins --out build/plugin-js   # 批量
```

| 参数 | 说明 | 默认 |
|---|---|---|
| `[DIR]` | 插件目录（含 main.ts + manifest.json） | 当前目录 |
| `--all` | 批量构建 `--plugins-dir` 下所有插件 | - |
| `--plugins-dir` | 批量模式插件根目录 | `plugins` |
| `--out` | 输出根目录（产物位于 `<out>/<plugin-name>/`） | `build/plugin-js` |

### `xipm pack [DIR]`

**最常用：在仓库根直接运行 `xipm pack`（零参数）** —— 自动批量打包 `plugins/` 下全部插件到
`build/plugin-release/*.xipk`。


编译 + 打包为 `<name>-<version>.xipk`（version 取自 manifest.json）。

```bash
xipm pack --all --plugins-dir plugins --out build/plugin-js --release-dir build/plugin-release
xipm pack <插件目录> --with-assets            # 单插件：打包并拷贝到 app assets
```

| 参数 | 说明 | 默认 |
|---|---|---|
| `--release-dir` | xipk 输出目录 | `build/plugin-release` |
| `--no-minify` | 不压缩产物（**pack 默认压缩**：compress + 局部变量 mangle，体积约 -40%） | 默认压缩 |
| `--with-assets` | 打包后拷贝到 app 内置资源目录（单插件模式用） | - |
| `--assets-dir` | app 内置资源目录 | `app/src/main/assets/plugins` |

### `xipm check [DIR]`

校验 `manifest.json`（宽松 JSON：支持 `//` 注释与尾逗号）字段与格式：id 命名空间、entry、version、入口源码存在性。

```bash
xipm check --all --plugins-dir plugins
```

### `xipm init <NAME>`

生成插件骨架：

```
<parent>/<name>/
  main.ts            入口源码（含最小示例）
  manifest.json      清单模板（含中文注释）
  xime-plugin.d.ts   SDK 类型定义（host API + 插件契约 + 环境 API）
  tsconfig.json      类型检查配置（ES2020 / strict）
  resources/         资源目录（宿主渲染图片等）
  .gitignore         dist/
```

```bash
xipm init my-plugin --type tool --parent .
# 类型可选：tool / emoji / speech / clipboard_sync / backup
```

## 插件结构

```
plugins/my-plugin/
  main.ts         源码（必须定义 globalThis.plugin = { ... }）
  libs/*.ts       可选拆分（相对 import，编译时内联进 main.js）
  manifest.json   清单（宿主解析；宽松 JSON 支持注释与尾逗号）
  resources/      资源文件（图片由宿主渲染，插件只拿路径）
```

`manifest.json` 关键字段：

```jsonc
{
  "id": "com.example.my_plugin",   // 反域名命名空间（字母/数字/下划线/连字符/点号，≤64）
  "name": "我的插件",
  "version": "0.1.0",
  "type": "tool",                   // tool / emoji / speech / clipboard_sync / backup
  "entry": "main.js",               // 固定 main.js（编译产物）
  "minHostVersion": "2.8.0",
  // "network": { "hosts": ["api.example.com"], "allowCustomHosts": false },
  // "capabilities": { "tool": { "display": "passive" } }
}
```

详细 schema：`docs/sdk/manifest.schema.json`。

## 产物压缩与"混淆"说明

- **默认策略**：`pack`（分发）**默认压缩**；`build`（测试/开发）默认不压缩（便于调试与错误定位）
  - `xipm build --minify`：手动压缩编译产物；`xipm pack --no-minify`：关闭打包压缩
  - 实测 ai-reply 6633→3935B（-41%）、volc-asr 7474→3878B（-48%）；压缩产物已在宿主测试全量验证
  - 压缩 = rolldown 的 compress + 局部变量 mangle（安全；属性名不混淆）
- **属性名混淆不可用**：宿主按 `plugin` 扩展点方法名（panel.state 等）、`host` API 属性名、
  事件/协议字段名访问，属性重命名会破坏契约（保留名单易漏，风险大于收益）
- 激进混淆（字符串加密/控制流平坦化，如 javascript-obfuscator）需引入 Node 工具链，且体积/性能恶化，
  当前不支持；如防逆向是硬需求，更正确的方向是 QuickJS 字节码加载（宿主改造，未实现）

## 插件入口范式（TS 模块 + definePlugin）

插件主文件是**标准 TypeScript 模块**：用 `definePlugin` 定义扩展点，`export default` 导出。

```ts
// plugins/my-plugin/main.ts
import { helper } from './libs/helper';   // 多文件拆分（构建时内联）

interface LocalState { /* 类型定义放模块顶层 */ }

const plugin = definePlugin({
  onLoad(): void {
    host.log('loaded');
  },

  // 下行事件（manifest capabilities.events 声明后投递；槽名 = on + 事件名 PascalCase）
  events: {
    onTextCommitted(e) {
      host.log(String(e.sessionTotalChars));   // payload 类型自动推断（camelCase）
    },
  },

  // 工具面板扩展点（manifest type=tool；宿主渲染 ui 节点树）
  panel: {
    async state(input): Promise<XimePanelState> {
      return { items: [], ui: [], loading: false };
    },
    async onAction(input): Promise<void> {
      const resp = await host.http.request('POST', url, headers, body);   // async 服务
    },
  },
});

export default plugin;
```

- **构建产物**：`var plugin = (function () { ... })();`——QuickJS 脚本模式下即 `globalThis.plugin`（宿主契约不变）
- **不要手写 IIFE**：作用域隔离由 bundler 完成
- **SDK 类型无需 import**：`definePlugin` / `host` / `XimeError` / `Xime*` 等均为全局声明
  （`xime-plugin.d.ts` 由 tsconfig `include` 引入，init 骨架已配好）；
  `host` 在运行时即宿主注入的全局对象（同 Node 的 `process`、浏览器的 `window`）
- **契约校验**：`definePlugin<T extends XimePluginSpec>` 对扩展点名与方法签名做编译期校验（拼错直接报错）
- **TS 范式（async/await）**：host 网络/IO 服务为 async（`await host.http.request(...)`），
  失败 throw `XimeError`（`code` + `message`；无 `lastError()`）；纯计算 API 同步返回；
  扩展点按需 async（`panel.state/onAction`、`speech.*`、`clipboardSync.*`、`backup.*`、`onLoad/onUnload`；
  `transform.candidates` 必须同步）

## TypeScript 开发

- **SDK 类型**：`tools/xime-plugin/templates/xime-plugin.d.ts`（`init` 生成时释放到插件目录）
  - `host`：`log` / `config` / `resource` / `bin` / `zlib` / `crypto` / `http` / `ws` / `asr` / `quickSend` / `clipboard` / `uuid`
  - `XimePluginSpec`：扩展点 `panel` / `emoji` / `speech` / `clipboardSync` / `backup` /
    `transform` / `settings` / `events` / `ws` / `sse`
  - `XimeError`：`code` / `message`（async 服务失败的抛出形态）
- **类型检查**（可选，`xipm` 编译只做类型剥离不做检查）：

```bash
npx -p typescript tsc -p tsconfig.json --noEmit             # 仓库内全部插件（根配置）
npx -p typescript tsc -p <插件目录>/tsconfig.json --noEmit   # 单插件（插件目录也有就近配置）
```

- **语言基线**：ES2020（QuickJS 原生支持；class fields / async / 可选链 / 空值合并 / BigInt / Set / Map / TypedArray 均可）
- **宿主补齐的环境 API**：`console`（转发 host.log）、`TextEncoder` / `TextDecoder`、`atob` / `btoa`
- **不可用的 API**：`setTimeout` / `setInterval`、`URL` / `URLSearchParams`、`fetch`、`Intl` —— 网络与 IO 一律走 `host` 白名单
- **JSON**：用原生 `JSON.parse` / `JSON.stringify`（注意 `JSON.parse` 对非法输入**抛异常**，需 `try/catch`）
- **字节**：一律 `Uint8Array`；注意 `subarray()` 产生的子视图跨宿主桥会变 null，需 `new Uint8Array(view)` 复制

## 仓库内工作流（Xime 主仓库开发）

```bash
# 构建全部插件（测试前置：插件测试从 build/plugin-js 加载产物）
cd tools/xime-plugin && cargo run -- build --all --plugins-dir ../../plugins --out ../../build/plugin-js

# 打包全部 xipk + 同步 4 个内置插件到 app assets
bash scripts/build-plugins.sh --with-assets

# 校验全部插件清单
cd tools/xime-plugin && cargo run -- check --all --plugins-dir ../../plugins

# 跑宿主测试（需先构建插件）
./gradlew :plugin-core:testDebugUnitTest :app:testDebugUnitTest
```

## 常见问题

| 现象 | 原因 / 解决 |
|---|---|
| 测试报"找不到 build/plugin-js/&lt;name&gt;/main.js" | 先运行 `xipm build --all`（见上） |
| `JSON.parse('')` 抛异常 | 原生语义与旧 `host.json.decode`（失败返回 null）不同，需 `try/catch` 或保证输入合法 |
| 请求体传给 `host.http.request` 后为空 | body 必须是 `Uint8Array`（字符串不识别），用 `new TextEncoder().encode(...)` |
| 插件加载失败 "未定义全局对象 plugin" | `main.ts` 必须定义 `globalThis.plugin = { ... }` |
| 修改宿主主源码后测试行为未更新 | Gradle 中间产物缓存问题：删除 `plugin-core/build/intermediates/runtime_library_classes_dir` 后重跑 |
| 中文文件名在 `unzip` 中乱码 | 条目已带 UTF-8 flag，实际解码正常（macOS `ditto` / Java `ZipFile` 验证通过） |
