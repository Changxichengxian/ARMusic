use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

pub const WISHLIST_SCHEMA: &str = "armusic-wishlist-v2";
pub const WISHLIST_FILE_NAME: &str = ".armusic-wishlist.json";
const MAX_CATEGORIES: usize = 200;
const MAX_ITEMS: usize = 20_000;
const MAX_TITLE_BYTES: usize = 4 * 1024;
const MAX_ITEM_BYTES: usize = 64 * 1024;
const MAX_FILE_BYTES: u64 = 16 * 1024 * 1024;
static WISHLIST_IO_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WishlistCategory {
    #[serde(default)]
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub color: u32,
    #[serde(default)]
    pub items: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WishlistPayload {
    pub schema: String,
    pub device_id: String,
    pub generated_at: String,
    pub snapshot_id: String,
    /// Desktop-only migration marker. Android safely ignores this unknown field.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub phone_baseline_established: bool,
    #[serde(default)]
    pub categories: Vec<WishlistCategory>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WishlistSaveRequest {
    pub expected_snapshot_id: String,
    #[serde(default)]
    pub categories: Vec<WishlistCategory>,
}

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WishlistMergeStats {
    pub categories_added_to_desktop: usize,
    pub items_added_to_desktop: usize,
    pub categories_added_to_phone: usize,
    pub items_added_to_phone: usize,
    pub total_categories: usize,
    pub total_items: usize,
    pub snapshot_id: String,
}

pub fn empty_payload(device_id: impl Into<String>) -> WishlistPayload {
    payload(device_id, Vec::new(), false).expect("empty wishlist is valid")
}

pub fn payload(
    device_id: impl Into<String>,
    categories: Vec<WishlistCategory>,
    phone_baseline_established: bool,
) -> Result<WishlistPayload, String> {
    let categories = normalize_categories(categories)?;
    Ok(WishlistPayload {
        schema: WISHLIST_SCHEMA.to_string(),
        device_id: device_id.into(),
        generated_at: crate::now_iso(),
        snapshot_id: snapshot_id(&categories),
        phone_baseline_established,
        categories,
    })
}

pub fn parse_payload(bytes: &[u8], fallback_device_id: &str) -> Result<WishlistPayload, String> {
    if bytes.len() as u64 > MAX_FILE_BYTES {
        return Err("愿望单文件超过 16 MB，已拒绝读取".to_string());
    }
    let value: serde_json::Value =
        serde_json::from_slice(bytes).map_err(|error| format!("愿望单 JSON 无法解析：{error}"))?;
    let (device_id, supplied_snapshot, generated_at, baseline, categories) = match value {
        serde_json::Value::Array(items) => (
            fallback_device_id.to_string(),
            String::new(),
            String::new(),
            false,
            serde_json::from_value(serde_json::Value::Array(items))
                .map_err(|error| format!("旧版愿望单栏目无法解析：{error}"))?,
        ),
        serde_json::Value::Object(mut object) => {
            let schema = object
                .get("schema")
                .and_then(serde_json::Value::as_str)
                .unwrap_or(WISHLIST_SCHEMA);
            if schema != WISHLIST_SCHEMA && schema != "armusic-wishlist-v1" {
                return Err(format!("不支持的愿望单格式：{schema}"));
            }
            let categories = object
                .remove("categories")
                .ok_or_else(|| "愿望单缺少 categories".to_string())?;
            (
                object
                    .get("deviceId")
                    .and_then(serde_json::Value::as_str)
                    .filter(|value| !value.trim().is_empty())
                    .unwrap_or(fallback_device_id)
                    .to_string(),
                object
                    .get("snapshotId")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                object
                    .get("generatedAt")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                object
                    .get("phoneBaselineEstablished")
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(false),
                serde_json::from_value(categories)
                    .map_err(|error| format!("愿望单栏目无法解析：{error}"))?,
            )
        }
        _ => return Err("愿望单必须是栏目数组或同步对象".to_string()),
    };
    let mut parsed = payload(device_id, categories, baseline)?;
    if !generated_at.trim().is_empty() {
        parsed.generated_at = generated_at;
    }
    if !supplied_snapshot.is_empty() && supplied_snapshot != parsed.snapshot_id {
        return Err("愿望单快照校验失败，已拒绝使用".to_string());
    }
    Ok(parsed)
}

pub fn parse_android_categories_json(
    categories_json: &str,
    device_id: &str,
) -> Result<WishlistPayload, String> {
    parse_payload(categories_json.as_bytes(), device_id)
}

pub fn snapshot_id(categories: &[WishlistCategory]) -> String {
    let mut digest = Sha256::new();
    digest.update(b"armusic-wishlist-v2\0");
    digest.update((categories.len() as u64).to_be_bytes());
    for category in categories {
        digest_field(&mut digest, category.id.as_bytes());
        digest_field(&mut digest, category.title.as_bytes());
        digest.update(category.color.to_be_bytes());
        digest.update((category.items.len() as u64).to_be_bytes());
        for item in &category.items {
            digest_field(&mut digest, item.as_bytes());
        }
    }
    format!("wishlist-sha256-{:x}", digest.finalize())[..48].to_string()
}

fn digest_field(digest: &mut Sha256, value: &[u8]) {
    digest.update((value.len() as u64).to_be_bytes());
    digest.update(value);
}

pub fn union_for_desktop(
    desktop: &WishlistPayload,
    phone: &WishlistPayload,
) -> Result<(WishlistPayload, WishlistMergeStats), String> {
    validate_payload(desktop)?;
    validate_payload(phone)?;

    // The first sync deliberately starts with the phone sequence and metadata. This protects the
    // real mobile list from an empty/default browser list while still appending desktop-only data.
    let first_phone_sync = !desktop.phone_baseline_established;
    let (base, additional) = if first_phone_sync {
        (&phone.categories, &desktop.categories)
    } else {
        (&desktop.categories, &phone.categories)
    };
    let categories = union_categories(base, additional)?;
    let merged = payload(desktop.device_id.clone(), categories, true)?;
    let stats = WishlistMergeStats {
        categories_added_to_desktop: merged
            .categories
            .len()
            .saturating_sub(desktop.categories.len()),
        items_added_to_desktop: item_count(&merged).saturating_sub(item_count(desktop)),
        categories_added_to_phone: merged
            .categories
            .len()
            .saturating_sub(phone.categories.len()),
        items_added_to_phone: item_count(&merged).saturating_sub(item_count(phone)),
        total_categories: merged.categories.len(),
        total_items: item_count(&merged),
        snapshot_id: merged.snapshot_id.clone(),
    };
    Ok((merged, stats))
}

/// Used by the phone importer as well as tests: keep the first argument's category metadata and
/// order, then append everything that exists only in the second argument.
pub fn union_categories(
    base: &[WishlistCategory],
    additional: &[WishlistCategory],
) -> Result<Vec<WishlistCategory>, String> {
    let mut merged = normalize_categories(base.to_vec())?;
    for mut incoming in normalize_categories(additional.to_vec())? {
        let matching = merged
            .iter()
            .position(|existing| categories_match(existing, &incoming));
        if let Some(index) = matching {
            merged[index].items = multiset_union(&merged[index].items, &incoming.items);
            continue;
        }
        if merged.iter().any(|existing| existing.id == incoming.id) {
            incoming.id = conflict_safe_id(&incoming, &merged);
        }
        merged.push(incoming);
    }
    normalize_categories(merged)
}

fn categories_match(left: &WishlistCategory, right: &WishlistCategory) -> bool {
    match (canonical_category(left), canonical_category(right)) {
        (Some(left), Some(right)) => left == right,
        _ => {
            left.id == right.id
                && left.title.trim() == right.title.trim()
                && left.color == right.color
        }
    }
}

fn canonical_category(category: &WishlistCategory) -> Option<&'static str> {
    let id = category.id.trim().to_lowercase();
    let title = category.title.trim().to_lowercase();
    if id == "to-listen"
        || matches!(
            title.as_str(),
            "准备听" | "准备听的音乐" | "想听的歌" | "to listen" | "聴きたい曲"
        )
    {
        return Some("to-listen");
    }
    if id == "anime"
        || matches!(
            title.as_str(),
            "动漫" | "動畫" | "动画" | "anime" | "アニメ"
        )
    {
        return Some("anime");
    }
    if id == "manga" || matches!(title.as_str(), "漫画" | "漫畫" | "manga" | "マンガ") {
        return Some("manga");
    }
    if id == "novel"
        || matches!(
            title.as_str(),
            "小说" | "小說" | "novel" | "novels" | "小説"
        )
    {
        return Some("novel");
    }
    None
}

fn multiset_union(base: &[String], additional: &[String]) -> Vec<String> {
    let mut result = base.to_vec();
    let mut available = HashMap::<&str, usize>::new();
    for item in base {
        *available.entry(item.as_str()).or_default() += 1;
    }
    let mut seen = HashMap::<&str, usize>::new();
    for item in additional {
        let count = seen.entry(item.as_str()).or_default();
        *count += 1;
        if *count > available.get(item.as_str()).copied().unwrap_or_default() {
            result.push(item.clone());
        }
    }
    result
}

fn conflict_safe_id(category: &WishlistCategory, existing: &[WishlistCategory]) -> String {
    let mut nonce = 0_u64;
    loop {
        let mut digest = Sha256::new();
        digest.update(b"armusic-wishlist-category-conflict-v1\0");
        digest.update(category.id.as_bytes());
        digest.update(category.title.as_bytes());
        digest.update(category.color.to_be_bytes());
        digest.update(nonce.to_be_bytes());
        let candidate = format!("category-{:x}", digest.finalize())[..41].to_string();
        if existing.iter().all(|item| item.id != candidate) {
            return candidate;
        }
        nonce = nonce.saturating_add(1);
    }
}

pub fn item_count(payload: &WishlistPayload) -> usize {
    payload
        .categories
        .iter()
        .map(|category| category.items.len())
        .sum()
}

pub fn validate_payload(payload: &WishlistPayload) -> Result<(), String> {
    if payload.schema != WISHLIST_SCHEMA && payload.schema != "armusic-wishlist-v1" {
        return Err(format!("不支持的愿望单格式：{}", payload.schema));
    }
    let categories = normalize_categories(payload.categories.clone())?;
    if categories != payload.categories {
        return Err("愿望单含有未规范化字段，已拒绝直接覆盖".to_string());
    }
    if snapshot_id(&categories) != payload.snapshot_id {
        return Err("愿望单快照校验失败，已拒绝覆盖".to_string());
    }
    Ok(())
}

fn normalize_categories(
    categories: Vec<WishlistCategory>,
) -> Result<Vec<WishlistCategory>, String> {
    if categories.len() > MAX_CATEGORIES {
        return Err(format!("愿望单栏目超过 {MAX_CATEGORIES} 个，已拒绝保存"));
    }
    let mut total_items = 0_usize;
    let mut used_ids = HashSet::new();
    let mut result = Vec::with_capacity(categories.len());
    for (index, category) in categories.into_iter().enumerate() {
        let title = category.title.trim().to_string();
        if title.is_empty() || title.len() > MAX_TITLE_BYTES {
            return Err(format!("第 {} 个愿望单栏目名称为空或过长", index + 1));
        }
        let mut items = Vec::with_capacity(category.items.len());
        for item in category.items {
            let item = item.trim().to_string();
            if item.is_empty() {
                continue;
            }
            if item.len() > MAX_ITEM_BYTES {
                return Err(format!("愿望单栏目“{title}”中有一条内容超过 64 KB"));
            }
            items.push(item);
        }
        total_items = total_items.saturating_add(items.len());
        if total_items > MAX_ITEMS {
            return Err(format!("愿望单内容超过 {MAX_ITEMS} 条，已拒绝保存"));
        }
        let mut id = category.id.trim().to_string();
        if id.is_empty() || id.len() > 512 || used_ids.contains(&id) {
            let seed = WishlistCategory {
                id,
                title: title.clone(),
                color: category.color,
                items: items.clone(),
            };
            id = conflict_safe_id(&seed, &result);
        }
        used_ids.insert(id.clone());
        result.push(WishlistCategory {
            id,
            title,
            color: category.color,
            items,
        });
    }
    Ok(result)
}

pub fn wishlist_path(root: &Path) -> PathBuf {
    root.join(WISHLIST_FILE_NAME)
}

pub fn load(root: &Path, device_id: &str) -> Result<WishlistPayload, String> {
    let _guard = wishlist_io_lock().lock().expect("wishlist io lock");
    load_unlocked(root, device_id)
}

pub fn save(root: &Path, payload: &WishlistPayload) -> Result<WishlistPayload, String> {
    let _guard = wishlist_io_lock().lock().expect("wishlist io lock");
    save_unlocked(root, payload)
}

fn wishlist_io_lock() -> &'static Mutex<()> {
    WISHLIST_IO_LOCK.get_or_init(|| Mutex::new(()))
}

fn canonical_root(root: &Path) -> Result<PathBuf, String> {
    let root = root
        .canonicalize()
        .map_err(|error| format!("读取愿望单目录失败：{error}"))?;
    if !root.is_dir() {
        return Err("愿望单目录不存在".to_string());
    }
    Ok(root)
}

fn safe_file(root: &Path, name: &str) -> Result<PathBuf, String> {
    let path = root.join(name);
    if path.parent() != Some(root) {
        return Err("愿望单文件路径越过便携曲库边界".to_string());
    }
    if let Ok(metadata) = fs::symlink_metadata(&path) {
        if metadata.is_dir() || crate::metadata_is_reparse_point(&metadata) {
            return Err(format!("愿望单数据路径不是普通文件：{}", path.display()));
        }
    }
    Ok(path)
}

fn load_unlocked(root: &Path, device_id: &str) -> Result<WishlistPayload, String> {
    let root = canonical_root(root)?;
    let primary = wishlist_path(&root);
    safe_file(&root, WISHLIST_FILE_NAME)?;
    let temp = safe_file(&root, &format!("{WISHLIST_FILE_NAME}.tmp"))?;
    let backup = safe_file(&root, &format!("{WISHLIST_FILE_NAME}.bak"))?;
    if primary.is_file() {
        if let Ok(payload) = read_file(&primary, device_id) {
            return Ok(payload);
        }
    } else if !temp.exists() && !backup.exists() {
        return Ok(empty_payload(device_id));
    }

    let mut candidates = [&temp, &backup]
        .into_iter()
        .filter_map(|path| {
            read_file(path, device_id).ok().map(|payload| {
                let modified = fs::metadata(path)
                    .and_then(|value| value.modified())
                    .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
                (modified, path, payload)
            })
        })
        .collect::<Vec<_>>();
    candidates.sort_by(|left, right| right.0.cmp(&left.0));
    let Some((_, source, recovered)) = candidates.into_iter().next() else {
        return Err("正式愿望单损坏，临时文件与备份也无法通过校验".to_string());
    };
    let recovery = safe_file(&root, &format!("{WISHLIST_FILE_NAME}.recovery.tmp"))?;
    write_verified(&recovery, &recovered)?;
    crate::atomic_replace_file(&recovery, &primary).map_err(|error| {
        format!(
            "已从 {} 找到有效愿望单，但恢复正式文件失败：{error}",
            source.display()
        )
    })?;
    read_file(&primary, device_id)
}

fn save_unlocked(root: &Path, payload: &WishlistPayload) -> Result<WishlistPayload, String> {
    validate_payload(payload)?;
    let root = canonical_root(root)?;
    let primary = wishlist_path(&root);
    safe_file(&root, WISHLIST_FILE_NAME)?;
    let temp = safe_file(&root, &format!("{WISHLIST_FILE_NAME}.tmp"))?;
    let backup = safe_file(&root, &format!("{WISHLIST_FILE_NAME}.bak"))?;
    let backup_temp = safe_file(&root, &format!("{WISHLIST_FILE_NAME}.bak.tmp"))?;
    write_verified(&temp, payload)?;

    if primary.exists() {
        let previous = read_file(&primary, &payload.device_id)
            .map_err(|error| format!("旧愿望单损坏，已拒绝覆盖：{error}"))?;
        write_verified(&backup_temp, &previous)?;
        crate::atomic_replace_file(&backup_temp, &backup)
            .map_err(|error| format!("原子更新愿望单备份失败：{error}"))?;
    }
    crate::atomic_replace_file(&temp, &primary)
        .map_err(|error| format!("原子保存愿望单失败，旧数据保持不变：{error}"))?;
    let persisted = read_file(&primary, &payload.device_id)?;
    if persisted != *payload {
        return Err("保存后的愿望单复核失败；备份仍保留".to_string());
    }
    Ok(persisted)
}

fn write_verified(path: &Path, payload: &WishlistPayload) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(payload).map_err(|error| error.to_string())?;
    if bytes.len() as u64 > MAX_FILE_BYTES {
        return Err("愿望单序列化后超过 16 MB，已拒绝保存".to_string());
    }
    let mut file = File::create(path).map_err(|error| error.to_string())?;
    file.write_all(&bytes).map_err(|error| error.to_string())?;
    file.sync_all().map_err(|error| error.to_string())?;
    drop(file);
    let verified = read_file(path, &payload.device_id)?;
    if verified != *payload {
        return Err("愿望单临时文件复核失败".to_string());
    }
    Ok(())
}

fn read_file(path: &Path, fallback_device_id: &str) -> Result<WishlistPayload, String> {
    let metadata = fs::symlink_metadata(path).map_err(|error| error.to_string())?;
    if metadata.len() > MAX_FILE_BYTES || crate::metadata_is_reparse_point(&metadata) {
        return Err("愿望单文件过大或不是普通文件".to_string());
    }
    parse_payload(
        &fs::read(path).map_err(|error| error.to_string())?,
        fallback_device_id,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn category(id: &str, title: &str, color: u32, items: &[&str]) -> WishlistCategory {
        WishlistCategory {
            id: id.to_string(),
            title: title.to_string(),
            color,
            items: items.iter().map(|item| item.to_string()).collect(),
        }
    }

    fn temp_root(label: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!(
            "armusic-wishlist-{label}-{}-{}",
            std::process::id(),
            crate::now_iso().replace([':', '.'], "-")
        ));
        fs::create_dir_all(&root).expect("temp root");
        root
    }

    #[test]
    fn first_merge_keeps_phone_ids_colors_order_and_unions_desktop_items() {
        let desktop = payload(
            "desktop",
            vec![
                category("to-listen", "准备听", 1, &["电脑条目"]),
                category("anime", "动漫", 2, &[]),
            ],
            false,
        )
        .unwrap();
        let phone = payload(
            "phone",
            vec![
                category("phone-anime", "动漫", 22, &["手机动漫"]),
                category("phone-listen", "准备听", 11, &["手机条目"]),
                category("phone-diary", "日记", 33, &[]),
            ],
            false,
        )
        .unwrap();

        let (merged, stats) = union_for_desktop(&desktop, &phone).unwrap();
        assert_eq!(
            merged
                .categories
                .iter()
                .map(|item| item.id.as_str())
                .collect::<Vec<_>>(),
            vec!["phone-anime", "phone-listen", "phone-diary"]
        );
        assert_eq!(merged.categories[0].color, 22);
        assert_eq!(
            merged.categories[1].items,
            vec!["手机条目".to_string(), "电脑条目".to_string()]
        );
        assert!(merged.phone_baseline_established);
        assert_eq!(stats.items_added_to_phone, 1);
    }

    #[test]
    fn multiset_union_keeps_the_larger_duplicate_count_without_multiplying() {
        assert_eq!(
            multiset_union(
                &["同一条".to_string(), "同一条".to_string()],
                &["同一条".to_string(), "另一条".to_string()]
            ),
            vec![
                "同一条".to_string(),
                "同一条".to_string(),
                "另一条".to_string()
            ]
        );
    }

    #[test]
    fn conflicting_custom_category_metadata_is_preserved_as_two_categories() {
        let merged = union_categories(
            &[category("same", "日记", 1, &["a"])],
            &[category("same", "重命名日记", 2, &["b"])],
        )
        .unwrap();
        assert_eq!(merged.len(), 2);
        assert_ne!(merged[0].id, merged[1].id);
        assert_eq!(item_count(&payload("test", merged, true).unwrap()), 2);
    }

    #[test]
    fn parses_real_phone_backup_wishlist_when_fixture_is_available() {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("..")
            .join("output")
            .join("phone-qa")
            .join("armusic-backup-final-20260710.json");
        if !path.is_file() {
            return;
        }
        let root: serde_json::Value =
            serde_json::from_slice(&fs::read(path).unwrap()).expect("phone backup json");
        let value = root["sharedPreferences"]["com.armusic"]
            ["KEY_SETTINGS_WISHLIST_CATEGORIES_JSON"]["value"]
            .as_str()
            .expect("wishlist preference");
        let parsed = parse_android_categories_json(value, "phone-fixture").unwrap();
        assert_eq!(parsed.categories.len(), 5);
        assert_eq!(item_count(&parsed), 381);
        assert_eq!(parsed.categories[0].title, "准备听");
        assert_eq!(parsed.categories[0].items[0], "memoris 海贼王");
        // Independent Android-compatible fixture digest: category colors are unsigned ARGB and
        // therefore exactly four big-endian bytes on both sides of the USB protocol.
        assert_eq!(
            parsed.snapshot_id,
            "wishlist-sha256-034d38bdf91cb9c62db2bf51653983fd"
        );
    }

    #[test]
    fn atomic_save_keeps_a_verified_previous_backup() {
        let root = temp_root("atomic");
        let first = payload("desktop", vec![category("one", "一", 1, &["first"])], false).unwrap();
        save(&root, &first).unwrap();
        let second = payload("desktop", vec![category("two", "二", 2, &["second"])], true).unwrap();
        save(&root, &second).unwrap();
        assert_eq!(load(&root, "desktop").unwrap(), second);
        let backup = read_file(&root.join(format!("{WISHLIST_FILE_NAME}.bak")), "desktop").unwrap();
        assert_eq!(backup, first);
        let _ = fs::remove_dir_all(root);
    }
}
