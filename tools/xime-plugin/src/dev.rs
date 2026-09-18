// 真机开发支持（xipm dev / xipm logs）。
//
// dev：watch 插件源码 → 编译 + 打包 xipk → adb push 到应用外部私有目录 →
//      广播触发 debug 宿主热安装/重载 → 同时跟随 logcat（插件日志与错误回显）。
// logs：实时跟随插件日志（logcat: JsPlugin / PluginErrorLog tag）；
//       --history 拉取宿主错误落盘文件 errors.jsonl（run-as，debug 包）。
//
// 依赖：adb（PATH / ANDROID_HOME / ANDROID_SDK_ROOT / --adb），USB 或无线调试均可。
// 宿主侧要求：debug 构建包含 DebugPluginInstallReceiver（app/src/debug）。

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant, SystemTime};

use anyhow::{Context, Result};

use crate::engine_js as color;
use crate::manifest::Manifest;

/// 应用外部私有目录下的热更新暂存目录（无需任何存储权限）。
const DEV_DIR: &str = "xipm-dev";

/// 热安装组件（app/src/debug 的 DebugPluginInstallActivity：透明无界面、
/// `am start` 由 shell 特权发起，不受应用后台执行限制）。
const INSTALL_ACTIVITY_SUFFIX: &str = "plugin.DebugPluginInstallActivity";

// ─────────────────────────────────────────────────────────────────────────────
// adb 封装
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Clone)]
pub struct Adb {
    exe: PathBuf,
    serial: Option<String>,
}

impl Adb {
    /// 定位 adb：--adb > $ADB > $ANDROID_HOME/platform-tools > $ANDROID_SDK_ROOT/... > PATH
    pub fn detect(explicit: Option<PathBuf>) -> Result<Self> {
        let exe = explicit
            .or_else(|| std::env::var_os("ADB").map(PathBuf::from))
            .or_else(|| {
                std::env::var_os("ANDROID_HOME")
                    .map(|h| PathBuf::from(h).join("platform-tools").join("adb"))
            })
            .or_else(|| {
                std::env::var_os("ANDROID_SDK_ROOT")
                    .map(|h| PathBuf::from(h).join("platform-tools").join("adb"))
            })
            .unwrap_or_else(|| PathBuf::from("adb"));
        let adb = Self { exe, serial: None };
        adb.run(&["version"])
            .with_context(|| format!("adb 不可用（{}）。请安装 Android SDK Platform-Tools 或指定 --adb", adb.exe.display()))?;
        Ok(adb)
    }

    pub fn with_serial(mut self, serial: Option<String>) -> Self {
        self.serial = serial;
        self
    }

    fn cmd(&self) -> Command {
        let mut cmd = Command::new(&self.exe);
        if let Some(s) = &self.serial {
            cmd.arg("-s").arg(s);
        }
        cmd
    }

    fn run(&self, args: &[&str]) -> Result<String> {
        let out = self
            .cmd()
            .args(args)
            .output()
            .with_context(|| format!("执行 adb {} 失败", args.join(" ")))?;
        if !out.status.success() {
            anyhow::bail!(
                "adb {} 失败: {}",
                args.join(" "),
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        Ok(String::from_utf8_lossy(&out.stdout).into_owned())
    }

    /// 设备在线检查（无设备给出明确提示，含无线调试引导）。
    pub fn ensure_device(&self) -> Result<()> {
        let state = self.run(&["get-state"]).unwrap_or_default();
        if state.trim() != "device" {
            anyhow::bail!(
                "未检测到在线设备（adb get-state = {:?}）。\n\
                 提示：USB 调试请确认已授权；无线调试可用：\n  \
                 adb pair <ip:port> && adb connect <ip:port>",
                state.trim()
            );
        }
        Ok(())
    }

    pub fn shell(&self, args: &[&str]) -> Result<String> {
        let mut full = vec!["shell"];
        full.extend_from_slice(args);
        self.run(&full)
    }

    /// 以 app uid 执行（debug 包 run-as），返回 stdout。
    pub fn run_as(&self, package: &str, args: &[&str]) -> Result<String> {
        let mut full = vec!["run-as", package];
        full.extend_from_slice(args);
        self.shell(&full)
    }

    /// 管道写入 app 内部目录（绕开 /sdcard Android/data 在部分 ROM 上的访问限制）：
    /// 本地文件 → stdin → `run-as sh -c 'cat > files/xipm-dev/<name>'`。
    /// 返回设备上的内部绝对路径（供广播 extra 使用）。
    pub fn push_to_internal(&self, package: &str, name: &str, local: &Path) -> Result<String> {
        let stdin = std::fs::File::open(local)
            .with_context(|| format!("打开本地插件包失败: {}", local.display()))?;
        let remote_cmd = format!("run-as {package} sh -c 'cat > files/xipm-dev/{name}'");
        let out = self
            .cmd()
            .arg("shell")
            .arg("-T") // 禁用 PTY：保证 stdin 二进制不被终端规则破坏
            .arg(&remote_cmd)
            .stdin(Stdio::from(stdin))
            .output()
            .with_context(|| "管道写入设备内部目录失败")?;
        if !out.status.success() {
            anyhow::bail!(
                "写入设备内部目录失败: {}",
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        Ok(format!("/data/user/0/{package}/files/xipm-dev/{name}"))
    }

    /// 触发热安装（`am start` 无界面 Activity；path 指向设备上的 xipk）。
    pub fn start_install(&self, package: &str, remote_xipk: &str) -> Result<()> {
        let component = format!("{package}/{package}.{INSTALL_ACTIVITY_SUFFIX}");
        let out = self.run(&[
            "shell",
            "am",
            "start",
            "-n",
            &component,
            "--es",
            "path",
            remote_xipk,
        ])?;
        if out.contains("Error") || out.contains("does not exist") {
            anyhow::bail!(
                "设备上的 {package} 不包含插件热安装组件（仅最新 debug 构建包含）。\n\
                 请先执行：./gradlew installDebug\n（am start: {}）",
                out.trim()
            );
        }
        Ok(())
    }

    /// 读取应用私有文件（debug 包 run-as）。
    pub fn exec_out(&self, args: &[&str]) -> Result<Vec<u8>> {
        let out = self
            .cmd()
            .arg("exec-out")
            .args(args)
            .output()
            .with_context(|| format!("执行 adb exec-out {} 失败", args.join(" ")))?;
        if !out.status.success() {
            anyhow::bail!(
                "adb exec-out {} 失败: {}",
                args.join(" "),
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        Ok(out.stdout)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 日志跟随（logcat）
// ─────────────────────────────────────────────────────────────────────────────

/// 启动 logcat 跟随线程（过滤插件相关 tag），返回子进程句柄（用于退出清理）。
fn spawn_logcat(adb: &Adb, plugin_id: &str) -> Result<Child> {
    let mut child = adb
        .cmd()
        .arg("logcat")
        .arg("-v")
        .arg("time")
        .arg("-T") // 从"最新 1 行"开始，不回放历史缓冲
        .arg("1")
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .context("启动 adb logcat 失败")?;
    let stdout = child.stdout.take().expect("logcat stdout");
    let id = plugin_id.to_string();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(std::result::Result::ok) {
            // 宿主行为日志（加载/热更新/事件等，含插件 id 的行）；
            // JsPlugin（console）与 PluginErrorLog（错误）已由落盘通道负责，避免重复显示。
            if !(line.contains(&id) && !line.contains("JsPlugin") && !line.contains("PluginErrorLog"))
            {
                continue;
            }
            print_log_line(&line);
        }
    });
    Ok(child)
}

fn print_log_line(line: &str) {
    let out = if line.contains(" E ") {
        color::red(line)
    } else if line.contains(" W ") {
        color::yellow(line)
    } else {
        line.to_string()
    };
    println!("{out}");
}

// ─────────────────────────────────────────────────────────────────────────────
// 源码变更指纹（watch）
// ─────────────────────────────────────────────────────────────────────────────

/// 汇总插件目录（main.ts/manifest.json/resources/** 等）的 mtime+size 指纹。
/// 跳过构建产物与依赖目录。
fn fingerprint(dir: &Path) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    let walker = walkdir::WalkDir::new(dir).into_iter().filter_entry(|e| {
        let name = e.file_name().to_string_lossy();
        !matches!(name.as_ref(), "node_modules" | ".git" | "build" | "dist" | ".idea")
    });
    for entry in walker.flatten() {
        if !entry.file_type().is_file() {
            continue;
        }
        entry.path().hash(&mut hasher);
        if let Ok(meta) = entry.metadata() {
            meta.len().hash(&mut hasher);
            if let Ok(mtime) = meta.modified() {
                let nanos = mtime
                    .duration_since(SystemTime::UNIX_EPOCH)
                    .map(|d| d.as_nanos())
                    .unwrap_or(0);
                nanos.hash(&mut hasher);
            }
        }
    }
    hasher.finish()
}

// ─────────────────────────────────────────────────────────────────────────────
// xipm dev
// ─────────────────────────────────────────────────────────────────────────────

pub struct DevArgs {
    pub dir: Option<PathBuf>,
    pub package: String,
    pub adb: Option<PathBuf>,
    pub device: Option<String>,
    pub no_logs: bool,
    pub out: PathBuf,
}

pub async fn run_dev(args: DevArgs) -> Result<()> {
    let dir = args
        .dir
        .clone()
        .unwrap_or_else(|| PathBuf::from("."))
        .canonicalize()
        .map_err(|e| anyhow::anyhow!("插件目录不存在: {e}"))?;
    let manifest = Manifest::load(&dir).map_err(|e| anyhow::anyhow!("{e}"))?;
    let adb = Adb::detect(args.adb.clone())?.with_serial(args.device.clone());
    adb.ensure_device()?;

    adb.shell(&["run-as", &args.package, "mkdir", "-p", &format!("files/{DEV_DIR}")])?;
    let plugin_name = dir
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_else(|| "plugin".into());

    println!("▶ 插件热更新：{} ({})", manifest.id, manifest.version);

    deploy(&dir, &args.out, &adb, &args.package, &plugin_name).await?;

    // 插件错误实时跟随（errors.jsonl 增量轮询：不依赖 ROM 限流的 logcat）
    let error_follower = spawn_error_follower(adb.clone(), args.package.clone(), manifest.id.clone());
    let mut logcat = if args.no_logs {
        None
    } else {
        println!("  日志跟随中（console/错误落盘通道 + 宿主日志；Ctrl-C 退出）");
        Some(spawn_logcat(&adb, &manifest.id)?)
    };

    let mut last = fingerprint(&dir);
    loop {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {
                println!("\n已退出（设备上仍保留最后一次安装）");
                break;
            }
            _ = tokio::time::sleep(Duration::from_millis(500)) => {
                let current = fingerprint(&dir);
                if current != last {
                    // 防抖：编辑器保存常触发多次事件
                    tokio::time::sleep(Duration::from_millis(250)).await;
                    last = fingerprint(&dir);
                    let started = Instant::now();
                    match deploy(&dir, &args.out, &adb, &args.package, &plugin_name).await {
                        Ok(()) => println!("↻ 热更新完成（{:.0}ms）", started.elapsed().as_millis()),
                        Err(e) => eprintln!("{} 热更新失败: {e:#}", color::red("✗")),
                    }
                }
            }
        }
    }
    if let Some(child) = &mut logcat {
        let _ = child.kill();
    }
    error_follower.stop();
    Ok(())
}

/// 编译 → 打包 → 管道写入设备内部目录 → 广播安装 → 等待落盘回执。
/// （dev 用未压缩产物，便于真机排查；内部目录通道避免 /sdcard 访问限制）
async fn deploy(dir: &Path, out_root: &Path, adb: &Adb, package: &str, plugin_name: &str) -> Result<()> {
    let outcome = crate::build::build_plugin(dir, out_root, false).await?;
    let xipk = crate::pack::pack_plugin(
        &outcome.out_dir,
        &outcome.manifest,
        &out_root.join("dist"),
    )?;
    let before = fetch_results(adb, package).map(|v| v.len()).unwrap_or(0);
    let remote_path = adb.push_to_internal(package, &format!("{plugin_name}.xipk"), &xipk)?;
    adb.start_install(package, &remote_path)?;

    // 等待设备落盘回执（不依赖 logcat：部分 ROM 限流后台日志）
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        tokio::time::sleep(Duration::from_millis(400)).await;
        if let Ok(results) = fetch_results(adb, package) {
            if results.len() > before {
                if let Some(done) = results
                    .iter()
                    .skip(before)
                    .rev()
                    .find(|r| r.stage == "done")
                {
                    if done.ok == Some(true) {
                        println!(
                            "  {} 热安装成功：{} {}",
                            color::green("✓"),
                            done.p.clone().unwrap_or_else(|| outcome.manifest.id.clone()),
                            done.m
                        );
                    } else {
                        println!("  {} 热安装失败：{}", color::red("✗"), done.m);
                    }
                    return Ok(());
                }
            }
        }
        if Instant::now() >= deadline {
            println!(
                "  {} 未收到设备回执（10s）：请确认宿主为最新 debug 包（./gradlew installDebug），\
                 或用 `xipm logs {}` 查看设备日志",
                color::yellow("!"),
                dir.display()
            );
            return Ok(());
        }
    }
}

/// 读取热安装回执行（`files/logs/xipm-dev-result.jsonl`，run-as）。
#[derive(serde::Deserialize)]
struct DevResult {
    stage: String,
    #[serde(default)]
    ok: Option<bool>,
    #[serde(default)]
    p: Option<String>,
    #[serde(default)]
    m: String,
}

fn fetch_results(adb: &Adb, package: &str) -> Result<Vec<DevResult>> {
    let text = adb
        .run_as(package, &["cat", "files/logs/xipm-dev-result.jsonl"])
        .unwrap_or_default();
    Ok(text.lines().filter_map(|l| serde_json::from_str(l).ok()).collect())
}

// ─────────────────────────────────────────────────────────────────────────────
// xipm logs
// ─────────────────────────────────────────────────────────────────────────────

pub struct LogsArgs {
    pub dir: Option<PathBuf>,
    pub package: String,
    pub adb: Option<PathBuf>,
    pub device: Option<String>,
    pub history: bool,
    pub lines: usize,
    pub json: bool,
}

pub async fn run_logs(args: LogsArgs) -> Result<()> {
    let dir = args
        .dir
        .clone()
        .unwrap_or_else(|| PathBuf::from("."))
        .canonicalize()
        .map_err(|e| anyhow::anyhow!("插件目录不存在: {e}"))?;
    let manifest = Manifest::load(&dir).map_err(|e| anyhow::anyhow!("{e}"))?;
    let adb = Adb::detect(args.adb.clone())?.with_serial(args.device.clone());
    adb.ensure_device()?;

    if args.history {
        let bytes = adb.exec_out(&[
            "run-as",
            &args.package,
            "cat",
            "files/logs/plugins/errors.jsonl",
        ])?;
        print_history(&bytes, &manifest.id, args.lines, args.json);
        return Ok(());
    }

    println!(
        "▶ 跟随 {} 的插件日志与错误（Ctrl-C 退出；历史错误用 --history）",
        manifest.id
    );
    let follower = spawn_error_follower(adb.clone(), args.package.clone(), manifest.id.clone());
    let mut child = spawn_logcat(&adb, &manifest.id)?;
    tokio::signal::ctrl_c().await.ok();
    let _ = child.kill();
    follower.stop();
    Ok(())
}

/// 错误实时跟随器句柄（置位停止标志 + 唤醒线程退出）。
pub struct ErrorFollower {
    stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

impl ErrorFollower {
    pub fn stop(&self) {
        self.stop.store(true, std::sync::atomic::Ordering::Relaxed);
    }
}

/// 实时跟随插件开发日志（1s 轮询，不依赖 logcat，规避 ROM 后台日志限流）：
/// - console（`files/logs/plugins/dev-console.jsonl`）：只输出跟随开始后的新增；
/// - 错误（`files/logs/plugins/errors.jsonl`）：启动时提示最近 1 条，之后只输出新增。
fn spawn_error_follower(adb: Adb, package: String, plugin_id: String) -> ErrorFollower {
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let stop_flag = stop.clone();
    std::thread::spawn(move || {
        let mut last_error_ts: i64 = 0;
        let mut error_baseline_set = false;
        let mut last_console_ts: i64 = 0;
        let mut console_baseline_set = false;
        while !stop_flag.load(std::sync::atomic::Ordering::Relaxed) {
            // ---- console 落盘（新增） ----
            if let Ok(lines) = fetch_console(&adb, &package) {
                let mine: Vec<&ConsoleLine> = lines.iter().filter(|e| e.p == plugin_id).collect();
                // 首次可读即建立基线（即便为空）：启动时已有历史则静默，之后新增实时显示
                if !console_baseline_set {
                    console_baseline_set = true;
                    last_console_ts = mine.iter().map(|e| e.t).max().unwrap_or(0);
                }
                let mut fresh: Vec<&&ConsoleLine> =
                    mine.iter().filter(|e| e.t > last_console_ts).collect();
                fresh.sort_by_key(|e| e.t);
                for e in fresh {
                    print_console_line(e);
                    last_console_ts = last_console_ts.max(e.t);
                }
            }
            // ---- 错误落盘 ----
            if let Ok(errors) = fetch_errors(&adb, &package) {
                let mine: Vec<&StoredError> = errors.iter().filter(|e| e.p == plugin_id).collect();
                if !mine.is_empty() {
                    if !error_baseline_set {
                        error_baseline_set = true;
                        last_error_ts = mine.iter().map(|e| e.t).max().unwrap_or(0);
                        if let Some(latest) = mine.iter().max_by_key(|e| e.t) {
                            println!(
                                "  {} 最近错误（历史用 `xipm logs . --history`）:",
                                color::yellow("●")
                            );
                            print_runtime_error(latest);
                        }
                    } else {
                        let mut fresh: Vec<&&StoredError> =
                            mine.iter().filter(|e| e.t > last_error_ts).collect();
                        fresh.sort_by_key(|e| e.t);
                        for e in fresh {
                            print_runtime_error(e);
                            last_error_ts = last_error_ts.max(e.t);
                        }
                    }
                }
            }
            std::thread::sleep(Duration::from_millis(1000));
        }
    });
    ErrorFollower { stop }
}

/// 渲染插件 console 行（落盘通道；error 级别红色）。
fn print_console_line(e: &ConsoleLine) {
    let time = clock_hms(e.t);
    if e.l == "error" {
        println!("  {time} {} {}", color::red("[error]"), e.m);
    } else {
        println!("  {time} {} {}", color::yellow("[log]"), e.m);
    }
}

/// epoch 毫秒 → "HH:MM:SS"（UTC）。
fn clock_hms(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let rem = secs.rem_euclid(86_400);
    format!("{:02}:{:02}:{:02}", rem / 3600, (rem % 3600) / 60, rem % 60)
}

/// 读取插件 console 落盘（`files/logs/plugins/dev-console.jsonl`，run-as）。
fn fetch_console(adb: &Adb, package: &str) -> Result<Vec<ConsoleLine>> {
    let text = adb
        .run_as(package, &["cat", "files/logs/plugins/dev-console.jsonl"])
        .unwrap_or_default();
    Ok(text.lines().filter_map(|l| serde_json::from_str(l).ok()).collect())
}

/// dev-console.jsonl 行（PluginDevConsoleFileSink 的短键 JSONL）。
#[derive(serde::Deserialize)]
struct ConsoleLine {
    t: i64,
    p: String,
    l: String,
    m: String,
}

/// 渲染单条插件错误（终端高亮 + 堆栈前几行）。
fn print_runtime_error(e: &StoredError) {
    println!(
        "  {} [{}] {} — {}",
        color::red("● 插件错误"),
        e.c.as_deref().unwrap_or("OTHER"),
        e.o,
        e.m
    );
    if let Some(stack) = &e.s {
        for line in stack.lines().take(4) {
            println!("      {line}");
        }
    }
}

/// 读取宿主错误落盘（`files/logs/plugins/errors.jsonl`，run-as）。
fn fetch_errors(adb: &Adb, package: &str) -> Result<Vec<StoredError>> {
    let text = adb
        .run_as(package, &["cat", "files/logs/plugins/errors.jsonl"])
        .unwrap_or_default();
    Ok(text.lines().filter_map(|l| serde_json::from_str(l).ok()).collect())
}

/// errors.jsonl 行（FilePluginErrorStore 的短键 JSONL）。
#[derive(serde::Deserialize)]
struct StoredError {
    t: i64,
    p: String,
    o: String,
    m: String,
    s: Option<String>,
    c: Option<String>,
}

fn print_history(bytes: &[u8], plugin_id: &str, limit: usize, raw: bool) {
    let text = String::from_utf8_lossy(bytes);
    let mut errors: Vec<StoredError> = text
        .lines()
        .filter_map(|line| serde_json::from_str::<StoredError>(line).ok())
        .filter(|e| e.p == plugin_id)
        .collect();

    if errors.is_empty() {
        println!("（暂无历史错误：{}）", plugin_id);
        return;
    }
    let start = errors.len().saturating_sub(limit);
    errors.drain(..start);
    for e in &errors {
        if raw {
            println!(
                "{}",
                serde_json::json!({
                    "time": fmt_epoch_ms(e.t),
                    "category": e.c,
                    "operation": e.o,
                    "message": e.m,
                    "stack": e.s,
                })
            );
            continue;
        }
        let category = e.c.as_deref().unwrap_or("OTHER");
        println!(
            "{} [{}] {} — {}",
            color::yellow(&fmt_epoch_ms(e.t)),
            category,
            e.o,
            e.m
        );
        if let Some(stack) = &e.s {
            for line in stack.lines().take(6) {
                println!("    {line}");
            }
        }
    }
    println!("（共 {} 条，展示最新 {} 条）", errors_len_hint(bytes), errors.len());
}

fn errors_len_hint(bytes: &[u8]) -> usize {
    String::from_utf8_lossy(bytes).lines().count()
}

/// epoch 毫秒 → "YYYY-MM-DD HH:MM:SSZ"（UTC，纯算术，无时区依赖）。
fn fmt_epoch_ms(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!(
        "{y:04}-{m:02}-{d:02} {:02}:{:02}:{:02}Z",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

/// Howard Hinnant 的 civil_from_days（epoch 天数 → 年月日）。
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m as u32, d as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epoch_formatting() {
        assert_eq!(fmt_epoch_ms(0), "1970-01-01 00:00:00Z");
        // 2026-09-18T00:00:00Z
        assert_eq!(fmt_epoch_ms(1_789_689_600_000), "2026-09-18 00:00:00Z");
    }
}