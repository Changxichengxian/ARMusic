use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

pub const HISTORY_SCHEMA: &str = "armusic-listening-history-v2";
pub const HISTORY_FILE_NAME: &str = ".armusic-history.json";
const LEGACY_SOURCE: &str = "legacy-sidecar";
static HISTORY_IO_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ListeningSession {
    pub event_id: String,
    pub sync_id: String,
    pub source_device: String,
    pub started_at_ms: i64,
    pub duration_ms: u64,
    #[serde(default)]
    pub repeat_count: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub media_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content_title: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RawHistory {
    pub id: i64,
    pub content_id: String,
    pub content_title: String,
    pub parent_id: String,
    pub parent_title: String,
    pub duration: i64,
    pub repeat_count: i32,
    pub start_time: i64,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HistoryStore {
    pub schema: String,
    pub device_id: String,
    pub updated_at: String,
    #[serde(default)]
    pub sessions: Vec<ListeningSession>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistorySyncPayload {
    pub schema: String,
    pub device_id: String,
    pub generated_at: String,
    pub snapshot_id: String,
    #[serde(default)]
    pub snapshot_complete: bool,
    #[serde(default)]
    pub raw_history_count: usize,
    #[serde(default)]
    pub raw_snapshot_id: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub raw_histories: Vec<RawHistory>,
    #[serde(default)]
    pub covered_sync_ids: Vec<String>,
    #[serde(default)]
    pub sessions: Vec<ListeningSession>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryMergeResult {
    pub added: usize,
    pub updated: usize,
    pub duplicates: usize,
    pub removed_provisional: usize,
    pub total_sessions: usize,
    pub total_duration_ms: u64,
    pub snapshot_id: String,
    pub persisted: bool,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecordListeningRequest {
    pub sync_id: String,
    pub session_id: String,
    pub started_at_ms: i64,
    pub duration_ms: u64,
    #[serde(default)]
    pub repeat_count: u32,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistorySummary {
    pub play_seconds: u64,
    pub last_played_at: Option<String>,
}

impl Default for HistoryStore {
    fn default() -> Self {
        Self {
            schema: HISTORY_SCHEMA.to_string(),
            device_id: desktop_device_id(),
            updated_at: Utc::now().to_rfc3339(),
            sessions: Vec::new(),
        }
    }
}

pub fn history_path(root: &Path) -> PathBuf {
    root.join(HISTORY_FILE_NAME)
}

pub fn load(root: &Path) -> Result<HistoryStore, String> {
    let _guard = history_io_lock().lock().expect("history io lock");
    load_unlocked(root)
}

pub fn save(root: &Path, store: &mut HistoryStore) -> Result<(), String> {
    let _guard = history_io_lock().lock().expect("history io lock");
    store.schema = HISTORY_SCHEMA.to_string();
    store.updated_at = Utc::now().to_rfc3339();
    store.sessions.sort_by(|a, b| {
        a.started_at_ms
            .cmp(&b.started_at_ms)
            .then_with(|| a.event_id.cmp(&b.event_id))
    });

    validate_store(store)?;
    let root = canonical_history_root(root)?;
    let path = safe_history_file(&root, HISTORY_FILE_NAME)?;
    let temp = safe_history_file(&root, &format!("{HISTORY_FILE_NAME}.tmp"))?;
    let backup = safe_history_file(&root, &format!("{HISTORY_FILE_NAME}.bak"))?;
    let backup_temp = safe_history_file(&root, &format!("{HISTORY_FILE_NAME}.bak.tmp"))?;
    let bytes = serde_json::to_vec_pretty(store).map_err(|error| error.to_string())?;
    write_verified_store(&temp, &bytes, store)?;

    if path.exists() {
        let previous_bytes =
            fs::read(&path).map_err(|error| format!("读取旧听歌记录失败：{error}"))?;
        let previous = parse_store(&previous_bytes)
            .map_err(|error| format!("旧听歌记录损坏，已拒绝覆盖：{error}"))?;
        write_verified_store(&backup_temp, &previous_bytes, &previous)?;
        crate::atomic_replace_file(&backup_temp, &backup)
            .map_err(|error| format!("原子更新听歌记录备份失败：{error}"))?;
    }

    if let Err(error) = crate::atomic_replace_file(&temp, &path) {
        let _ = fs::remove_file(&temp);
        return Err(format!("原子保存听歌记录失败，旧记录保持不变：{error}"));
    }
    let persisted = read_store_file(&path)?;
    if persisted != *store {
        return Err("保存后的听歌记录复核失败；备份文件仍保留".to_string());
    }
    Ok(())
}

fn history_io_lock() -> &'static Mutex<()> {
    HISTORY_IO_LOCK.get_or_init(|| Mutex::new(()))
}

fn canonical_history_root(root: &Path) -> Result<PathBuf, String> {
    let root = root
        .canonicalize()
        .map_err(|error| format!("读取听歌记录目录失败：{error}"))?;
    if !root.is_dir() {
        return Err("听歌记录目录不是文件夹".to_string());
    }
    Ok(root)
}

fn safe_history_file(root: &Path, name: &str) -> Result<PathBuf, String> {
    let path = root.join(name);
    match fs::symlink_metadata(&path) {
        Ok(metadata) => {
            if crate::metadata_is_reparse_point(&metadata) || !metadata.is_file() {
                return Err(format!(
                    "听歌记录内部路径不是普通文件，已拒绝读写：{}",
                    path.display()
                ));
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(format!("检查听歌记录内部路径失败：{error}")),
    }
    Ok(path)
}

fn parse_store(bytes: &[u8]) -> Result<HistoryStore, String> {
    let store: HistoryStore =
        serde_json::from_slice(bytes).map_err(|error| format!("听歌记录格式损坏：{error}"))?;
    validate_store(&store)?;
    Ok(store)
}

fn validate_store(store: &HistoryStore) -> Result<(), String> {
    if store.schema != HISTORY_SCHEMA {
        return Err(format!("不支持的听歌记录格式：{}", store.schema));
    }
    let mut events = HashSet::new();
    for session in &store.sessions {
        if session.event_id.trim().is_empty()
            || session.sync_id.trim().is_empty()
            || session.duration_ms == 0
        {
            return Err("听歌记录含有无效会话".to_string());
        }
        if !events.insert(&session.event_id) {
            return Err(format!("听歌记录含有重复事件：{}", session.event_id));
        }
    }
    Ok(())
}

fn read_store_file(path: &Path) -> Result<HistoryStore, String> {
    parse_store(&fs::read(path).map_err(|error| format!("读取听歌记录失败：{error}"))?)
}

fn write_verified_store(path: &Path, bytes: &[u8], expected: &HistoryStore) -> Result<(), String> {
    let mut file = File::create(path).map_err(|error| error.to_string())?;
    file.write_all(bytes).map_err(|error| error.to_string())?;
    file.sync_all().map_err(|error| error.to_string())?;
    drop(file);
    let verified = read_store_file(path)?;
    if verified != *expected {
        return Err("听歌记录临时文件复核失败".to_string());
    }
    Ok(())
}

fn load_unlocked(root: &Path) -> Result<HistoryStore, String> {
    let root = canonical_history_root(root)?;
    let primary = safe_history_file(&root, HISTORY_FILE_NAME)?;
    let temp = safe_history_file(&root, &format!("{HISTORY_FILE_NAME}.tmp"))?;
    let backup = safe_history_file(&root, &format!("{HISTORY_FILE_NAME}.bak"))?;

    if primary.exists() {
        if let Ok(store) = read_store_file(&primary) {
            return Ok(store);
        }
    } else if !temp.exists() && !backup.exists() {
        return Ok(HistoryStore::default());
    }

    let mut candidates = [&temp, &backup]
        .into_iter()
        .filter_map(|path| {
            read_store_file(path).ok().map(|store| {
                let modified = fs::metadata(path)
                    .and_then(|value| value.modified())
                    .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
                (modified, path, store)
            })
        })
        .collect::<Vec<_>>();
    candidates.sort_by(|left, right| right.0.cmp(&left.0));
    let Some((_, source, recovered)) = candidates.into_iter().next() else {
        return Err("正式听歌记录损坏，且临时文件与备份都无法通过校验".to_string());
    };

    let recovery = safe_history_file(&root, &format!("{HISTORY_FILE_NAME}.recovery.tmp"))?;
    let bytes = serde_json::to_vec_pretty(&recovered).map_err(|error| error.to_string())?;
    write_verified_store(&recovery, &bytes, &recovered)?;
    crate::atomic_replace_file(&recovery, &primary).map_err(|error| {
        format!(
            "已从 {} 找到有效听歌记录，但恢复正式文件失败：{error}",
            source.display()
        )
    })?;
    if read_store_file(&primary)? != recovered {
        return Err("恢复后的正式听歌记录复核失败".to_string());
    }
    Ok(recovered)
}

pub fn seed_legacy_summary(
    store: &mut HistoryStore,
    sync_id: &str,
    play_seconds: u64,
    last_played_at: Option<&str>,
) {
    if play_seconds == 0 || store.sessions.iter().any(|item| item.sync_id == sync_id) {
        return;
    }
    let started_at_ms = last_played_at
        .and_then(|value| DateTime::parse_from_rfc3339(value).ok())
        .map(|value| value.timestamp_millis())
        .unwrap_or(0);
    store.sessions.push(ListeningSession {
        event_id: format!("legacy-summary-{sync_id}"),
        sync_id: sync_id.to_string(),
        source_device: LEGACY_SOURCE.to_string(),
        started_at_ms,
        duration_ms: play_seconds.saturating_mul(1000),
        repeat_count: 0,
        media_id: None,
        content_title: None,
    });
}

pub fn record(
    store: &mut HistoryStore,
    request: RecordListeningRequest,
) -> Result<ListeningSession, String> {
    if request.sync_id.trim().is_empty() || request.session_id.trim().is_empty() {
        return Err("歌曲编号和播放会话编号不能为空".to_string());
    }
    if request.duration_ms == 0 {
        return Err("本次实际播放时长必须大于 0".to_string());
    }

    let event_id = desktop_event_id(&request.session_id, &request.sync_id);
    if let Some(existing) = store
        .sessions
        .iter_mut()
        .find(|item| item.event_id == event_id)
    {
        // The UI may checkpoint the same playback session more than once. Keep the largest
        // observed value instead of adding it repeatedly.
        existing.duration_ms = existing.duration_ms.max(request.duration_ms);
        existing.repeat_count = existing.repeat_count.max(request.repeat_count);
        existing.started_at_ms = existing.started_at_ms.min(request.started_at_ms);
        return Ok(existing.clone());
    }

    let session = ListeningSession {
        event_id,
        sync_id: request.sync_id,
        source_device: store.device_id.clone(),
        started_at_ms: request.started_at_ms,
        duration_ms: request.duration_ms,
        repeat_count: request.repeat_count,
        media_id: None,
        content_title: None,
    };
    store.sessions.push(session.clone());
    Ok(session)
}

pub fn payload(store: &HistoryStore, covered_sync_ids: Vec<String>) -> HistorySyncPayload {
    let snapshot_id = snapshot_id(&store.sessions);
    HistorySyncPayload {
        schema: HISTORY_SCHEMA.to_string(),
        device_id: store.device_id.clone(),
        generated_at: Utc::now().to_rfc3339(),
        snapshot_id: snapshot_id.clone(),
        snapshot_complete: true,
        raw_history_count: 0,
        raw_snapshot_id: String::new(),
        raw_histories: Vec::new(),
        covered_sync_ids,
        sessions: store.sessions.clone(),
    }
}

pub fn merge(
    store: &mut HistoryStore,
    incoming: HistorySyncPayload,
) -> Result<HistoryMergeResult, String> {
    if incoming.schema != HISTORY_SCHEMA {
        return Err(format!("不支持的听歌记录格式：{}", incoming.schema));
    }
    if incoming.snapshot_id != snapshot_id(&incoming.sessions) {
        return Err("手机听歌记录校验失败，电脑没有保存任何变化".to_string());
    }

    let covered = incoming
        .covered_sync_ids
        .iter()
        .cloned()
        .collect::<HashSet<_>>();
    let before = store.sessions.len();
    if incoming.snapshot_complete && !covered.is_empty() {
        store.sessions.retain(|item| {
            !(item.source_device == LEGACY_SOURCE && covered.contains(&item.sync_id))
        });
    }
    let removed_provisional = before - store.sessions.len();

    let mut by_event = store
        .sessions
        .iter()
        .enumerate()
        .map(|(index, item)| (item.event_id.clone(), index))
        .collect::<HashMap<_, _>>();
    let mut semantic = store
        .sessions
        .iter()
        .enumerate()
        .map(|(index, item)| (semantic_key(item), index))
        .collect::<HashMap<_, _>>();
    let mut added = 0;
    let mut updated = 0;
    let mut duplicates = 0;

    for item in incoming.sessions {
        if item.event_id.trim().is_empty()
            || item.sync_id.trim().is_empty()
            || item.duration_ms == 0
        {
            continue;
        }
        if let Some(index) = by_event.get(&item.event_id).copied() {
            let existing = &mut store.sessions[index];
            let next_duration = existing.duration_ms.max(item.duration_ms);
            let next_repeat = existing.repeat_count.max(item.repeat_count);
            if next_duration != existing.duration_ms || next_repeat != existing.repeat_count {
                existing.duration_ms = next_duration;
                existing.repeat_count = next_repeat;
                updated += 1;
            } else {
                duplicates += 1;
            }
            continue;
        }

        let key = semantic_key(&item);
        if let Some(index) = semantic.get(&key).copied() {
            let existing = &mut store.sessions[index];
            let next_duration = existing.duration_ms.max(item.duration_ms);
            let next_repeat = existing.repeat_count.max(item.repeat_count);
            if next_duration != existing.duration_ms || next_repeat != existing.repeat_count {
                existing.duration_ms = next_duration;
                existing.repeat_count = next_repeat;
                updated += 1;
            } else {
                duplicates += 1;
            }
            continue;
        }
        semantic.insert(key, store.sessions.len());
        by_event.insert(item.event_id.clone(), store.sessions.len());
        store.sessions.push(item);
        added += 1;
    }

    let total_duration_ms = store.sessions.iter().map(|item| item.duration_ms).sum();
    Ok(HistoryMergeResult {
        added,
        updated,
        duplicates,
        removed_provisional,
        total_sessions: store.sessions.len(),
        total_duration_ms,
        snapshot_id: snapshot_id(&store.sessions),
        persisted: false,
    })
}

pub fn archive_raw_snapshot(
    root: &Path,
    payload: &HistorySyncPayload,
) -> Result<Option<PathBuf>, String> {
    let _guard = history_io_lock().lock().expect("history io lock");
    if payload.raw_history_count == 0 && payload.raw_histories.is_empty() {
        return Ok(None);
    }
    if payload.raw_history_count != payload.raw_histories.len()
        || payload.raw_snapshot_id != raw_snapshot_id(&payload.raw_histories)
    {
        return Err("手机原始听歌记录校验失败，电脑没有确认持久化".to_string());
    }
    let safe_device = payload
        .device_id
        .chars()
        .map(|value| {
            if value.is_ascii_alphanumeric() {
                value
            } else {
                '_'
            }
        })
        .collect::<String>();
    let root = canonical_history_root(root)?;
    let directory = crate::ensure_safe_directory(&root, &root.join(".armusic-history-raw"))?;
    let path = directory.join(format!("{}-{}.json", safe_device, payload.raw_snapshot_id));
    let temp = path.with_extension("json.tmp");
    for candidate in [&path, &temp] {
        if let Ok(metadata) = fs::symlink_metadata(candidate) {
            if crate::metadata_is_reparse_point(&metadata) || !metadata.is_file() {
                return Err(format!(
                    "原始听歌记录归档路径不安全：{}",
                    candidate.display()
                ));
            }
        }
    }
    let bytes = serde_json::to_vec_pretty(payload).map_err(|error| error.to_string())?;
    let mut output = File::create(&temp).map_err(|error| error.to_string())?;
    output
        .write_all(&bytes)
        .map_err(|error| error.to_string())?;
    output.sync_all().map_err(|error| error.to_string())?;
    drop(output);
    let verified: HistorySyncPayload =
        serde_json::from_slice(&fs::read(&temp).map_err(|error| error.to_string())?)
            .map_err(|error| error.to_string())?;
    if verified.raw_history_count != payload.raw_history_count
        || verified.raw_snapshot_id != payload.raw_snapshot_id
    {
        let _ = fs::remove_file(&temp);
        return Err("电脑原始听歌记录归档复核失败".to_string());
    }
    crate::atomic_replace_file(&temp, &path).map_err(|error| error.to_string())?;
    Ok(Some(path))
}

fn raw_snapshot_id(histories: &[RawHistory]) -> String {
    let mut rows = histories
        .iter()
        .map(|history| {
            format!(
                "{}\0{}\0{}\0{}\0{}\0{}\0{}\0{}",
                history.id,
                history.content_id,
                history.content_title,
                history.parent_id,
                history.parent_title,
                history.duration,
                history.repeat_count,
                history.start_time,
            )
        })
        .collect::<Vec<_>>();
    rows.sort();
    let mut digest = Sha256::new();
    digest.update(b"armusic-raw-history-v1\0");
    rows.iter().for_each(|row| digest.update(row.as_bytes()));
    format!("raw-history-sha256-{:x}", digest.finalize())[..51].to_string()
}

pub fn summaries(store: &HistoryStore) -> HashMap<String, HistorySummary> {
    let mut result = HashMap::<String, (u64, i64)>::new();
    for session in &store.sessions {
        let entry = result.entry(session.sync_id.clone()).or_insert((0, 0));
        entry.0 = entry.0.saturating_add(session.duration_ms);
        entry.1 = entry.1.max(session.started_at_ms);
    }
    result
        .into_iter()
        .map(|(sync_id, (duration_ms, last_ms))| {
            let last_played_at =
                DateTime::<Utc>::from_timestamp_millis(last_ms).map(|value| value.to_rfc3339());
            (
                sync_id,
                HistorySummary {
                    play_seconds: duration_ms.saturating_add(500) / 1000,
                    last_played_at,
                },
            )
        })
        .collect()
}

pub fn snapshot_id(sessions: &[ListeningSession]) -> String {
    let mut rows = sessions
        .iter()
        .map(|item| {
            format!(
                "{}\0{}\0{}\0{}\0{}\0{}",
                item.event_id,
                item.sync_id,
                item.source_device,
                item.started_at_ms,
                item.duration_ms,
                item.repeat_count
            )
        })
        .collect::<Vec<_>>();
    rows.sort();
    let mut digest = Sha256::new();
    digest.update(b"armusic-history-v2\0");
    rows.iter().for_each(|row| digest.update(row.as_bytes()));
    format!("history-sha256-{:x}", digest.finalize())[..47].to_string()
}

fn desktop_device_id() -> String {
    let name = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "desktop".to_string());
    format!("desktop-{}", name.to_lowercase().replace(' ', "-"))
}

fn desktop_event_id(session_id: &str, sync_id: &str) -> String {
    let mut digest = Sha256::new();
    digest.update(b"armusic-desktop-session-v1\0");
    digest.update(session_id.as_bytes());
    digest.update(b"\0");
    digest.update(sync_id.as_bytes());
    format!("desktop-{:x}", digest.finalize())[..40].to_string()
}

fn semantic_key(item: &ListeningSession) -> String {
    format!("{}\0{}", item.sync_id, item.started_at_ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_root(name: &str) -> PathBuf {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::SystemTime::UNIX_EPOCH)
            .expect("clock")
            .as_nanos();
        let path = std::env::temp_dir().join(format!("armusic-history-{name}-{nonce}"));
        fs::create_dir_all(&path).expect("temp history root");
        path
    }

    fn session(id: &str, sync_id: &str, duration_ms: u64) -> ListeningSession {
        ListeningSession {
            event_id: id.to_string(),
            sync_id: sync_id.to_string(),
            source_device: "phone".to_string(),
            started_at_ms: 123,
            duration_ms,
            repeat_count: 0,
            media_id: None,
            content_title: None,
        }
    }

    #[test]
    fn repeated_merge_does_not_double_count() {
        let sessions = vec![session("one", "song", 5000)];
        let incoming = HistorySyncPayload {
            schema: HISTORY_SCHEMA.to_string(),
            device_id: "phone".to_string(),
            generated_at: String::new(),
            snapshot_id: snapshot_id(&sessions),
            snapshot_complete: true,
            raw_history_count: 1,
            raw_snapshot_id: String::new(),
            raw_histories: Vec::new(),
            covered_sync_ids: vec!["song".to_string()],
            sessions,
        };
        let mut store = HistoryStore::default();
        let first = merge(&mut store, incoming.clone()).expect("first");
        let second = merge(&mut store, incoming).expect("second");
        assert_eq!(first.added, 1);
        assert_eq!(second.added, 0);
        assert_eq!(second.duplicates, 1);
        assert_eq!(second.total_duration_ms, 5000);
    }

    #[test]
    fn complete_phone_snapshot_replaces_provisional_sidecar_total() {
        let mut store = HistoryStore::default();
        seed_legacy_summary(&mut store, "song", 10, None);
        let sessions = vec![session("raw", "song", 10_000)];
        let incoming = HistorySyncPayload {
            schema: HISTORY_SCHEMA.to_string(),
            device_id: "phone".to_string(),
            generated_at: String::new(),
            snapshot_id: snapshot_id(&sessions),
            snapshot_complete: true,
            raw_history_count: 1,
            raw_snapshot_id: String::new(),
            raw_histories: Vec::new(),
            covered_sync_ids: vec!["song".to_string()],
            sessions,
        };
        let result = merge(&mut store, incoming).expect("merge");
        assert_eq!(result.removed_provisional, 1);
        assert_eq!(result.total_duration_ms, 10_000);
    }

    #[test]
    fn checkpoints_update_one_desktop_session() {
        let mut store = HistoryStore::default();
        let request = |duration_ms| RecordListeningRequest {
            sync_id: "song".to_string(),
            session_id: "ui-session".to_string(),
            started_at_ms: 100,
            duration_ms,
            repeat_count: 0,
        };
        record(&mut store, request(1_000)).expect("first checkpoint");
        record(&mut store, request(3_000)).expect("second checkpoint");
        assert_eq!(store.sessions.len(), 1);
        assert_eq!(store.sessions[0].duration_ms, 3_000);
    }

    #[test]
    fn same_song_and_start_updates_even_when_event_id_changed() {
        let mut store = HistoryStore::default();
        store.sessions.push(session("old-event", "song", 1_000));
        let sessions = vec![session("new-event", "song", 4_000)];
        let incoming = HistorySyncPayload {
            schema: HISTORY_SCHEMA.to_string(),
            device_id: "phone".to_string(),
            generated_at: String::new(),
            snapshot_id: snapshot_id(&sessions),
            snapshot_complete: true,
            raw_history_count: 1,
            raw_snapshot_id: String::new(),
            raw_histories: Vec::new(),
            covered_sync_ids: vec!["song".to_string()],
            sessions,
        };
        let result = merge(&mut store, incoming).expect("merge");
        assert_eq!(store.sessions.len(), 1);
        assert_eq!(store.sessions[0].duration_ms, 4_000);
        assert_eq!(result.updated, 1);
        assert_eq!(result.total_duration_ms, 4_000);
    }

    #[test]
    fn save_keeps_verified_backup_and_atomically_loads_latest() {
        let root = temp_root("atomic-save");
        let mut first = HistoryStore::default();
        first.sessions.push(session("first", "song", 1_000));
        save(&root, &mut first).expect("first save");

        let mut second = first.clone();
        second.sessions.push(session("second", "other", 2_000));
        save(&root, &mut second).expect("second save");

        assert_eq!(load(&root).expect("load latest"), second);
        let backup = read_store_file(&root.join(format!("{HISTORY_FILE_NAME}.bak")))
            .expect("verified backup");
        assert_eq!(backup, first);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn load_recovers_verified_temp_when_primary_is_corrupt() {
        let root = temp_root("recover-temp");
        let mut expected = HistoryStore::default();
        expected.sessions.push(session("recover", "song", 3_000));
        expected.updated_at = "2026-07-10T00:00:00Z".to_string();
        fs::write(history_path(&root), b"{broken").expect("corrupt primary");
        fs::write(
            root.join(format!("{HISTORY_FILE_NAME}.tmp")),
            serde_json::to_vec_pretty(&expected).expect("serialize"),
        )
        .expect("valid temp");

        assert_eq!(load(&root).expect("recover"), expected);
        assert_eq!(
            read_store_file(&history_path(&root)).expect("primary"),
            expected
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn load_recovers_verified_backup_when_primary_and_temp_are_corrupt() {
        let root = temp_root("recover-backup");
        let mut expected = HistoryStore::default();
        expected.sessions.push(session("backup", "song", 4_000));
        fs::write(history_path(&root), b"bad").expect("corrupt primary");
        fs::write(root.join(format!("{HISTORY_FILE_NAME}.tmp")), b"bad").expect("corrupt temp");
        fs::write(
            root.join(format!("{HISTORY_FILE_NAME}.bak")),
            serde_json::to_vec_pretty(&expected).expect("serialize"),
        )
        .expect("valid backup");

        assert_eq!(load(&root).expect("recover backup"), expected);
        assert_eq!(
            read_store_file(&history_path(&root)).expect("primary"),
            expected
        );
        let _ = fs::remove_dir_all(root);
    }
}
