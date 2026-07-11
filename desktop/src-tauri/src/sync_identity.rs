use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::UNIX_EPOCH;

const SAMPLE_SIZE: usize = 64 * 1024;
const HASH_BUFFER_SIZE: usize = 1024 * 1024;
const CACHE_VERSION: u8 = 3;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct AudioIdentity {
    /// Stable across ordinary ID3v2/ID3v1/APEv2/Lyrics3 metadata edits.
    pub stable_id: String,
    /// Changes when the file bytes change. Used for conflict detection only.
    pub revision_hash: String,
    /// Identifier used by ARMusic 1.4.1 and older clients.
    pub legacy_id: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct CacheEntry {
    size: u64,
    modified_nanos: u128,
    identity: AudioIdentity,
}

#[derive(Default, Deserialize, Serialize)]
struct CacheFile {
    version: u8,
    entries: HashMap<String, CacheEntry>,
}

struct CacheState {
    file: CacheFile,
    dirty: bool,
}

static IDENTITY_CACHE: OnceLock<Mutex<CacheState>> = OnceLock::new();

pub fn create_audio_identity(path: &Path) -> Result<AudioIdentity, String> {
    let metadata = fs::metadata(path).map_err(|error| error.to_string())?;
    let size = metadata.len();
    let modified_nanos = metadata
        .modified()
        .ok()
        .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
        .map(|value| value.as_nanos())
        .unwrap_or(0);
    let key = path
        .canonicalize()
        .unwrap_or_else(|_| path.to_path_buf())
        .to_string_lossy()
        .to_string();
    if let Some(identity) = cache_state()
        .lock()
        .expect("identity cache lock")
        .file
        .entries
        .get(&key)
        .filter(|entry| entry.size == size && entry.modified_nanos == modified_nanos)
        .map(|entry| entry.identity.clone())
    {
        return Ok(identity);
    }

    let identity = compute_audio_identity(path, size)?;
    let mut cache = cache_state().lock().expect("identity cache lock");
    cache.file.entries.insert(
        key,
        CacheEntry {
            size,
            modified_nanos,
            identity: identity.clone(),
        },
    );
    cache.dirty = true;
    Ok(identity)
}

pub fn create_audio_identity_uncached(path: &Path) -> Result<AudioIdentity, String> {
    let size = fs::metadata(path).map_err(|error| error.to_string())?.len();
    compute_audio_identity(path, size)
}

/// Recomputes every byte even when size and mtime are unchanged, then replaces
/// the cached entry. Sync planning uses this to fail closed after out-of-process
/// file changes that could otherwise preserve both metadata fields.
pub fn refresh_audio_identity(path: &Path) -> Result<AudioIdentity, String> {
    let metadata = fs::metadata(path).map_err(|error| error.to_string())?;
    let size = metadata.len();
    let modified_nanos = metadata
        .modified()
        .ok()
        .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
        .map(|value| value.as_nanos())
        .unwrap_or(0);
    let identity = compute_audio_identity(path, size)?;
    let key = path
        .canonicalize()
        .unwrap_or_else(|_| path.to_path_buf())
        .to_string_lossy()
        .to_string();
    let mut cache = cache_state().lock().expect("identity cache lock");
    cache.file.entries.insert(
        key,
        CacheEntry {
            size,
            modified_nanos,
            identity: identity.clone(),
        },
    );
    cache.dirty = true;
    Ok(identity)
}

fn compute_audio_identity(path: &Path, size: u64) -> Result<AudioIdentity, String> {
    let (audio_start, audio_end) = audio_payload_bounds(path, size)?;
    let (stable_id, revision_hash) = full_hashes(path, size, audio_start, audio_end)?;
    let legacy_id = legacy_sync_id(path, size)?;

    Ok(AudioIdentity {
        stable_id,
        revision_hash,
        legacy_id,
    })
}

fn full_hashes(
    path: &Path,
    size: u64,
    audio_start: u64,
    audio_end: u64,
) -> Result<(String, String), String> {
    let payload_len = audio_end.saturating_sub(audio_start);
    let mut file = File::open(path).map_err(|error| error.to_string())?;
    let mut stable = Sha256::new();
    stable.update(b"armusic-audio-v2\0");
    stable.update(payload_len.to_string().as_bytes());
    let mut revision = Sha256::new();
    revision.update(b"armusic-file-v1\0");
    revision.update(size.to_string().as_bytes());
    let mut buffer = vec![0u8; HASH_BUFFER_SIZE];
    let mut position = 0u64;

    loop {
        let read = file.read(&mut buffer).map_err(|error| error.to_string())?;
        if read == 0 {
            break;
        }
        revision.update(&buffer[..read]);
        let chunk_end = position + read as u64;
        let overlap_start = position.max(audio_start);
        let overlap_end = chunk_end.min(audio_end);
        if overlap_start < overlap_end {
            let from = (overlap_start - position) as usize;
            let to = (overlap_end - position) as usize;
            stable.update(&buffer[from..to]);
        }
        position = chunk_end;
    }
    if position != size {
        return Err("音乐文件在计算校验值时发生变化，请重试".to_string());
    }

    Ok((
        format!("audio-sha256-{:x}", stable.finalize())[..45].to_string(),
        format!("file-sha256-{:x}", revision.finalize())[..44].to_string(),
    ))
}

pub fn flush_identity_cache() -> Result<(), String> {
    let mut state = cache_state().lock().expect("identity cache lock");
    if !state.dirty {
        return Ok(());
    }
    let path = cache_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let temp = path.with_extension("json.tmp");
    let bytes = serde_json::to_vec(&state.file).map_err(|error| error.to_string())?;
    let mut output = File::create(&temp).map_err(|error| error.to_string())?;
    output
        .write_all(&bytes)
        .map_err(|error| error.to_string())?;
    output.sync_all().map_err(|error| error.to_string())?;
    if path.exists() {
        fs::remove_file(&path).map_err(|error| error.to_string())?;
    }
    fs::rename(&temp, &path).map_err(|error| error.to_string())?;
    state.dirty = false;
    Ok(())
}

fn cache_state() -> &'static Mutex<CacheState> {
    IDENTITY_CACHE.get_or_init(|| {
        let file = fs::read(cache_path())
            .ok()
            .and_then(|bytes| serde_json::from_slice::<CacheFile>(&bytes).ok())
            .filter(|cache| cache.version == CACHE_VERSION)
            .unwrap_or_else(|| CacheFile {
                version: CACHE_VERSION,
                entries: HashMap::new(),
            });
        Mutex::new(CacheState { file, dirty: false })
    })
}

fn cache_path() -> PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join("ARMusic")
        .join("identity-cache-v3.json")
}

fn legacy_sync_id(path: &Path, size: u64) -> Result<String, String> {
    let mut file = File::open(path).map_err(|error| error.to_string())?;
    let mut hash = Sha256::new();
    hash.update(size.to_string().as_bytes());
    hash.update(
        path.file_name()
            .map(|value| value.to_string_lossy().to_ascii_lowercase())
            .unwrap_or_default()
            .as_bytes(),
    );

    let first_len = SAMPLE_SIZE.min(size as usize);
    if first_len > 0 {
        let mut first = vec![0u8; first_len];
        file.read_exact(&mut first)
            .map_err(|error| error.to_string())?;
        hash.update(first);
    }

    if size > SAMPLE_SIZE as u64 {
        let last_len = SAMPLE_SIZE.min(size as usize);
        let mut last = vec![0u8; last_len];
        file.seek(SeekFrom::Start(size.saturating_sub(last_len as u64)))
            .map_err(|error| error.to_string())?;
        file.read_exact(&mut last)
            .map_err(|error| error.to_string())?;
        hash.update(last);
    }

    Ok(format!("sha256-{:x}", hash.finalize())[..39].to_string())
}

fn audio_payload_bounds(path: &Path, size: u64) -> Result<(u64, u64), String> {
    if path
        .extension()
        .and_then(|value| value.to_str())
        .map(|value| value.eq_ignore_ascii_case("mp3"))
        != Some(true)
    {
        return Ok((0, size));
    }

    let mut file = File::open(path).map_err(|error| error.to_string())?;
    let mut start = 0u64;
    let mut end = size;

    if size >= 10 {
        let mut header = [0u8; 10];
        file.read_exact(&mut header)
            .map_err(|error| error.to_string())?;
        if &header[..3] == b"ID3" && header[6..10].iter().all(|value| value & 0x80 == 0) {
            let tag_size = ((header[6] as u64) << 21)
                | ((header[7] as u64) << 14)
                | ((header[8] as u64) << 7)
                | header[9] as u64;
            let footer_size = if header[5] & 0x10 != 0 { 10 } else { 0 };
            start = (10 + tag_size + footer_size).min(size);
        }
    }

    // Tail tags can be stacked. Peel the known containers until no more match.
    loop {
        let before = end;

        if end >= 128 && read_at(&mut file, end - 128, 3)? == b"TAG" {
            end -= 128;
        }

        if end >= 32 {
            let footer = read_at(&mut file, end - 32, 32)?;
            if &footer[..8] == b"APETAGEX" {
                let ape_size =
                    u32::from_le_bytes(footer[12..16].try_into().expect("four bytes")) as u64;
                if ape_size >= 32 && ape_size <= end.saturating_sub(start) {
                    end -= ape_size;
                }
            }
        }

        if end >= 15 && read_at(&mut file, end - 9, 9)? == b"LYRICS200" {
            let size_text = read_at(&mut file, end - 15, 6)?;
            if let Ok(size_text) = std::str::from_utf8(&size_text) {
                if let Ok(lyrics_size) = size_text.parse::<u64>() {
                    let total = lyrics_size.saturating_add(15);
                    if total <= end.saturating_sub(start) {
                        end -= total;
                    }
                }
            }
        }

        if end == before {
            break;
        }
    }

    if end <= start {
        // A malformed tag must never collapse every file to the same identity.
        return Ok((0, size));
    }
    Ok((start, end))
}

fn read_at(file: &mut File, offset: u64, len: usize) -> Result<Vec<u8>, String> {
    let mut bytes = vec![0u8; len];
    file.seek(SeekFrom::Start(offset))
        .map_err(|error| error.to_string())?;
    file.read_exact(&mut bytes)
        .map_err(|error| error.to_string())?;
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn temp_file(name: &str, bytes: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!(
            "armusic-identity-{name}-{}-{}.mp3",
            std::process::id(),
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .expect("clock")
                .as_nanos()
        ));
        File::create(&path)
            .expect("create")
            .write_all(bytes)
            .expect("write");
        path
    }

    use std::time::SystemTime;

    #[test]
    fn id3_edits_keep_stable_identity_but_change_revision() {
        let audio = vec![0x55; SAMPLE_SIZE * 2 + 17];
        let mut first = b"ID3\x04\x00\x00\x00\x00\x00\x04old!".to_vec();
        first.extend_from_slice(&audio);
        first.extend_from_slice(b"TAG");
        first.extend_from_slice(&[0u8; 125]);

        let mut second = b"ID3\x04\x00\x00\x00\x00\x00\x08new tag!".to_vec();
        second.extend_from_slice(&audio);

        let first_path = temp_file("first", &first);
        let second_path = temp_file("second", &second);
        let first_id = create_audio_identity(&first_path).expect("first identity");
        let second_id = create_audio_identity(&second_path).expect("second identity");
        let _ = fs::remove_file(first_path);
        let _ = fs::remove_file(second_path);

        assert_eq!(first_id.stable_id, second_id.stable_id);
        assert_ne!(first_id.revision_hash, second_id.revision_hash);
        assert_ne!(first_id.legacy_id, second_id.legacy_id);
    }

    #[test]
    fn middle_audio_change_changes_full_identity_and_revision() {
        let mut first = vec![0x11; SAMPLE_SIZE * 4];
        let mut second = first.clone();
        second[SAMPLE_SIZE * 2] = 0x99;
        let first_path = temp_file("middle-first", &first);
        let second_path = temp_file("middle-second", &second);
        let first_id = compute_audio_identity(&first_path, first.len() as u64).expect("first");
        let second_id = compute_audio_identity(&second_path, second.len() as u64).expect("second");
        let _ = fs::remove_file(first_path);
        let _ = fs::remove_file(second_path);
        first.clear();

        assert_ne!(first_id.stable_id, second_id.stable_id);
        assert_ne!(first_id.revision_hash, second_id.revision_hash);
    }
}
