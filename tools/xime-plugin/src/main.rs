mod build;
mod manifest;
mod pack;

use std::path::{Path, PathBuf};

use clap::{Args, Parser, Subcommand};

/// Xime 输入法插件工具链：TS 编译 / xipk 打包 / 脚手架 / 校验。
///
/// 插件源码为 TypeScript（main.ts + 可选 libs/*.ts 相对 import 内联），
/// 编译产物为 IIFE 单文件 main.js（QuickJS 脚本模式直接执行，无顶层 import/export）。
#[derive(Parser)]
#[command(name = "xipm", version, about, long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// 编译插件：main.ts（多文件 import 内联）→ IIFE 单文件 main.js
    Build(BuildArgs),
    /// 打包：编译 + 生成 <name>-<version>.xipk（可选拷贝到 app assets）
    Pack(PackArgs),
    /// 校验：manifest.json（宽松 JSON）字段与格式检查
    Check(CheckArgs),
    /// 创建插件骨架（main.ts + manifest.json + SDK 类型 + tsconfig）
    Init(InitArgs),
}

#[derive(Args)]
struct BuildArgs {
    /// 插件目录（含 main.ts + manifest.json）；缺省为当前目录
    dir: Option<PathBuf>,
    /// 批量构建指定根目录下的所有插件
    #[arg(long)]
    all: bool,
    /// 批量模式：插件根目录
    #[arg(long, default_value = "plugins")]
    plugins_dir: PathBuf,
    /// 输出根目录（产物位于 <out>/<plugin-name>/）
    #[arg(long, default_value = "build/plugin-js")]
    out: PathBuf,
    /// 压缩产物（compress + 局部变量 mangle；不混淆属性名，宿主契约安全）
    #[arg(long)]
    minify: bool,
}

#[derive(Args)]
struct PackArgs {
    /// 插件目录；缺省为当前目录
    dir: Option<PathBuf>,
    /// 批量打包指定根目录下的所有插件
    #[arg(long)]
    all: bool,
    /// 批量模式：插件根目录
    #[arg(long, default_value = "plugins")]
    plugins_dir: PathBuf,
    /// 编译产物根目录（同 build --out）
    #[arg(long, default_value = "build/plugin-js")]
    out: PathBuf,
    /// xipk 输出目录
    #[arg(long, default_value = "build/plugin-release")]
    release_dir: PathBuf,
    /// 不压缩产物（pack 默认压缩：compress + 局部变量 mangle，不混淆属性名契约安全）
    #[arg(long = "no-minify")]
    no_minify: bool,
    /// 打包后拷贝到 app 内置资源目录（debug 内置插件）
    #[arg(long)]
    with_assets: bool,
    /// app 内置资源目录
    #[arg(long, default_value = "app/src/main/assets/plugins")]
    assets_dir: PathBuf,
}

#[derive(Args)]
struct CheckArgs {
    /// 插件目录；缺省为当前目录
    dir: Option<PathBuf>,
    /// 批量校验指定根目录下的所有插件
    #[arg(long)]
    all: bool,
    /// 批量模式：插件根目录
    #[arg(long, default_value = "plugins")]
    plugins_dir: PathBuf,
}

#[derive(Args)]
struct InitArgs {
    /// 插件目录名（生成到 <parent>/<name>/）
    name: String,
    /// 插件类型（tool/emoji/speech/clipboard_sync/backup）
    #[arg(long, default_value = "tool")]
    r#type: String,
    /// 生成位置（父目录，默认当前目录）
    #[arg(long, default_value = ".")]
    parent: PathBuf,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Commands::Build(args) => run_build(args).await,
        Commands::Pack(args) => run_pack(args).await,
        Commands::Check(args) => run_check(args),
        Commands::Init(args) => run_init(args),
    }
}

/// 操作模式：批量（扫描 plugins 根）或单插件（指定目录/当前目录）。
enum Mode {
    All,
    Single(PathBuf),
}

/// 决定操作模式（三层命令共用）：
/// - `--all` 显式批量
/// - 未指定 DIR 且当前目录不是插件、且 plugins 根存在 → 批量（仓库根零参数即可
///   `xipm build` / `xipm pack` / `xipm check` 全量操作）
/// - 否则单插件（DIR，缺省当前目录）
fn resolve_mode(all: bool, dir: &Option<PathBuf>, plugins_dir: &Path) -> Mode {
    let cwd_is_plugin = Path::new("manifest.json").is_file();
    if all || (dir.is_none() && !cwd_is_plugin && plugins_dir.is_dir()) {
        Mode::All
    } else {
        Mode::Single(dir.clone().unwrap_or_else(|| PathBuf::from(".")))
    }
}

/// 扫描插件根目录（含 manifest.json 的子目录，按名称排序）。
fn scan_plugin_dirs(plugins_dir: &Path) -> anyhow::Result<Vec<PathBuf>> {
    let mut dirs = Vec::new();
    for entry in std::fs::read_dir(plugins_dir).map_err(|e| {
        anyhow::anyhow!("读取插件根目录失败 {}: {e}", plugins_dir.display())
    })? {
        let dir = entry?.path();
        if dir.is_dir() && manifest::Manifest::path_in(&dir).is_file() {
            dirs.push(dir);
        }
    }
    dirs.sort();
    Ok(dirs)
}

async fn run_build(args: BuildArgs) -> anyhow::Result<()> {
    match resolve_mode(args.all, &args.dir, &args.plugins_dir) {
        Mode::All => {
            let outcomes = build::build_all(&args.plugins_dir, &args.out, args.minify).await?;
            if outcomes.is_empty() {
                anyhow::bail!("未在 {} 下找到插件（需含 manifest.json）", args.plugins_dir.display());
            }
            for outcome in &outcomes {
                println!(
                    "✓ 编译 {} ({}) → {}",
                    outcome.manifest.id,
                    outcome.manifest.version,
                    outcome.out_dir.display()
                );
            }
            println!("共 {} 个插件", outcomes.len());
        }
        Mode::Single(dir) => {
            let outcome = build::build_plugin(&dir, &args.out, args.minify).await?;
            println!(
                "✓ 编译 {} ({}) → {}",
                outcome.manifest.id,
                outcome.manifest.version,
                outcome.out_dir.display()
            );
        }
    }
    println!();
    println!("提示：build 只编译到 {}（供测试/开发加载）；", args.out.display());
    println!("      生成 xipk 安装包运行 `xipm pack`（仓库根零参数即批量打包）");
    Ok(())
}

async fn run_pack(args: PackArgs) -> anyhow::Result<()> {
    let plugin_dirs = match resolve_mode(args.all, &args.dir, &args.plugins_dir) {
        Mode::All => scan_plugin_dirs(&args.plugins_dir)?,
        Mode::Single(dir) => vec![dir],
    };

    if plugin_dirs.is_empty() {
        anyhow::bail!("未找到插件目录（{} 下需含 manifest.json）", args.plugins_dir.display());
    }

    // pack 默认压缩分发产物（build 默认不压缩，便于测试/调试）
    let minify = !args.no_minify;
    for dir in &plugin_dirs {
        let outcome = build::build_plugin(dir, &args.out, minify).await?;
        let xipk = pack::pack_plugin(&outcome.out_dir, &outcome.manifest, &args.release_dir)?;
        println!(
            "✓ 打包 {} ({}) → {}",
            outcome.manifest.id,
            outcome.manifest.version,
            xipk.display()
        );
        if args.with_assets {
            let target = pack::copy_to_assets(&xipk, &args.assets_dir)?;
            println!("  ↳ 已拷贝内置资源 {}", target.display());
        }
    }
    println!();
    println!("共 {} 个 xipk → {}", plugin_dirs.len(), args.release_dir.display());
    Ok(())
}

fn run_check(args: CheckArgs) -> anyhow::Result<()> {
    let plugin_dirs = match resolve_mode(args.all, &args.dir, &args.plugins_dir) {
        Mode::All => scan_plugin_dirs(&args.plugins_dir)?,
        Mode::Single(dir) => vec![dir],
    };

    let mut failed = 0;
    for dir in plugin_dirs {
        match manifest::Manifest::load(&dir) {
            Ok(manifest) => {
                println!(
                    "✓ {} ({}) type={} entry={}",
                    manifest.id, manifest.version, manifest.r#type, manifest.entry
                );
                // TS 源码存在性提示（非强制：纯 JS 插件可无 main.ts）
                if !dir.join("main.ts").is_file() && !dir.join("main.js").is_file() {
                    println!("  ! 缺少入口源码 main.ts（或 main.js）");
                    failed += 1;
                }
            }
            Err(e) => {
                eprintln!("✗ {}: {e}", dir.display());
                failed += 1;
            }
        }
    }
    if failed > 0 {
        anyhow::bail!("{failed} 个插件校验失败");
    }
    Ok(())
}

fn run_init(args: InitArgs) -> anyhow::Result<()> {
    let target = args.parent.join(&args.name);
    if target.exists() {
        anyhow::bail!("目标目录已存在: {}", target.display());
    }
    std::fs::create_dir_all(&target)?;
    std::fs::create_dir_all(target.join("resources"))?;

    let package_name = args.name.trim_start_matches('-');
    let manifest_text = include_str!("../templates/manifest.json")
        .replace("{{PACKAGE}}", package_name)
        .replace("{{NAME}}", package_name)
        .replace("{{TYPE}}", &args.r#type);
    std::fs::write(target.join("manifest.json"), manifest_text)?;
    std::fs::write(target.join("main.ts"), include_str!("../templates/main.ts"))?;
    std::fs::write(
        target.join("xime-plugin.d.ts"),
        include_str!("../templates/xime-plugin.d.ts"),
    )?;
    std::fs::write(target.join("tsconfig.json"), include_str!("../templates/tsconfig.json"))?;
    std::fs::write(target.join(".gitignore"), "dist/\n")?;

    println!("✓ 已创建插件骨架 {}", target.display());
    println!("  下一步：xipm build {} --out dist", target.display());
    Ok(())
}
