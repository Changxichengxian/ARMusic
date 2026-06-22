use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use chrono::{DateTime, SecondsFormat, Utc};
use local_ip_address::list_afinet_netifas;
use percent_encoding::{percent_decode_str, utf8_percent_encode, NON_ALPHANUMERIC};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::env;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::net::{IpAddr, TcpStream, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime};
use tauri::State;
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

const AUDIO_EXTENSIONS: &[&str] = &["mp3", "flac", "wav", "m4a", "aac", "ogg", "ape", "wma"];
const DEFAULT_SYNC_PORT: u16 = 49321;
const DISCOVERY_PORT: u16 = 49322;
const DISCOVERY_REQUEST: &[u8] = b"ARMUSIC_DISCOVER_V1";
const MAX_SCAN_FILES: usize = 5000;

#[derive(Default)]
struct AppState {
    inner: Arc<AppInner>,
}

#[derive(Default)]
struct AppInner {
    library_folder: Mutex<Option<PathBuf>>,
    tracks: Mutex<Vec<Track>>,
    sync_server: Mutex<Option<SyncServerHandle>>,
}

struct SyncServerHandle {
    port: u16,
    stop: Arc<AtomicBool>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Track {
    sync_id: String,
    title: String,
    artist: String,
    album: String,
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
    #[serde(skip)]
    file_path: Option<PathBuf>,
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
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DiscoveryResponse {
    kind: String,
    name: String,
    port: u16,
    addresses: Vec<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SyncManifest {
    library_id: String,
    device_name: String,
    generated_at: String,
    tracks: Vec<Track>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ImportResult {
    folder_path: String,
    scanned_at: String,
    track: Track,
    track_count: usize,
}

fn now_iso() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
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
            if artist.is_empty() { "未知歌手" } else { artist }.to_string(),
            if title.trim().is_empty() { file_name } else { title.trim().to_string() },
        );
    }

    ("未知歌手".to_string(), file_name)
}

fn create_sync_id(file_path: &Path, size: u64) -> Result<String, String> {
    let mut file = File::open(file_path).map_err(|error| error.to_string())?;
    let mut hash = Sha256::new();
    let chunk_size = 64 * 1024usize;

    hash.update(size.to_string().as_bytes());
    hash.update(
        file_path
            .file_name()
            .map(|value| value.to_string_lossy().to_ascii_lowercase())
            .unwrap_or_default()
            .as_bytes(),
    );

    let first_len = chunk_size.min(size as usize);
    if first_len > 0 {
        let mut first = vec![0u8; first_len];
        file.read_exact(&mut first).map_err(|error| error.to_string())?;
        hash.update(first);
    }

    if size > chunk_size as u64 {
        let last_len = chunk_size.min(size as usize);
        let mut last = vec![0u8; last_len];
        file.seek(SeekFrom::Start(size.saturating_sub(last_len as u64)))
            .map_err(|error| error.to_string())?;
        file.read_exact(&mut last).map_err(|error| error.to_string())?;
        hash.update(last);
    }

    Ok(format!("sha256-{:x}", hash.finalize())[..39].to_string())
}

fn create_track(file_path: &Path, root_path: &Path) -> Result<Track, String> {
    let metadata = fs::metadata(file_path).map_err(|error| error.to_string())?;
    let (artist, title) = guess_track_name(file_path);
    let album = file_path
        .parent()
        .and_then(|value| value.file_name())
        .map(|value| value.to_string_lossy().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "本地音乐".to_string());

    Ok(Track {
        sync_id: create_sync_id(file_path, metadata.len())?,
        title,
        artist,
        album,
        duration_seconds: 0,
        size_bytes: metadata.len(),
        relative_path: relative_path(file_path, root_path)?,
        play_url: None,
        modified_at: metadata.modified().ok().map(system_time_iso),
        play_seconds: 0,
        last_played_at: None,
        source: "desktop".to_string(),
        local_path: None,
        file_path: Some(file_path.to_path_buf()),
    })
}

fn walk_directory(root_path: &Path, current_path: &Path, files: &mut Vec<PathBuf>) -> Result<(), String> {
    if files.len() >= MAX_SCAN_FILES {
        return Ok(());
    }

    let entries = fs::read_dir(current_path).map_err(|error| error.to_string())?;
    for entry in entries {
        let entry = entry.map_err(|error| error.to_string())?;
        let path = entry.path();
        let file_name = entry.file_name().to_string_lossy().to_string();

        if file_name.starts_with('.') {
            continue;
        }

        let file_type = entry.file_type().map_err(|error| error.to_string())?;
        if file_type.is_dir() {
            walk_directory(root_path, &path, files)?;
        } else if file_type.is_file() && is_audio_file(&path) {
            files.push(path);
        }

        if files.len() >= MAX_SCAN_FILES {
            break;
        }
    }

    let _ = root_path;
    Ok(())
}

fn scan_music_folder(folder_path: &Path) -> Result<LibraryScanResult, String> {
    let root_path = folder_path
        .canonicalize()
        .map_err(|error| format!("读取音乐文件夹失败：{error}"))?;

    if !root_path.is_dir() {
        return Err("请选择一个音乐文件夹".to_string());
    }

    let mut files = Vec::new();
    walk_directory(&root_path, &root_path, &mut files)?;

    let mut tracks = Vec::new();
    for file_path in files {
        match create_track(&file_path, &root_path) {
            Ok(track) => tracks.push(track),
            Err(error) => eprintln!("Failed to scan {}: {error}", file_path.display()),
        }
    }

    tracks.sort_by(|a, b| a.relative_path.cmp(&b.relative_path));

    Ok(LibraryScanResult {
        canceled: Some(false),
        folder_path: Some(root_path.to_string_lossy().to_string()),
        scanned_at: Some(now_iso()),
        tracks,
    })
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
    let tracks = state
        .tracks
        .lock()
        .expect("tracks lock")
        .iter()
        .map(track_for_manifest)
        .collect::<Vec<_>>();

    SyncManifest {
        library_id: format!("desktop-{}", device_name.to_lowercase().replace(' ', "-")),
        device_name,
        generated_at: now_iso(),
        tracks,
    }
}

fn get_lan_addresses(port: u16) -> Vec<String> {
    let mut addresses = list_afinet_netifas()
        .map(|items| {
            items
                .into_iter()
                .filter_map(|(_, ip)| match ip {
                    IpAddr::V4(ipv4) if !ipv4.is_loopback() => Some(format!("http://{ipv4}:{port}")),
                    _ => None,
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();

    if addresses.is_empty() {
        addresses.push(format!("http://127.0.0.1:{port}"));
    }

    addresses.sort();
    addresses.dedup();
    addresses
}

fn sync_status(state: &AppInner) -> SyncServerStatus {
    let port = state
        .sync_server
        .lock()
        .expect("sync server lock")
        .as_ref()
        .map(|handle| handle.port);

    SyncServerStatus {
        running: port.is_some(),
        port,
        addresses: port.map(get_lan_addresses).unwrap_or_default(),
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

fn unique_file_path(target: &Path) -> PathBuf {
    if !path_exists(target) {
        return target.to_path_buf();
    }

    let parent = target.parent().unwrap_or_else(|| Path::new(""));
    let stem = target
        .file_stem()
        .map(|value| value.to_string_lossy().to_string())
        .unwrap_or_else(|| "track".to_string());
    let ext = target.extension().map(|value| value.to_string_lossy().to_string());

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

    let parent = target_path
        .parent()
        .ok_or_else(|| "上传路径不安全".to_string())?;
    fs::create_dir_all(parent).map_err(|error| error.to_string())?;

    let final_path = unique_file_path(&target_path);
    if !final_path.starts_with(&root) {
        return Err("上传路径不安全".to_string());
    }

    let mut output = File::create(&final_path).map_err(|error| error.to_string())?;
    std::io::copy(reader, &mut output).map_err(|error| error.to_string())?;

    create_track(&final_path, &root)
}

fn handle_track_get(request: Request, state: Arc<AppInner>, sync_id: &str) {
    let track = state
        .tracks
        .lock()
        .expect("tracks lock")
        .iter()
        .find(|track| track.sync_id == sync_id)
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

    let root_path = state
        .library_folder
        .lock()
        .expect("library folder lock")
        .clone();

    let Some(root_path) = root_path else {
        write_error(request, 400, "请先在桌面端选择音乐文件夹");
        return;
    };

    let imported = import_track_from_reader(&root_path, &remote_track, request.as_reader());
    let Ok(imported) = imported else {
        write_error(request, 500, "接收上传失败");
        return;
    };

    let scan = scan_music_folder(&root_path);
    let Ok(scan) = scan else {
        write_error(request, 500, "歌曲已接收，但重新扫描失败");
        return;
    };

    let track_count = scan.tracks.len();
    *state.tracks.lock().expect("tracks lock") = scan.tracks;

    let result = ImportResult {
        folder_path: root_path.to_string_lossy().to_string(),
        scanned_at: now_iso(),
        track: track_for_manifest(&imported),
        track_count,
    };

    write_json(request, 201, &serde_json::json!({ "ok": true, "result": result }));
}

fn handle_request(request: Request, state: Arc<AppInner>) {
    if request.method() == &Method::Options {
        let response = Response::empty(StatusCode(204))
            .with_header(cors_header())
            .with_header(
                Header::from_bytes("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    .expect("valid header"),
            )
            .with_header(
                Header::from_bytes("Access-Control-Allow-Headers", "Content-Type, X-ARMusic-Track")
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

    if url == "/manifest" {
        if request.method() != &Method::Get {
            write_error(request, 405, "只支持 GET");
            return;
        }
        write_json(request, 200, &create_manifest(&state));
        return;
    }

    if let Some(raw_sync_id) = url.strip_prefix("/tracks/") {
        let sync_id = percent_decode_str(raw_sync_id)
            .decode_utf8_lossy()
            .to_string();

        match *request.method() {
            Method::Get => handle_track_get(request, state, &sync_id),
            Method::Post => handle_track_post(request, state, &sync_id),
            _ => write_error(request, 405, "只支持 GET 或 POST"),
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

    let discovery_stop = stop.clone();
    thread::spawn(move || run_discovery_server(port, discovery_stop));

    *guard = Some(SyncServerHandle { port, stop });
    drop(guard);
    Ok(sync_status(&state))
}

fn run_discovery_server(port: u16, stop: Arc<AtomicBool>) {
    let Ok(socket) = UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)) else {
        eprintln!("Discovery server failed to bind UDP {DISCOVERY_PORT}");
        return;
    };

    let _ = socket.set_broadcast(true);
    let _ = socket.set_read_timeout(Some(Duration::from_millis(250)));
    let mut buffer = [0u8; 512];

    while !stop.load(Ordering::SeqCst) {
        let Ok((size, peer)) = socket.recv_from(&mut buffer) else {
            continue;
        };

        if &buffer[..size] != DISCOVERY_REQUEST {
            continue;
        }

        let response = DiscoveryResponse {
            kind: "armusic-sync".to_string(),
            name: desktop_name(),
            port,
            addresses: get_lan_addresses(port),
        };

        let Ok(payload) = serde_json::to_vec(&response) else {
            continue;
        };
        let _ = socket.send_to(&payload, peer);
    }
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

#[tauri::command]
fn choose_music_folder(state: State<'_, AppState>) -> Result<LibraryScanResult, String> {
    let Some(folder_path) = rfd::FileDialog::new()
        .set_title("选择音乐文件夹")
        .pick_folder()
    else {
        return Ok(library_result(&state.inner, true));
    };

    let mut result = scan_music_folder(&folder_path)?;
    *state.inner.library_folder.lock().expect("library folder lock") =
        result.folder_path.as_ref().map(PathBuf::from);
    *state.inner.tracks.lock().expect("tracks lock") = result.tracks.clone();
    result.tracks = result.tracks.iter().map(track_for_ui).collect();

    Ok(result)
}

#[tauri::command]
fn get_library_state(state: State<'_, AppState>) -> LibraryScanResult {
    library_result(&state.inner, false)
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

fn main() {
    tauri::Builder::default()
        .manage(AppState::default())
        .invoke_handler(tauri::generate_handler![
            choose_music_folder,
            get_library_state,
            start_sync_server,
            stop_sync_server,
            get_sync_status
        ])
        .run(tauri::generate_context!())
        .expect("error while running ARMusic desktop");
}
