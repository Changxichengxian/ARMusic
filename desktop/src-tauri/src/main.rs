#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod adb_sync;
mod listening_history;
mod playlists;
mod sync_identity;
mod tag_editor;
mod tray_player;
mod wishlist;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use chrono::{DateTime, SecondsFormat, Utc};
use local_ip_address::list_afinet_netifas;
use lofty::file::{AudioFile, TaggedFile, TaggedFileExt};
use lofty::picture::{MimeType, Picture, PictureType};
use lofty::tag::{ItemKey, Tag, TagExt};
use percent_encoding::{percent_decode_str, utf8_percent_encode, NON_ALPHANUMERIC};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::env;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::net::{IpAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime};
use tauri::State;
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

const AUDIO_EXTENSIONS: &[&str] = &["mp3", "flac", "wav", "m4a", "aac", "ogg", "ape", "wma"];
const DEFAULT_SYNC_PORT: u16 = 49321;
const MAX_SCAN_FILES: usize = 5000;
const MIN_SYNC_DURATION_SECONDS: u64 = 15;

#[derive(Default)]
struct AppState {
    inner: Arc<AppInner>,
}

#[derive(Default)]
struct AppInner {
    library_folder: Mutex<Option<PathBuf>>,
    tracks: Mutex<Vec<Track>>,
    sync_server: Mutex<Option<SyncServerHandle>>,
    history_lock: Mutex<()>,
    playlists_lock: Mutex<()>,
    wishlist_lock: Mutex<()>,
    file_mutation_lock: Mutex<()>,
}

struct SyncServerHandle {
    port: u16,
    stop: Arc<AtomicBool>,
    token: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Track {
    sync_id: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    legacy_sync_ids: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    revision_hash: Option<String>,
    title: String,
    artist: String,
    album: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    work: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    genre: Option<String>,
    duration_seconds: u64,
    size_bytes: u64,
    relative_path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    play_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    modified_at: Option<String>,
    play_seconds: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    last_played_at: Option<String>,
    source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    local_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    cover_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    lyrics: Option<String>,
    #[serde(skip)]
    file_path: Option<PathBuf>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LibrarySidecar {
    #[serde(default)]
    songs: Vec<LibrarySidecarSong>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LibrarySidecarSong {
    #[serde(rename = "mediaId")]
    _media_id: Option<String>,
    title: Option<String>,
    artist: Option<String>,
    album: Option<String>,
    work: Option<String>,
    duration_ms: Option<u64>,
    file_path: Option<String>,
    history: Option<LibrarySidecarHistory>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LibrarySidecarHistory {
    play_ms: u64,
    #[serde(rename = "playCount")]
    _play_count: u64,
    #[serde(rename = "sessionCount")]
    _session_count: u64,
    last_played_at_ms: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LibraryScanResult {
    #[serde(skip_serializing_if = "Option::is_none")]
    canceled: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    folder_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    scanned_at: Option<String>,
    tracks: Vec<Track>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SyncServerStatus {
    running: bool,
    port: Option<u16>,
    addresses: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pairing_token: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SyncManifest {
    library_id: String,
    device_name: String,
    generated_at: String,
    ignored_sync_track_count: usize,
    sync_notice: String,
    tracks: Vec<Track>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ImportResult {
    folder_path: String,
    scanned_at: String,
    track: Track,
    track_count: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    warning: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct HistoryMergeResponse {
    result: listening_history::HistoryMergeResult,
    history: listening_history::HistorySyncPayload,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TagSaveResult {
    previous_sync_id: String,
    new_sync_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    warning: Option<String>,
    library: LibraryScanResult,
}

fn now_iso() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
}

fn new_pairing_token() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes)
        .map_err(|error| format!("系统安全随机数不可用，已拒绝开启同步服务：{error}"))?;
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

fn system_time_iso(time: SystemTime) -> String {
    let date_time: DateTime<Utc> = time.into();
    date_time.to_rfc3339_opts(SecondsFormat::Millis, true)
}

fn desktop_name() -> String {
    env::var("COMPUTERNAME")
        .or_else(|_| env::var("HOSTNAME"))
        .unwrap_or_else(|_| "ARMusic Desktop".to_string())
}

fn desktop_wishlist_device_id() -> String {
    format!(
        "desktop-{}",
        desktop_name().to_lowercase().replace(' ', "-")
    )
}

fn desktop_playlists_device_id() -> String {
    format!(
        "desktop-{}",
        desktop_name().to_lowercase().replace(' ', "-")
    )
}

fn is_audio_file(path: &Path) -> bool {
    path.extension()
        .and_then(|value| value.to_str())
        .map(|value| AUDIO_EXTENSIONS.contains(&value.to_ascii_lowercase().as_str()))
        .unwrap_or(false)
}

fn relative_path(file_path: &Path, root_path: &Path) -> Result<String, String> {
    let relative = file_path
        .strip_prefix(root_path)
        .map_err(|_| "歌曲路径不在音乐文件夹内".to_string())?;

    Ok(relative
        .components()
        .map(|part| part.as_os_str().to_string_lossy().to_string())
        .collect::<Vec<_>>()
        .join("/"))
}

fn guess_track_name(file_path: &Path) -> (String, String) {
    let file_name = file_path
        .file_stem()
        .map(|value| value.to_string_lossy().trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "未命名歌曲".to_string());

    let parts = file_name.split(" - ").collect::<Vec<_>>();
    if parts.len() >= 2 {
        let artist = parts[0].trim();
        let title = parts[1..].join(" - ");
        return (
            if artist.is_empty() {
                "未知歌手"
            } else {
                artist
            }
            .to_string(),
            if title.trim().is_empty() {
                file_name
            } else {
                title.trim().to_string()
            },
        );
    }

    ("未知歌手".to_string(), file_name)
}

fn basename_key(path: &str) -> Option<String> {
    path.rsplit(['/', '\\'])
        .find(|part| !part.is_empty())
        .map(|part| part.to_lowercase())
}

fn load_library_sidecar(root_path: &Path) -> Result<HashMap<String, LibrarySidecarSong>, String> {
    let sidecar_path = root_path.join("armusic-library.json");
    if !sidecar_path.is_file() {
        return Ok(HashMap::new());
    }

    let content = fs::read_to_string(&sidecar_path)
        .map_err(|error| format!("读取曲库元数据 {} 失败：{error}", sidecar_path.display()))?;
    let sidecar = serde_json::from_str::<LibrarySidecar>(&content)
        .map_err(|error| format!("解析曲库元数据 {} 失败：{error}", sidecar_path.display()))?;

    let mut songs_by_basename = HashMap::new();
    for song in sidecar.songs {
        let Some(key) = song.file_path.as_deref().and_then(basename_key) else {
            continue;
        };

        // Keep the first record when Android reports duplicate basenames. The
        // export order is stable, and this avoids assigning a later, arbitrary
        // record while still allowing the rest of the sidecar to be used.
        if songs_by_basename.contains_key(&key) {
            eprintln!(
                "Duplicate basename in library sidecar {}: {key}",
                sidecar_path.display()
            );
            continue;
        }
        songs_by_basename.insert(key, song);
    }

    Ok(songs_by_basename)
}

fn apply_sidecar_metadata(track: &mut Track, song: &LibrarySidecarSong) {
    if let Some(title) = song
        .title
        .as_deref()
        .map(str::trim)
        .filter(|v| !v.is_empty())
    {
        track.title = title.to_string();
    }
    if let Some(artist) = song
        .artist
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        track.artist = artist.to_string();
    }
    if let Some(album) = song
        .album
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        track.album = album.to_string();
    }
    if let Some(work) = song
        .work
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        track.work = Some(work.to_string());
    }
    if let Some(duration_ms) = song.duration_ms {
        track.duration_seconds = duration_ms / 1000;
    }
    if let Some(history) = &song.history {
        track.play_seconds = history.play_ms.saturating_add(500) / 1000;
        track.last_played_at = i64::try_from(history.last_played_at_ms)
            .ok()
            .and_then(DateTime::<Utc>::from_timestamp_millis)
            .map(|value| value.to_rfc3339_opts(SecondsFormat::Millis, true));
    }
}

fn prioritized_tags(tagged_file: &TaggedFile) -> Vec<&Tag> {
    let primary_type = tagged_file.primary_tag().map(TagExt::tag_type);
    let mut tags = Vec::with_capacity(tagged_file.tags().len());

    if let Some(primary) = tagged_file.primary_tag() {
        tags.push(primary);
    }
    tags.extend(
        tagged_file
            .tags()
            .iter()
            .filter(|tag| Some(tag.tag_type()) != primary_type),
    );
    tags
}

fn embedded_text(tagged_file: &TaggedFile, keys: &[ItemKey]) -> Option<String> {
    prioritized_tags(tagged_file).into_iter().find_map(|tag| {
        keys.iter().find_map(|key| {
            tag.get_string(*key)
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(str::to_string)
        })
    })
}

fn embedded_picture(tagged_file: &TaggedFile) -> Option<&Picture> {
    let tags = prioritized_tags(tagged_file);

    tags.iter()
        .find_map(|tag| tag.get_picture_type(PictureType::CoverFront))
        .or_else(|| tags.iter().find_map(|tag| tag.pictures().first()))
}

fn picture_extension(picture: &Picture) -> &'static str {
    match picture.mime_type() {
        Some(MimeType::Png) => "png",
        Some(MimeType::Jpeg) => "jpg",
        Some(MimeType::Tiff) => "tiff",
        Some(MimeType::Bmp) => "bmp",
        Some(MimeType::Gif) => "gif",
        Some(MimeType::Unknown(mime)) if mime.to_ascii_lowercase().contains("webp") => "webp",
        _ if picture.data().starts_with(b"\x89PNG\r\n\x1a\n") => "png",
        _ if picture.data().starts_with(b"\xff\xd8\xff") => "jpg",
        _ if picture.data().starts_with(b"GIF8") => "gif",
        _ if picture.data().starts_with(b"BM") => "bmp",
        _ if picture.data().starts_with(b"RIFF") && picture.data().get(8..12) == Some(b"WEBP") => {
            "webp"
        }
        _ => "img",
    }
}

fn cache_embedded_picture(sync_id: &str, picture: &Picture) -> Option<String> {
    if picture.data().is_empty() {
        return None;
    }

    let cache_dir = env::temp_dir().join("ARMusic").join("cover-cache-v1");
    if let Err(error) = fs::create_dir_all(&cache_dir) {
        eprintln!(
            "Failed to create cover cache {}: {error}",
            cache_dir.display()
        );
        return None;
    }

    // Include the bytes in the cache key. A same-length cover replacement must produce a new URL,
    // otherwise WebView's image cache can keep showing the old artwork after a successful save.
    let cover_digest = format!("{:x}", Sha256::digest(picture.data()));
    let cache_path = cache_dir.join(format!(
        "{sync_id}-{}.{}",
        &cover_digest[..16],
        picture_extension(picture)
    ));
    if !cache_path.is_file() {
        if let Err(error) = fs::write(&cache_path, picture.data()) {
            eprintln!("Failed to cache cover {}: {error}", cache_path.display());
            return None;
        }
    }

    Some(cache_path.to_string_lossy().to_string())
}

fn apply_embedded_metadata(track: &mut Track, file_path: &Path) -> Result<(), String> {
    let tagged_file = lofty::read_from_path(file_path)
        .map_err(|error| format!("读取音频元数据 {} 失败：{error}", file_path.display()))?;

    if let Some(title) = embedded_text(&tagged_file, &[ItemKey::TrackTitle]) {
        track.title = title;
    }
    if let Some(artist) = embedded_text(&tagged_file, &[ItemKey::TrackArtist]) {
        track.artist = artist;
    }
    if let Some(album) = embedded_text(&tagged_file, &[ItemKey::AlbumTitle]) {
        track.album = album;
    }
    track.work = embedded_text(&tagged_file, &[ItemKey::Work, ItemKey::ContentGroup]);
    track.genre = embedded_text(&tagged_file, &[ItemKey::Genre]);

    let embedded_duration = tagged_file.properties().duration().as_secs();
    if embedded_duration > 0 {
        track.duration_seconds = embedded_duration;
    }

    track.lyrics = embedded_text(&tagged_file, &[ItemKey::Lyrics, ItemKey::UnsyncLyrics]);
    track.cover_path = embedded_picture(&tagged_file)
        .and_then(|picture| cache_embedded_picture(&track.sync_id, picture));
    Ok(())
}

fn create_base_track(file_path: &Path, root_path: &Path) -> Result<Track, String> {
    create_base_track_with_identity_mode(file_path, root_path, false)
}

fn create_base_track_with_identity_mode(
    file_path: &Path,
    root_path: &Path,
    force_identity_refresh: bool,
) -> Result<Track, String> {
    let metadata = fs::metadata(file_path).map_err(|error| error.to_string())?;
    let identity = if force_identity_refresh {
        sync_identity::refresh_audio_identity(file_path)?
    } else {
        sync_identity::create_audio_identity(file_path)?
    };
    let (artist, title) = guess_track_name(file_path);
    let album = file_path
        .parent()
        .and_then(|value| value.file_name())
        .map(|value| value.to_string_lossy().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "本地音乐".to_string());

    Ok(Track {
        sync_id: identity.stable_id,
        legacy_sync_ids: vec![identity.legacy_id],
        revision_hash: Some(identity.revision_hash),
        title,
        artist,
        album,
        work: None,
        genre: None,
        duration_seconds: 0,
        size_bytes: metadata.len(),
        relative_path: relative_path(file_path, root_path)?,
        play_url: None,
        modified_at: metadata.modified().ok().map(system_time_iso),
        play_seconds: 0,
        last_played_at: None,
        source: "desktop".to_string(),
        local_path: None,
        cover_path: None,
        lyrics: None,
        file_path: Some(file_path.to_path_buf()),
    })
}

fn create_track(file_path: &Path, root_path: &Path) -> Result<Track, String> {
    let mut track = create_base_track(file_path, root_path)?;
    apply_embedded_metadata(&mut track, file_path)?;
    Ok(track)
}

fn create_library_track(
    file_path: &Path,
    root_path: &Path,
    sidecar: &HashMap<String, LibrarySidecarSong>,
    force_identity_refresh: bool,
) -> Result<Track, String> {
    let mut track =
        create_base_track_with_identity_mode(file_path, root_path, force_identity_refresh)?;
    let sidecar_song = file_path
        .file_name()
        .and_then(|value| value.to_str())
        .and_then(basename_key)
        .and_then(|key| sidecar.get(&key));
    if let Some(song) = sidecar_song {
        apply_sidecar_metadata(&mut track, song);
    }
    // Embedded metadata is authoritative and therefore applied after the old sidecar fallback.
    apply_embedded_metadata(&mut track, file_path)?;
    Ok(track)
}

fn walk_directory(
    root_path: &Path,
    current_path: &Path,
    files: &mut Vec<PathBuf>,
) -> Result<(), String> {
    if files.len() > MAX_SCAN_FILES {
        return Err(format!(
            "音乐文件超过 {MAX_SCAN_FILES} 首，为避免生成不完整同步清单，本次扫描已停止"
        ));
    }

    let entries = fs::read_dir(current_path).map_err(|error| error.to_string())?;
    for entry in entries {
        let entry = entry.map_err(|error| error.to_string())?;
        let path = entry.path();
        let file_name = entry.file_name().to_string_lossy().to_string();

        if file_name.starts_with('.') {
            continue;
        }

        let metadata = fs::symlink_metadata(&path).map_err(|error| error.to_string())?;
        if metadata_is_reparse_point(&metadata) {
            return Err(format!(
                "曲库中含有链接或重解析点，已拒绝扫描以防路径越界：{}",
                path.display()
            ));
        }
        let canonical = path
            .canonicalize()
            .map_err(|error| format!("解析曲库路径 {} 失败：{error}", path.display()))?;
        if !canonical.starts_with(root_path) {
            return Err(format!(
                "曲库路径越过音乐文件夹边界，已拒绝扫描：{}",
                path.display()
            ));
        }
        if metadata.is_dir() {
            walk_directory(root_path, &canonical, files)?;
        } else if metadata.is_file() && is_audio_file(&canonical) {
            files.push(canonical);
        }

        if files.len() > MAX_SCAN_FILES {
            return Err(format!(
                "音乐文件超过 {MAX_SCAN_FILES} 首，为避免生成不完整同步清单，本次扫描已停止"
            ));
        }
    }
    Ok(())
}

fn scan_music_folder(folder_path: &Path) -> Result<LibraryScanResult, String> {
    scan_music_folder_with_identity_mode(folder_path, false)
}

fn scan_music_folder_uncached(folder_path: &Path) -> Result<LibraryScanResult, String> {
    scan_music_folder_with_identity_mode(folder_path, true)
}

fn scan_music_folder_with_identity_mode(
    folder_path: &Path,
    force_identity_refresh: bool,
) -> Result<LibraryScanResult, String> {
    let root_path = folder_path
        .canonicalize()
        .map_err(|error| format!("读取音乐文件夹失败：{error}"))?;

    if !root_path.is_dir() {
        return Err("请选择一个音乐文件夹".to_string());
    }

    let mut files = Vec::new();
    walk_directory(&root_path, &root_path, &mut files)?;
    let sidecar = load_library_sidecar(&root_path)?;

    let mut tracks = Vec::new();
    for file_path in files {
        tracks.push(
            create_library_track(&file_path, &root_path, &sidecar, force_identity_refresh)
                .map_err(|error| format!("扫描音频 {} 失败：{error}", file_path.display()))?,
        );
    }

    tracks.sort_by(|a, b| a.relative_path.cmp(&b.relative_path));
    validate_unique_track_identities(&tracks)?;

    if listening_history::history_path(&root_path).is_file() {
        let mut history = listening_history::load(&root_path)?;
        normalize_history_ids(&mut history, &tracks);
        apply_history_to_tracks(&history, &mut tracks);
    }
    sync_identity::flush_identity_cache()?;

    Ok(LibraryScanResult {
        canceled: Some(false),
        folder_path: Some(root_path.to_string_lossy().to_string()),
        scanned_at: Some(now_iso()),
        tracks,
    })
}

fn validate_unique_track_identities(tracks: &[Track]) -> Result<(), String> {
    let mut owners = HashMap::<String, &str>::new();
    for track in tracks {
        if track.sync_id.trim().is_empty() {
            return Err(format!("歌曲缺少音频身份：{}", track.relative_path));
        }
        let mut own_ids = std::collections::HashSet::new();
        for identity in std::iter::once(&track.sync_id).chain(track.legacy_sync_ids.iter()) {
            if identity.trim().is_empty() {
                continue;
            }
            if !own_ids.insert(identity) {
                continue;
            }
            if let Some(previous) = owners.insert(identity.clone(), &track.relative_path) {
                return Err(format!(
                    "检测到重复音频身份，已停止同步以免生成副本：{} 与 {}",
                    previous, track.relative_path
                ));
            }
        }
    }
    Ok(())
}

/// Reloads the entire library from the filesystem and replaces the in-memory
/// snapshot. The caller must hold `file_mutation_lock` for the whole call.
fn refresh_library_locked(state: &AppInner) -> Result<PathBuf, String> {
    let root = history_root(state)?
        .canonicalize()
        .map_err(|error| format!("读取音乐文件夹失败：{error}"))?;
    let scan = scan_music_folder_uncached(&root)?;
    *state.tracks.lock().expect("tracks lock") = scan.tracks;
    Ok(root)
}

fn copy_file_synced(source: &Path, destination: &Path) -> Result<(), String> {
    let mut input = File::open(source).map_err(|error| error.to_string())?;
    let mut output = File::create(destination).map_err(|error| error.to_string())?;
    std::io::copy(&mut input, &mut output).map_err(|error| error.to_string())?;
    output.sync_all().map_err(|error| error.to_string())
}

#[cfg(windows)]
fn atomic_replace_file(replacement: &Path, target: &Path) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };

    let replacement = replacement
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect::<Vec<_>>();
    let target = target
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect::<Vec<_>>();
    let result = unsafe {
        MoveFileExW(
            replacement.as_ptr(),
            target.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if result == 0 {
        Err(std::io::Error::last_os_error().to_string())
    } else {
        Ok(())
    }
}

#[cfg(not(windows))]
fn atomic_replace_file(replacement: &Path, target: &Path) -> Result<(), String> {
    fs::rename(replacement, target).map_err(|error| error.to_string())
}

#[cfg(windows)]
fn atomic_replace_with_backup(
    replacement: &Path,
    target: &Path,
    backup: &Path,
) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{ReplaceFileW, REPLACEFILE_WRITE_THROUGH};

    let wide = |path: &Path| {
        path.as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect::<Vec<_>>()
    };
    let replacement = wide(replacement);
    let target = wide(target);
    let backup = wide(backup);
    let result = unsafe {
        ReplaceFileW(
            target.as_ptr(),
            replacement.as_ptr(),
            backup.as_ptr(),
            REPLACEFILE_WRITE_THROUGH,
            std::ptr::null(),
            std::ptr::null(),
        )
    };
    if result == 0 {
        Err(std::io::Error::last_os_error().to_string())
    } else {
        Ok(())
    }
}

#[cfg(not(windows))]
fn atomic_replace_with_backup(
    replacement: &Path,
    target: &Path,
    backup: &Path,
) -> Result<(), String> {
    fs::rename(target, backup).map_err(|error| error.to_string())?;
    if let Err(error) = fs::rename(replacement, target) {
        let _ = fs::rename(backup, target);
        return Err(error.to_string());
    }
    Ok(())
}

fn identity_index(tracks: &[Track]) -> HashMap<String, String> {
    let mut result = HashMap::new();
    for track in tracks {
        result.insert(track.sync_id.clone(), track.sync_id.clone());
        for legacy in &track.legacy_sync_ids {
            result
                .entry(legacy.clone())
                .or_insert_with(|| track.sync_id.clone());
        }
    }
    result
}

fn normalize_history_ids(store: &mut listening_history::HistoryStore, tracks: &[Track]) {
    let aliases = identity_index(tracks);
    for session in &mut store.sessions {
        if let Some(canonical) = aliases.get(&session.sync_id) {
            session.sync_id.clone_from(canonical);
        }
    }
}

fn apply_history_to_tracks(store: &listening_history::HistoryStore, tracks: &mut [Track]) {
    let summaries = listening_history::summaries(store);
    for track in tracks {
        if let Some(summary) = summaries.get(&track.sync_id) {
            track.play_seconds = summary.play_seconds;
            track.last_played_at.clone_from(&summary.last_played_at);
        }
    }
}

fn load_seeded_history(
    root_path: &Path,
    tracks: &[Track],
) -> Result<listening_history::HistoryStore, String> {
    let mut store = listening_history::load(root_path)?;
    normalize_history_ids(&mut store, tracks);
    for track in tracks {
        listening_history::seed_legacy_summary(
            &mut store,
            &track.sync_id,
            track.play_seconds,
            track.last_played_at.as_deref(),
        );
    }
    Ok(store)
}

fn initial_app_state() -> AppState {
    let state = AppState::default();
    let music_folder = env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(|parent| parent.join("Music")));

    let Some(music_folder) = music_folder.filter(|path| path.is_dir()) else {
        return state;
    };

    match scan_music_folder(&music_folder) {
        Ok(result) => {
            *state
                .inner
                .library_folder
                .lock()
                .expect("library folder lock") = result.folder_path.as_ref().map(PathBuf::from);
            *state.inner.tracks.lock().expect("tracks lock") = result.tracks;
        }
        Err(error) => eprintln!(
            "Failed to scan portable music folder {}: {error}",
            music_folder.display()
        ),
    }

    state
}

fn track_for_ui(track: &Track) -> Track {
    let mut public = track.clone();
    public.local_path = track
        .file_path
        .as_ref()
        .map(|value| value.to_string_lossy().to_string());
    public.file_path = None;
    public
}

fn track_for_manifest(track: &Track) -> Track {
    let mut public = track.clone();
    public.local_path = None;
    public.cover_path = None;
    public.lyrics = None;
    public.file_path = None;
    public.play_url = None;
    public
}

fn library_result(state: &AppInner, canceled: bool) -> LibraryScanResult {
    let folder_path = state
        .library_folder
        .lock()
        .expect("library folder lock")
        .as_ref()
        .map(|value| value.to_string_lossy().to_string());
    let tracks = state
        .tracks
        .lock()
        .expect("tracks lock")
        .iter()
        .map(track_for_ui)
        .collect();

    LibraryScanResult {
        canceled: Some(canceled),
        folder_path,
        scanned_at: None,
        tracks,
    }
}

fn create_manifest(state: &AppInner) -> SyncManifest {
    let device_name = desktop_name();
    let library = state.tracks.lock().expect("tracks lock");
    let ignored_sync_track_count = library
        .iter()
        .filter(|track| !track_in_sync_scope(track))
        .count();
    let tracks = library
        .iter()
        .filter(|track| track_in_sync_scope(track))
        .map(track_for_manifest)
        .collect::<Vec<_>>();

    SyncManifest {
        library_id: format!("desktop-{}", device_name.to_lowercase().replace(' ', "-")),
        device_name,
        generated_at: now_iso(),
        ignored_sync_track_count,
        sync_notice: "双向歌曲同步暂只包含不少于 15 秒的 MP3；其他格式仍可在本机播放".to_string(),
        tracks,
    }
}

fn track_in_sync_scope(track: &Track) -> bool {
    track.duration_seconds >= MIN_SYNC_DURATION_SECONDS
        && Path::new(&track.relative_path)
            .extension()
            .and_then(|value| value.to_str())
            .map(|value| value.eq_ignore_ascii_case("mp3"))
            .unwrap_or(false)
}

fn get_lan_addresses(port: u16, token: Option<&str>) -> Vec<String> {
    let suffix = token
        .map(|value| format!("?token={value}"))
        .unwrap_or_default();
    let mut addresses = list_afinet_netifas()
        .map(|items| {
            items
                .into_iter()
                .filter_map(|(_, ip)| match ip {
                    IpAddr::V4(ipv4) if !ipv4.is_loopback() => {
                        Some(format!("http://{ipv4}:{port}{suffix}"))
                    }
                    _ => None,
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();

    if addresses.is_empty() {
        addresses.push(format!("http://127.0.0.1:{port}{suffix}"));
    }

    addresses.sort();
    addresses.dedup();
    addresses
}

fn sync_status(state: &AppInner) -> SyncServerStatus {
    let server = state
        .sync_server
        .lock()
        .expect("sync server lock")
        .as_ref()
        .map(|handle| (handle.port, handle.token.clone()));

    SyncServerStatus {
        running: server.is_some(),
        port: server.as_ref().map(|value| value.0),
        addresses: server
            .as_ref()
            .map(|(port, token)| get_lan_addresses(*port, Some(token)))
            .unwrap_or_default(),
        pairing_token: server.map(|value| value.1),
    }
}

fn json_header() -> Header {
    Header::from_bytes("Content-Type", "application/json; charset=utf-8").expect("valid header")
}

fn cors_header() -> Header {
    Header::from_bytes("Access-Control-Allow-Origin", "*").expect("valid header")
}

fn write_json<T: Serialize>(request: Request, status: u16, payload: &T) {
    let body = serde_json::to_string_pretty(payload).unwrap_or_else(|_| "{}".to_string());
    let response = Response::from_string(body)
        .with_status_code(StatusCode(status))
        .with_header(json_header())
        .with_header(cors_header());
    let _ = request.respond(response);
}

fn write_error(request: Request, status: u16, message: &str) {
    write_json(request, status, &serde_json::json!({ "error": message }));
}

fn find_header(request: &Request, name: &str) -> Option<String> {
    request
        .headers()
        .iter()
        .find(|header| header.field.to_string().eq_ignore_ascii_case(name))
        .map(|header| header.value.as_str().to_string())
}

fn safe_path_segments(relative_path: Option<&str>, fallback_name: &str) -> Vec<String> {
    let raw = relative_path
        .filter(|value| !value.trim().is_empty())
        .unwrap_or(fallback_name);

    let mut segments = raw
        .split(['/', '\\'])
        .filter(|value| !value.trim().is_empty())
        .map(|segment| {
            let safe = segment
                .chars()
                .map(|ch| match ch {
                    '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
                    ch if ch.is_control() => '_',
                    ch => ch,
                })
                .collect::<String>()
                .trim()
                .to_string();

            match safe.as_str() {
                "" | "." | ".." => "_".to_string(),
                _ => safe,
            }
        })
        .collect::<Vec<_>>();

    if segments.is_empty() {
        segments.push(fallback_name.to_string());
    }

    segments
}

fn path_exists(path: &Path) -> bool {
    fs::metadata(path).is_ok()
}

#[cfg(windows)]
fn metadata_is_reparse_point(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0400;
    metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
}

#[cfg(not(windows))]
fn metadata_is_reparse_point(metadata: &fs::Metadata) -> bool {
    metadata.file_type().is_symlink()
}

fn ensure_safe_directory(root: &Path, requested: &Path) -> Result<PathBuf, String> {
    if !requested.starts_with(root) {
        return Err("内部目录越过音乐文件夹边界".to_string());
    }
    let relative = requested
        .strip_prefix(root)
        .map_err(|_| "内部目录越过音乐文件夹边界".to_string())?;
    let mut cursor = root.to_path_buf();
    for component in relative.components() {
        let std::path::Component::Normal(component) = component else {
            return Err("内部目录包含不安全的路径组件".to_string());
        };
        let next = cursor.join(component);
        match fs::symlink_metadata(&next) {
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                fs::create_dir(&next)
                    .map_err(|error| format!("创建内部目录 {} 失败：{error}", next.display()))?;
            }
            Err(error) => return Err(format!("检查内部目录 {} 失败：{error}", next.display())),
        }
        let metadata = fs::symlink_metadata(&next)
            .map_err(|error| format!("复核内部目录 {} 失败：{error}", next.display()))?;
        if metadata_is_reparse_point(&metadata) {
            return Err(format!(
                "内部目录含有链接或重解析点，已拒绝写入：{}",
                next.display()
            ));
        }
        if !metadata.is_dir() {
            return Err(format!("内部路径不是文件夹：{}", next.display()));
        }
        let canonical = next
            .canonicalize()
            .map_err(|error| format!("解析内部目录 {} 失败：{error}", next.display()))?;
        if !canonical.starts_with(root) {
            return Err(format!(
                "内部目录解析后越过音乐文件夹边界，已拒绝写入：{}",
                next.display()
            ));
        }
        cursor = canonical;
    }
    Ok(cursor)
}

fn unique_file_path(target: &Path) -> PathBuf {
    if !path_exists(target) {
        return target.to_path_buf();
    }

    let parent = target.parent().unwrap_or_else(|| Path::new(""));
    let stem = target
        .file_stem()
        .map(|value| value.to_string_lossy().to_string())
        .unwrap_or_else(|| "track".to_string());
    let ext = target
        .extension()
        .map(|value| value.to_string_lossy().to_string());

    for index in 1.. {
        let file_name = match &ext {
            Some(ext) if !ext.is_empty() => format!("{stem} ({index}).{ext}"),
            _ => format!("{stem} ({index})"),
        };
        let candidate = parent.join(file_name);
        if !path_exists(&candidate) {
            return candidate;
        }
    }

    target.to_path_buf()
}

fn import_track_from_reader(
    root_path: &Path,
    remote_track: &Track,
    existing_tracks: &[Track],
    refresh_before_publish: bool,
    reader: &mut dyn Read,
) -> Result<Track, String> {
    let root = root_path
        .canonicalize()
        .map_err(|error| format!("读取音乐文件夹失败：{error}"))?;
    let fallback_name = format!("{}.mp3", remote_track.sync_id);
    let segments = safe_path_segments(Some(&remote_track.relative_path), &fallback_name);
    let mut target_path = root.join("ARMusic Imports");

    for segment in segments {
        target_path.push(segment);
    }

    let requested_parent = target_path
        .parent()
        .ok_or_else(|| "上传路径不安全".to_string())?;
    let parent = ensure_safe_directory(&root, requested_parent)?;
    let file_name = target_path
        .file_name()
        .ok_or_else(|| "上传文件名不安全".to_string())?;

    let final_path = unique_file_path(&parent.join(file_name));
    if !final_path.starts_with(&root) {
        return Err("上传路径不安全".to_string());
    }

    let extension = final_path
        .extension()
        .map(|value| value.to_string_lossy().to_string())
        .unwrap_or_else(|| "audio".to_string());
    let nonce = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_nanos();
    let staging = parent.join(format!(".armusic-incoming-{nonce}.{extension}"));
    let transfer = (|| -> Result<(), String> {
        let mut output = File::create(&staging).map_err(|error| error.to_string())?;
        std::io::copy(reader, &mut output).map_err(|error| error.to_string())?;
        output.sync_all().map_err(|error| error.to_string())?;
        let identity = sync_identity::create_audio_identity_uncached(&staging)?;
        let id_matches = remote_track.sync_id == identity.stable_id
            || remote_track.sync_id == identity.legacy_id
            || remote_track
                .legacy_sync_ids
                .iter()
                .any(|id| id == &identity.stable_id || id == &identity.legacy_id);
        if !id_matches {
            return Err("上传歌曲的音频身份校验失败".to_string());
        }
        if existing_tracks.iter().any(|track| {
            track_has_identity(track, &identity.stable_id)
                || track_has_identity(track, &identity.legacy_id)
        }) {
            return Err("电脑曲库已经有相同音频，已拒绝重复导入".to_string());
        }
        if remote_track
            .revision_hash
            .as_ref()
            .map(|expected| expected != &identity.revision_hash)
            .unwrap_or(false)
        {
            return Err("上传歌曲的完整文件校验失败".to_string());
        }
        let staged_track = create_track(&staging, &root)?;
        if !track_in_sync_scope(&staged_track) {
            return Err(
                "跨端歌曲同步暂只接收不少于 15 秒的 MP3；该文件仍可手动放入本机曲库".to_string(),
            );
        }
        // External programs do not share ARMusic's mutex. Re-read every
        // existing audio byte immediately before publishing the hidden stage.
        if refresh_before_publish {
            let fresh = scan_music_folder_uncached(&root)?;
            if fresh.tracks.iter().any(|track| {
                track_has_identity(track, &identity.stable_id)
                    || track_has_identity(track, &identity.legacy_id)
            }) {
                return Err("电脑曲库在传输期间加入了相同音频，已拒绝发布重复文件".to_string());
            }
        }
        if final_path.exists() {
            return Err("上传期间目标路径出现了新文件，已保留两边等待处理".to_string());
        }
        fs::rename(&staging, &final_path).map_err(|error| error.to_string())?;
        Ok(())
    })();
    if let Err(error) = transfer {
        let _ = fs::remove_file(&staging);
        return Err(error);
    }

    create_track(&final_path, &root)
}

fn tracks_share_identity(left: &Track, right: &Track) -> bool {
    let left_ids = std::iter::once(&left.sync_id)
        .chain(left.legacy_sync_ids.iter())
        .collect::<std::collections::HashSet<_>>();
    std::iter::once(&right.sync_id)
        .chain(right.legacy_sync_ids.iter())
        .any(|id| left_ids.contains(id))
}

fn track_has_identity(track: &Track, sync_id: &str) -> bool {
    track.sync_id == sync_id || track.legacy_sync_ids.iter().any(|id| id == sync_id)
}

fn remove_imported_track_if_unchanged(track: &Track) -> Result<bool, String> {
    let path = track
        .file_path
        .as_ref()
        .ok_or_else(|| "本次导入歌曲缺少本地路径".to_string())?;
    let current = sync_identity::create_audio_identity_uncached(path)?;
    let unchanged = track_has_identity(track, &current.stable_id)
        && track
            .revision_hash
            .as_ref()
            .map(|expected| expected == &current.revision_hash)
            .unwrap_or(false);
    if !unchanged {
        return Ok(false);
    }
    fs::remove_file(path).map_err(|error| error.to_string())?;
    Ok(true)
}

fn handle_track_get(request: Request, state: Arc<AppInner>, sync_id: &str) {
    let track = state
        .tracks
        .lock()
        .expect("tracks lock")
        .iter()
        .find(|track| track_has_identity(track, sync_id))
        .cloned();

    let Some(track) = track else {
        write_error(request, 404, "没有找到这首歌");
        return;
    };

    let Some(file_path) = track.file_path else {
        write_error(request, 404, "这首歌没有本地文件");
        return;
    };

    let Ok(file) = File::open(&file_path) else {
        write_error(request, 404, "文件已经不存在");
        return;
    };

    let filename = utf8_percent_encode(&track.relative_path, NON_ALPHANUMERIC).to_string();
    let content_disposition = Header::from_bytes(
        "Content-Disposition",
        format!("attachment; filename*=UTF-8''{filename}"),
    )
    .expect("valid header");

    let response = Response::from_file(file)
        .with_header(cors_header())
        .with_header(content_disposition);
    let _ = request.respond(response);
}

fn handle_track_post(mut request: Request, state: Arc<AppInner>, sync_id: &str) {
    let Some(raw_header) = find_header(&request, "X-ARMusic-Track") else {
        write_error(request, 400, "缺少歌曲信息");
        return;
    };

    let remote_track = BASE64
        .decode(raw_header)
        .map_err(|error| error.to_string())
        .and_then(|bytes| String::from_utf8(bytes).map_err(|error| error.to_string()))
        .and_then(|text| serde_json::from_str::<Track>(&text).map_err(|error| error.to_string()));

    let Ok(remote_track) = remote_track else {
        write_error(request, 400, "歌曲信息无法识别");
        return;
    };

    if remote_track.sync_id != sync_id {
        write_error(request, 400, "歌曲编号不一致");
        return;
    }

    let result = (|| -> Result<ImportResult, String> {
        // Keep the staged hash, final uncached duplicate check and publish in
        // one critical section so ADB/LAN/tag edits cannot create a second copy.
        let _file_guard = state.file_mutation_lock.lock().expect("file mutation lock");
        let root_path = history_root(&state)?
            .canonicalize()
            .map_err(|error| format!("读取音乐文件夹失败：{error}"))?;

        let imported =
            import_track_from_reader(&root_path, &remote_track, &[], true, request.as_reader())?;

        let scan = match scan_music_folder_uncached(&root_path) {
            Ok(scan) => scan,
            Err(error) => {
                let removed = remove_imported_track_if_unchanged(&imported).unwrap_or(false);
                if let Ok(restored) = scan_music_folder_uncached(&root_path) {
                    *state.tracks.lock().expect("tracks lock") = restored.tracks;
                }
                return Err(if removed {
                    format!("歌曲发布后曲库复核失败，已撤回未变化的新文件：{error}")
                } else {
                    format!("歌曲发布后曲库复核失败，且新文件随后发生变化，程序没有删除它：{error}")
                });
            }
        };
        let track_count = scan.tracks.len();
        *state.tracks.lock().expect("tracks lock") = scan.tracks;

        Ok(ImportResult {
            folder_path: root_path.to_string_lossy().to_string(),
            scanned_at: now_iso(),
            track: track_for_manifest(&imported),
            track_count,
            warning: None,
        })
    })();

    match result {
        Ok(result) => write_json(
            request,
            201,
            &serde_json::json!({ "ok": true, "result": result }),
        ),
        Err(error) => write_error(request, 409, &error),
    }
}

fn handle_track_put(mut request: Request, state: Arc<AppInner>, sync_id: &str) {
    let result = (|| -> Result<ImportResult, String> {
        let raw_header =
            find_header(&request, "X-ARMusic-Track").ok_or_else(|| "缺少歌曲信息".to_string())?;
        let remote_track = BASE64
            .decode(raw_header)
            .map_err(|error| error.to_string())
            .and_then(|bytes| String::from_utf8(bytes).map_err(|error| error.to_string()))
            .and_then(|text| {
                serde_json::from_str::<Track>(&text).map_err(|error| error.to_string())
            })?;
        if !track_has_identity(&remote_track, sync_id) {
            return Err("歌曲编号不一致".to_string());
        }
        let expected_revision = find_header(&request, "X-ARMusic-If-Match")
            .ok_or_else(|| "缺少电脑端预览版本，已拒绝覆盖".to_string())?;

        // The rescan, optimistic revision check, upload and atomic replacement
        // must see one stable desktop filesystem snapshot.
        let _file_guard = state.file_mutation_lock.lock().expect("file mutation lock");
        let root = refresh_library_locked(&state)?;
        let local = state
            .tracks
            .lock()
            .expect("tracks lock")
            .iter()
            .find(|track| tracks_share_identity(track, &remote_track))
            .cloned()
            .ok_or_else(|| "电脑端没有可原位替换的同一首歌".to_string())?;
        let target = local
            .file_path
            .as_ref()
            .ok_or_else(|| "电脑端歌曲没有本地文件".to_string())?
            .canonicalize()
            .map_err(|error| error.to_string())?;
        if !target.starts_with(&root) {
            return Err("歌曲路径不安全，已拒绝替换".to_string());
        }
        let current_identity = sync_identity::create_audio_identity_uncached(&target)?;
        if current_identity.revision_hash != expected_revision {
            return Err("电脑文件在预览后发生变化，请重新对比；原文件保持不变".to_string());
        }

        let extension = target
            .extension()
            .map(|value| value.to_string_lossy().to_string())
            .unwrap_or_else(|| "audio".to_string());
        let nonce = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map_err(|error| error.to_string())?
            .as_nanos();
        let staging = target.with_file_name(format!(".armusic-replace-{nonce}.{extension}"));
        let mut output = File::create(&staging).map_err(|error| error.to_string())?;
        if let Err(error) = std::io::copy(request.as_reader(), &mut output) {
            let _ = fs::remove_file(&staging);
            return Err(error.to_string());
        }
        output.sync_all().map_err(|error| error.to_string())?;
        drop(output);
        let staged_track = create_track(&staging, &root)?;
        if !track_in_sync_scope(&staged_track) {
            let _ = fs::remove_file(&staging);
            return Err("跨端歌曲同步暂只接收不少于 15 秒的 MP3；电脑原文件保持不变".to_string());
        }
        if !tracks_share_identity(&local, &staged_track) {
            let _ = fs::remove_file(&staging);
            return Err("替换文件的音频身份不同，原文件保持不变".to_string());
        }
        if let (Some(expected), Some(actual)) = (
            remote_track.revision_hash.as_ref(),
            staged_track.revision_hash.as_ref(),
        ) {
            if expected != actual {
                let _ = fs::remove_file(&staging);
                return Err("替换文件传输校验失败，原文件保持不变".to_string());
            }
        }
        if sync_identity::create_audio_identity_uncached(&target)?.revision_hash
            != expected_revision
        {
            let _ = fs::remove_file(&staging);
            return Err("电脑文件在传输期间发生了变化，请重新对比；原文件保持不变".to_string());
        }

        let backup = root
            .join(".armusic-conflict-backups")
            .join(format!("{}-{}", Utc::now().format("%Y%m%d-%H%M%S"), nonce))
            .join(&local.relative_path);
        let backup_parent = backup
            .parent()
            .ok_or_else(|| "备份目录不安全".to_string())?;
        let safe_backup_parent = ensure_safe_directory(&root, backup_parent)?;
        let backup = safe_backup_parent.join(
            backup
                .file_name()
                .ok_or_else(|| "备份文件名不安全".to_string())?,
        );
        let final_current = sync_identity::create_audio_identity_uncached(&target)?;
        if final_current != current_identity {
            let _ = fs::remove_file(&staging);
            return Err("电脑文件在原子替换前再次发生变化，原文件保持不变".to_string());
        }

        let replace_result = atomic_replace_with_backup(&staging, &target, &backup);
        let final_identity =
            sync_identity::create_audio_identity_uncached(&target).map_err(|error| {
                format!(
                    "原子替换后无法确认电脑目标文件状态，程序没有盲目回滚；备份在 {}：{error}",
                    backup.display()
                )
            })?;
        let replacement_valid = track_has_identity(&staged_track, &final_identity.stable_id)
            && staged_track
                .revision_hash
                .as_ref()
                .map(|revision| revision == &final_identity.revision_hash)
                .unwrap_or(false);
        if !replacement_valid {
            if final_identity == current_identity {
                return Err(format!(
                    "原子替换没有发生，电脑原文件保持不变：{}",
                    replace_result
                        .err()
                        .unwrap_or_else(|| "目标仍是旧版本".to_string())
                ));
            }
            return Err(format!(
                "原子替换后目标文件又发生变化，程序没有覆盖该变化；原文件备份在 {}",
                backup.display()
            ));
        }
        let captured_backup = sync_identity::create_audio_identity_uncached(&backup).map_err(
            |error| {
                format!(
                    "替换已完成，但无法验证 ReplaceFileW 在交换瞬间捕获的旧目标；当前文件未回滚，备份路径为 {}：{error}",
                    backup.display()
                )
            },
        )?;
        if captured_backup.stable_id != current_identity.stable_id {
            return Err(format!(
                "替换已完成，但交换瞬间的备份音频身份异常；当前文件未回滚，备份路径为 {}",
                backup.display()
            ));
        }

        let scan = scan_music_folder_uncached(&root)?;
        let track_count = scan.tracks.len();
        let replaced = scan
            .tracks
            .iter()
            .find(|track| track.relative_path == local.relative_path)
            .cloned()
            .ok_or_else(|| "替换成功，但刷新后没有找到歌曲".to_string())?;
        *state.tracks.lock().expect("tracks lock") = scan.tracks;
        Ok(ImportResult {
            folder_path: root.to_string_lossy().to_string(),
            scanned_at: now_iso(),
            track: track_for_manifest(&replaced),
            track_count,
            warning: (captured_backup.revision_hash != current_identity.revision_hash).then(|| {
                format!(
                    "替换瞬间检测到外部编辑，较新的旧版本已完整保存在 {}",
                    backup.display()
                )
            }),
        })
    })();

    match result {
        Ok(result) => write_json(
            request,
            200,
            &serde_json::json!({ "ok": true, "result": result }),
        ),
        Err(error) => write_error(request, 409, &error),
    }
}

fn history_root(state: &AppInner) -> Result<PathBuf, String> {
    state
        .library_folder
        .lock()
        .expect("library folder lock")
        .clone()
        .ok_or_else(|| "请先在桌面端选择音乐文件夹".to_string())
}

fn handle_history_get(request: Request, state: Arc<AppInner>) {
    let result = (|| -> Result<listening_history::HistorySyncPayload, String> {
        let _history_guard = state.history_lock.lock().expect("history lock");
        let root = history_root(&state)?;
        let tracks = state.tracks.lock().expect("tracks lock").clone();
        let mut store = load_seeded_history(&root, &tracks)?;
        listening_history::save(&root, &mut store)?;
        Ok(listening_history::payload(
            &store,
            tracks.iter().map(|track| track.sync_id.clone()).collect(),
        ))
    })();

    match result {
        Ok(payload) => write_json(request, 200, &payload),
        Err(error) => write_error(request, 500, &error),
    }
}

fn handle_history_merge(mut request: Request, state: Arc<AppInner>) {
    let result = (|| -> Result<HistoryMergeResponse, String> {
        let mut body = Vec::new();
        request
            .as_reader()
            .take(16 * 1024 * 1024 + 1)
            .read_to_end(&mut body)
            .map_err(|error| error.to_string())?;
        if body.len() > 16 * 1024 * 1024 {
            return Err("听歌记录数据过大".to_string());
        }
        let incoming: listening_history::HistorySyncPayload =
            serde_json::from_slice(&body).map_err(|error| format!("听歌记录格式错误：{error}"))?;
        let _history_guard = state.history_lock.lock().expect("history lock");
        let root = history_root(&state)?;
        let tracks = state.tracks.lock().expect("tracks lock").clone();
        listening_history::archive_raw_snapshot(&root, &incoming)?;
        let mut store = load_seeded_history(&root, &tracks)?;
        let mut merge_result = listening_history::merge(&mut store, incoming)?;
        normalize_history_ids(&mut store, &tracks);
        listening_history::save(&root, &mut store)?;
        merge_result.persisted = true;
        merge_result.snapshot_id = listening_history::snapshot_id(&store.sessions);

        {
            let mut current_tracks = state.tracks.lock().expect("tracks lock");
            apply_history_to_tracks(&store, &mut current_tracks);
        }
        let history = listening_history::payload(
            &store,
            tracks.iter().map(|track| track.sync_id.clone()).collect(),
        );
        Ok(HistoryMergeResponse {
            result: merge_result,
            history,
        })
    })();

    match result {
        Ok(payload) => write_json(request, 200, &payload),
        Err(error) => write_error(request, 400, &error),
    }
}

fn handle_request(request: Request, state: Arc<AppInner>) {
    if request.method() == &Method::Options {
        let response = Response::empty(StatusCode(204))
            .with_header(cors_header())
            .with_header(
                Header::from_bytes("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
                    .expect("valid header"),
            )
            .with_header(
                Header::from_bytes(
                    "Access-Control-Allow-Headers",
                    "Content-Type, X-ARMusic-Track, X-ARMusic-If-Match, X-ARMusic-Token",
                )
                .expect("valid header"),
            );
        let _ = request.respond(response);
        return;
    }

    let url = request.url().split('?').next().unwrap_or("/").to_string();

    if url == "/health" {
        if request.method() != &Method::Get {
            write_error(request, 405, "只支持 GET");
            return;
        }
        write_json(
            request,
            200,
            &serde_json::json!({ "ok": true, "name": "ARMusic Desktop", "time": now_iso() }),
        );
        return;
    }

    let expected_token = state
        .sync_server
        .lock()
        .expect("sync server lock")
        .as_ref()
        .map(|handle| handle.token.clone());
    let supplied_token = find_header(&request, "X-ARMusic-Token").or_else(|| {
        request.url().split_once('?').and_then(|(_, query)| {
            query
                .split('&')
                .find_map(|part| part.strip_prefix("token="))
                .map(str::to_string)
        })
    });
    if expected_token.is_none() || supplied_token.as_ref() != expected_token.as_ref() {
        write_error(request, 401, "同步配对令牌无效，请从桌面端重新连接");
        return;
    }

    if url == "/manifest" {
        if request.method() != &Method::Get {
            write_error(request, 405, "只支持 GET");
            return;
        }
        let manifest = (|| -> Result<SyncManifest, String> {
            let _file_guard = state.file_mutation_lock.lock().expect("file mutation lock");
            refresh_library_locked(&state)?;
            Ok(create_manifest(&state))
        })();
        match manifest {
            Ok(manifest) => write_json(request, 200, &manifest),
            Err(error) => write_error(request, 500, &format!("曲库重扫失败：{error}")),
        }
        return;
    }

    if url == "/history" {
        if request.method() != &Method::Get {
            write_error(request, 405, "只支持 GET");
            return;
        }
        handle_history_get(request, state);
        return;
    }

    if url == "/history/merge" {
        if request.method() != &Method::Post {
            write_error(request, 405, "只支持 POST");
            return;
        }
        handle_history_merge(request, state);
        return;
    }

    if let Some(raw_sync_id) = url.strip_prefix("/tracks/") {
        let sync_id = percent_decode_str(raw_sync_id)
            .decode_utf8_lossy()
            .to_string();

        match *request.method() {
            Method::Get => handle_track_get(request, state, &sync_id),
            Method::Post => handle_track_post(request, state, &sync_id),
            Method::Put => handle_track_put(request, state, &sync_id),
            _ => write_error(request, 405, "只支持 GET、POST 或 PUT"),
        }
        return;
    }

    write_error(request, 404, "未知接口");
}

fn bind_server(port: u16) -> Result<Server, String> {
    Server::http(("0.0.0.0", port)).map_err(|error| error.to_string())
}

fn server_port(server: &Server) -> Result<u16, String> {
    server
        .server_addr()
        .to_ip()
        .map(|addr| addr.port())
        .ok_or_else(|| "无法读取同步服务端口".to_string())
}

fn start_server(state: Arc<AppInner>) -> Result<SyncServerStatus, String> {
    let mut guard = state.sync_server.lock().expect("sync server lock");
    if guard.is_some() {
        drop(guard);
        return Ok(sync_status(&state));
    }

    let server = bind_server(DEFAULT_SYNC_PORT).or_else(|_| bind_server(0))?;
    let port = server_port(&server)?;
    let stop = Arc::new(AtomicBool::new(false));
    let token = new_pairing_token()?;
    let thread_stop = stop.clone();
    let thread_state = state.clone();

    thread::spawn(move || {
        while !thread_stop.load(Ordering::SeqCst) {
            match server.recv_timeout(Duration::from_millis(250)) {
                Ok(Some(request)) => handle_request(request, thread_state.clone()),
                Ok(None) => {}
                Err(error) => {
                    eprintln!("Sync server stopped: {error}");
                    break;
                }
            }
        }
    });

    *guard = Some(SyncServerHandle { port, stop, token });
    drop(guard);
    Ok(sync_status(&state))
}

fn stop_server(state: Arc<AppInner>) -> SyncServerStatus {
    let handle = state.sync_server.lock().expect("sync server lock").take();

    if let Some(handle) = handle {
        handle.stop.store(true, Ordering::SeqCst);
        if let Ok(mut stream) = TcpStream::connect(("127.0.0.1", handle.port)) {
            let _ = stream.write_all(b"GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
        }
    }

    sync_status(&state)
}

fn editable_track(state: &AppInner, sync_id: &str) -> Result<(Track, PathBuf, PathBuf), String> {
    let root = state
        .library_folder
        .lock()
        .expect("library folder lock")
        .clone()
        .ok_or_else(|| "请先选择音乐文件夹".to_string())?
        .canonicalize()
        .map_err(|error| format!("读取音乐文件夹失败：{error}"))?;
    let track = state
        .tracks
        .lock()
        .expect("tracks lock")
        .iter()
        .find(|track| track.sync_id == sync_id)
        .cloned()
        .ok_or_else(|| "曲库里没有找到这首歌".to_string())?;
    let path = track
        .file_path
        .as_ref()
        .ok_or_else(|| "这首歌没有可编辑的本地文件".to_string())?
        .canonicalize()
        .map_err(|error| format!("读取歌曲文件失败：{error}"))?;
    if !path.starts_with(&root) {
        return Err("歌曲文件不在当前音乐文件夹内，已拒绝编辑".to_string());
    }
    Ok((track, path, root))
}

fn refresh_edited_track(
    state: &AppInner,
    previous: &Track,
    path: &Path,
    root: &Path,
) -> Result<(LibraryScanResult, String), String> {
    let sidecar = load_library_sidecar(root)?;
    let mut refreshed = create_library_track(path, root, &sidecar, true)
        .map_err(|error| format!("标签已经保存，但重新读取这首歌失败：{error}"))?;

    sync_identity::flush_identity_cache()
        .map_err(|error| format!("标签已经保存，但身份缓存刷新失败：{error}"))?;

    let mut tracks = state.tracks.lock().expect("tracks lock");
    let index = tracks
        .iter()
        .position(|track| {
            track.sync_id == previous.sync_id || track.relative_path == previous.relative_path
        })
        .ok_or_else(|| "标签已经保存，但内存曲库里没有找到这首歌".to_string())?;
    let current = &tracks[index];

    // A listening checkpoint may have arrived while the MP3 copy was being verified. Take the
    // summary from the current in-memory row under the same lock used for replacement, never from
    // the older editor snapshot.
    preserve_live_track_state(current, &mut refreshed);

    let mut next = tracks.clone();
    let new_sync_id = refreshed.sync_id.clone();
    next[index] = refreshed;
    next.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
    validate_unique_track_identities(&next)
        .map_err(|error| format!("标签已经保存，但更新曲库身份失败：{error}"))?;
    *tracks = next;
    drop(tracks);

    let mut library = library_result(state, false);
    library.scanned_at = Some(now_iso());
    Ok((library, new_sync_id))
}

fn preserve_live_track_state(current: &Track, refreshed: &mut Track) {
    refreshed.play_seconds = current.play_seconds;
    refreshed.last_played_at.clone_from(&current.last_played_at);
    let mut aliases = refreshed.legacy_sync_ids.clone();
    aliases.extend(current.legacy_sync_ids.iter().cloned());
    if current.sync_id != refreshed.sync_id {
        aliases.push(current.sync_id.clone());
    }
    aliases.retain(|identity| identity != &refreshed.sync_id && !identity.trim().is_empty());
    aliases.sort();
    aliases.dedup();
    refreshed.legacy_sync_ids = aliases;
}

#[tauri::command]
fn get_track_tags(
    sync_id: String,
    state: State<'_, AppState>,
) -> Result<tag_editor::TrackTagData, String> {
    let (track, path, _) = editable_track(&state.inner, &sync_id)?;
    tag_editor::read_track_tags(
        &path,
        track.sync_id,
        track.relative_path,
        tag_editor::TagFallback {
            title: track.title,
            artist: track.artist,
            album: track.album,
        },
    )
}

#[tauri::command]
fn save_track_tags(
    request: tag_editor::UpdateTrackTagsRequest,
    state: State<'_, AppState>,
) -> Result<TagSaveResult, String> {
    let _file_guard = state
        .inner
        .file_mutation_lock
        .lock()
        .expect("file mutation lock");
    let previous_sync_id = request.sync_id.clone();
    let (track, path, root) = editable_track(&state.inner, &previous_sync_id)?;
    let warning = tag_editor::save_track_tags(&path, request)?;
    let (library, new_sync_id) = refresh_edited_track(&state.inner, &track, &path, &root)?;

    Ok(TagSaveResult {
        previous_sync_id,
        new_sync_id,
        warning,
        library,
    })
}

#[tauri::command]
fn choose_music_folder(state: State<'_, AppState>) -> Result<LibraryScanResult, String> {
    let Some(folder_path) = rfd::FileDialog::new()
        .set_title("选择音乐文件夹")
        .pick_folder()
    else {
        return Ok(library_result(&state.inner, true));
    };

    let _file_guard = state
        .inner
        .file_mutation_lock
        .lock()
        .expect("file mutation lock");
    let mut result = scan_music_folder(&folder_path)?;
    *state
        .inner
        .library_folder
        .lock()
        .expect("library folder lock") = result.folder_path.as_ref().map(PathBuf::from);
    *state.inner.tracks.lock().expect("tracks lock") = result.tracks.clone();
    result.tracks = result.tracks.iter().map(track_for_ui).collect();

    Ok(result)
}

#[tauri::command]
fn get_library_state(state: State<'_, AppState>) -> LibraryScanResult {
    library_result(&state.inner, false)
}

#[tauri::command]
fn record_listening_session(
    mut request: listening_history::RecordListeningRequest,
    state: State<'_, AppState>,
) -> Result<listening_history::ListeningSession, String> {
    let _history_guard = state.inner.history_lock.lock().expect("history lock");
    let root = history_root(&state.inner)?;
    let tracks = state.inner.tracks.lock().expect("tracks lock").clone();
    let aliases = identity_index(&tracks);
    request.sync_id = aliases
        .get(&request.sync_id)
        .cloned()
        .ok_or_else(|| "曲库里没有找到这首歌".to_string())?;
    let mut store = load_seeded_history(&root, &tracks)?;
    let session = listening_history::record(&mut store, request)?;
    listening_history::save(&root, &mut store)?;
    apply_history_to_tracks(&store, &mut state.inner.tracks.lock().expect("tracks lock"));
    Ok(session)
}

#[tauri::command]
fn get_listening_history(
    state: State<'_, AppState>,
) -> Result<listening_history::HistorySyncPayload, String> {
    let _history_guard = state.inner.history_lock.lock().expect("history lock");
    let root = history_root(&state.inner)?;
    let tracks = state.inner.tracks.lock().expect("tracks lock").clone();
    let mut store = load_seeded_history(&root, &tracks)?;
    listening_history::save(&root, &mut store)?;
    Ok(listening_history::payload(
        &store,
        tracks.iter().map(|track| track.sync_id.clone()).collect(),
    ))
}

#[tauri::command]
fn get_wishlist(state: State<'_, AppState>) -> Result<wishlist::WishlistPayload, String> {
    let _guard = state.inner.wishlist_lock.lock().expect("wishlist lock");
    let root = history_root(&state.inner)?;
    wishlist::load(&root, &desktop_wishlist_device_id())
}

#[tauri::command]
fn save_wishlist(
    request: wishlist::WishlistSaveRequest,
    state: State<'_, AppState>,
) -> Result<wishlist::WishlistPayload, String> {
    let _guard = state.inner.wishlist_lock.lock().expect("wishlist lock");
    let root = history_root(&state.inner)?;
    let current = wishlist::load(&root, &desktop_wishlist_device_id())?;
    if request.expected_snapshot_id != current.snapshot_id {
        return Err(
            "愿望单在编辑期间已被同步更新，请重新打开后再保存；现有数据没有覆盖".to_string(),
        );
    }
    let replacement = wishlist::payload(
        current.device_id,
        request.categories,
        current.phone_baseline_established,
    )?;
    wishlist::save(&root, &replacement)
}

#[tauri::command]
fn migrate_legacy_wishlist(
    categories: Vec<wishlist::WishlistCategory>,
    state: State<'_, AppState>,
) -> Result<wishlist::WishlistPayload, String> {
    let _guard = state.inner.wishlist_lock.lock().expect("wishlist lock");
    let root = history_root(&state.inner)?;
    let current = wishlist::load(&root, &desktop_wishlist_device_id())?;
    let merged_categories = wishlist::union_categories(&current.categories, &categories)?;
    let merged = wishlist::payload(
        current.device_id,
        merged_categories,
        current.phone_baseline_established,
    )?;
    wishlist::save(&root, &merged)
}

#[tauri::command]
fn get_playlists(state: State<'_, AppState>) -> Result<playlists::PlaylistsPayload, String> {
    let _guard = state.inner.playlists_lock.lock().expect("playlists lock");
    let root = history_root(&state.inner)?;
    playlists::load(&root, &desktop_playlists_device_id())
}

#[tauri::command]
fn save_playlists(
    request: playlists::PlaylistsSaveRequest,
    state: State<'_, AppState>,
) -> Result<playlists::PlaylistsPayload, String> {
    let _guard = state.inner.playlists_lock.lock().expect("playlists lock");
    let root = history_root(&state.inner)?;
    let current = playlists::load(&root, &desktop_playlists_device_id())?;
    if request.expected_snapshot_id != current.snapshot_id {
        return Err("歌单在编辑期间已被同步更新，请重新打开后再保存；现有数据没有覆盖".to_string());
    }
    let requested_ids = request
        .playlists
        .iter()
        .map(|playlist| playlist.id.as_str())
        .collect::<std::collections::HashSet<_>>();
    let changed_at = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_millis()
        .min(u64::MAX as u128) as u64;
    let mut tombstones = current.deleted_playlists;
    for removed in current
        .playlists
        .iter()
        .filter(|playlist| !requested_ids.contains(playlist.id.as_str()))
    {
        if let Some(existing) = tombstones.iter_mut().find(|item| item.id == removed.id) {
            existing.deleted_at = existing.deleted_at.max(changed_at);
        } else {
            tombstones.push(playlists::PlaylistTombstone {
                id: removed.id.clone(),
                deleted_at: changed_at,
            });
        }
    }
    tombstones.retain(|tombstone| {
        !request.playlists.iter().any(|playlist| {
            playlist.id == tombstone.id && playlist.modify_time > tombstone.deleted_at
        })
    });
    let mut track_tombstones = current.removed_tracks;
    for previous in &current.playlists {
        let Some(next) = request.playlists.iter().find(|item| item.id == previous.id) else {
            continue;
        };
        for removed_track in previous
            .track_ids
            .iter()
            .filter(|track_id| !next.track_ids.contains(track_id))
        {
            if let Some(existing) = track_tombstones
                .iter_mut()
                .find(|item| item.playlist_id == previous.id && item.track_id == *removed_track)
            {
                existing.removed_at = existing.removed_at.max(changed_at);
            } else {
                track_tombstones.push(playlists::PlaylistTrackTombstone {
                    playlist_id: previous.id.clone(),
                    track_id: removed_track.clone(),
                    removed_at: changed_at,
                });
            }
        }
    }
    track_tombstones.retain(|tombstone| {
        !request.playlists.iter().any(|playlist| {
            playlist.id == tombstone.playlist_id
                && playlist.track_ids.contains(&tombstone.track_id)
                && playlist.modify_time > tombstone.removed_at
        })
    });
    let replacement = playlists::payload_with_tombstones(
        current.device_id,
        request.playlists,
        tombstones,
        track_tombstones,
        current.phone_baseline_established,
    )?;
    playlists::save(&root, &replacement)
}

#[tauri::command]
fn list_adb_devices() -> Result<Vec<adb_sync::AdbDevice>, String> {
    adb_sync::list_devices()
}

#[tauri::command]
async fn preview_adb_sync(
    serial: Option<String>,
    state: State<'_, AppState>,
) -> Result<adb_sync::AdbSyncPreview, String> {
    let inner = state.inner.clone();
    tauri::async_runtime::spawn_blocking(move || adb_sync::preview(inner, serial))
        .await
        .map_err(|error| format!("USB 同步任务异常结束：{error}"))?
}

#[tauri::command]
async fn execute_adb_sync(
    request: adb_sync::ExecuteAdbSyncRequest,
    state: State<'_, AppState>,
) -> Result<adb_sync::ExecuteAdbSyncResult, String> {
    let inner = state.inner.clone();
    tauri::async_runtime::spawn_blocking(move || adb_sync::execute(inner, request))
        .await
        .map_err(|error| format!("USB 同步任务异常结束：{error}"))?
}

#[tauri::command]
fn start_sync_server(state: State<'_, AppState>) -> Result<SyncServerStatus, String> {
    start_server(state.inner.clone())
}

#[tauri::command]
fn stop_sync_server(state: State<'_, AppState>) -> SyncServerStatus {
    stop_server(state.inner.clone())
}

#[tauri::command]
fn get_sync_status(state: State<'_, AppState>) -> SyncServerStatus {
    sync_status(&state.inner)
}

#[tauri::command]
fn open_external_url(url: String) -> Result<(), String> {
    const ALLOWED_PREFIX: &str = "https://github.com/Changxichengxian/ARMusic";
    if !url.starts_with(ALLOWED_PREFIX) {
        return Err("只允许打开 ARMusic 的 GitHub 页面".to_string());
    }

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let mut command = std::process::Command::new("rundll32.exe");
        command
            .arg("url.dll,FileProtocolHandler")
            .arg(&url)
            .creation_flags(0x0800_0000)
            .spawn()
            .map_err(|error| format!("无法打开浏览器：{error}"))?;
        Ok(())
    }

    #[cfg(not(windows))]
    {
        let _ = url;
        Err("当前系统暂不支持打开外部链接".to_string())
    }
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            // A duplicate normal launch should bring back the existing player. A delayed
            // Windows login launch must stay quiet if ARMusic is already running.
            if !args.iter().any(|argument| argument == "--background") {
                let _ = tray_player::show_main_window(app);
            }
        }))
        .manage(initial_app_state())
        .manage(tray_player::TrayPlayerStateStore::default())
        .setup(tray_player::initialize)
        .on_window_event(tray_player::handle_window_event)
        .invoke_handler(tauri::generate_handler![
            choose_music_folder,
            get_library_state,
            get_track_tags,
            save_track_tags,
            record_listening_session,
            get_listening_history,
            get_wishlist,
            save_wishlist,
            migrate_legacy_wishlist,
            get_playlists,
            save_playlists,
            list_adb_devices,
            preview_adb_sync,
            execute_adb_sync,
            start_sync_server,
            stop_sync_server,
            get_sync_status,
            open_external_url,
            tray_player::get_desktop_behavior_preferences,
            tray_player::set_close_to_tray,
            tray_player::set_launch_at_startup,
            tray_player::update_tray_player_state,
            tray_player::get_tray_player_state,
            tray_player::tray_player_action
        ])
        .run(tauri::generate_context!())
        .expect("error while running ARMusic desktop");
}

#[cfg(test)]
mod safety_tests {
    use super::*;

    fn test_track(sync_id: &str, legacy_id: &str, relative_path: &str) -> Track {
        Track {
            sync_id: sync_id.to_string(),
            legacy_sync_ids: vec![legacy_id.to_string()],
            revision_hash: Some("revision".to_string()),
            title: relative_path.to_string(),
            artist: "artist".to_string(),
            album: "album".to_string(),
            work: None,
            genre: None,
            duration_seconds: 180,
            size_bytes: 1,
            relative_path: relative_path.to_string(),
            play_url: None,
            modified_at: None,
            play_seconds: 0,
            last_played_at: None,
            source: "desktop".to_string(),
            local_path: None,
            cover_path: None,
            lyrics: None,
            file_path: None,
        }
    }

    #[test]
    fn duplicate_stable_or_legacy_identity_is_rejected() {
        let stable_duplicate = vec![
            test_track("audio-one", "legacy-one", "one.mp3"),
            test_track("audio-one", "legacy-two", "two.mp3"),
        ];
        assert!(validate_unique_track_identities(&stable_duplicate).is_err());

        let legacy_duplicate = vec![
            test_track("audio-one", "legacy-same", "one.mp3"),
            test_track("audio-two", "legacy-same", "two.mp3"),
        ];
        assert!(validate_unique_track_identities(&legacy_duplicate).is_err());
    }

    #[test]
    fn pairing_tokens_are_256_bit_system_random_values() {
        let first = new_pairing_token().expect("first token");
        let second = new_pairing_token().expect("second token");
        assert_eq!(first.len(), 64);
        assert!(first.bytes().all(|value| value.is_ascii_hexdigit()));
        assert_ne!(first, second);
    }

    #[test]
    fn sync_scope_requires_mp3_and_fifteen_seconds() {
        assert!(track_in_sync_scope(&test_track(
            "audio-one",
            "legacy-one",
            "one.MP3"
        )));
        let mut short = test_track("audio-two", "legacy-two", "short.mp3");
        short.duration_seconds = 14;
        assert!(!track_in_sync_scope(&short));
        assert!(!track_in_sync_scope(&test_track(
            "audio-three",
            "legacy-three",
            "three.flac"
        )));
    }

    #[test]
    fn one_track_tag_refresh_keeps_live_history_and_old_identity_aliases() {
        let mut current = test_track("audio-old", "legacy-old", "song.mp3");
        current.play_seconds = 321;
        current.last_played_at = Some("2026-07-10T21:00:00Z".to_string());
        let mut refreshed = test_track("audio-new", "legacy-new", "song.mp3");

        preserve_live_track_state(&current, &mut refreshed);

        assert_eq!(refreshed.play_seconds, 321);
        assert_eq!(refreshed.last_played_at, current.last_played_at);
        assert_eq!(
            refreshed.legacy_sync_ids,
            vec![
                "audio-old".to_string(),
                "legacy-new".to_string(),
                "legacy-old".to_string()
            ]
        );
    }

    #[test]
    fn safe_directory_rejects_links_before_creating_descendants() {
        let nonce = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .expect("clock")
            .as_nanos();
        let root = env::temp_dir().join(format!("armusic-safe-dir-root-{nonce}"));
        let outside = env::temp_dir().join(format!("armusic-safe-dir-outside-{nonce}"));
        fs::create_dir_all(&root).expect("root");
        fs::create_dir_all(&outside).expect("outside");
        let root = root.canonicalize().expect("canonical root");
        let link = root.join("linked");
        #[cfg(windows)]
        let linked = std::os::windows::fs::symlink_dir(&outside, &link);
        #[cfg(unix)]
        let linked = std::os::unix::fs::symlink(&outside, &link);
        if linked.is_err() {
            let _ = fs::remove_dir_all(&root);
            let _ = fs::remove_dir_all(&outside);
            return;
        }
        let requested = link.join("must-not-be-created");
        assert!(ensure_safe_directory(&root, &requested).is_err());
        assert!(!outside.join("must-not-be-created").exists());
        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_dir_all(&outside);
    }

    #[test]
    fn atomic_replace_captures_old_target_and_can_exchange_it_back() {
        let nonce = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .expect("clock")
            .as_nanos();
        let root = env::temp_dir().join(format!("armusic-replace-file-{nonce}"));
        fs::create_dir_all(&root).expect("root");
        let target = root.join("target.bin");
        let replacement = root.join("replacement.bin");
        let backup = root.join("backup.bin");
        fs::write(&target, b"old").expect("old");
        fs::write(&replacement, b"new").expect("new");
        let mut playback_handle = File::open(&target).expect("open playback source");

        atomic_replace_with_backup(&replacement, &target, &backup).expect("replace");
        assert_eq!(fs::read(&target).expect("target"), b"new");
        assert_eq!(fs::read(&backup).expect("backup"), b"old");
        let mut still_playing_old_bytes = Vec::new();
        playback_handle
            .read_to_end(&mut still_playing_old_bytes)
            .expect("read existing playback handle");
        assert_eq!(still_playing_old_bytes, b"old");

        let failed_new = root.join("failed-new.bin");
        let restore = root.join("restore.bin");
        copy_file_synced(&backup, &restore).expect("copy occupied backup for rollback");
        atomic_replace_with_backup(&restore, &target, &failed_new).expect("rollback");
        assert_eq!(fs::read(&target).expect("restored"), b"old");
        assert_eq!(fs::read(&failed_new).expect("failed new"), b"new");
        let mut playback_after_rollback = Vec::new();
        playback_handle
            .read_to_end(&mut playback_after_rollback)
            .expect("playback handle remains valid after rollback");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn scans_real_library_uncached_when_fixture_is_provided() {
        let Some(root) = env::var_os("ARMUSIC_LIBRARY_FIXTURE").map(PathBuf::from) else {
            return;
        };
        let scan = scan_music_folder_uncached(&root).expect("uncached real-library scan");
        assert!(!scan.tracks.is_empty());
        validate_unique_track_identities(&scan.tracks).expect("unique real-library identities");
    }

    /// Opt-in release QA for the user's connected phone. It is ignored by normal test runs and
    /// requires explicit paths/serial so a developer can never touch a device accidentally.
    #[test]
    #[ignore = "requires an explicitly selected real phone and library"]
    fn real_phone_two_way_sync_preview_and_optional_execute() {
        let root = env::var_os("ARMUSIC_REAL_LIBRARY")
            .map(PathBuf::from)
            .expect("set ARMUSIC_REAL_LIBRARY to the portable Music folder");
        let serial = env::var("ARMUSIC_REAL_DEVICE_SERIAL")
            .expect("set ARMUSIC_REAL_DEVICE_SERIAL to the authorized ADB serial");
        let expected_tracks = env::var("ARMUSIC_REAL_EXPECTED_TRACKS")
            .unwrap_or_else(|_| "343".to_string())
            .parse::<usize>()
            .expect("ARMUSIC_REAL_EXPECTED_TRACKS must be a number");

        let state = Arc::new(AppInner::default());
        *state.library_folder.lock().expect("library folder lock") = Some(root);

        if env::var("ARMUSIC_REAL_SYNC_EXECUTE").as_deref() != Ok("1") {
            let preview =
                adb_sync::preview(state, Some(serial)).expect("build real-device sync preview");
            println!(
                "{}",
                serde_json::to_string_pretty(&preview).expect("serialize preview")
            );
            assert_eq!(preview.desktop_track_count, expected_tracks);
            assert_eq!(preview.phone_track_count, expected_tracks);
            assert!(preview.upload_to_phone.is_empty());
            assert!(preview.download_to_desktop.is_empty());
            assert!(preview.conflicts.is_empty());
            return;
        }
        let request: adb_sync::ExecuteAdbSyncRequest = serde_json::from_value(serde_json::json!({
            "serial": serial,
            "syncSongs": true,
            "syncWishlist": true,
            "syncPlaylists": true,
            "historyMode": "keepOnBoth"
        }))
        .expect("real-device sync request");
        let result = adb_sync::execute(state, request).expect("execute real-device two-way sync");
        println!(
            "{}",
            serde_json::to_string_pretty(&result).expect("serialize result")
        );
        assert_eq!(result.preview.desktop_track_count, expected_tracks);
        assert_eq!(result.preview.phone_track_count, expected_tracks);
        assert!(result.preview.upload_to_phone.is_empty());
        assert!(result.preview.download_to_desktop.is_empty());
        assert!(result.preview.conflicts.is_empty());
        assert_eq!(result.uploaded_to_phone, 0);
        assert_eq!(result.downloaded_to_desktop, 0);
        assert_eq!(result.conflicts_left_untouched, 0);
        assert!(result.wishlist_synced);
        assert!(result.playlists_synced);
    }
}
