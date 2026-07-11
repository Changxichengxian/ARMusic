use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

pub const PLAYLISTS_SCHEMA: &str = "armusic-playlists-v1";
pub const PLAYLISTS_FILE_NAME: &str = ".armusic-playlists.json";
const MAX_PLAYLISTS: usize = 500;
const MAX_TRACKS: usize = 100_000;
const MAX_ID_BYTES: usize = 4 * 1024;
const MAX_TEXT_BYTES: usize = 64 * 1024;
const MAX_FILE_BYTES: u64 = 16 * 1024 * 1024;
static PLAYLIST_IO_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Playlist {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub sub_title: String,
    #[serde(default)]
    pub cover_uri: String,
    #[serde(default)]
    pub create_time: u64,
    #[serde(default)]
    pub modify_time: u64,
    #[serde(default)]
    pub track_ids: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistTombstone {
    pub id: String,
    pub deleted_at: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistTrackTombstone {
    pub playlist_id: String,
    pub track_id: String,
    pub removed_at: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistsPayload {
    pub schema: String,
    pub device_id: String,
    pub generated_at: String,
    pub snapshot_id: String,
    /// Desktop-only marker. Android ignores it and desktop uses it to make the first phone
    /// snapshot authoritative for playlist order and metadata.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub phone_baseline_established: bool,
    #[serde(default)]
    pub playlists: Vec<Playlist>,
    /// Explicit deletion markers. They are created only by a user deletion and prevent an older
    /// copy on the other device from silently resurrecting the playlist.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub deleted_playlists: Vec<PlaylistTombstone>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub removed_tracks: Vec<PlaylistTrackTombstone>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistsSaveRequest {
    pub expected_snapshot_id: String,
    #[serde(default)]
    pub playlists: Vec<Playlist>,
}

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistsMergeStats {
    pub playlists_added_to_desktop: usize,
    pub tracks_added_to_desktop: usize,
    pub playlists_added_to_phone: usize,
    pub tracks_added_to_phone: usize,
    pub playlists_deleted_from_desktop: usize,
    pub playlists_deleted_from_phone: usize,
    pub total_playlists: usize,
    pub total_tracks: usize,
    pub snapshot_id: String,
}

pub fn empty_payload(device_id: impl Into<String>) -> PlaylistsPayload {
    payload(device_id, Vec::new(), false).expect("empty playlists are valid")
}

pub fn payload(
    device_id: impl Into<String>,
    playlists: Vec<Playlist>,
    phone_baseline_established: bool,
) -> Result<PlaylistsPayload, String> {
    payload_with_tombstones(
        device_id,
        playlists,
        Vec::new(),
        Vec::new(),
        phone_baseline_established,
    )
}

pub fn payload_with_tombstones(
    device_id: impl Into<String>,
    playlists: Vec<Playlist>,
    deleted_playlists: Vec<PlaylistTombstone>,
    removed_tracks: Vec<PlaylistTrackTombstone>,
    phone_baseline_established: bool,
) -> Result<PlaylistsPayload, String> {
    let playlists = normalize_playlists(playlists)?;
    let deleted_playlists = normalize_tombstones(deleted_playlists)?;
    let removed_tracks = normalize_track_tombstones(removed_tracks)?;
    Ok(PlaylistsPayload {
        schema: PLAYLISTS_SCHEMA.to_string(),
        device_id: device_id.into(),
        generated_at: crate::now_iso(),
        snapshot_id: snapshot_id(&playlists, &deleted_playlists, &removed_tracks),
        phone_baseline_established,
        playlists,
        deleted_playlists,
        removed_tracks,
    })
}

pub fn parse_payload(bytes: &[u8], fallback_device_id: &str) -> Result<PlaylistsPayload, String> {
    if bytes.len() as u64 > MAX_FILE_BYTES {
        return Err("歌单文件超过 16 MB，已拒绝读取".to_string());
    }
    let mut object = serde_json::from_slice::<serde_json::Value>(bytes)
        .map_err(|error| format!("歌单 JSON 无法解析：{error}"))?
        .as_object()
        .cloned()
        .ok_or_else(|| "歌单同步文件必须是对象".to_string())?;
    let schema = object
        .get("schema")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default();
    if schema != PLAYLISTS_SCHEMA {
        return Err(format!("不支持的歌单格式：{schema}"));
    }
    let supplied_snapshot = object
        .get("snapshotId")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default()
        .to_string();
    let generated_at = object
        .get("generatedAt")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default()
        .to_string();
    let device_id = object
        .get("deviceId")
        .and_then(serde_json::Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .unwrap_or(fallback_device_id)
        .to_string();
    let baseline = object
        .get("phoneBaselineEstablished")
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false);
    let playlists = serde_json::from_value(
        object
            .remove("playlists")
            .ok_or_else(|| "歌单同步文件缺少 playlists".to_string())?,
    )
    .map_err(|error| format!("歌单列表无法解析：{error}"))?;
    let deleted_playlists = object
        .remove("deletedPlaylists")
        .map(serde_json::from_value)
        .transpose()
        .map_err(|error| format!("歌单删除记录无法解析：{error}"))?
        .unwrap_or_default();
    let removed_tracks = object
        .remove("removedTracks")
        .map(serde_json::from_value)
        .transpose()
        .map_err(|error| format!("歌单歌曲删除记录无法解析：{error}"))?
        .unwrap_or_default();
    let mut parsed = payload_with_tombstones(
        device_id,
        playlists,
        deleted_playlists,
        removed_tracks,
        baseline,
    )?;
    if !generated_at.trim().is_empty() {
        parsed.generated_at = generated_at;
    }
    if supplied_snapshot != parsed.snapshot_id {
        return Err("歌单快照校验失败，已拒绝使用".to_string());
    }
    Ok(parsed)
}

/// Cross-platform digest. Kotlin uses the same order: fixed prefix, playlist count as u64 BE,
/// length-prefixed UTF-8 strings, u64 BE timestamps, then track count and track IDs.
pub fn snapshot_id(
    playlists: &[Playlist],
    deleted_playlists: &[PlaylistTombstone],
    removed_tracks: &[PlaylistTrackTombstone],
) -> String {
    let mut digest = Sha256::new();
    digest.update(b"armusic-playlists-v1\0");
    digest.update((playlists.len() as u64).to_be_bytes());
    for playlist in playlists {
        digest_field(&mut digest, playlist.id.as_bytes());
        digest_field(&mut digest, playlist.title.as_bytes());
        digest_field(&mut digest, playlist.sub_title.as_bytes());
        digest_field(&mut digest, playlist.cover_uri.as_bytes());
        digest.update(playlist.create_time.to_be_bytes());
        digest.update(playlist.modify_time.to_be_bytes());
        digest.update((playlist.track_ids.len() as u64).to_be_bytes());
        for track_id in &playlist.track_ids {
            digest_field(&mut digest, track_id.as_bytes());
        }
    }
    digest.update((deleted_playlists.len() as u64).to_be_bytes());
    for tombstone in deleted_playlists {
        digest_field(&mut digest, tombstone.id.as_bytes());
        digest.update(tombstone.deleted_at.to_be_bytes());
    }
    digest.update((removed_tracks.len() as u64).to_be_bytes());
    for tombstone in removed_tracks {
        digest_field(&mut digest, tombstone.playlist_id.as_bytes());
        digest_field(&mut digest, tombstone.track_id.as_bytes());
        digest.update(tombstone.removed_at.to_be_bytes());
    }
    format!("playlists-sha256-{:x}", digest.finalize())[..49].to_string()
}

fn digest_field(digest: &mut Sha256, value: &[u8]) {
    digest.update((value.len() as u64).to_be_bytes());
    digest.update(value);
}

pub fn union_for_desktop(
    desktop: &PlaylistsPayload,
    phone: &PlaylistsPayload,
) -> Result<(PlaylistsPayload, PlaylistsMergeStats), String> {
    validate_payload(desktop)?;
    validate_payload(phone)?;
    let first_phone_sync = !desktop.phone_baseline_established;
    let (base, additional) = if first_phone_sync {
        (&phone.playlists, &desktop.playlists)
    } else {
        (&desktop.playlists, &phone.playlists)
    };
    let mut tombstones = if first_phone_sync {
        // Before a phone baseline exists, desktop may contain preview/default deletions that have
        // never described the user's real mobile playlists. They must not delete phone data.
        normalize_tombstones(phone.deleted_playlists.clone())?
    } else {
        merge_tombstones(&desktop.deleted_playlists, &phone.deleted_playlists)?
    };
    let mut track_tombstones = if first_phone_sync {
        normalize_track_tombstones(phone.removed_tracks.clone())?
    } else {
        merge_track_tombstones(&desktop.removed_tracks, &phone.removed_tracks)?
    };
    let mut merged_playlists = union_playlists(base, additional)?;
    merged_playlists.retain(|playlist| {
        !tombstones.iter().any(|tombstone| {
            tombstone.id == playlist.id && tombstone.deleted_at >= playlist.modify_time
        })
    });
    tombstones.retain(|tombstone| {
        !merged_playlists.iter().any(|playlist| {
            playlist.id == tombstone.id && playlist.modify_time > tombstone.deleted_at
        })
    });
    for playlist in &mut merged_playlists {
        playlist.track_ids.retain(|track_id| {
            let present_at =
                track_presence_time(&playlist.id, track_id, &desktop.playlists, &phone.playlists);
            !track_tombstones.iter().any(|tombstone| {
                tombstone.playlist_id == playlist.id
                    && tombstone.track_id == *track_id
                    && tombstone.removed_at >= present_at
            })
        });
    }
    track_tombstones.retain(|tombstone| {
        track_presence_time(
            &tombstone.playlist_id,
            &tombstone.track_id,
            &desktop.playlists,
            &phone.playlists,
        ) <= tombstone.removed_at
    });
    let merged = payload_with_tombstones(
        desktop.device_id.clone(),
        merged_playlists,
        tombstones,
        track_tombstones,
        true,
    )?;
    let stats = PlaylistsMergeStats {
        playlists_added_to_desktop: playlist_additions(desktop, &merged),
        tracks_added_to_desktop: track_additions(desktop, &merged),
        playlists_added_to_phone: playlist_additions(phone, &merged),
        tracks_added_to_phone: track_additions(phone, &merged),
        playlists_deleted_from_desktop: desktop
            .playlists
            .iter()
            .filter(|item| merged.playlists.iter().all(|merged| merged.id != item.id))
            .count(),
        playlists_deleted_from_phone: phone
            .playlists
            .iter()
            .filter(|item| merged.playlists.iter().all(|merged| merged.id != item.id))
            .count(),
        total_playlists: merged.playlists.len(),
        total_tracks: track_count(&merged),
        snapshot_id: merged.snapshot_id.clone(),
    };
    Ok((merged, stats))
}

fn track_presence_time(
    playlist_id: &str,
    track_id: &str,
    left: &[Playlist],
    right: &[Playlist],
) -> u64 {
    left.iter()
        .chain(right)
        .filter(|playlist| {
            playlist.id == playlist_id && playlist.track_ids.iter().any(|id| id == track_id)
        })
        .map(|playlist| playlist.modify_time)
        .max()
        .unwrap_or_default()
}

fn playlist_additions(target: &PlaylistsPayload, merged: &PlaylistsPayload) -> usize {
    merged
        .playlists
        .iter()
        .filter(|playlist| target.playlists.iter().all(|item| item.id != playlist.id))
        .count()
}

fn track_additions(target: &PlaylistsPayload, merged: &PlaylistsPayload) -> usize {
    merged
        .playlists
        .iter()
        .map(|playlist| {
            let existing = target
                .playlists
                .iter()
                .find(|item| item.id == playlist.id)
                .map(|item| {
                    item.track_ids
                        .iter()
                        .map(String::as_str)
                        .collect::<HashSet<_>>()
                })
                .unwrap_or_default();
            playlist
                .track_ids
                .iter()
                .filter(|track_id| !existing.contains(track_id.as_str()))
                .count()
        })
        .sum()
}

/// Keeps the base playlist sequence. A matching ID is one logical playlist; the metadata with
/// the newer modifyTime wins. Its order is the base for a stable union; explicit track removal
/// markers are applied by union_for_desktop after concurrent additions have been preserved.
pub fn union_playlists(
    base: &[Playlist],
    additional: &[Playlist],
) -> Result<Vec<Playlist>, String> {
    let mut merged = normalize_playlists(base.to_vec())?;
    for incoming in normalize_playlists(additional.to_vec())? {
        if let Some(index) = merged.iter().position(|item| item.id == incoming.id) {
            let existing = merged[index].clone();
            let mut combined = match incoming.modify_time.cmp(&existing.modify_time) {
                std::cmp::Ordering::Greater => {
                    let mut value = incoming;
                    value.track_ids = ordered_union(&value.track_ids, &existing.track_ids);
                    value
                }
                std::cmp::Ordering::Less => {
                    let mut value = existing.clone();
                    value.track_ids = ordered_union(&value.track_ids, &incoming.track_ids);
                    value
                }
                std::cmp::Ordering::Equal => {
                    let mut value = existing.clone();
                    value.track_ids = ordered_union(&value.track_ids, &incoming.track_ids);
                    value
                }
            };
            combined.create_time = combined.create_time.min(merged[index].create_time.max(1));
            merged[index] = combined;
        } else {
            merged.push(incoming);
        }
    }
    normalize_playlists(merged)
}

fn ordered_union(base: &[String], additional: &[String]) -> Vec<String> {
    let mut seen = base.iter().cloned().collect::<HashSet<_>>();
    let mut result = base.to_vec();
    for item in additional {
        if seen.insert(item.clone()) {
            result.push(item.clone());
        }
    }
    result
}

pub fn track_count(payload: &PlaylistsPayload) -> usize {
    payload
        .playlists
        .iter()
        .map(|item| item.track_ids.len())
        .sum()
}

pub fn validate_payload(payload: &PlaylistsPayload) -> Result<(), String> {
    if payload.schema != PLAYLISTS_SCHEMA {
        return Err(format!("不支持的歌单格式：{}", payload.schema));
    }
    let normalized = normalize_playlists(payload.playlists.clone())?;
    let normalized_tombstones = normalize_tombstones(payload.deleted_playlists.clone())?;
    let normalized_track_tombstones = normalize_track_tombstones(payload.removed_tracks.clone())?;
    if normalized != payload.playlists {
        return Err("歌单含有未规范化字段，已拒绝覆盖".to_string());
    }
    if normalized_tombstones != payload.deleted_playlists {
        return Err("歌单删除记录含有未规范化字段，已拒绝覆盖".to_string());
    }
    if normalized_track_tombstones != payload.removed_tracks {
        return Err("歌单歌曲删除记录含有未规范化字段，已拒绝覆盖".to_string());
    }
    if snapshot_id(
        &normalized,
        &normalized_tombstones,
        &normalized_track_tombstones,
    ) != payload.snapshot_id
    {
        return Err("歌单快照校验失败，已拒绝覆盖".to_string());
    }
    Ok(())
}

fn normalize_track_tombstones(
    tombstones: Vec<PlaylistTrackTombstone>,
) -> Result<Vec<PlaylistTrackTombstone>, String> {
    let mut result = Vec::<PlaylistTrackTombstone>::new();
    for mut tombstone in tombstones {
        tombstone.playlist_id = tombstone.playlist_id.trim().to_string();
        tombstone.track_id = tombstone.track_id.trim().to_string();
        if tombstone.playlist_id.is_empty()
            || tombstone.track_id.is_empty()
            || tombstone.playlist_id.len() > MAX_ID_BYTES
            || tombstone.track_id.len() > MAX_ID_BYTES
            || tombstone.removed_at == 0
        {
            return Err("歌单歌曲删除记录含有空标识、过长标识或无效时间".to_string());
        }
        if let Some(existing) = result.iter_mut().find(|item| {
            item.playlist_id == tombstone.playlist_id && item.track_id == tombstone.track_id
        }) {
            existing.removed_at = existing.removed_at.max(tombstone.removed_at);
        } else {
            result.push(tombstone);
        }
    }
    Ok(result)
}

fn merge_track_tombstones(
    base: &[PlaylistTrackTombstone],
    additional: &[PlaylistTrackTombstone],
) -> Result<Vec<PlaylistTrackTombstone>, String> {
    normalize_track_tombstones(base.iter().chain(additional).cloned().collect())
}

fn normalize_tombstones(
    tombstones: Vec<PlaylistTombstone>,
) -> Result<Vec<PlaylistTombstone>, String> {
    let mut result = Vec::<PlaylistTombstone>::new();
    for mut tombstone in tombstones {
        tombstone.id = tombstone.id.trim().to_string();
        if tombstone.id.is_empty() || tombstone.id.len() > MAX_ID_BYTES || tombstone.deleted_at == 0
        {
            return Err("歌单删除记录含有空 ID、过长 ID 或无效时间".to_string());
        }
        if let Some(existing) = result.iter_mut().find(|item| item.id == tombstone.id) {
            existing.deleted_at = existing.deleted_at.max(tombstone.deleted_at);
        } else {
            result.push(tombstone);
        }
    }
    Ok(result)
}

fn merge_tombstones(
    base: &[PlaylistTombstone],
    additional: &[PlaylistTombstone],
) -> Result<Vec<PlaylistTombstone>, String> {
    normalize_tombstones(base.iter().chain(additional).cloned().collect())
}

fn normalize_playlists(playlists: Vec<Playlist>) -> Result<Vec<Playlist>, String> {
    if playlists.len() > MAX_PLAYLISTS {
        return Err(format!("歌单超过 {MAX_PLAYLISTS} 个，已拒绝保存"));
    }
    let mut used_ids = HashSet::new();
    let mut total_tracks = 0usize;
    let mut result = Vec::with_capacity(playlists.len());
    for (index, mut playlist) in playlists.into_iter().enumerate() {
        playlist.id = playlist.id.trim().to_string();
        playlist.title = playlist.title.trim().to_string();
        playlist.sub_title = playlist.sub_title.trim().to_string();
        playlist.cover_uri = playlist.cover_uri.trim().to_string();
        if playlist.id.is_empty()
            || playlist.id.len() > MAX_ID_BYTES
            || !used_ids.insert(playlist.id.clone())
        {
            return Err(format!("第 {} 个歌单 ID 为空、重复或过长", index + 1));
        }
        if playlist.title.is_empty() || playlist.title.len() > MAX_TEXT_BYTES {
            return Err(format!("歌单“{}”名称为空或过长", playlist.id));
        }
        if playlist.sub_title.len() > MAX_TEXT_BYTES || playlist.cover_uri.len() > MAX_TEXT_BYTES {
            return Err(format!("歌单“{}”的说明或封面地址过长", playlist.title));
        }
        let mut seen_tracks = HashSet::new();
        playlist.track_ids = playlist
            .track_ids
            .into_iter()
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty())
            .filter(|value| seen_tracks.insert(value.clone()))
            .collect();
        if playlist
            .track_ids
            .iter()
            .any(|value| value.len() > MAX_ID_BYTES)
        {
            return Err(format!("歌单“{}”包含过长的歌曲标识", playlist.title));
        }
        total_tracks = total_tracks.saturating_add(playlist.track_ids.len());
        if total_tracks > MAX_TRACKS {
            return Err(format!("歌单歌曲条目超过 {MAX_TRACKS} 条，已拒绝保存"));
        }
        if playlist.create_time == 0 {
            playlist.create_time = playlist.modify_time.max(1);
        }
        if playlist.modify_time == 0 {
            playlist.modify_time = playlist.create_time;
        }
        result.push(playlist);
    }
    Ok(result)
}

pub fn playlists_path(root: &Path) -> PathBuf {
    root.join(PLAYLISTS_FILE_NAME)
}

pub fn load(root: &Path, device_id: &str) -> Result<PlaylistsPayload, String> {
    let _guard = playlist_io_lock().lock().expect("playlist io lock");
    load_unlocked(root, device_id)
}

pub fn save(root: &Path, payload: &PlaylistsPayload) -> Result<PlaylistsPayload, String> {
    let _guard = playlist_io_lock().lock().expect("playlist io lock");
    save_unlocked(root, payload)
}

fn playlist_io_lock() -> &'static Mutex<()> {
    PLAYLIST_IO_LOCK.get_or_init(|| Mutex::new(()))
}

fn canonical_root(root: &Path) -> Result<PathBuf, String> {
    let root = root
        .canonicalize()
        .map_err(|error| format!("读取歌单目录失败：{error}"))?;
    if !root.is_dir() {
        return Err("歌单目录不存在".to_string());
    }
    Ok(root)
}

fn safe_file(root: &Path, name: &str) -> Result<PathBuf, String> {
    let path = root.join(name);
    if path.parent() != Some(root) {
        return Err("歌单文件路径越过便携曲库边界".to_string());
    }
    if let Ok(metadata) = fs::symlink_metadata(&path) {
        if metadata.is_dir() || crate::metadata_is_reparse_point(&metadata) {
            return Err(format!("歌单数据路径不是普通文件：{}", path.display()));
        }
    }
    Ok(path)
}

fn load_unlocked(root: &Path, device_id: &str) -> Result<PlaylistsPayload, String> {
    let root = canonical_root(root)?;
    let primary = playlists_path(&root);
    safe_file(&root, PLAYLISTS_FILE_NAME)?;
    let temp = safe_file(&root, &format!("{PLAYLISTS_FILE_NAME}.tmp"))?;
    let backup = safe_file(&root, &format!("{PLAYLISTS_FILE_NAME}.bak"))?;
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
        return Err("正式歌单损坏，临时文件与备份也无法通过校验".to_string());
    };
    let recovery = safe_file(&root, &format!("{PLAYLISTS_FILE_NAME}.recovery.tmp"))?;
    write_verified(&recovery, &recovered)?;
    crate::atomic_replace_file(&recovery, &primary).map_err(|error| {
        format!(
            "已从 {} 找到有效歌单，但恢复正式文件失败：{error}",
            source.display()
        )
    })?;
    read_file(&primary, device_id)
}

fn save_unlocked(root: &Path, payload: &PlaylistsPayload) -> Result<PlaylistsPayload, String> {
    validate_payload(payload)?;
    let root = canonical_root(root)?;
    let primary = playlists_path(&root);
    safe_file(&root, PLAYLISTS_FILE_NAME)?;
    let temp = safe_file(&root, &format!("{PLAYLISTS_FILE_NAME}.tmp"))?;
    let backup = safe_file(&root, &format!("{PLAYLISTS_FILE_NAME}.bak"))?;
    let backup_temp = safe_file(&root, &format!("{PLAYLISTS_FILE_NAME}.bak.tmp"))?;
    write_verified(&temp, payload)?;
    if primary.exists() {
        let previous = read_file(&primary, &payload.device_id)
            .map_err(|error| format!("旧歌单损坏，已拒绝覆盖：{error}"))?;
        write_verified(&backup_temp, &previous)?;
        crate::atomic_replace_file(&backup_temp, &backup)
            .map_err(|error| format!("原子更新歌单备份失败：{error}"))?;
    }
    crate::atomic_replace_file(&temp, &primary)
        .map_err(|error| format!("原子保存歌单失败，旧数据保持不变：{error}"))?;
    let persisted = read_file(&primary, &payload.device_id)?;
    if persisted != *payload {
        return Err("保存后的歌单复核失败；备份仍保留".to_string());
    }
    Ok(persisted)
}

fn write_verified(path: &Path, payload: &PlaylistsPayload) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(payload).map_err(|error| error.to_string())?;
    if bytes.len() as u64 > MAX_FILE_BYTES {
        return Err("歌单序列化后超过 16 MB，已拒绝保存".to_string());
    }
    let mut file = File::create(path).map_err(|error| error.to_string())?;
    file.write_all(&bytes).map_err(|error| error.to_string())?;
    file.sync_all().map_err(|error| error.to_string())?;
    drop(file);
    if read_file(path, &payload.device_id)? != *payload {
        return Err("歌单临时文件复核失败".to_string());
    }
    Ok(())
}

fn read_file(path: &Path, fallback_device_id: &str) -> Result<PlaylistsPayload, String> {
    let metadata = fs::symlink_metadata(path).map_err(|error| error.to_string())?;
    if metadata.len() > MAX_FILE_BYTES || crate::metadata_is_reparse_point(&metadata) {
        return Err("歌单文件过大或不是普通文件".to_string());
    }
    parse_payload(
        &fs::read(path).map_err(|error| error.to_string())?,
        fallback_device_id,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn playlist(id: &str, title: &str, modified: u64, tracks: &[&str]) -> Playlist {
        Playlist {
            id: id.to_string(),
            title: title.to_string(),
            sub_title: String::new(),
            cover_uri: String::new(),
            create_time: 1,
            modify_time: modified,
            track_ids: tracks.iter().map(|item| item.to_string()).collect(),
        }
    }

    fn temp_root(label: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!(
            "armusic-playlists-{label}-{}-{}",
            std::process::id(),
            crate::now_iso().replace([':', '.'], "-")
        ));
        fs::create_dir_all(&root).expect("temp root");
        root
    }

    #[test]
    fn first_merge_keeps_newer_phone_order_metadata_and_tracks() {
        let desktop = payload(
            "desktop",
            vec![playlist("shared", "电脑旧名", 2, &["a", "desktop-only"])],
            false,
        )
        .unwrap();
        let phone = payload(
            "phone",
            vec![
                playlist("phone-first", "手机一", 10, &["x"]),
                playlist("shared", "手机新名", 20, &["a", "phone-only"]),
            ],
            false,
        )
        .unwrap();
        let (merged, stats) = union_for_desktop(&desktop, &phone).unwrap();
        assert_eq!(merged.playlists[0].id, "phone-first");
        assert_eq!(merged.playlists[1].title, "手机新名");
        assert_eq!(
            merged.playlists[1].track_ids,
            vec!["a", "phone-only", "desktop-only"]
        );
        assert!(merged.phone_baseline_established);
        assert_eq!(stats.tracks_added_to_phone, 1);
    }

    #[test]
    fn cross_platform_snapshot_fixture_is_stable() {
        let playlists = vec![
            Playlist {
                id: "p-α".into(),
                title: "夜航".into(),
                sub_title: "说明".into(),
                cover_uri: "content://封面/1".into(),
                create_time: 1_783_256_122_959,
                modify_time: 1_783_256_123_999,
                track_ids: vec!["audio-sha256-abc".into(), "sha256-旧".into()],
            },
            Playlist {
                id: "p-empty".into(),
                title: "空".into(),
                sub_title: String::new(),
                cover_uri: String::new(),
                create_time: 1,
                modify_time: 2,
                track_ids: Vec::new(),
            },
        ];
        let deleted = vec![PlaylistTombstone {
            id: "p-deleted".into(),
            deleted_at: 1_783_256_124_000,
        }];
        let removed = vec![PlaylistTrackTombstone {
            playlist_id: "p-α".into(),
            track_id: "sha256-移除".into(),
            removed_at: 1_783_256_124_001,
        }];

        // The Android implementation has the same UTF-8 fixture and expected ID. Keeping this
        // golden value catches field-order, byte-length and big-endian timestamp drift.
        assert_eq!(
            snapshot_id(&playlists, &deleted, &removed),
            "playlists-sha256-7b717f23f63b9ecec2d9a9b8b3be42d6"
        );
    }

    #[test]
    fn newer_metadata_wins_without_destroying_base_track_order() {
        let merged = union_playlists(
            &[playlist("same", "旧名", 10, &["one", "two"])],
            &[playlist("same", "新名", 20, &["two", "three"])],
        )
        .unwrap();
        assert_eq!(merged[0].title, "新名");
        assert_eq!(merged[0].track_ids, vec!["two", "three", "one"]);
    }

    #[test]
    fn equal_timestamp_conflict_unions_tracks_without_reordering_base() {
        let merged = union_playlists(
            &[playlist("same", "名称", 20, &["one", "two"])],
            &[playlist("same", "名称", 20, &["two", "three"])],
        )
        .unwrap();
        assert_eq!(merged[0].track_ids, vec!["one", "two", "three"]);
    }

    #[test]
    fn explicit_newer_tombstone_prevents_resurrection() {
        let desktop = payload_with_tombstones(
            "desktop",
            vec![],
            vec![PlaylistTombstone {
                id: "gone".into(),
                deleted_at: 30,
            }],
            vec![],
            true,
        )
        .unwrap();
        let phone = payload(
            "phone",
            vec![playlist("gone", "旧副本", 20, &["one"])],
            false,
        )
        .unwrap();
        let (merged, _) = union_for_desktop(&desktop, &phone).unwrap();
        assert!(merged.playlists.is_empty());
        assert_eq!(merged.deleted_playlists[0].id, "gone");
    }

    #[test]
    fn explicit_track_removal_beats_older_copy_but_keeps_concurrent_addition() {
        let desktop = payload_with_tombstones(
            "desktop",
            vec![playlist("same", "歌单", 30, &["kept", "desktop-add"])],
            vec![],
            vec![PlaylistTrackTombstone {
                playlist_id: "same".into(),
                track_id: "removed".into(),
                removed_at: 30,
            }],
            true,
        )
        .unwrap();
        let phone = payload(
            "phone",
            vec![playlist(
                "same",
                "歌单",
                25,
                &["kept", "removed", "phone-add"],
            )],
            false,
        )
        .unwrap();
        let (merged, _) = union_for_desktop(&desktop, &phone).unwrap();
        assert_eq!(
            merged.playlists[0].track_ids,
            vec!["kept", "desktop-add", "phone-add"]
        );
        assert_eq!(merged.removed_tracks.len(), 1);
    }

    #[test]
    fn newer_explicit_readd_clears_track_tombstone() {
        let desktop = payload_with_tombstones(
            "desktop",
            vec![playlist("same", "歌单", 30, &["kept"])],
            vec![],
            vec![PlaylistTrackTombstone {
                playlist_id: "same".into(),
                track_id: "readded".into(),
                removed_at: 30,
            }],
            true,
        )
        .unwrap();
        let phone = payload(
            "phone",
            vec![playlist("same", "歌单", 31, &["kept", "readded"])],
            false,
        )
        .unwrap();

        let (merged, _) = union_for_desktop(&desktop, &phone).unwrap();
        assert_eq!(merged.playlists[0].track_ids, vec!["kept", "readded"]);
        assert!(merged.removed_tracks.is_empty());
    }

    #[test]
    fn first_phone_baseline_ignores_untrusted_desktop_tombstones() {
        let desktop = payload_with_tombstones(
            "desktop",
            vec![],
            vec![PlaylistTombstone {
                id: "phone-real".into(),
                deleted_at: 999,
            }],
            vec![PlaylistTrackTombstone {
                playlist_id: "phone-real".into(),
                track_id: "one".into(),
                removed_at: 999,
            }],
            false,
        )
        .unwrap();
        let phone = payload(
            "phone",
            vec![playlist("phone-real", "手机真实歌单", 10, &["one"])],
            false,
        )
        .unwrap();

        let (merged, _) = union_for_desktop(&desktop, &phone).unwrap();
        assert_eq!(merged.playlists.len(), 1);
        assert_eq!(merged.playlists[0].track_ids, vec!["one"]);
        assert!(merged.deleted_playlists.is_empty());
        assert!(merged.removed_tracks.is_empty());
    }

    #[test]
    fn atomic_save_keeps_verified_backup() {
        let root = temp_root("atomic");
        let first = payload("desktop", vec![playlist("one", "一", 1, &["a"])], false).unwrap();
        save(&root, &first).unwrap();
        let second = payload("desktop", vec![playlist("two", "二", 2, &["b"])], true).unwrap();
        save(&root, &second).unwrap();
        assert_eq!(load(&root, "desktop").unwrap(), second);
        assert_eq!(
            read_file(&root.join(format!("{PLAYLISTS_FILE_NAME}.bak")), "desktop").unwrap(),
            first
        );
        let _ = fs::remove_dir_all(root);
    }
}
