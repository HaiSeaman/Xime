use std::path::{Path, PathBuf};

use serde::Deserialize;

pub const MANIFEST_FILE: &str = "manifest.json";

/// 插件清单（manifest.json，宽松 JSON5 解析：支持 // 注释与尾逗号）。
///
/// 与宿主 InstallerManager 的字段约定保持一致（camelCase），
/// 未知字段静默忽略（向前兼容）。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)] // 部分字段供校验/未来使用（解析保留完整清单）
pub struct Manifest {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default = "default_version")]
    pub version: String,
    #[serde(default = "default_type")]
    pub r#type: String,
    #[serde(default = "default_entry")]
    pub entry: String,
    #[serde(default)]
    pub icon: Option<String>,
    #[serde(default)]
    pub min_host_version: Option<String>,
    #[serde(default)]
    pub max_host_version: Option<String>,
}

fn default_version() -> String {
    "0.0.0".to_string()
}

fn default_type() -> String {
    "unknown".to_string()
}

fn default_entry() -> String {
    "main.js".to_string()
}

impl Manifest {
    /// manifest.json 路径（插件目录下）。
    pub fn path_in(plugin_dir: &Path) -> PathBuf {
        plugin_dir.join(MANIFEST_FILE)
    }

    /// 从插件目录读取并校验 manifest.json。
    pub fn load(plugin_dir: &Path) -> Result<Manifest, String> {
        let path = Manifest::path_in(plugin_dir);
        let text = std::fs::read_to_string(&path)
            .map_err(|e| format!("读取 {} 失败: {e}", path.display()))?;
        // json5 与宿主 kotlinx 宽松模式对齐（注释、尾逗号、单引号）
        let manifest: Manifest =
            json5::from_str(&text).map_err(|e| format!("manifest.json 解析失败: {e}"))?;
        manifest.validate()?;
        Ok(manifest)
    }

    /// 校验必填字段与格式约束（与宿主 InstallerManager 的校验规则一致）。
    pub fn validate(&self) -> Result<(), String> {
        if !is_valid_plugin_id(&self.id) {
            return Err(format!(
                "非法插件 id: {}（仅允许字母/数字/下划线/连字符，点号分段，最长 64）",
                self.id
            ));
        }
        if !is_valid_entry(&self.entry) {
            return Err(format!("非法入口脚本: {}", self.entry));
        }
        if self.version.trim().is_empty() {
            return Err("version 不能为空".to_string());
        }
        Ok(())
    }
}

/// 插件 id：字母/数字/下划线/连字符，点号仅作命名空间分段（禁止空段/首尾点），最长 64。
pub fn is_valid_plugin_id(id: &str) -> bool {
    if id.is_empty() || id.len() > 64 {
        return false;
    }
    if id.starts_with('.') || id.ends_with('.') {
        return false;
    }
    id.split('.').all(|segment| {
        !segment.is_empty()
            && segment
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
    })
}

/// 入口脚本：普通文件名，不允许路径分隔符与 ".."。
pub fn is_valid_entry(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && !name.contains('/')
        && !name.contains('\\')
        && !name.contains("..")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plugin_id_validation() {
        assert!(is_valid_plugin_id("kaomoji"));
        assert!(is_valid_plugin_id("com.kingzcheung.xime.plugin.typing_stats"));
        assert!(is_valid_plugin_id("webdav-clipboard_sync"));
        assert!(!is_valid_plugin_id(""));
        assert!(!is_valid_plugin_id("a..b"));
        assert!(!is_valid_plugin_id(".abc"));
        assert!(!is_valid_plugin_id("abc."));
        assert!(!is_valid_plugin_id("a/b"));
        assert!(!is_valid_plugin_id(&"a".repeat(65)));
    }

    #[test]
    fn entry_validation() {
        assert!(is_valid_entry("main.js"));
        assert!(!is_valid_entry(""));
        assert!(!is_valid_entry("libs/main.js"));
        assert!(!is_valid_entry("../main.js"));
    }

    #[test]
    fn parse_manifest_with_comments_and_trailing_commas() {
        let text = r#"
            // 顶层注释
            {
              "id": "demo",
              "name": "示例",
              "version": "1.0.0",
              "entry": "main.js", // 行内注释
              /* 块注释 */
              "type": "tool",
            }
        "#;
        let manifest: Manifest = json5::from_str(text).unwrap();
        assert_eq!(manifest.id, "demo");
        assert_eq!(manifest.version, "1.0.0");
        manifest.validate().unwrap();
    }

    #[test]
    fn defaults_applied() {
        let manifest: Manifest = json5::from_str(r#"{ "id": "mini" }"#).unwrap();
        assert_eq!(manifest.entry, "main.js");
        assert_eq!(manifest.version, "0.0.0");
        assert_eq!(manifest.r#type, "unknown");
    }

    #[test]
    fn unknown_fields_ignored() {
        let manifest: Manifest =
            json5::from_str(r#"{ "id": "a", "futureField": 123, "capabilities": { "events": [] } }"#)
                .unwrap();
        assert_eq!(manifest.id, "a");
    }
}
