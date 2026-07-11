use crate::listening_history::{self, HistorySyncPayload};
use crate::playlists::{self, PlaylistsMergeStats, PlaylistsPayload};
use crate::wishlist::{self, WishlistMergeStats, WishlistPayload};
use crate::{
    apply_history_to_tracks, atomic_replace_with_backup, copy_file_synced,
    desktop_playlists_device_id, desktop_wishlist_device_id, ensure_safe_directory, history_root,
    identity_index, import_track_from_reader, load_seeded_history, normalize_history_ids, now_iso,
    refresh_library_locked, remove_imported_track_if_unchanged, scan_music_folder_uncached,
    track_in_sync_scope, tracks_share_identity, validate_unique_track_identities, AppInner, Track,
};
use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::env;
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use wait_timeout::ChildExt;

const AGENT_RECEIVER: &str = "com.armusic/com.lalilu.lmusic.agent.ARMusicAgentReceiver";
const PHONE_LIBRARY: &str =
    "/storage/emulated/0/Android/data/com.armusic/files/agent/armusic-library.json";
const PHONE_HISTORY: &str =
    "/storage/emulated/0/Android/data/com.armusic/files/agent/armusic-history.json";
const PHONE_IMPORT: &str =
    "/storage/emulated/0/Android/data/com.armusic/files/agent/history-merged-from-desktop.json";
const PHONE_RECEIPT: &str =
    "/storage/emulated/0/Android/data/com.armusic/files/agent/history-clear-receipt.json";
const PHONE_WISHLIST: &str =
    "/storage/emulated/0/Android/data/com.armusic/files/agent/armusic-wishlist.json";
const PHONE_WISHLIST_IMPORT: &str =
    "/storage/emulated/0/Android/data/com.armusic/files/agent/wishlist-merged-from-desktop.json";
const PHONE_PLAYLISTS: &str =
    "/storage/emulated/0/Android/data/com.armusic/files/agent/armusic-playlists.json";
const PHONE_PLAYLISTS_IMPORT: &str =
    "/storage/emulated/0/Android/data/com.armusic/files/agent/playlists-merged-from-desktop.json";
const PHONE_AGENT_DIR: &str = "/storage/emulated/0/Android/data/com.armusic/files/agent";
const RENAME_EXCHANGE_ARM64: &[u8] = include_bytes!("../assets/armusic-rename-exchange-arm64");
const PHONE_RENAME_EXCHANGE: &str = "/data/local/tmp/armusic-rename-exchange-v1";
static ADB_OPERATION_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdbDevice {
    pub serial: String,
    pub model: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdbTrackSummary {
    pub sync_id: String,
    pub title: String,
    pub artist: String,
    pub relative_path: String,
    pub size_bytes: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdbSyncConflict {
    pub sync_id: String,
    pub title: String,
    pub desktop_revision_hash: Option<String>,
    pub phone_revision_hash: Option<String>,
    pub desktop_modified_at: Option<String>,
    pub phone_modified_at: Option<String>,
    pub recommended_resolution: Option<ConflictResolution>,
    pub reason: String,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum ConflictResolution {
    DesktopToPhone,
    PhoneToDesktop,
    Skip,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdbSyncPreview {
    pub device: AdbDevice,
    pub desktop_track_count: usize,
    pub phone_track_count: usize,
    pub upload_to_phone: Vec<AdbTrackSummary>,
    pub download_to_desktop: Vec<AdbTrackSummary>,
    pub conflicts: Vec<AdbSyncConflict>,
    pub phone_history_count: usize,
    pub phone_history_snapshot_id: String,
    pub phone_raw_history_count: usize,
    pub phone_raw_snapshot_id: String,
    pub wishlist_supported: bool,
    pub desktop_wishlist_category_count: usize,
    pub desktop_wishlist_item_count: usize,
    pub phone_wishlist_category_count: usize,
    pub phone_wishlist_item_count: usize,
    pub wishlist_categories_add_to_desktop: usize,
    pub wishlist_items_add_to_desktop: usize,
    pub wishlist_categories_add_to_phone: usize,
    pub wishlist_items_add_to_phone: usize,
    pub wishlist_snapshot_id_after_merge: String,
    pub playlists_supported: bool,
    pub desktop_playlist_count: usize,
    pub desktop_playlist_track_count: usize,
    pub phone_playlist_count: usize,
    pub phone_playlist_track_count: usize,
    pub playlists_add_to_desktop: usize,
    pub playlist_tracks_add_to_desktop: usize,
    pub playlists_add_to_phone: usize,
    pub playlist_tracks_add_to_phone: usize,
    pub playlists_delete_from_desktop: usize,
    pub playlists_delete_from_phone: usize,
    pub playlists_snapshot_id_after_merge: String,
    pub desktop_ignored_sync_track_count: usize,
    pub phone_ignored_sync_track_count: usize,
    pub sync_notice: String,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum HistoryMode {
    #[default]
    KeepOnBoth,
    DesktopOnly,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecuteAdbSyncRequest {
    #[serde(default)]
    pub serial: Option<String>,
    #[serde(default = "default_true")]
    pub sync_songs: bool,
    #[serde(default = "default_true")]
    pub sync_wishlist: bool,
    #[serde(default = "default_true")]
    pub sync_playlists: bool,
    #[serde(default)]
    pub history_mode: HistoryMode,
    #[serde(default)]
    pub confirm_delete_phone_history: bool,
    #[serde(default)]
    pub confirmed_phone_snapshot_id: Option<String>,
    #[serde(default)]
    pub confirmed_phone_history_count: Option<usize>,
    #[serde(default)]
    pub confirmed_phone_raw_snapshot_id: Option<String>,
    #[serde(default)]
    pub confirmed_phone_raw_history_count: Option<usize>,
    #[serde(default)]
    pub apply_newer_conflicts: bool,
    #[serde(default)]
    pub conflict_resolutions: HashMap<String, ConflictResolution>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecuteAdbSyncResult {
    pub preview: AdbSyncPreview,
    pub uploaded_to_phone: usize,
    pub downloaded_to_desktop: usize,
    pub phone_imported_histories: usize,
    pub phone_duplicate_histories: usize,
    pub phone_cleared_histories: usize,
    pub desktop_history_sessions: usize,
    pub desktop_history_snapshot_id: String,
    pub conflicts_left_untouched: usize,
    pub warnings: Vec<String>,
    pub phone_history_backup_path: Option<String>,
    pub wishlist_synced: bool,
    pub wishlist_categories: usize,
    pub wishlist_items: usize,
    pub wishlist_snapshot_id: String,
    pub wishlist_categories_added_to_desktop: usize,
    pub wishlist_items_added_to_desktop: usize,
    pub wishlist_categories_added_to_phone: usize,
    pub wishlist_items_added_to_phone: usize,
    pub playlists_synced: bool,
    pub playlist_count: usize,
    pub playlist_track_count: usize,
    pub playlists_snapshot_id: String,
    pub playlists_added_to_desktop: usize,
    pub playlist_tracks_added_to_desktop: usize,
    pub playlists_added_to_phone: usize,
    pub playlist_tracks_added_to_phone: usize,
    pub playlists_deleted_from_desktop: usize,
    pub playlists_deleted_from_phone: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentLibrary {
    #[serde(default)]
    songs: Vec<AgentSong>,
    #[serde(default)]
    ignored_songs: Vec<serde_json::Value>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSong {
    sync_id: String,
    #[serde(default)]
    legacy_sync_ids: Vec<String>,
    revision_hash: Option<String>,
    #[serde(default)]
    #[serde(rename = "mediaId")]
    _media_id: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    artist: String,
    #[serde(default)]
    album: String,
    #[serde(default)]
    duration_ms: u64,
    #[serde(default)]
    file_path: String,
    #[serde(default)]
    relative_path: String,
    #[serde(default)]
    size_bytes: u64,
    modified_at: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentResult {
    ok: bool,
    #[serde(default)]
    message: String,
    #[serde(default)]
    command_id: String,
    #[serde(default)]
    output_path: String,
    #[serde(default)]
    imported_histories: usize,
    #[serde(default)]
    duplicates: usize,
    #[serde(default)]
    cleared_histories: usize,
    #[serde(default)]
    committed_songs: usize,
    #[serde(default)]
    already_present: bool,
    #[serde(default)]
    committed_sync_id: String,
    #[serde(default)]
    verified_songs: usize,
    #[serde(default)]
    current_revision_hash: String,
    #[serde(default)]
    lease_token: String,
    #[serde(default)]
    lease_expires_at: u64,
    #[serde(default)]
    wishlist_categories: usize,
    #[serde(default)]
    wishlist_items: usize,
    #[serde(default)]
    wishlist_snapshot_id: String,
    #[serde(default)]
    playlist_count: usize,
    #[serde(default)]
    playlist_items: usize,
    #[serde(default)]
    playlist_snapshot_id: String,
}

struct PhoneSnapshot {
    device: AdbDevice,
    library: AgentLibrary,
    history: HistorySyncPayload,
    wishlist: Option<WishlistPayload>,
    playlists: Option<PlaylistsPayload>,
}

struct PreparedPhoneReplacement {
    title: String,
    desired_track: Track,
    target: String,
    staging: String,
    old_identity: crate::sync_identity::AudioIdentity,
    new_identity: crate::sync_identity::AudioIdentity,
}

pub fn list_devices() -> Result<Vec<AdbDevice>, String> {
    let output = adb_output(None, &["devices", "-l"])?;
    ensure_success(&output, "读取 Android 设备")?;
    let text = String::from_utf8_lossy(&output.stdout);
    Ok(text
        .lines()
        .skip(1)
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let serial = parts.next()?.to_string();
            if parts.next()? != "device" {
                return None;
            }
            let model = line
                .split_whitespace()
                .find_map(|part| part.strip_prefix("model:"))
                .unwrap_or("Android")
                .replace('_', " ");
            Some(AdbDevice { serial, model })
        })
        .collect())
}

pub fn preview(
    state: Arc<AppInner>,
    requested_serial: Option<String>,
) -> Result<AdbSyncPreview, String> {
    let _adb_guard = adb_operation_lock().lock().expect("adb operation lock");
    let device = choose_device(requested_serial)?;
    let snapshot = export_phone_snapshot(&device)?;
    let _file_guard = state.file_mutation_lock.lock().expect("file mutation lock");
    refresh_library_locked(&state)?;
    build_preview(&state, &snapshot)
}

pub fn execute(
    state: Arc<AppInner>,
    request: ExecuteAdbSyncRequest,
) -> Result<ExecuteAdbSyncResult, String> {
    let _adb_guard = adb_operation_lock().lock().expect("adb operation lock");
    if request.history_mode == HistoryMode::DesktopOnly && !request.confirm_delete_phone_history {
        return Err("仅电脑保留听歌时间需要在界面中再次确认；本次没有删除手机记录".to_string());
    }

    let device = choose_device(request.serial.clone())?;
    let mut snapshot = export_phone_snapshot(&device)?;
    if request.history_mode == HistoryMode::DesktopOnly
        && (request.confirmed_phone_snapshot_id.as_deref()
            != Some(snapshot.history.snapshot_id.as_str())
            || request.confirmed_phone_history_count != Some(snapshot.history.sessions.len())
            || request.confirmed_phone_raw_snapshot_id.as_deref()
                != Some(snapshot.history.raw_snapshot_id.as_str())
            || request.confirmed_phone_raw_history_count
                != Some(snapshot.history.raw_history_count))
    {
        return Err("手机听歌记录已在预览后变化，请重新预览并再次确认；本次没有删除".to_string());
    }
    let preview = {
        let _file_guard = state.file_mutation_lock.lock().expect("file mutation lock");
        refresh_library_locked(&state)?;
        build_preview(&state, &snapshot)?
    };
    let mut uploaded_to_phone = 0;
    let mut downloaded_to_desktop = 0;
    let mut resolved_conflicts = 0;
    let mut warnings = Vec::new();

    if request.sync_songs {
        let (uploaded, upload_warnings) = upload_missing_to_phone(&state, &snapshot, &preview)?;
        uploaded_to_phone = uploaded;
        warnings.extend(upload_warnings);
        downloaded_to_desktop = download_missing_to_desktop(&state, &snapshot, &preview)?;
        let (resolved, conflict_warnings) =
            resolve_conflicts(&state, &snapshot, &preview, &request)?;
        resolved_conflicts = resolved;
        warnings.extend(conflict_warnings);

        // Conflict replacement may force a final paused-session flush before
        // stopping the phone app. Always export history again so those seconds
        // cannot be omitted from the desktop merge.
        snapshot.history = export_phone_history(&device)?;
        if request.history_mode == HistoryMode::DesktopOnly
            && (request.confirmed_phone_snapshot_id.as_deref()
                != Some(snapshot.history.snapshot_id.as_str())
                || request.confirmed_phone_history_count != Some(snapshot.history.sessions.len())
                || request.confirmed_phone_raw_snapshot_id.as_deref()
                    != Some(snapshot.history.raw_snapshot_id.as_str())
                || request.confirmed_phone_raw_history_count
                    != Some(snapshot.history.raw_history_count))
        {
            return Err(
                "手机听歌记录在文件同步期间完成了最后一次保存；歌曲变更已安全完成，但听歌记录没有删除。请重新预览并再次确认"
                    .to_string(),
            );
        }
    }

    // Persist the exact phone snapshot before any optional destructive action.
    let _history_guard = state.history_lock.lock().expect("history lock");
    let root = history_root(&state)?;
    let tracks = state.tracks.lock().expect("tracks lock").clone();
    listening_history::archive_raw_snapshot(&root, &snapshot.history)?;
    let mut history_store = load_seeded_history(&root, &tracks)?;
    let mut merge_result = listening_history::merge(&mut history_store, snapshot.history.clone())?;
    normalize_history_ids(&mut history_store, &tracks);
    listening_history::save(&root, &mut history_store)?;
    merge_result.persisted = true;
    merge_result.snapshot_id = listening_history::snapshot_id(&history_store.sessions);
    apply_history_to_tracks(
        &history_store,
        &mut state.tracks.lock().expect("tracks lock"),
    );
    let canonical_history = listening_history::payload(
        &history_store,
        tracks.iter().map(|track| track.sync_id.clone()).collect(),
    );
    drop(_history_guard);

    let mut phone_imported_histories = 0;
    let mut phone_duplicate_histories = 0;
    let mut phone_cleared_histories = 0;
    let mut phone_history_backup_path = None;
    match request.history_mode {
        HistoryMode::KeepOnBoth => {
            let local_path = temp_session_dir(&device.serial)?.join("history-merged.json");
            fs::write(
                &local_path,
                serde_json::to_vec_pretty(&canonical_history).map_err(|error| error.to_string())?,
            )
            .map_err(|error| error.to_string())?;
            adb_push(&device.serial, &local_path, PHONE_IMPORT)?;
            let result = run_agent_command(&device.serial, "import_history", Some(PHONE_IMPORT))?;
            phone_imported_histories = result.imported_histories;
            phone_duplicate_histories = result.duplicates;
        }
        HistoryMode::DesktopOnly => {
            let clear_command_id = new_command_id(&device.serial, "clear_history")?;
            let receipt = serde_json::json!({
                "schema": "armusic-history-clear-receipt-v1",
                "phoneSnapshotId": snapshot.history.snapshot_id,
                "phoneHistoryCount": snapshot.history.sessions.len(),
                "phoneRawHistoryCount": snapshot.history.raw_history_count,
                "phoneRawSnapshotId": snapshot.history.raw_snapshot_id,
                "desktopStoreSnapshotId": canonical_history.snapshot_id,
                "desktopPersisted": true,
                "userConfirmed": true,
                "commandId": clear_command_id.clone(),
                "expiresAt": epoch_millis()?.saturating_add(2 * 60 * 1000),
                "createdAt": now_iso(),
            });
            let local_path = temp_session_dir(&device.serial)?.join("history-clear-receipt.json");
            fs::write(
                &local_path,
                serde_json::to_vec_pretty(&receipt).map_err(|error| error.to_string())?,
            )
            .map_err(|error| error.to_string())?;
            adb_push(&device.serial, &local_path, PHONE_RECEIPT)?;
            let result = run_agent_command_with_id(
                &device.serial,
                "clear_history",
                Some(PHONE_RECEIPT),
                Some(&clear_command_id),
            )?;
            phone_cleared_histories = result.cleared_histories;
            phone_history_backup_path = Some(result.output_path);
        }
    }

    let mut wishlist_synced = false;
    let mut wishlist_categories = 0;
    let mut wishlist_items = 0;
    let mut wishlist_snapshot_id = String::new();
    let mut wishlist_categories_added_to_desktop = 0;
    let mut wishlist_items_added_to_desktop = 0;
    let mut wishlist_categories_added_to_phone = 0;
    let mut wishlist_items_added_to_phone = 0;
    if request.sync_wishlist {
        if snapshot.wishlist.is_none() {
            warnings.push(
                "手机上的 ARMusic 版本尚不支持愿望单同步；歌曲和听歌时间照常完成，愿望单两端均未改动"
                    .to_string(),
            );
        } else if let Some(fresh_phone_wishlist) = export_phone_wishlist(&snapshot.device)? {
            let (merged, stats) =
                sync_wishlist_with_phone(&state, &snapshot.device, &fresh_phone_wishlist)?;
            wishlist_synced = true;
            wishlist_categories = merged.categories.len();
            wishlist_items = wishlist::item_count(&merged);
            wishlist_snapshot_id = merged.snapshot_id;
            wishlist_categories_added_to_desktop = stats.categories_added_to_desktop;
            wishlist_items_added_to_desktop = stats.items_added_to_desktop;
            wishlist_categories_added_to_phone = stats.categories_added_to_phone;
            wishlist_items_added_to_phone = stats.items_added_to_phone;
        } else {
            warnings.push(
                "手机愿望单能力在预览后不可用；愿望单两端均未覆盖，请升级手机 ARMusic 后重试"
                    .to_string(),
            );
        }
    }

    let mut playlists_synced = false;
    let mut playlist_count = 0;
    let mut playlist_track_count = 0;
    let mut playlists_snapshot_id = String::new();
    let mut playlists_added_to_desktop = 0;
    let mut playlist_tracks_added_to_desktop = 0;
    let mut playlists_added_to_phone = 0;
    let mut playlist_tracks_added_to_phone = 0;
    let mut playlists_deleted_from_desktop = 0;
    let mut playlists_deleted_from_phone = 0;
    if request.sync_playlists {
        if snapshot.playlists.is_none() {
            warnings.push(
                "手机上的 ARMusic 版本尚不支持歌单同步；歌曲、听歌时间和愿望单照常完成，歌单两端均未改动"
                    .to_string(),
            );
        } else if let Some(fresh_phone_playlists) = export_phone_playlists(&snapshot.device)? {
            let (merged, stats) =
                sync_playlists_with_phone(&state, &snapshot.device, &fresh_phone_playlists)?;
            playlists_synced = true;
            playlist_count = merged.playlists.len();
            playlist_track_count = playlists::track_count(&merged);
            playlists_snapshot_id = merged.snapshot_id;
            playlists_added_to_desktop = stats.playlists_added_to_desktop;
            playlist_tracks_added_to_desktop = stats.tracks_added_to_desktop;
            playlists_added_to_phone = stats.playlists_added_to_phone;
            playlist_tracks_added_to_phone = stats.tracks_added_to_phone;
            playlists_deleted_from_desktop = stats.playlists_deleted_from_desktop;
            playlists_deleted_from_phone = stats.playlists_deleted_from_phone;
        } else {
            warnings.push(
                "手机歌单能力在预览后不可用；歌单两端均未覆盖，请升级手机 ARMusic 后重试"
                    .to_string(),
            );
        }
    }

    Ok(ExecuteAdbSyncResult {
        preview: preview.clone(),
        uploaded_to_phone,
        downloaded_to_desktop,
        phone_imported_histories,
        phone_duplicate_histories,
        phone_cleared_histories,
        desktop_history_sessions: canonical_history.sessions.len(),
        desktop_history_snapshot_id: canonical_history.snapshot_id,
        conflicts_left_untouched: preview.conflicts.len().saturating_sub(resolved_conflicts),
        warnings,
        phone_history_backup_path,
        wishlist_synced,
        wishlist_categories,
        wishlist_items,
        wishlist_snapshot_id,
        wishlist_categories_added_to_desktop,
        wishlist_items_added_to_desktop,
        wishlist_categories_added_to_phone,
        wishlist_items_added_to_phone,
        playlists_synced,
        playlist_count,
        playlist_track_count,
        playlists_snapshot_id,
        playlists_added_to_desktop,
        playlist_tracks_added_to_desktop,
        playlists_added_to_phone,
        playlist_tracks_added_to_phone,
        playlists_deleted_from_desktop,
        playlists_deleted_from_phone,
    })
}

fn build_preview(state: &AppInner, snapshot: &PhoneSnapshot) -> Result<AdbSyncPreview, String> {
    let all_desktop = state.tracks.lock().expect("tracks lock").clone();
    validate_unique_track_identities(&all_desktop)?;
    let desktop_ignored_sync_track_count = all_desktop
        .iter()
        .filter(|track| !track_in_sync_scope(track))
        .count();
    let desktop = all_desktop
        .into_iter()
        .filter(track_in_sync_scope)
        .collect::<Vec<_>>();
    let all_phone_tracks = snapshot
        .library
        .songs
        .iter()
        .map(AgentSong::as_track)
        .collect::<Vec<_>>();
    validate_unique_track_identities(&all_phone_tracks)
        .map_err(|error| format!("手机曲库存在重复音频，已停止同步：{error}"))?;
    let phone_ignored_sync_track_count = snapshot.library.ignored_songs.len()
        + all_phone_tracks
            .iter()
            .filter(|track| !track_in_sync_scope(track))
            .count();
    let phone_tracks = all_phone_tracks
        .into_iter()
        .filter(track_in_sync_scope)
        .collect::<Vec<_>>();
    let desktop_ids = identity_index(&desktop);
    let phone_ids = identity_index(&phone_tracks);

    let upload_to_phone = desktop
        .iter()
        .filter(|track| {
            !track_ids(track)
                .into_iter()
                .any(|id| phone_ids.contains_key(id))
        })
        .map(AdbTrackSummary::from)
        .collect();
    let download_to_desktop = phone_tracks
        .iter()
        .filter(|track| {
            !track_ids(track)
                .into_iter()
                .any(|id| desktop_ids.contains_key(id))
        })
        .map(AdbTrackSummary::from)
        .collect();
    let conflicts = desktop
        .iter()
        .filter_map(|local| {
            let remote = phone_tracks
                .iter()
                .find(|remote| tracks_share_identity(local, remote))?;
            if !track_conflicts(local, remote) {
                return None;
            }
            Some(AdbSyncConflict {
                sync_id: local.sync_id.clone(),
                title: local.title.clone(),
                desktop_revision_hash: local.revision_hash.clone(),
                phone_revision_hash: remote.revision_hash.clone(),
                desktop_modified_at: local.modified_at.clone(),
                phone_modified_at: remote.modified_at.clone(),
                recommended_resolution: recommend_conflict(local, remote),
                reason: "同一音频的文件字节或标签不同，已保留两端原文件等待选择".to_string(),
            })
        })
        .collect::<Vec<_>>();

    let desktop_wishlist = {
        let _wishlist_guard = state.wishlist_lock.lock().expect("wishlist lock");
        let root = history_root(state)?;
        wishlist::load(&root, &desktop_wishlist_device_id())?
    };
    let (
        wishlist_supported,
        phone_wishlist_category_count,
        phone_wishlist_item_count,
        wishlist_stats,
    ) = if let Some(phone_wishlist) = &snapshot.wishlist {
        let (_, stats) = wishlist::union_for_desktop(&desktop_wishlist, phone_wishlist)?;
        (
            true,
            phone_wishlist.categories.len(),
            wishlist::item_count(phone_wishlist),
            Some(stats),
        )
    } else {
        (false, 0, 0, None)
    };

    let desktop_playlists = {
        let _playlists_guard = state.playlists_lock.lock().expect("playlists lock");
        let root = history_root(state)?;
        playlists::load(&root, &desktop_playlists_device_id())?
    };
    let (playlists_supported, phone_playlist_count, phone_playlist_track_count, playlists_stats) =
        if let Some(phone_playlists) = &snapshot.playlists {
            let (_, stats) = playlists::union_for_desktop(&desktop_playlists, phone_playlists)?;
            (
                true,
                phone_playlists.playlists.len(),
                playlists::track_count(phone_playlists),
                Some(stats),
            )
        } else {
            (false, 0, 0, None)
        };

    Ok(AdbSyncPreview {
        device: snapshot.device.clone(),
        desktop_track_count: desktop.len(),
        phone_track_count: phone_tracks.len(),
        upload_to_phone,
        download_to_desktop,
        conflicts,
        phone_history_count: snapshot.history.sessions.len(),
        phone_history_snapshot_id: snapshot.history.snapshot_id.clone(),
        phone_raw_history_count: snapshot.history.raw_history_count,
        phone_raw_snapshot_id: snapshot.history.raw_snapshot_id.clone(),
        wishlist_supported,
        desktop_wishlist_category_count: desktop_wishlist.categories.len(),
        desktop_wishlist_item_count: wishlist::item_count(&desktop_wishlist),
        phone_wishlist_category_count,
        phone_wishlist_item_count,
        wishlist_categories_add_to_desktop: wishlist_stats
            .as_ref()
            .map_or(0, |stats| stats.categories_added_to_desktop),
        wishlist_items_add_to_desktop: wishlist_stats
            .as_ref()
            .map_or(0, |stats| stats.items_added_to_desktop),
        wishlist_categories_add_to_phone: wishlist_stats
            .as_ref()
            .map_or(0, |stats| stats.categories_added_to_phone),
        wishlist_items_add_to_phone: wishlist_stats
            .as_ref()
            .map_or(0, |stats| stats.items_added_to_phone),
        wishlist_snapshot_id_after_merge: wishlist_stats
            .map(|stats| stats.snapshot_id)
            .unwrap_or_default(),
        playlists_supported,
        desktop_playlist_count: desktop_playlists.playlists.len(),
        desktop_playlist_track_count: playlists::track_count(&desktop_playlists),
        phone_playlist_count,
        phone_playlist_track_count,
        playlists_add_to_desktop: playlists_stats
            .as_ref()
            .map_or(0, |stats| stats.playlists_added_to_desktop),
        playlist_tracks_add_to_desktop: playlists_stats
            .as_ref()
            .map_or(0, |stats| stats.tracks_added_to_desktop),
        playlists_add_to_phone: playlists_stats
            .as_ref()
            .map_or(0, |stats| stats.playlists_added_to_phone),
        playlist_tracks_add_to_phone: playlists_stats
            .as_ref()
            .map_or(0, |stats| stats.tracks_added_to_phone),
        playlists_delete_from_desktop: playlists_stats
            .as_ref()
            .map_or(0, |stats| stats.playlists_deleted_from_desktop),
        playlists_delete_from_phone: playlists_stats
            .as_ref()
            .map_or(0, |stats| stats.playlists_deleted_from_phone),
        playlists_snapshot_id_after_merge: playlists_stats
            .map(|stats| stats.snapshot_id)
            .unwrap_or_default(),
        desktop_ignored_sync_track_count,
        phone_ignored_sync_track_count,
        sync_notice: "双向歌曲同步暂只包含不少于 15 秒的 MP3；其他格式仍可在本机播放".to_string(),
    })
}

fn upload_missing_to_phone(
    state: &AppInner,
    snapshot: &PhoneSnapshot,
    preview: &AdbSyncPreview,
) -> Result<(usize, Vec<String>), String> {
    let _file_guard = state.file_mutation_lock.lock().expect("file mutation lock");
    refresh_library_locked(state)?;
    let desktop = state.tracks.lock().expect("tracks lock").clone();
    let wanted = preview
        .upload_to_phone
        .iter()
        .map(|item| item.sync_id.as_str())
        .collect::<HashSet<_>>();
    let mut names = snapshot
        .library
        .songs
        .iter()
        .map(|song| {
            song.relative_path
                .rsplit(['/', '\\'])
                .next()
                .unwrap_or("")
                .to_lowercase()
        })
        .collect::<HashSet<_>>();
    let mut committed_ids = Vec::new();
    let mut tracks_to_verify = Vec::new();
    let mut warnings = Vec::new();
    for track in desktop
        .iter()
        .filter(|track| wanted.contains(track.sync_id.as_str()))
    {
        let path = track
            .file_path
            .as_ref()
            .ok_or_else(|| format!("{} 没有本地文件", track.title))?;
        let name = unique_phone_name(&track.relative_path, &mut names);
        let source_identity = crate::sync_identity::create_audio_identity_uncached(path)?;
        if track
            .revision_hash
            .as_ref()
            .map(|expected| expected != &source_identity.revision_hash)
            .unwrap_or(true)
        {
            return Err(format!("{} 在预览后发生变化，请重新预览", track.title));
        }
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|error| error.to_string())?
            .as_nanos();
        let remote_temp = format!("{PHONE_AGENT_DIR}/incoming-{nonce}.mp3");
        adb_push(&snapshot.device.serial, path, &remote_temp)?;
        let verify = temp_session_dir(&snapshot.device.serial)?.join(format!(
            "new-phone-{}",
            safe_file_name(&track.relative_path, "track.audio")
        ));
        adb_pull(&snapshot.device.serial, &remote_temp, &verify)?;
        let remote_identity = crate::sync_identity::create_audio_identity_uncached(&verify)?;
        if remote_identity.stable_id != source_identity.stable_id
            || remote_identity.revision_hash != source_identity.revision_hash
        {
            let _ = adb_shell_script(
                &snapshot.device.serial,
                &format!("rm -f {}", shell_quote(&remote_temp)),
            );
            return Err(format!(
                "{} 传到手机临时文件后校验失败，未发布",
                track.title
            ));
        }

        let revision_hash = track
            .revision_hash
            .as_ref()
            .ok_or_else(|| format!("{} 缺少完整文件校验值", track.title))?;
        let request_payload = serde_json::json!({
            "schema": "armusic-track-commit-v1",
            "temporaryPath": remote_temp,
            "relativePath": name,
            "syncId": track.sync_id,
            "legacySyncIds": track.legacy_sync_ids,
            "revisionHash": revision_hash,
            "sizeBytes": track.size_bytes,
            "durationSeconds": track.duration_seconds,
            "title": track.title,
            "artist": track.artist,
            "album": track.album,
        });
        let local_request =
            temp_session_dir(&snapshot.device.serial)?.join(format!("track-commit-{nonce}.json"));
        fs::write(
            &local_request,
            serde_json::to_vec_pretty(&request_payload).map_err(|error| error.to_string())?,
        )
        .map_err(|error| error.to_string())?;
        let remote_request = format!("{PHONE_AGENT_DIR}/track-commit-{nonce}.json");
        adb_push(&snapshot.device.serial, &local_request, &remote_request)?;
        let result = run_agent_command(
            &snapshot.device.serial,
            "commit_track",
            Some(&remote_request),
        )?;
        if result.committed_sync_id != track.sync_id {
            return Err(format!("{} 手机安全提交回执与请求歌曲不一致", track.title));
        }
        if result.already_present {
            if result.committed_songs != 0 {
                return Err(format!("{} 手机安全提交回执自相矛盾", track.title));
            }
            warnings.push(format!(
                "{} 已在同步期间通过其他方式加入手机，本次没有再复制",
                track.title
            ));
        } else if result.committed_songs == 1 {
            committed_ids.push(track.sync_id.clone());
        } else {
            return Err(format!("{} 没有得到手机安全发布确认", track.title));
        }
        tracks_to_verify.push(track.clone());
    }

    if tracks_to_verify.is_empty() {
        return Ok((0, warnings));
    }
    for track in &tracks_to_verify {
        verify_phone_track(&snapshot.device, track)?;
    }
    Ok((committed_ids.len(), warnings))
}

fn download_missing_to_desktop(
    state: &AppInner,
    snapshot: &PhoneSnapshot,
    preview: &AdbSyncPreview,
) -> Result<usize, String> {
    let wanted = preview
        .download_to_desktop
        .iter()
        .map(|item| item.sync_id.as_str())
        .collect::<HashSet<_>>();
    let staging = temp_session_dir(&snapshot.device.serial)?.join("phone-tracks");
    fs::create_dir_all(&staging).map_err(|error| error.to_string())?;
    let mut pending = Vec::new();

    for song in snapshot
        .library
        .songs
        .iter()
        .filter(|song| wanted.contains(song.sync_id.as_str()))
    {
        if song.file_path.trim().is_empty() {
            return Err(format!("手机没有返回 {} 的文件路径", song.title));
        }
        let file_name = safe_file_name(&song.relative_path, &song.sync_id);
        let local = staging.join(file_name);
        adb_pull(&snapshot.device.serial, &song.file_path, &local)?;
        let identity = crate::sync_identity::create_audio_identity_uncached(&local)?;
        if !song
            .all_ids()
            .any(|id| id == identity.stable_id || id == identity.legacy_id)
            || song
                .revision_hash
                .as_ref()
                .map(|expected| expected != &identity.revision_hash)
                .unwrap_or(true)
        {
            let _ = fs::remove_file(&local);
            return Err(format!("{} 传输后身份校验失败，未加入电脑曲库", song.title));
        }
        pending.push((song, local));
    }

    if pending.is_empty() {
        return Ok(0);
    }

    // Pulling can be slow, so only lock desktop writes for the fail-closed
    // rescan, second duplicate check, uncached staged hash and publish.
    let _file_guard = state.file_mutation_lock.lock().expect("file mutation lock");
    let root = refresh_library_locked(state)?;
    let mut existing_tracks = state.tracks.lock().expect("tracks lock").clone();
    let before_snapshot = library_revision_snapshot(&existing_tracks);
    let mut imported_tracks = Vec::new();
    for (song, local) in pending {
        let remote_track = song.as_track();
        if existing_tracks
            .iter()
            .any(|track| tracks_share_identity(track, &remote_track))
        {
            let _ = fs::remove_file(&local);
            continue;
        }
        let mut input = File::open(&local).map_err(|error| error.to_string())?;
        let imported_track =
            import_track_from_reader(&root, &remote_track, &existing_tracks, false, &mut input);
        let _ = fs::remove_file(&local);
        let imported_track = match imported_track {
            Ok(track) => track,
            Err(error) => {
                rollback_imported_batch(state, &root, &imported_tracks)
                    .map_err(|rollback| format!("{error}；撤回本批已发布文件时失败：{rollback}"))?;
                return Err(error);
            }
        };
        existing_tracks.push(imported_track.clone());
        imported_tracks.push(imported_track);
    }

    let scan = match scan_music_folder_uncached(&root) {
        Ok(scan) => scan,
        Err(error) => {
            rollback_imported_batch(state, &root, &imported_tracks)
                .map_err(|rollback| format!("{error}；撤回本批已发布文件时失败：{rollback}"))?;
            return Err(format!(
                "手机歌曲发布后曲库复核失败，已撤回本批未变化的新文件：{error}"
            ));
        }
    };
    let imported_paths = imported_tracks
        .iter()
        .map(|track| track.relative_path.as_str())
        .collect::<HashSet<_>>();
    let remaining = scan
        .tracks
        .iter()
        .filter(|track| !imported_paths.contains(track.relative_path.as_str()))
        .cloned()
        .collect::<Vec<_>>();
    if library_revision_snapshot(&remaining) != before_snapshot {
        rollback_imported_batch(state, &root, &imported_tracks)?;
        return Err(
            "电脑曲库在批次发布期间被其他程序改动，已撤回本批未变化的新文件，请重新预览"
                .to_string(),
        );
    }
    *state.tracks.lock().expect("tracks lock") = scan.tracks;
    Ok(imported_tracks.len())
}

fn library_revision_snapshot(tracks: &[Track]) -> Vec<(String, String, String, u64)> {
    let mut snapshot = tracks
        .iter()
        .map(|track| {
            (
                track.relative_path.clone(),
                track.sync_id.clone(),
                track.revision_hash.clone().unwrap_or_default(),
                track.size_bytes,
            )
        })
        .collect::<Vec<_>>();
    snapshot.sort();
    snapshot
}

fn rollback_imported_batch(
    state: &AppInner,
    root: &Path,
    imported_tracks: &[Track],
) -> Result<(), String> {
    let mut preserved = Vec::new();
    for track in imported_tracks.iter().rev() {
        match remove_imported_track_if_unchanged(track) {
            Ok(true) => {}
            Ok(false) | Err(_) => preserved.push(track.relative_path.clone()),
        }
    }
    let scan = scan_music_folder_uncached(root)?;
    *state.tracks.lock().expect("tracks lock") = scan.tracks;
    if preserved.is_empty() {
        Ok(())
    } else {
        Err(format!(
            "这些本批文件随后发生变化，程序没有删除：{}",
            preserved.join("；")
        ))
    }
}

fn resolve_conflicts(
    state: &AppInner,
    snapshot: &PhoneSnapshot,
    preview: &AdbSyncPreview,
    request: &ExecuteAdbSyncRequest,
) -> Result<(usize, Vec<String>), String> {
    if preview.conflicts.is_empty() {
        return Ok((0, Vec::new()));
    }
    let _file_guard = state.file_mutation_lock.lock().expect("file mutation lock");
    let root = refresh_library_locked(state)?;
    let desktop_tracks = state.tracks.lock().expect("tracks lock").clone();
    let session_dir = temp_session_dir(&snapshot.device.serial)?.join("conflicts");
    fs::create_dir_all(&session_dir).map_err(|error| error.to_string())?;
    let mut resolved = 0;
    let mut changed_desktop = false;
    let mut warnings = Vec::new();

    let (phone_replaced, phone_warnings) =
        replace_phone_conflicts_batch(snapshot, preview, request, &desktop_tracks, &session_dir)?;
    resolved += phone_replaced;
    warnings.extend(phone_warnings);

    for conflict in &preview.conflicts {
        let resolution = requested_conflict_resolution(request, conflict);
        if resolution == ConflictResolution::Skip {
            continue;
        }
        let local = desktop_tracks
            .iter()
            .find(|track| track.sync_id == conflict.sync_id)
            .ok_or_else(|| format!("电脑曲库里没有找到冲突歌曲 {}", conflict.title))?;
        let phone = snapshot
            .library
            .songs
            .iter()
            .find(|song| tracks_share_identity(local, &song.as_track()))
            .ok_or_else(|| format!("手机曲库里没有找到冲突歌曲 {}", conflict.title))?;

        match resolution {
            ConflictResolution::DesktopToPhone => {
                // This direction was handled above as one force-stopped,
                // renameat2(RENAME_EXCHANGE) batch.
            }
            ConflictResolution::PhoneToDesktop => {
                let target = local
                    .file_path
                    .as_ref()
                    .ok_or_else(|| format!("{} 没有电脑文件", conflict.title))?
                    .canonicalize()
                    .map_err(|error| error.to_string())?;
                let current_desktop_identity =
                    crate::sync_identity::create_audio_identity_uncached(&target)?;
                if conflict
                    .desktop_revision_hash
                    .as_ref()
                    .map(|expected| expected != &current_desktop_identity.revision_hash)
                    .unwrap_or(true)
                {
                    warnings.push(format!(
                        "{} 电脑文件在预览后发生变化，已跳过",
                        conflict.title
                    ));
                    continue;
                }
                let pulled = session_dir.join(format!(
                    "phone-new-{}",
                    safe_file_name(&phone.relative_path, "track.audio")
                ));
                adb_pull(&snapshot.device.serial, &phone.file_path, &pulled)?;
                let identity = crate::sync_identity::create_audio_identity_uncached(&pulled)?;
                let phone_ids = phone.all_ids().collect::<HashSet<_>>();
                if !phone_ids.contains(identity.stable_id.as_str())
                    || phone
                        .revision_hash
                        .as_ref()
                        .map(|hash| hash != &identity.revision_hash)
                        .unwrap_or(false)
                {
                    warnings.push(format!(
                        "{} 从手机拉取后校验失败，电脑原文件未动",
                        conflict.title
                    ));
                    continue;
                }
                if conflict
                    .phone_revision_hash
                    .as_ref()
                    .map(|expected| expected != &identity.revision_hash)
                    .unwrap_or(true)
                {
                    warnings.push(format!(
                        "{} 手机文件在预览后发生变化，已跳过",
                        conflict.title
                    ));
                    continue;
                }

                let backup = root
                    .join(".armusic-conflict-backups")
                    .join(format!(
                        "desktop-before-phone-{}",
                        SystemTime::now()
                            .duration_since(UNIX_EPOCH)
                            .map_err(|error| error.to_string())?
                            .as_millis()
                    ))
                    .join(&local.relative_path);
                let backup_parent = backup
                    .parent()
                    .ok_or_else(|| "电脑备份目录不安全".to_string())?;
                let safe_backup_parent = ensure_safe_directory(&root, backup_parent)?;
                let backup = safe_backup_parent.join(
                    backup
                        .file_name()
                        .ok_or_else(|| "电脑备份文件名不安全".to_string())?,
                );
                let extension = target
                    .extension()
                    .map(|value| value.to_string_lossy().to_string())
                    .unwrap_or_else(|| "audio".to_string());
                let staging = target.with_file_name(format!(
                    ".armusic-phone-replace-{}.{}",
                    SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .map_err(|error| error.to_string())?
                        .as_nanos(),
                    extension
                ));
                copy_file_synced(&pulled, &staging).map_err(|error| error.to_string())?;
                let staged_identity =
                    crate::sync_identity::create_audio_identity_uncached(&staging)?;
                if staged_identity != identity {
                    let _ = fs::remove_file(&staging);
                    return Err(format!("{} 电脑替换临时文件校验失败", conflict.title));
                }
                let final_current = crate::sync_identity::create_audio_identity_uncached(&target)?;
                if final_current != current_desktop_identity {
                    let _ = fs::remove_file(&staging);
                    warnings.push(format!(
                        "{} 电脑文件在原子替换前再次变化，已跳过",
                        conflict.title
                    ));
                    continue;
                }
                let replace_result = atomic_replace_with_backup(&staging, &target, &backup);
                let final_identity = match crate::sync_identity::create_audio_identity_uncached(
                    &target,
                ) {
                    Ok(identity) => identity,
                    Err(error) => {
                        return Err(format!(
                            "{} 原子替换后无法确认电脑目标状态，程序没有盲目回滚；备份在 {}：{error}",
                            conflict.title,
                            backup.display()
                        ));
                    }
                };
                if final_identity != identity {
                    if final_identity == current_desktop_identity {
                        warnings.push(format!(
                            "{} 原子替换没有发生，电脑原文件保持不变：{}",
                            conflict.title,
                            replace_result
                                .err()
                                .unwrap_or_else(|| "目标仍是旧版本".to_string())
                        ));
                        continue;
                    }
                    return Err(format!(
                        "{} 原子替换后目标又发生变化，程序没有覆盖该变化；交换瞬间备份路径为 {}",
                        conflict.title,
                        backup.display()
                    ));
                }
                let captured_backup =
                    crate::sync_identity::create_audio_identity_uncached(&backup).map_err(
                        |error| {
                            format!(
                                "{} 替换已完成，但无法验证 ReplaceFileW 捕获的旧目标；当前文件未回滚，备份在 {}：{error}",
                                conflict.title,
                                backup.display()
                            )
                        },
                    )?;
                if captured_backup.stable_id != current_desktop_identity.stable_id {
                    return Err(format!(
                        "{} 替换已完成，但交换瞬间备份的音频身份异常；当前文件未回滚，备份在 {}",
                        conflict.title,
                        backup.display()
                    ));
                }
                if captured_backup.revision_hash != current_desktop_identity.revision_hash {
                    warnings.push(format!(
                        "{} 在原子替换瞬间还有外部编辑；较新的旧版本已完整保存在 {}",
                        conflict.title,
                        backup.display()
                    ));
                }
                changed_desktop = true;
                resolved += 1;
            }
            ConflictResolution::Skip => {}
        }
    }

    if changed_desktop {
        let scan = scan_music_folder_uncached(&root)?;
        *state.tracks.lock().expect("tracks lock") = scan.tracks;
    }
    Ok((resolved, warnings))
}

fn requested_conflict_resolution(
    request: &ExecuteAdbSyncRequest,
    conflict: &AdbSyncConflict,
) -> ConflictResolution {
    request
        .conflict_resolutions
        .get(&conflict.sync_id)
        .copied()
        .or_else(|| {
            request
                .apply_newer_conflicts
                .then_some(conflict.recommended_resolution)
                .flatten()
        })
        .unwrap_or(ConflictResolution::Skip)
}

fn replace_phone_conflicts_batch(
    snapshot: &PhoneSnapshot,
    preview: &AdbSyncPreview,
    request: &ExecuteAdbSyncRequest,
    desktop_tracks: &[Track],
    session_dir: &Path,
) -> Result<(usize, Vec<String>), String> {
    let selected = preview
        .conflicts
        .iter()
        .filter(|conflict| {
            requested_conflict_resolution(request, conflict) == ConflictResolution::DesktopToPhone
        })
        .collect::<Vec<_>>();
    if selected.is_empty() {
        return Ok((0, Vec::new()));
    }

    deploy_rename_exchange_helper(&snapshot.device.serial)?;
    let batch_id = format!("usb-replace-{}", epoch_millis()?);
    let mut prepared = Vec::new();
    let mut warnings = Vec::new();

    for (index, conflict) in selected.into_iter().enumerate() {
        let local = desktop_tracks
            .iter()
            .find(|track| track.sync_id == conflict.sync_id)
            .ok_or_else(|| format!("电脑曲库里没有找到冲突歌曲 {}", conflict.title))?;
        let phone = snapshot
            .library
            .songs
            .iter()
            .find(|song| tracks_share_identity(local, &song.as_track()))
            .ok_or_else(|| format!("手机曲库里没有找到冲突歌曲 {}", conflict.title))?;
        let local_path = local
            .file_path
            .as_ref()
            .ok_or_else(|| format!("{} 没有电脑文件", conflict.title))?;
        if phone.file_path.trim().is_empty() {
            return Err(format!("{} 没有手机文件路径", conflict.title));
        }

        let new_identity = crate::sync_identity::create_audio_identity_uncached(local_path)?;
        if conflict
            .desktop_revision_hash
            .as_ref()
            .map(|expected| expected != &new_identity.revision_hash)
            .unwrap_or(true)
        {
            warnings.push(format!(
                "{} 电脑文件在预览后发生变化，已跳过",
                conflict.title
            ));
            continue;
        }

        let before = session_dir.join(format!(
            "{batch_id}-before-{index}-{}",
            safe_file_name(&phone.relative_path, "track.audio")
        ));
        adb_pull(&snapshot.device.serial, &phone.file_path, &before)?;
        let old_identity = crate::sync_identity::create_audio_identity_uncached(&before)?;
        if conflict
            .phone_revision_hash
            .as_ref()
            .map(|expected| expected != &old_identity.revision_hash)
            .unwrap_or(true)
        {
            warnings.push(format!(
                "{} 手机文件在预览后发生变化，已跳过",
                conflict.title
            ));
            continue;
        }

        let staging = remote_sibling(
            &phone.file_path,
            &format!(".armusic-exchange-{batch_id}-{index}"),
        );
        adb_push(&snapshot.device.serial, local_path, &staging)?;
        let staged_copy = session_dir.join(format!(
            "{batch_id}-staged-{index}-{}",
            safe_file_name(&phone.relative_path, "track.audio")
        ));
        adb_pull(&snapshot.device.serial, &staging, &staged_copy)?;
        let staged_identity = crate::sync_identity::create_audio_identity_uncached(&staged_copy)?;
        if !same_file_identity(&staged_identity, &new_identity) {
            return Err(format!(
                "{} 手机隐藏 staging 校验失败；手机原文件未改动",
                conflict.title
            ));
        }
        prepared.push(PreparedPhoneReplacement {
            title: conflict.title.clone(),
            desired_track: local.clone(),
            target: phone.file_path.clone(),
            staging,
            old_identity,
            new_identity,
        });
    }

    if prepared.is_empty() {
        return Ok((0, warnings));
    }
    write_phone_replace_recovery(
        &snapshot.device.serial,
        session_dir,
        &batch_id,
        "prepared",
        &prepared,
        0,
    )?;

    let lease = prepare_phone_replace_with_lease(&snapshot.device.serial)?;
    if let Err(error) = force_stop_phone_app(&snapshot.device.serial) {
        let _ = phone_lease_command(
            &snapshot.device.serial,
            "cancel_usb_file_replace_lease",
            &lease.lease_token,
        );
        return Err(error);
    }

    // The decisive CAS happens only after force-stop. Every target is pulled
    // again and compared byte-for-byte with the previewed phone revision.
    for (index, item) in prepared.iter().enumerate() {
        let identity = match pull_phone_identity(
            &snapshot.device.serial,
            &item.target,
            &session_dir.join(format!("{batch_id}-cas-{index}.audio")),
        ) {
            Ok(identity) => identity,
            Err(error) => {
                let _ = restart_phone_app(&snapshot.device.serial);
                let _ = write_phone_replace_recovery(
                    &snapshot.device.serial,
                    session_dir,
                    &batch_id,
                    "cas-read-failed",
                    &prepared,
                    0,
                );
                return Err(format!(
                    "{} 在手机停止后无法完成 fresh CAS；没有交换用户文件：{error}",
                    item.title
                ));
            }
        };
        if !same_file_identity(&identity, &item.old_identity) {
            let _ = restart_phone_app(&snapshot.device.serial);
            write_phone_replace_recovery(
                &snapshot.device.serial,
                session_dir,
                &batch_id,
                "cas-rejected",
                &prepared,
                0,
            )?;
            return Err(format!(
                "{} 在手机停止后仍与预览版本不一致；没有交换任何用户文件",
                item.title
            ));
        }
    }
    if let Err(error) = write_phone_replace_recovery(
        &snapshot.device.serial,
        session_dir,
        &batch_id,
        "cas-confirmed",
        &prepared,
        0,
    ) {
        let _ = restart_phone_app(&snapshot.device.serial);
        return Err(format!(
            "手机恢复记录写入失败；没有交换用户文件，已重新打开 ARMusic：{error}"
        ));
    }

    let mut applied = Vec::new();
    for (index, item) in prepared.iter().enumerate() {
        let exchange_result =
            exchange_phone_files(&snapshot.device.serial, &item.target, &item.staging);
        let pair = pull_phone_pair(&snapshot.device.serial, session_dir, &batch_id, index, item);
        let pair = match pair {
            Ok(pair) => pair,
            Err(error) => {
                let rollback = rollback_phone_exchange_batch(
                    &snapshot.device.serial,
                    session_dir,
                    &batch_id,
                    &prepared,
                    &applied,
                );
                let _ = restart_phone_app(&snapshot.device.serial);
                return Err(format!(
                    "{} 原子交换后无法读取两端状态：{error}；此前批次回滚结果：{}",
                    item.title,
                    rollback.err().unwrap_or_else(|| "已验证恢复".to_string())
                ));
            }
        };
        let exchanged = same_file_identity(&pair.0, &item.new_identity)
            && same_file_identity(&pair.1, &item.old_identity);
        if exchange_result.is_ok() && exchanged {
            applied.push(index);
            if let Err(error) = write_phone_replace_recovery(
                &snapshot.device.serial,
                session_dir,
                &batch_id,
                "exchanging",
                &prepared,
                applied.len(),
            ) {
                let rollback = rollback_phone_exchange_batch(
                    &snapshot.device.serial,
                    session_dir,
                    &batch_id,
                    &prepared,
                    &applied,
                );
                let _ = restart_phone_app(&snapshot.device.serial);
                return Err(format!(
                    "手机恢复记录写入失败，已停止继续交换；回滚结果：{}；日志错误：{error}",
                    rollback.err().unwrap_or_else(|| "已验证恢复".to_string())
                ));
            }
            continue;
        }

        // A non-zero helper result can mean that renameat2 succeeded but the
        // post-exchange fsync failed. Only the observed pair decides whether a
        // compensating exchange is safe.
        let current_was_exchanged = exchanged;
        if current_was_exchanged {
            let _ = exchange_phone_files(&snapshot.device.serial, &item.target, &item.staging);
        }
        let current_restored = pull_phone_pair(
            &snapshot.device.serial,
            session_dir,
            &format!("{batch_id}-current-rollback"),
            index,
            item,
        )
        .map(|pair| {
            same_file_identity(&pair.0, &item.old_identity)
                && same_file_identity(&pair.1, &item.new_identity)
        })
        .unwrap_or(false);
        let previous_rollback = rollback_phone_exchange_batch(
            &snapshot.device.serial,
            session_dir,
            &batch_id,
            &prepared,
            &applied,
        );
        let _ = restart_phone_app(&snapshot.device.serial);
        write_phone_replace_recovery(
            &snapshot.device.serial,
            session_dir,
            &batch_id,
            "exchange-failed",
            &prepared,
            0,
        )?;
        if !current_restored || previous_rollback.is_err() {
            return Err(format!(
                "{} 原子交换状态异常；程序只在可证明 new/old 配对时回滚。target 与 staging 均已保留，请按恢复记录人工核对",
                item.title
            ));
        }
        return Err(format!(
            "{} 的文件系统拒绝或未完整确认 RENAME_EXCHANGE；整批已验证恢复，没有退回两步移动",
            item.title
        ));
    }

    for item in &prepared {
        let _ = scan_phone_file(&snapshot.device.serial, &item.target);
    }
    let verification = prepared
        .iter()
        .try_for_each(|item| verify_phone_track(&snapshot.device, &item.desired_track).map(|_| ()));
    if let Err(error) = verification {
        // verify_track restarts the process. Obtain a new pause/flush lease and
        // stop it again before attempting the same identity-guarded exchange.
        let rollback_stop =
            prepare_phone_replace_with_lease(&snapshot.device.serial).and_then(|lease| {
                force_stop_phone_app(&snapshot.device.serial).inspect_err(|_| {
                    let _ = phone_lease_command(
                        &snapshot.device.serial,
                        "cancel_usb_file_replace_lease",
                        &lease.lease_token,
                    );
                })
            });
        if rollback_stop.is_ok() {
            let rollback = rollback_phone_exchange_batch(
                &snapshot.device.serial,
                session_dir,
                &batch_id,
                &prepared,
                &applied,
            );
            let _ = restart_phone_app(&snapshot.device.serial);
            write_phone_replace_recovery(
                &snapshot.device.serial,
                session_dir,
                &batch_id,
                "verification-rolled-back",
                &prepared,
                0,
            )?;
            rollback?;
            return Err(format!(
                "手机 fresh exactly-one 验证失败，整批已原子恢复：{error}"
            ));
        }
        write_phone_replace_recovery(
            &snapshot.device.serial,
            session_dir,
            &batch_id,
            "verification-needs-attention",
            &prepared,
            applied.len(),
        )?;
        return Err(format!(
            "手机 fresh exactly-one 验证失败，且无法安全再次停止 App；程序没有盲目回滚。旧文件仍在 staging：{error}"
        ));
    }

    write_phone_replace_recovery(
        &snapshot.device.serial,
        session_dir,
        &batch_id,
        "verified",
        &prepared,
        applied.len(),
    )?;
    match restart_phone_app(&snapshot.device.serial) {
        Ok(()) => warnings.push(format!(
            "同步冲突时手机 ARMusic 已短暂关闭并重新打开；{} 个旧文件以隐藏 staging 形式保留，{PHONE_AGENT_DIR} 下以 {batch_id} 开头的不可变 JSON 是恢复记录",
            prepared.len()
        )),
        Err(error) => warnings.push(format!(
            "冲突文件已完成原子交换与 fresh 验证，但手机 ARMusic 没有自动打开，请手动打开；旧文件与恢复记录均已保留：{error}"
        )),
    }
    Ok((prepared.len(), warnings))
}

fn prepare_phone_replace_with_lease(serial: &str) -> Result<AgentResult, String> {
    let prepared = run_agent_command(serial, "prepare_usb_file_replace", None)?;
    if prepared.lease_token.trim().is_empty() || prepared.lease_expires_at == 0 {
        return Err(
            "手机文件替换准备回执缺少 leaseToken/leaseExpiresAt，程序没有停止 App".to_string(),
        );
    }
    for _ in 0..2 {
        let started = Instant::now();
        let verified = phone_lease_command(
            serial,
            "verify_usb_file_replace_lease",
            &prepared.lease_token,
        )?;
        if verified.lease_token != prepared.lease_token || verified.lease_expires_at == 0 {
            return Err("手机文件替换租约复核回执不匹配，程序没有停止 App".to_string());
        }
        // The phone renews for 120 seconds using its monotonic clock. A local
        // round trip under 30 seconds guarantees ample remaining lease without
        // comparing the two devices' wall clocks.
        if started.elapsed() <= Duration::from_secs(30) {
            return Ok(verified);
        }
    }
    let _ = phone_lease_command(
        serial,
        "cancel_usb_file_replace_lease",
        &prepared.lease_token,
    );
    Err("手机租约复核往返连续超过 30 秒，程序没有停止 App".to_string())
}

fn phone_lease_command(
    serial: &str,
    command: &str,
    lease_token: &str,
) -> Result<AgentResult, String> {
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_nanos();
    let payload = serde_json::json!({
        "schema": "armusic-usb-replace-lease-v1",
        "leaseToken": lease_token,
    });
    let local = temp_session_dir(serial)?.join(format!("{command}-{nonce}.json"));
    fs::write(
        &local,
        serde_json::to_vec_pretty(&payload).map_err(|error| error.to_string())?,
    )
    .map_err(|error| error.to_string())?;
    let remote = format!("{PHONE_AGENT_DIR}/{command}-{nonce}.json");
    adb_push(serial, &local, &remote)?;
    run_agent_command(serial, command, Some(&remote))
}

fn same_file_identity(
    left: &crate::sync_identity::AudioIdentity,
    right: &crate::sync_identity::AudioIdentity,
) -> bool {
    left.stable_id == right.stable_id && left.revision_hash == right.revision_hash
}

fn pull_phone_identity(
    serial: &str,
    remote: &str,
    local: &Path,
) -> Result<crate::sync_identity::AudioIdentity, String> {
    adb_pull(serial, remote, local)?;
    crate::sync_identity::create_audio_identity_uncached(local)
}

fn pull_phone_pair(
    serial: &str,
    session_dir: &Path,
    batch_id: &str,
    index: usize,
    item: &PreparedPhoneReplacement,
) -> Result<
    (
        crate::sync_identity::AudioIdentity,
        crate::sync_identity::AudioIdentity,
    ),
    String,
> {
    let target = pull_phone_identity(
        serial,
        &item.target,
        &session_dir.join(format!("{batch_id}-pair-target-{index}.audio")),
    )?;
    let staging = pull_phone_identity(
        serial,
        &item.staging,
        &session_dir.join(format!("{batch_id}-pair-staging-{index}.audio")),
    )?;
    Ok((target, staging))
}

fn rollback_phone_exchange_batch(
    serial: &str,
    session_dir: &Path,
    batch_id: &str,
    prepared: &[PreparedPhoneReplacement],
    applied: &[usize],
) -> Result<(), String> {
    let mut unknown = Vec::new();
    for index in applied.iter().rev().copied() {
        let item = &prepared[index];
        let before = pull_phone_pair(
            serial,
            session_dir,
            &format!("{batch_id}-rollback-before"),
            index,
            item,
        );
        let Ok(before) = before else {
            unknown.push(item.title.clone());
            continue;
        };
        if same_file_identity(&before.0, &item.old_identity)
            && same_file_identity(&before.1, &item.new_identity)
        {
            continue;
        }
        if !same_file_identity(&before.0, &item.new_identity)
            || !same_file_identity(&before.1, &item.old_identity)
        {
            unknown.push(item.title.clone());
            continue;
        }

        let _ = exchange_phone_files(serial, &item.target, &item.staging);
        let restored = pull_phone_pair(
            serial,
            session_dir,
            &format!("{batch_id}-rollback-after"),
            index,
            item,
        )
        .map(|pair| {
            same_file_identity(&pair.0, &item.old_identity)
                && same_file_identity(&pair.1, &item.new_identity)
        })
        .unwrap_or(false);
        if !restored {
            unknown.push(item.title.clone());
        }
    }
    if unknown.is_empty() {
        Ok(())
    } else {
        Err(format!(
            "以下歌曲状态无法证明，target 与 staging 均已保留：{}",
            unknown.join("；")
        ))
    }
}

fn write_phone_replace_recovery(
    serial: &str,
    session_dir: &Path,
    batch_id: &str,
    phase: &str,
    prepared: &[PreparedPhoneReplacement],
    applied_count: usize,
) -> Result<(), String> {
    let payload = serde_json::json!({
        "schema": "armusic-usb-file-replace-recovery-v1",
        "batchId": batch_id,
        "phase": phase,
        "updatedAt": now_iso(),
        "appliedCount": applied_count,
        "items": prepared.iter().map(|item| serde_json::json!({
            "title": item.title,
            "targetPath": item.target,
            "stagingPath": item.staging,
            "oldStableId": item.old_identity.stable_id,
            "oldRevisionHash": item.old_identity.revision_hash,
            "newStableId": item.new_identity.stable_id,
            "newRevisionHash": item.new_identity.revision_hash,
        })).collect::<Vec<_>>(),
    });
    let bytes = serde_json::to_vec_pretty(&payload).map_err(|error| error.to_string())?;
    let safe_phase = phase
        .chars()
        .map(|value| {
            if value.is_ascii_alphanumeric() || value == '-' {
                value
            } else {
                '_'
            }
        })
        .collect::<String>();
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_nanos();
    let file_name = format!("{batch_id}-{safe_phase}-{applied_count}-{nonce}.json");
    let local = session_dir.join(&file_name);
    let mut output = File::create(&local).map_err(|error| error.to_string())?;
    use std::io::Write as _;
    output
        .write_all(&bytes)
        .map_err(|error| error.to_string())?;
    output.sync_all().map_err(|error| error.to_string())?;
    drop(output);
    let local_verified = fs::read(&local).map_err(|error| error.to_string())?;
    serde_json::from_slice::<serde_json::Value>(&local_verified)
        .map_err(|error| format!("本地恢复记录复核失败：{error}"))?;
    if local_verified != bytes {
        return Err("本地恢复记录字节复核失败".to_string());
    }

    let remote = format!("{PHONE_AGENT_DIR}/{file_name}");
    adb_push(serial, &local, &remote)?;
    let pulled = session_dir.join(format!("verified-{file_name}"));
    adb_pull(serial, &remote, &pulled)?;
    let remote_verified = fs::read(&pulled).map_err(|error| error.to_string())?;
    serde_json::from_slice::<serde_json::Value>(&remote_verified)
        .map_err(|error| format!("手机恢复记录复核失败：{error}"))?;
    if remote_verified != bytes {
        return Err("手机恢复记录字节复核失败，上一份不可变记录仍保留".to_string());
    }
    Ok(())
}

fn recommend_conflict(left: &Track, right: &Track) -> Option<ConflictResolution> {
    let left_time = parse_modified_at(left.modified_at.as_deref()?)?;
    let right_time = parse_modified_at(right.modified_at.as_deref()?)?;
    match left_time.cmp(&right_time) {
        std::cmp::Ordering::Greater => Some(ConflictResolution::DesktopToPhone),
        std::cmp::Ordering::Less => Some(ConflictResolution::PhoneToDesktop),
        std::cmp::Ordering::Equal => None,
    }
}

fn parse_modified_at(value: &str) -> Option<i64> {
    let value = value.trim();
    if let Ok(numeric) = value.parse::<i64>() {
        return Some(if numeric < 100_000_000_000 {
            numeric.saturating_mul(1000)
        } else {
            numeric
        });
    }
    chrono::DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|value| value.timestamp_millis())
}

fn export_phone_snapshot(device: &AdbDevice) -> Result<PhoneSnapshot, String> {
    let library = export_phone_library(device)?;
    let history = export_phone_history(device)?;
    let wishlist = export_phone_wishlist(device)?;
    let playlists = export_phone_playlists(device)?;
    Ok(PhoneSnapshot {
        device: device.clone(),
        library,
        history,
        wishlist,
        playlists,
    })
}

fn export_phone_wishlist(device: &AdbDevice) -> Result<Option<WishlistPayload>, String> {
    if let Err(error) = run_agent_command(&device.serial, "export_wishlist", Some(PHONE_WISHLIST)) {
        if error.contains("Unknown command") || error.contains("未知") {
            return Ok(None);
        }
        return Err(error);
    }
    let path = temp_session_dir(&device.serial)?.join("armusic-wishlist.json");
    adb_pull(&device.serial, PHONE_WISHLIST, &path)?;
    let bytes = fs::read(path).map_err(|error| error.to_string())?;
    wishlist::parse_payload(&bytes, "android")
        .map(Some)
        .map_err(|error| format!("手机愿望单无法解析：{error}"))
}

fn sync_wishlist_with_phone(
    state: &AppInner,
    device: &AdbDevice,
    phone: &WishlistPayload,
) -> Result<(WishlistPayload, WishlistMergeStats), String> {
    let root = history_root(state)?;
    let (persisted, mut stats) = {
        let _wishlist_guard = state.wishlist_lock.lock().expect("wishlist lock");
        let desktop = wishlist::load(&root, &desktop_wishlist_device_id())?;
        let (merged, stats) = wishlist::union_for_desktop(&desktop, phone)?;
        let persisted = wishlist::save(&root, &merged)?;
        (persisted, stats)
    };

    // Desktop persistence comes first. If the phone import is interrupted, the complete phone
    // source snapshot remains both on the phone and in the verified desktop file.
    let local = temp_session_dir(&device.serial)?.join("wishlist-merged.json");
    fs::write(
        &local,
        serde_json::to_vec_pretty(&persisted).map_err(|error| error.to_string())?,
    )
    .map_err(|error| error.to_string())?;
    adb_push(&device.serial, &local, PHONE_WISHLIST_IMPORT)?;
    let receipt = run_agent_command(
        &device.serial,
        "import_wishlist",
        Some(PHONE_WISHLIST_IMPORT),
    )?;
    if (receipt.wishlist_categories == 0 && !persisted.categories.is_empty())
        || receipt.wishlist_items < wishlist::item_count(&persisted)
    {
        return Err(
            "手机愿望单导入回执数量不完整；电脑已保留完整并集，手机原数据未删除".to_string(),
        );
    }

    // Read back instead of trusting only a command receipt. A phone-side edit racing with import
    // is unioned into desktop as a second, non-destructive pass.
    let final_phone = export_phone_wishlist(device)?
        .ok_or_else(|| "手机在写入后不再支持愿望单导出；电脑已保留完整并集".to_string())?;
    let final_desktop = {
        let _wishlist_guard = state.wishlist_lock.lock().expect("wishlist lock");
        let current = wishlist::load(&root, &desktop_wishlist_device_id())?;
        let (merged, extra) = wishlist::union_for_desktop(&current, &final_phone)?;
        stats.categories_added_to_desktop = stats
            .categories_added_to_desktop
            .saturating_add(extra.categories_added_to_desktop);
        stats.items_added_to_desktop = stats
            .items_added_to_desktop
            .saturating_add(extra.items_added_to_desktop);
        let persisted = wishlist::save(&root, &merged)?;
        stats.total_categories = persisted.categories.len();
        stats.total_items = wishlist::item_count(&persisted);
        stats.snapshot_id.clone_from(&persisted.snapshot_id);
        persisted
    };
    if receipt.wishlist_snapshot_id.is_empty() {
        return Err("手机愿望单导入回执缺少快照；电脑已保存并回读手机最终数据".to_string());
    }
    Ok((final_desktop, stats))
}

fn export_phone_playlists(device: &AdbDevice) -> Result<Option<PlaylistsPayload>, String> {
    if let Err(error) = run_agent_command(&device.serial, "export_playlists", Some(PHONE_PLAYLISTS))
    {
        if error.contains("Unknown command") || error.contains("未知") {
            return Ok(None);
        }
        return Err(error);
    }
    let path = temp_session_dir(&device.serial)?.join("armusic-playlists.json");
    adb_pull(&device.serial, PHONE_PLAYLISTS, &path)?;
    let bytes = fs::read(path).map_err(|error| error.to_string())?;
    playlists::parse_payload(&bytes, "android")
        .map(Some)
        .map_err(|error| format!("手机歌单无法解析：{error}"))
}

fn sync_playlists_with_phone(
    state: &AppInner,
    device: &AdbDevice,
    phone: &PlaylistsPayload,
) -> Result<(PlaylistsPayload, PlaylistsMergeStats), String> {
    let root = history_root(state)?;
    let (persisted, mut stats) = {
        let _playlists_guard = state.playlists_lock.lock().expect("playlists lock");
        let desktop = playlists::load(&root, &desktop_playlists_device_id())?;
        let (merged, stats) = playlists::union_for_desktop(&desktop, phone)?;
        let persisted = playlists::save(&root, &merged)?;
        (persisted, stats)
    };

    // Persist the verified desktop union first. An interrupted phone import can therefore be
    // retried without losing the original phone snapshot or desktop-only additions.
    let local = temp_session_dir(&device.serial)?.join("playlists-merged.json");
    fs::write(
        &local,
        serde_json::to_vec_pretty(&persisted).map_err(|error| error.to_string())?,
    )
    .map_err(|error| error.to_string())?;
    adb_push(&device.serial, &local, PHONE_PLAYLISTS_IMPORT)?;
    let receipt = run_agent_command(
        &device.serial,
        "import_playlists",
        Some(PHONE_PLAYLISTS_IMPORT),
    )?;
    if (receipt.playlist_count == 0 && !persisted.playlists.is_empty())
        || receipt.playlist_items < playlists::track_count(&persisted)
        || receipt.playlist_snapshot_id.is_empty()
    {
        return Err(
            "手机歌单导入回执不完整；电脑已保留完整并集，手机原歌单未被自动删除".to_string(),
        );
    }

    // A phone edit may race with import. Re-export and union once more instead of trusting the
    // receipt alone; this second pass can only add data to the verified desktop file.
    let final_phone = export_phone_playlists(device)?
        .ok_or_else(|| "手机在写入后不再支持歌单导出；电脑已保留完整并集".to_string())?;
    let final_desktop = {
        let _playlists_guard = state.playlists_lock.lock().expect("playlists lock");
        let current = playlists::load(&root, &desktop_playlists_device_id())?;
        let (merged, extra) = playlists::union_for_desktop(&current, &final_phone)?;
        stats.playlists_added_to_desktop = stats
            .playlists_added_to_desktop
            .saturating_add(extra.playlists_added_to_desktop);
        stats.tracks_added_to_desktop = stats
            .tracks_added_to_desktop
            .saturating_add(extra.tracks_added_to_desktop);
        stats.playlists_deleted_from_desktop = stats
            .playlists_deleted_from_desktop
            .saturating_add(extra.playlists_deleted_from_desktop);
        stats.playlists_deleted_from_phone = stats
            .playlists_deleted_from_phone
            .saturating_add(extra.playlists_deleted_from_phone);
        let persisted = playlists::save(&root, &merged)?;
        stats.total_playlists = persisted.playlists.len();
        stats.total_tracks = playlists::track_count(&persisted);
        stats.snapshot_id.clone_from(&persisted.snapshot_id);
        persisted
    };
    Ok((final_desktop, stats))
}

fn export_phone_history(device: &AdbDevice) -> Result<HistorySyncPayload, String> {
    run_agent_command(&device.serial, "export_history", Some(PHONE_HISTORY))?;
    let temp = temp_session_dir(&device.serial)?;
    let history_path = temp.join("armusic-history.json");
    adb_pull(&device.serial, PHONE_HISTORY, &history_path)?;
    let history: HistorySyncPayload =
        serde_json::from_slice(&fs::read(&history_path).map_err(|error| error.to_string())?)
            .map_err(|error| format!("手机听歌记录无法解析：{error}"))?;
    if history.snapshot_id != listening_history::snapshot_id(&history.sessions) {
        return Err("手机听歌记录校验失败，本次不会同步或删除".to_string());
    }
    Ok(history)
}

fn export_phone_library(device: &AdbDevice) -> Result<AgentLibrary, String> {
    run_agent_command(&device.serial, "export_library", Some(PHONE_LIBRARY))?;
    let path = temp_session_dir(&device.serial)?.join("armusic-library.json");
    adb_pull(&device.serial, PHONE_LIBRARY, &path)?;
    serde_json::from_slice(&fs::read(path).map_err(|error| error.to_string())?)
        .map_err(|error| format!("手机曲库清单无法解析：{error}"))
}

fn verify_phone_track(device: &AdbDevice, track: &Track) -> Result<AgentResult, String> {
    let expected_revision = track
        .revision_hash
        .as_ref()
        .ok_or_else(|| format!("{} 缺少完整文件校验值", track.title))?;
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_nanos();
    let request = serde_json::json!({
        "schema": "armusic-track-verify-v1",
        "syncId": track.sync_id,
        "legacySyncIds": track.legacy_sync_ids,
        "expectedRevisionHash": expected_revision,
    });
    let local = temp_session_dir(&device.serial)?.join(format!("track-verify-{nonce}.json"));
    fs::write(
        &local,
        serde_json::to_vec_pretty(&request).map_err(|error| error.to_string())?,
    )
    .map_err(|error| error.to_string())?;
    let remote = format!("{PHONE_AGENT_DIR}/track-verify-{nonce}.json");
    adb_push(&device.serial, &local, &remote)?;
    let result = run_agent_command(&device.serial, "verify_track", Some(&remote))?;
    if result.verified_songs != 1
        || result.committed_sync_id != track.sync_id
        || result.current_revision_hash != *expected_revision
    {
        return Err(format!(
            "{} 手机 fresh exactly-one 复核回执不完整，程序没有自动删除文件",
            track.title
        ));
    }
    Ok(result)
}

fn run_agent_command(
    serial: &str,
    command: &str,
    path: Option<&str>,
) -> Result<AgentResult, String> {
    run_agent_command_with_id(serial, command, path, None)
}

fn run_agent_command_with_id(
    serial: &str,
    command: &str,
    path: Option<&str>,
    requested_command_id: Option<&str>,
) -> Result<AgentResult, String> {
    let command_id = requested_command_id
        .map(str::to_string)
        .map(Ok)
        .unwrap_or_else(|| new_command_id(serial, command))?;
    let remote_result = format!("{PHONE_AGENT_DIR}/armusic-agent-result-{command_id}.json");
    let removed = adb_output(Some(serial), &["shell", "rm", "-f", &remote_result])?;
    ensure_success(&removed, "清理手机端旧命令结果")?;
    let mut args = vec![
        "shell",
        "am",
        "broadcast",
        "--receiver-foreground",
        "-n",
        AGENT_RECEIVER,
        "-a",
        "com.armusic.AGENT_COMMAND",
        "--es",
        "command",
        command,
        "--es",
        "resultPath",
        &remote_result,
        "--es",
        "commandId",
        &command_id,
    ];
    if let Some(path) = path {
        args.extend(["--es", "path", path]);
    }
    let started = adb_output(Some(serial), &args)?;
    ensure_success(&started, "投递手机后台同步命令")?;
    let dispatch = String::from_utf8_lossy(&started.stdout);
    if !dispatch.contains("result=-1") {
        return Err(format!(
            "手机没有接收后台同步命令：{}",
            dispatch.trim().replace('\r', " ").replace('\n', " ")
        ));
    }

    // These commands may have to hash the complete phone library to resolve stable track
    // identities. A cold scan of a multi-gigabyte library can legitimately take longer than
    // three minutes even though the agent is still making progress.
    let timeout = if command == "export_library"
        || command == "export_history"
        || command == "import_history"
        || command == "import_bundle"
        || command == "import_works"
        || command == "import_groups"
        || command == "export_playlists"
        || command == "export_playlist"
        || command == "import_playlists"
        || command == "import_playlist"
        || command == "commit_track"
        || command == "verify_track"
    {
        Duration::from_secs(10 * 60)
    } else if command == "clear_history" {
        Duration::from_secs(2 * 60)
    } else {
        Duration::from_secs(3 * 60)
    };
    let deadline = Instant::now() + timeout;
    loop {
        let output = adb_output(Some(serial), &["shell", "cat", &remote_result])?;
        if output.status.success() && !output.stdout.is_empty() {
            if let Ok(result) = serde_json::from_slice::<AgentResult>(&output.stdout) {
                if result.command_id != command_id {
                    thread::sleep(Duration::from_millis(200));
                    continue;
                }
                if !result.ok {
                    return Err(format!("手机同步服务失败：{}", result.message));
                }
                return Ok(result);
            }
        }
        if Instant::now() >= deadline {
            if command == "clear_history" {
                return Err(
                    "等待手机清除结果超时，结果未知；请先检查手机备份与当前记录，切勿自动重试"
                        .to_string(),
                );
            }
            return Err(format!(
                "等待手机执行 {command} 超时；手机命令可能仍在后台运行，请检查结果后再重试"
            ));
        }
        thread::sleep(Duration::from_millis(500));
    }
}

fn adb_operation_lock() -> &'static Mutex<()> {
    ADB_OPERATION_LOCK.get_or_init(|| Mutex::new(()))
}

fn epoch_millis() -> Result<u64, String> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_millis() as u64)
        .map_err(|error| error.to_string())
}

fn new_command_id(serial: &str, command: &str) -> Result<String, String> {
    let mut digest = Sha256::new();
    digest.update(b"armusic-adb-command-v1\0");
    digest.update(serial.as_bytes());
    digest.update(b"\0");
    digest.update(command.as_bytes());
    digest.update(b"\0");
    digest.update(epoch_millis()?.to_string().as_bytes());
    digest.update(b"\0");
    digest.update(format!("{:?}", Instant::now()).as_bytes());
    Ok(format!("cmd-{:x}", digest.finalize())[..44].to_string())
}

fn choose_device(requested: Option<String>) -> Result<AdbDevice, String> {
    let devices = list_devices()?;
    if let Some(serial) = requested.filter(|value| !value.trim().is_empty()) {
        return devices
            .into_iter()
            .find(|device| device.serial == serial)
            .ok_or_else(|| "指定的手机没有连接或尚未允许 USB 调试".to_string());
    }
    match devices.as_slice() {
        [] => Err("没有找到已允许 USB 调试的 Android 手机".to_string()),
        [device] => Ok(device.clone()),
        _ => Err("检测到多台 Android 设备，请先在界面选择一台".to_string()),
    }
}

fn adb_push(serial: &str, local: &Path, remote: &str) -> Result<(), String> {
    let local_text = local.to_string_lossy();
    let output = adb_output(Some(serial), &["push", &local_text, remote])?;
    ensure_success(&output, "向手机传输文件")
}

fn adb_pull(serial: &str, remote: &str, local: &Path) -> Result<(), String> {
    if let Some(parent) = local.parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let local_text = local.to_string_lossy();
    let output = adb_output(Some(serial), &["pull", remote, &local_text])?;
    ensure_success(&output, "从手机读取文件")
}

fn deploy_rename_exchange_helper(serial: &str) -> Result<(), String> {
    let abi_output = adb_output(Some(serial), &["shell", "getprop", "ro.product.cpu.abi"])?;
    ensure_success(&abi_output, "读取手机处理器架构")?;
    let abi = String::from_utf8_lossy(&abi_output.stdout)
        .trim()
        .to_string();
    if abi != "arm64-v8a" {
        return Err(format!(
            "这台手机的架构是 {abi}，当前便携版只内置 arm64 原子交换工具；冲突文件保持两端不变"
        ));
    }

    let local = temp_session_dir(serial)?.join("armusic-rename-exchange-arm64");
    fs::write(&local, RENAME_EXCHANGE_ARM64).map_err(|error| error.to_string())?;
    adb_push(serial, &local, PHONE_RENAME_EXCHANGE)?;
    let chmod = adb_output(
        Some(serial),
        &["shell", "chmod", "700", PHONE_RENAME_EXCHANGE],
    )?;
    ensure_success(&chmod, "启用手机原子交换工具")?;

    let pulled = temp_session_dir(serial)?.join("armusic-rename-exchange-verified");
    adb_pull(serial, PHONE_RENAME_EXCHANGE, &pulled)?;
    let verified = fs::read(&pulled).map_err(|error| error.to_string())?;
    if verified != RENAME_EXCHANGE_ARM64 {
        return Err("手机原子交换工具传输校验失败，冲突文件保持两端不变".to_string());
    }
    Ok(())
}

fn exchange_phone_files(serial: &str, left: &str, right: &str) -> Result<(), String> {
    let left = hex_encode_utf8(left);
    let right = hex_encode_utf8(right);
    let output = adb_output(
        Some(serial),
        &["shell", PHONE_RENAME_EXCHANGE, &left, &right],
    )?;
    ensure_success(&output, "手机文件系统不支持安全原子交换")
}

fn hex_encode_utf8(value: &str) -> String {
    value
        .as_bytes()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn force_stop_phone_app(serial: &str) -> Result<(), String> {
    let output = adb_output_with_timeout(
        Some(serial),
        &["shell", "am", "force-stop", "com.armusic"],
        Duration::from_secs(20),
    )?;
    ensure_success(&output, "短暂关闭手机 ARMusic")?;
    let pid = adb_output_with_timeout(
        Some(serial),
        &[
            "shell",
            "if pidof com.armusic >/dev/null 2>&1; then echo RUNNING; else rc=$?; if [ \"$rc\" -eq 1 ]; then echo STOPPED; else echo ERROR:$rc; exit 2; fi; fi",
        ],
        Duration::from_secs(10),
    )?;
    ensure_success(&pid, "确认手机 ARMusic 已停止")?;
    match String::from_utf8_lossy(&pid.stdout).trim() {
        "STOPPED" => Ok(()),
        "RUNNING" => Err("手机 ARMusic 进程在 force-stop 后仍存在，程序没有交换文件".to_string()),
        other => Err(format!(
            "手机进程状态回执无法识别（{other}），程序没有交换文件"
        )),
    }
}

fn restart_phone_app(serial: &str) -> Result<(), String> {
    let output = adb_output(
        Some(serial),
        &[
            "shell",
            "monkey",
            "-p",
            "com.armusic",
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
        ],
    )?;
    ensure_success(&output, "重新打开手机 ARMusic")
}

fn scan_phone_file(serial: &str, path: &str) -> Result<(), String> {
    let uri = phone_file_uri(path);
    let output = adb_output(
        Some(serial),
        &[
            "shell",
            "am",
            "broadcast",
            "-a",
            "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d",
            &uri,
        ],
    )?;
    ensure_success(&output, "刷新手机媒体库")
}

fn phone_file_uri(path: &str) -> String {
    let encoded_path = path
        .split('/')
        .map(|segment| utf8_percent_encode(segment, NON_ALPHANUMERIC).to_string())
        .collect::<Vec<_>>()
        .join("/");
    format!("file://{encoded_path}")
}

fn adb_shell_script(serial: &str, script: &str) -> Result<(), String> {
    // `adb shell` joins client argv with spaces without escaping. Supplying the
    // complete script as its single remote argument preserves quoting exactly.
    let output = adb_output(Some(serial), &["shell", script])?;
    ensure_success(&output, "执行手机端原子文件操作")
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

fn remote_sibling(path: &str, prefix: &str) -> String {
    let (directory, name) = path.rsplit_once('/').unwrap_or(("", path));
    if directory.is_empty() {
        format!("{prefix}-{name}")
    } else {
        format!("{directory}/{prefix}-{name}")
    }
}

fn adb_output(serial: Option<&str>, args: &[&str]) -> Result<Output, String> {
    adb_output_with_timeout(serial, args, Duration::from_secs(10 * 60))
}

fn adb_output_with_timeout(
    serial: Option<&str>,
    args: &[&str],
    timeout: Duration,
) -> Result<Output, String> {
    let adb = adb_path();
    let mut command = Command::new(&adb);
    if let Some(serial) = serial {
        command.args(["-s", serial]);
    }
    command.args(args);
    command.stdout(Stdio::piped()).stderr(Stdio::piped());
    hide_console(&mut command);
    let mut child = command
        .spawn()
        .map_err(|error| format!("无法启动 ADB（{}）：{error}", adb.display()))?;
    match child
        .wait_timeout(timeout)
        .map_err(|error| format!("等待 ADB 失败：{error}"))?
    {
        Some(_) => child
            .wait_with_output()
            .map_err(|error| format!("读取 ADB 结果失败：{error}")),
        None => {
            let _ = child.kill();
            let _ = child.wait();
            Err(format!(
                "ADB 操作超过 {} 秒，已停止等待；程序不会假定手机端操作成功",
                timeout.as_secs()
            ))
        }
    }
}

fn adb_path() -> PathBuf {
    for key in ["ANDROID_SDK_ROOT", "ANDROID_HOME"] {
        if let Ok(root) = env::var(key) {
            let candidate = Path::new(&root).join("platform-tools").join(adb_name());
            if candidate.is_file() {
                return candidate;
            }
        }
    }
    if let Ok(local_app_data) = env::var("LOCALAPPDATA") {
        let candidate = Path::new(&local_app_data)
            .join("Android")
            .join("Sdk")
            .join("platform-tools")
            .join(adb_name());
        if candidate.is_file() {
            return candidate;
        }
    }
    PathBuf::from(adb_name())
}

#[cfg(windows)]
fn hide_console(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    command.creation_flags(0x0800_0000);
}

#[cfg(not(windows))]
fn hide_console(_command: &mut Command) {}

fn ensure_success(output: &Output, action: &str) -> Result<(), String> {
    if output.status.success() {
        return Ok(());
    }
    let detail = String::from_utf8_lossy(&output.stderr).trim().to_string();
    Err(format!("{action}失败：{detail}"))
}

fn adb_name() -> &'static str {
    if cfg!(windows) {
        "adb.exe"
    } else {
        "adb"
    }
}

fn temp_session_dir(serial: &str) -> Result<PathBuf, String> {
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_millis();
    let safe_serial = serial
        .chars()
        .map(|value| {
            if value.is_ascii_alphanumeric() {
                value
            } else {
                '_'
            }
        })
        .collect::<String>();
    let dir = env::temp_dir()
        .join("ARMusic")
        .join("adb-sync")
        .join(format!("{safe_serial}-{timestamp}"));
    fs::create_dir_all(&dir).map_err(|error| error.to_string())?;
    Ok(dir)
}

fn track_ids(track: &Track) -> Vec<&str> {
    std::iter::once(track.sync_id.as_str())
        .chain(track.legacy_sync_ids.iter().map(String::as_str))
        .collect()
}

fn track_conflicts(left: &Track, right: &Track) -> bool {
    match (&left.revision_hash, &right.revision_hash) {
        (Some(left), Some(right)) => left != right,
        _ => {
            left.title != right.title
                || left.artist != right.artist
                || left.album != right.album
                || safe_file_name(&left.relative_path, "")
                    != safe_file_name(&right.relative_path, "")
        }
    }
}

fn unique_phone_name(relative_path: &str, existing: &mut HashSet<String>) -> String {
    let original = safe_file_name(relative_path, "track.mp3");
    let stem = Path::new(&original)
        .file_stem()
        .map(|value| value.to_string_lossy().to_string())
        .unwrap_or_else(|| "track".to_string());
    let extension = Path::new(&original)
        .extension()
        .map(|value| format!(".{}", value.to_string_lossy()))
        .unwrap_or_default();
    for index in 0.. {
        let candidate = if index == 0 {
            original.clone()
        } else {
            format!("{stem} (来自电脑 {index}){extension}")
        };
        if existing.insert(candidate.to_lowercase()) {
            return candidate;
        }
    }
    unreachable!()
}

fn safe_file_name(relative_path: &str, fallback: &str) -> String {
    let raw = relative_path
        .rsplit(['/', '\\'])
        .find(|value| !value.trim().is_empty())
        .unwrap_or(fallback);
    let safe = raw
        .chars()
        .map(|value| match value {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            value if value.is_control() => '_',
            value => value,
        })
        .collect::<String>();
    if safe.trim().is_empty() {
        fallback.to_string()
    } else {
        safe
    }
}

impl AgentSong {
    fn all_ids(&self) -> impl Iterator<Item = &str> {
        std::iter::once(self.sync_id.as_str())
            .chain(self.legacy_sync_ids.iter().map(String::as_str))
    }

    fn as_track(&self) -> Track {
        Track {
            sync_id: self.sync_id.clone(),
            legacy_sync_ids: self.legacy_sync_ids.clone(),
            revision_hash: self.revision_hash.clone(),
            title: self.title.clone(),
            artist: self.artist.clone(),
            album: self.album.clone(),
            work: None,
            genre: None,
            duration_seconds: self.duration_ms / 1000,
            size_bytes: self.size_bytes,
            relative_path: self.relative_path.clone(),
            play_url: None,
            modified_at: self.modified_at.clone(),
            play_seconds: 0,
            last_played_at: None,
            source: "android".to_string(),
            local_path: None,
            cover_path: None,
            lyrics: None,
            file_path: None,
        }
    }
}

impl From<&Track> for AdbTrackSummary {
    fn from(track: &Track) -> Self {
        Self {
            sync_id: track.sync_id.clone(),
            title: track.title.clone(),
            artist: track.artist.clone(),
            relative_path: track.relative_path.clone(),
            size_bytes: track.size_bytes,
        }
    }
}

const fn default_true() -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::{hex_encode_utf8, phone_file_uri, ExecuteAdbSyncRequest, RENAME_EXCHANGE_ARM64};

    #[test]
    fn file_uri_encodes_every_segment_without_flattening_slashes() {
        assert_eq!(
            phone_file_uri("/sdcard/Music/中文 #?%&'.mp3"),
            "file:///sdcard/Music/%E4%B8%AD%E6%96%87%20%23%3F%25%26%27%2Emp3"
        );
    }

    #[test]
    fn exchange_helper_paths_use_shell_safe_utf8_hex() {
        assert_eq!(
            hex_encode_utf8("/音乐/a b.mp3"),
            "2fe99fb3e4b9902f6120622e6d7033"
        );
        assert_eq!(&RENAME_EXCHANGE_ARM64[..4], b"\x7fELF");
    }

    #[test]
    fn old_frontend_requests_enable_non_destructive_lists_sync_by_default() {
        let request: ExecuteAdbSyncRequest = serde_json::from_str("{}").expect("request");
        assert!(request.sync_wishlist);
        assert!(request.sync_playlists);
        assert!(request.sync_songs);
    }
}
