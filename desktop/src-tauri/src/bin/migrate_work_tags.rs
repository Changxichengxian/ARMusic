use lofty::config::{ParseOptions, WriteOptions};
use lofty::file::AudioFile;
use lofty::id3::v2::{Frame, FrameId, Id3v2Tag};
use lofty::mpeg::MpegFile;
use lofty::tag::Accessor;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::borrow::Cow;
use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process;
use std::time::{SystemTime, UNIX_EPOCH};

const WORK_DESCRIPTION: &str = "WORK";
const GROUP_DESCRIPTION: &str = "ARMUSIC_GROUP";
const NORMALIZED_LANGUAGE: [u8; 3] = *b"XXX";

#[derive(Debug)]
struct Cli {
    music_dir: PathBuf,
    library: PathBuf,
    write: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Library {
    #[serde(default)]
    songs: Vec<LibrarySong>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LibrarySong {
    #[serde(default)]
    file_path: Option<String>,
    #[serde(default)]
    work: Option<String>,
    #[serde(default)]
    same_song_group: Option<String>,
}

#[derive(Clone, Debug)]
struct DesiredTags {
    basename: String,
    work: Option<String>,
    group: Option<String>,
}

#[derive(Clone, Debug)]
struct WorkSemantics {
    txxx_work: Option<String>,
    content_group: Option<String>,
    values: Vec<String>,
    duplicate: bool,
    conflict: bool,
}

#[derive(Clone, Debug)]
struct PlannedWrite {
    basename: String,
    path: PathBuf,
    work: Option<String>,
    group: Option<String>,
    needs_work: bool,
    needs_group: bool,
    before: TagSnapshot,
}

#[derive(Clone, Debug)]
struct TagSnapshot {
    summary: SnapshotSummary,
    preserved_frame_fingerprints: Vec<String>,
    external_tail: ExternalTailSnapshot,
    semantic_work_values: Vec<String>,
    work_semantic_duplicate: bool,
    work_semantic_conflict: bool,
    group: Option<String>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ExternalTailSnapshot {
    id3v1: Option<Vec<u8>>,
    apev2: Option<Vec<u8>>,
}

#[derive(Clone, Debug)]
struct ExternalTailLayout {
    snapshot: ExternalTailSnapshot,
    apev2_start: Option<u64>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct PictureSummary {
    picture_type: String,
    mime_type: Option<String>,
    description: Option<String>,
    bytes: u64,
    sha256: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct LanguageFrameSummary {
    kind: String,
    language: Option<[u8; 3]>,
    encoding: String,
    flags: String,
    description: Option<String>,
    bytes: u64,
    sha256: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct LanguageNormalization {
    frame_kind: String,
    description: Option<String>,
    from: [u8; 3],
    to: [u8; 3],
    content_sha256: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotSummary {
    title: Option<String>,
    artist: Option<String>,
    album: Option<String>,
    duration_micros: u64,
    frame_count: usize,
    preserved_frame_count: usize,
    preserved_frames_sha256: String,
    cover_count: usize,
    cover_bytes: u64,
    covers: Vec<PictureSummary>,
    lyric_count: usize,
    lyric_bytes: u64,
    lyrics: Vec<LanguageFrameSummary>,
    comment_count: usize,
    comment_bytes: u64,
    comments: Vec<LanguageFrameSummary>,
    unknown_binary_frame_count: usize,
    unknown_binary_frames_sha256: String,
    id3v1_present: bool,
    id3v1_sha256: Option<String>,
    apev2_present: bool,
    apev2_bytes: u64,
    apev2_sha256: Option<String>,
    work: Option<String>,
    txxx_work: Option<String>,
    content_group: Option<String>,
    semantic_work_values: Vec<String>,
    work_semantic_duplicate: bool,
    work_semantic_conflict: bool,
    same_song_group: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ValidationReport {
    passed: bool,
    checks: Vec<String>,
    language_normalizations: Vec<LanguageNormalization>,
    before: SnapshotSummary,
    after: SnapshotSummary,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct PreflightReport {
    source: String,
    temporary_file: String,
    temporary_file_removed: bool,
    passed: bool,
    error: Option<String>,
    validation: Option<ValidationReport>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct FileFailure {
    basename: String,
    error: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Report {
    ok: bool,
    mode: String,
    music_dir: String,
    library: String,
    library_song_count: usize,
    mp3_file_count: usize,
    songs_without_file_path: usize,
    songs_without_work_or_group: usize,
    work_label_count: usize,
    group_label_count: usize,
    matched_candidate_count: usize,
    pending_file_count: usize,
    pending_work_count: usize,
    pending_group_count: usize,
    already_current_count: usize,
    unmatched_mp3_count: usize,
    duplicate_library_basenames: Vec<String>,
    duplicate_music_basenames: Vec<String>,
    missing_music_files: Vec<String>,
    semantic_work_duplicates: Vec<String>,
    semantic_work_conflicts: Vec<String>,
    metadata_read_failures: Vec<FileFailure>,
    blocked_reasons: Vec<String>,
    safe_to_write: bool,
    preflight: Option<PreflightReport>,
    written_file_count: usize,
    write_failures: Vec<FileFailure>,
    cleanup_warnings: Vec<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct FatalReport {
    ok: bool,
    error: String,
}

fn main() {
    let cli = match parse_cli() {
        Ok(Some(cli)) => cli,
        Ok(None) => return,
        Err(error) => {
            print_fatal(&error);
            process::exit(2);
        }
    };

    match run(cli) {
        Ok((report, exit_code)) => {
            match serde_json::to_string_pretty(&report) {
                Ok(json) => println!("{json}"),
                Err(error) => {
                    print_fatal(&format!("序列化统计失败: {error}"));
                    process::exit(1);
                }
            }
            if exit_code != 0 {
                process::exit(exit_code);
            }
        }
        Err(error) => {
            print_fatal(&error);
            process::exit(1);
        }
    }
}

fn print_fatal(error: &str) {
    let report = FatalReport {
        ok: false,
        error: error.to_string(),
    };
    println!(
        "{}",
        serde_json::to_string_pretty(&report)
            .unwrap_or_else(|_| format!("{{\"ok\":false,\"error\":{:?}}}", error))
    );
}

fn print_help() {
    println!(
        "ARMusic MP3 作品标签迁移工具\n\n\
         用法:\n  migrate_work_tags --music-dir <Music目录> --library <armusic-library.json> [--dry-run|--write]\n\n\
         默认为 --dry-run；只有显式传入 --write 才会替换真实 MP3。\n\
         每次都会先复制一首候选歌到系统临时目录，写入后验证原标签、封面、歌词、未知帧和音频时长。"
    );
}

fn parse_cli() -> Result<Option<Cli>, String> {
    let mut args = env::args_os().skip(1);
    let mut music_dir = None;
    let mut library = None;
    let mut write = false;
    let mut mode_seen: Option<&'static str> = None;

    while let Some(arg) = args.next() {
        let arg_text = arg.to_string_lossy();
        match arg_text.as_ref() {
            "--help" | "-h" => {
                print_help();
                return Ok(None);
            }
            "--music-dir" => {
                let value = args
                    .next()
                    .ok_or_else(|| "--music-dir 后缺少路径".to_string())?;
                music_dir = Some(PathBuf::from(value));
            }
            "--library" => {
                let value = args
                    .next()
                    .ok_or_else(|| "--library 后缺少路径".to_string())?;
                library = Some(PathBuf::from(value));
            }
            "--write" => {
                if mode_seen == Some("dry-run") {
                    return Err("--dry-run 与 --write 不能同时使用".to_string());
                }
                write = true;
                mode_seen = Some("write");
            }
            "--dry-run" => {
                if mode_seen == Some("write") {
                    return Err("--dry-run 与 --write 不能同时使用".to_string());
                }
                write = false;
                mode_seen = Some("dry-run");
            }
            _ => return Err(format!("不认识的参数: {arg_text}")),
        }
    }

    Ok(Some(Cli {
        music_dir: music_dir.ok_or_else(|| "必须显式传入 --music-dir".to_string())?,
        library: library.ok_or_else(|| "必须显式传入 --library".to_string())?,
        write,
    }))
}

fn run(cli: Cli) -> Result<(Report, i32), String> {
    let music_dir = canonical_existing_dir(&cli.music_dir, "Music 目录")?;
    let library_path = canonical_existing_file(&cli.library, "曲库 JSON")?;
    let library = read_library(&library_path)?;
    let mp3_paths = collect_mp3_files(&music_dir)?;

    let mut report = Report {
        ok: false,
        mode: if cli.write { "write" } else { "dry-run" }.to_string(),
        music_dir: music_dir.display().to_string(),
        library: library_path.display().to_string(),
        library_song_count: library.songs.len(),
        mp3_file_count: mp3_paths.len(),
        songs_without_file_path: 0,
        songs_without_work_or_group: 0,
        work_label_count: 0,
        group_label_count: 0,
        matched_candidate_count: 0,
        pending_file_count: 0,
        pending_work_count: 0,
        pending_group_count: 0,
        already_current_count: 0,
        unmatched_mp3_count: 0,
        duplicate_library_basenames: Vec::new(),
        duplicate_music_basenames: Vec::new(),
        missing_music_files: Vec::new(),
        semantic_work_duplicates: Vec::new(),
        semantic_work_conflicts: Vec::new(),
        metadata_read_failures: Vec::new(),
        blocked_reasons: Vec::new(),
        safe_to_write: false,
        preflight: None,
        written_file_count: 0,
        write_failures: Vec::new(),
        cleanup_warnings: Vec::new(),
    };

    let mut library_by_basename: BTreeMap<String, Vec<DesiredTags>> = BTreeMap::new();
    let mut all_library_keys = BTreeSet::new();
    for song in &library.songs {
        let Some(file_path) = song.file_path.as_deref() else {
            report.songs_without_file_path += 1;
            continue;
        };
        let Some(basename) = portable_basename(file_path) else {
            report.songs_without_file_path += 1;
            continue;
        };
        let key = basename_key(&basename);
        all_library_keys.insert(key.clone());
        let work = nonempty(song.work.as_deref());
        let group = nonempty(song.same_song_group.as_deref());
        if work.is_some() {
            report.work_label_count += 1;
        }
        if group.is_some() {
            report.group_label_count += 1;
        }
        if work.is_none() && group.is_none() {
            report.songs_without_work_or_group += 1;
        }
        library_by_basename
            .entry(key)
            .or_default()
            .push(DesiredTags {
                basename,
                work,
                group,
            });
    }

    let mut music_by_basename: BTreeMap<String, Vec<PathBuf>> = BTreeMap::new();
    for path in mp3_paths {
        let Some(name) = path.file_name().and_then(|value| value.to_str()) else {
            continue;
        };
        music_by_basename
            .entry(basename_key(name))
            .or_default()
            .push(path);
    }

    report.duplicate_library_basenames = library_by_basename
        .iter()
        .filter(|(_, entries)| entries.len() > 1)
        .map(|(_, entries)| entries[0].basename.clone())
        .collect();
    report.duplicate_music_basenames = music_by_basename
        .iter()
        .filter(|(_, entries)| entries.len() > 1)
        .filter_map(|(_, entries)| {
            entries[0]
                .file_name()
                .map(|name| name.to_string_lossy().to_string())
        })
        .collect();
    report.unmatched_mp3_count = music_by_basename
        .keys()
        .filter(|key| !all_library_keys.contains(*key))
        .count();

    let mut plans = Vec::new();
    for (key, desired_entries) in &library_by_basename {
        if desired_entries.len() != 1 {
            continue;
        }
        let desired = &desired_entries[0];
        if desired.work.is_none() && desired.group.is_none() {
            continue;
        }
        let Some(paths) = music_by_basename.get(key) else {
            report.missing_music_files.push(desired.basename.clone());
            continue;
        };
        if paths.len() != 1 {
            continue;
        }
        let path = paths[0].clone();
        match inspect_mp3(&path) {
            Ok(before) => {
                let needs_work = desired.work.as_deref().is_some_and(|value| {
                    !before
                        .semantic_work_values
                        .iter()
                        .any(|existing| existing == value.trim())
                });
                let needs_group = desired
                    .group
                    .as_deref()
                    .is_some_and(|value| before.group.as_deref() != Some(value));
                report.matched_candidate_count += 1;
                if before.work_semantic_duplicate {
                    report
                        .semantic_work_duplicates
                        .push(desired.basename.clone());
                }
                if before.work_semantic_conflict {
                    report
                        .semantic_work_conflicts
                        .push(desired.basename.clone());
                }
                if needs_work || needs_group {
                    report.pending_file_count += 1;
                    report.pending_work_count += usize::from(needs_work);
                    report.pending_group_count += usize::from(needs_group);
                } else {
                    report.already_current_count += 1;
                }
                plans.push(PlannedWrite {
                    basename: desired.basename.clone(),
                    path,
                    work: desired.work.clone(),
                    group: desired.group.clone(),
                    needs_work,
                    needs_group,
                    before,
                });
            }
            Err(error) => report.metadata_read_failures.push(FileFailure {
                basename: desired.basename.clone(),
                error,
            }),
        }
    }

    if !report.duplicate_library_basenames.is_empty() {
        report
            .blocked_reasons
            .push("曲库 JSON 存在重复 basename".to_string());
    }
    if !report.duplicate_music_basenames.is_empty() {
        report
            .blocked_reasons
            .push("Music 目录存在重复 basename".to_string());
    }
    if !report.missing_music_files.is_empty() {
        report
            .blocked_reasons
            .push("有带 work/group 的曲库条目找不到对应 MP3".to_string());
    }
    if !report.semantic_work_duplicates.is_empty() {
        report
            .blocked_reasons
            .push("存在等值 TXXX:WORK 与 TIT1/ContentGroup 重复，需先精确清理".to_string());
    }
    if !report.semantic_work_conflicts.is_empty() {
        report
            .blocked_reasons
            .push("存在 TXXX:WORK 与 TIT1/ContentGroup 语义冲突，需先人工确认".to_string());
    }
    if !report.metadata_read_failures.is_empty() {
        report
            .blocked_reasons
            .push("有候选 MP3 无法完整读取标签".to_string());
    }

    let preflight_plan = plans
        .iter()
        .filter(|plan| plan.needs_work || plan.needs_group)
        .max_by_key(|plan| {
            (
                usize::from(plan.group.is_some()) + usize::from(plan.work.is_some()),
                usize::from(plan.before.summary.lyric_count > 0),
                usize::from(plan.before.summary.cover_count > 0),
            )
        });

    if let Some(plan) = preflight_plan {
        let preflight = run_preflight(plan);
        if !preflight.passed {
            report
                .blocked_reasons
                .push("临时副本写入保真验证未通过".to_string());
        }
        report.preflight = Some(preflight);
    }

    report.safe_to_write = report.blocked_reasons.is_empty();

    if cli.write && report.safe_to_write {
        for plan in plans
            .iter()
            .filter(|plan| plan.needs_work || plan.needs_group)
        {
            match transactional_write(plan) {
                Ok((_validation, warning)) => {
                    report.written_file_count += 1;
                    if let Some(warning) = warning {
                        report.cleanup_warnings.push(warning);
                    }
                }
                Err(error) => {
                    report.write_failures.push(FileFailure {
                        basename: plan.basename.clone(),
                        error,
                    });
                    break;
                }
            }
        }
    }

    if cli.write && !report.safe_to_write {
        report.ok = false;
        return Ok((report, 3));
    }
    if !report.write_failures.is_empty() {
        report.ok = false;
        return Ok((report, 4));
    }

    report.ok = true;
    Ok((report, 0))
}

fn canonical_existing_dir(path: &Path, label: &str) -> Result<PathBuf, String> {
    let canonical = fs::canonicalize(path)
        .map_err(|error| format!("{label}不存在或无法访问 ({}): {error}", path.display()))?;
    if !canonical.is_dir() {
        return Err(format!("{label}不是目录: {}", canonical.display()));
    }
    Ok(canonical)
}

fn canonical_existing_file(path: &Path, label: &str) -> Result<PathBuf, String> {
    let canonical = fs::canonicalize(path)
        .map_err(|error| format!("{label}不存在或无法访问 ({}): {error}", path.display()))?;
    if !canonical.is_file() {
        return Err(format!("{label}不是文件: {}", canonical.display()));
    }
    Ok(canonical)
}

fn read_library(path: &Path) -> Result<Library, String> {
    let mut file = File::open(path)
        .map_err(|error| format!("打开曲库 JSON 失败 ({}): {error}", path.display()))?;
    let mut data = String::new();
    file.read_to_string(&mut data)
        .map_err(|error| format!("读取曲库 JSON 失败 ({}): {error}", path.display()))?;
    serde_json::from_str(&data)
        .map_err(|error| format!("解析曲库 JSON 失败 ({}): {error}", path.display()))
}

fn collect_mp3_files(root: &Path) -> Result<Vec<PathBuf>, String> {
    let mut pending = vec![root.to_path_buf()];
    let mut files = Vec::new();
    while let Some(dir) = pending.pop() {
        let entries = fs::read_dir(&dir)
            .map_err(|error| format!("读取 Music 子目录失败 ({}): {error}", dir.display()))?;
        for entry in entries {
            let entry = entry.map_err(|error| format!("读取 Music 目录项失败: {error}"))?;
            let file_type = entry.file_type().map_err(|error| {
                format!("读取文件类型失败 ({}): {error}", entry.path().display())
            })?;
            if file_type.is_dir() {
                pending.push(entry.path());
            } else if file_type.is_file() && is_mp3(&entry.path()) {
                files.push(entry.path());
            }
        }
    }
    files.sort();
    Ok(files)
}

fn is_mp3(path: &Path) -> bool {
    path.extension()
        .and_then(|value| value.to_str())
        .is_some_and(|value| value.eq_ignore_ascii_case("mp3"))
}

fn portable_basename(path: &str) -> Option<String> {
    path.replace('\\', "/")
        .rsplit('/')
        .find(|part| !part.trim().is_empty())
        .map(|part| part.trim().to_string())
}

fn basename_key(name: &str) -> String {
    name.trim().to_lowercase()
}

fn nonempty(value: Option<&str>) -> Option<String> {
    value
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned)
}

fn work_semantics(tag: Option<&Id3v2Tag>) -> WorkSemantics {
    let content_group_id = FrameId::Valid(Cow::Borrowed("TIT1"));
    let txxx_work = tag.and_then(|value| nonempty(value.get_user_text(WORK_DESCRIPTION)));
    let content_group = tag.and_then(|value| nonempty(value.get_text(&content_group_id)));
    let mut values = Vec::new();
    for value in [txxx_work.as_deref(), content_group.as_deref()]
        .into_iter()
        .flatten()
    {
        for item in value
            .split('\0')
            .map(str::trim)
            .filter(|item| !item.is_empty())
        {
            if !values.iter().any(|existing| existing == item) {
                values.push(item.to_string());
            }
        }
    }
    let duplicate = txxx_work
        .as_deref()
        .zip(content_group.as_deref())
        .is_some_and(|(left, right)| left.trim() == right.trim());
    let conflict = txxx_work.is_some() && content_group.is_some() && !duplicate;
    WorkSemantics {
        txxx_work,
        content_group,
        values,
        duplicate,
        conflict,
    }
}

fn has_semantic_work(tag: Option<&Id3v2Tag>, desired: &str) -> bool {
    work_semantics(tag)
        .values
        .iter()
        .any(|value| value == desired.trim())
}

fn insert_work_if_missing(tag: &mut Id3v2Tag, desired: &str) -> bool {
    if has_semantic_work(Some(tag), desired) {
        return false;
    }
    tag.insert_user_text(WORK_DESCRIPTION.to_string(), desired.trim().to_string());
    true
}

fn read_external_tail(path: &Path) -> Result<ExternalTailLayout, String> {
    let mut file =
        File::open(path).map_err(|error| format!("读取外部尾部标签时打开文件失败: {error}"))?;
    let file_len = file
        .metadata()
        .map_err(|error| format!("读取文件长度失败: {error}"))?
        .len();
    let id3v1 = if file_len >= 128 {
        let bytes = read_range(&mut file, file_len - 128, 128)?;
        (&bytes[..3] == b"TAG").then_some(bytes)
    } else {
        None
    };
    let tail_end = file_len - id3v1.as_ref().map_or(0, |value| value.len() as u64);
    let mut apev2 = None;
    let mut apev2_start = None;
    if tail_end >= 32 {
        let footer = read_range(&mut file, tail_end - 32, 32)?;
        if &footer[..8] == b"APETAGEX" {
            let size = u32::from_le_bytes(
                footer[12..16]
                    .try_into()
                    .map_err(|_| "APEv2 size 字段长度错误".to_string())?,
            ) as u64;
            if size < 32 || size > tail_end {
                return Err(format!("APEv2 size 非法: {size}"));
            }
            let without_header_start = tail_end - size;
            let start = if without_header_start >= 32 {
                let possible_header = read_range(&mut file, without_header_start - 32, 8)?;
                if possible_header == b"APETAGEX" {
                    without_header_start - 32
                } else {
                    without_header_start
                }
            } else {
                without_header_start
            };
            let bytes = read_range(&mut file, start, tail_end - start)?;
            apev2 = Some(bytes);
            apev2_start = Some(start);
        }
    }
    Ok(ExternalTailLayout {
        snapshot: ExternalTailSnapshot { id3v1, apev2 },
        apev2_start,
    })
}

fn read_range(file: &mut File, offset: u64, len: u64) -> Result<Vec<u8>, String> {
    let len = usize::try_from(len).map_err(|_| "尾部标签太大".to_string())?;
    let mut bytes = vec![0_u8; len];
    file.seek(SeekFrom::Start(offset))
        .map_err(|error| format!("定位尾部标签失败: {error}"))?;
    file.read_exact(&mut bytes)
        .map_err(|error| format!("读取尾部标签失败: {error}"))?;
    Ok(bytes)
}

fn restore_external_tail(path: &Path, desired: &ExternalTailSnapshot) -> Result<(), String> {
    let current = read_external_tail(path)?;
    if &current.snapshot == desired {
        return Ok(());
    }
    let file_len = fs::metadata(path)
        .map_err(|error| format!("读取待恢复文件长度失败: {error}"))?
        .len();
    let without_id3v1 = file_len
        - current
            .snapshot
            .id3v1
            .as_ref()
            .map_or(0, |value| value.len() as u64);
    let content_end = if let Some(start) = current.apev2_start {
        let ape_len = current
            .snapshot
            .apev2
            .as_ref()
            .map_or(0, |value| value.len() as u64);
        if start + ape_len != without_id3v1 {
            return Err("APEv2 不是 ID3v1 之前的连续尾部，已拒绝修改".to_string());
        }
        start
    } else {
        without_id3v1
    };
    let mut file = OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .map_err(|error| format!("打开待恢复文件失败: {error}"))?;
    file.set_len(content_end)
        .map_err(|error| format!("截断待恢复尾部失败: {error}"))?;
    file.seek(SeekFrom::End(0))
        .map_err(|error| format!("定位待恢复文件尾部失败: {error}"))?;
    if let Some(apev2) = &desired.apev2 {
        file.write_all(apev2)
            .map_err(|error| format!("恢复 APEv2 原始字节失败: {error}"))?;
    }
    if let Some(id3v1) = &desired.id3v1 {
        file.write_all(id3v1)
            .map_err(|error| format!("恢复 ID3v1 原始字节失败: {error}"))?;
    }
    file.sync_all()
        .map_err(|error| format!("刷新尾部标签失败: {error}"))?;
    Ok(())
}

fn inspect_mp3(path: &Path) -> Result<TagSnapshot, String> {
    let external_tail = read_external_tail(path)?.snapshot;
    let mut file =
        File::open(path).map_err(|error| format!("打开 MP3 失败 ({}): {error}", path.display()))?;
    let mpeg = <MpegFile as AudioFile>::read_from(&mut file, ParseOptions::default())
        .map_err(|error| format!("解析 MP3 失败 ({}): {error}", path.display()))?;
    Ok(snapshot_mpeg(&mpeg, external_tail))
}

fn snapshot_mpeg(mpeg: &MpegFile, external_tail: ExternalTailSnapshot) -> TagSnapshot {
    let duration_micros =
        u64::try_from(mpeg.properties().duration().as_micros()).unwrap_or(u64::MAX);
    let tag = mpeg.id3v2();
    let title = tag
        .and_then(|value| value.title())
        .map(|value| value.into_owned());
    let artist = tag
        .and_then(|value| value.artist())
        .map(|value| value.into_owned());
    let album = tag
        .and_then(|value| value.album())
        .map(|value| value.into_owned());
    let work_semantics = work_semantics(tag);
    let work = work_semantics.values.first().cloned();
    let group = tag.and_then(|value| value.get_user_text(GROUP_DESCRIPTION));

    let mut covers = Vec::new();
    let mut lyrics = Vec::new();
    let mut comments = Vec::new();
    let mut unknown_binary_fingerprints = Vec::new();
    let mut preserved_frame_fingerprints = Vec::new();
    let mut frame_count = 0usize;

    if let Some(tag) = tag {
        for frame in tag {
            frame_count += 1;
            if !is_target_frame(frame) {
                preserved_frame_fingerprints.push(preservation_fingerprint(frame));
            }
            match frame {
                Frame::Picture(picture_frame) => {
                    let picture = &picture_frame.picture;
                    covers.push(PictureSummary {
                        picture_type: format!("{:?}", picture.pic_type()),
                        mime_type: picture.mime_type().map(|value| value.as_str().to_string()),
                        description: picture.description().map(ToOwned::to_owned),
                        bytes: picture.data().len() as u64,
                        sha256: digest_bytes(picture.data()),
                    });
                }
                Frame::UnsynchronizedText(lyrics_frame) => {
                    lyrics.push(LanguageFrameSummary {
                        kind: "USLT".to_string(),
                        language: Some(lyrics_frame.language),
                        encoding: format!("{:?}", lyrics_frame.encoding),
                        flags: format!("{:?}", frame.flags()),
                        description: Some(lyrics_frame.description.to_string()),
                        bytes: lyrics_frame.content.len() as u64,
                        sha256: digest_bytes(lyrics_frame.content.as_bytes()),
                    });
                }
                Frame::Comment(comment_frame) => {
                    comments.push(LanguageFrameSummary {
                        kind: "COMM".to_string(),
                        language: Some(comment_frame.language),
                        encoding: format!("{:?}", comment_frame.encoding),
                        flags: format!("{:?}", frame.flags()),
                        description: Some(comment_frame.description.to_string()),
                        bytes: comment_frame.content.len() as u64,
                        sha256: digest_bytes(comment_frame.content.as_bytes()),
                    });
                }
                Frame::Binary(binary_frame) => {
                    let binary_digest =
                        language_neutral_binary_digest(frame.id_str(), &binary_frame.data);
                    let fingerprint = format!(
                        "{}:{}:{}",
                        frame.id_str(),
                        binary_frame.data.len(),
                        binary_digest
                    );
                    unknown_binary_fingerprints.push(fingerprint.clone());
                    if frame.id_str() == "SYLT" {
                        lyrics.push(LanguageFrameSummary {
                            kind: "SYLT".to_string(),
                            language: binary_language(&binary_frame.data),
                            encoding: binary_frame
                                .data
                                .first()
                                .map(|value| format!("byte:{value}"))
                                .unwrap_or_else(|| "missing".to_string()),
                            flags: format!("{:?}", frame.flags()),
                            description: None,
                            bytes: binary_frame.data.len() as u64,
                            sha256: language_neutral_binary_digest(
                                frame.id_str(),
                                &binary_frame.data,
                            ),
                        });
                    }
                }
                _ => {}
            }
        }
    }

    covers.sort_by(|left, right| left.sha256.cmp(&right.sha256));
    sort_language_summaries(&mut lyrics);
    sort_language_summaries(&mut comments);
    preserved_frame_fingerprints.sort();
    unknown_binary_fingerprints.sort();

    let cover_bytes = covers.iter().map(|value| value.bytes).sum();
    let lyric_bytes = lyrics.iter().map(|value| value.bytes).sum();
    let comment_bytes = comments.iter().map(|value| value.bytes).sum();
    let preserved_frames_sha256 = digest_joined(&preserved_frame_fingerprints);
    let unknown_binary_frames_sha256 = digest_joined(&unknown_binary_fingerprints);
    let id3v1_sha256 = external_tail.id3v1.as_deref().map(digest_bytes);
    let apev2_sha256 = external_tail.apev2.as_deref().map(digest_bytes);
    let summary = SnapshotSummary {
        title,
        artist,
        album,
        duration_micros,
        frame_count,
        preserved_frame_count: preserved_frame_fingerprints.len(),
        preserved_frames_sha256,
        cover_count: covers.len(),
        cover_bytes,
        covers,
        lyric_count: lyrics.len(),
        lyric_bytes,
        lyrics,
        comment_count: comments.len(),
        comment_bytes,
        comments,
        unknown_binary_frame_count: unknown_binary_fingerprints.len(),
        unknown_binary_frames_sha256,
        id3v1_present: external_tail.id3v1.is_some(),
        id3v1_sha256,
        apev2_present: external_tail.apev2.is_some(),
        apev2_bytes: external_tail
            .apev2
            .as_ref()
            .map_or(0, |value| value.len() as u64),
        apev2_sha256,
        work: work.clone(),
        txxx_work: work_semantics.txxx_work.clone(),
        content_group: work_semantics.content_group.clone(),
        semantic_work_values: work_semantics.values.clone(),
        work_semantic_duplicate: work_semantics.duplicate,
        work_semantic_conflict: work_semantics.conflict,
        same_song_group: group.map(ToOwned::to_owned),
    };

    TagSnapshot {
        summary,
        preserved_frame_fingerprints,
        external_tail,
        semantic_work_values: work_semantics.values,
        work_semantic_duplicate: work_semantics.duplicate,
        work_semantic_conflict: work_semantics.conflict,
        group: group.map(ToOwned::to_owned),
    }
}

fn is_target_frame(frame: &Frame<'_>) -> bool {
    match frame {
        Frame::UserText(user_text) => {
            user_text.description.eq_ignore_ascii_case(WORK_DESCRIPTION)
                || user_text
                    .description
                    .eq_ignore_ascii_case(GROUP_DESCRIPTION)
        }
        _ => false,
    }
}

fn language_is_valid(language: [u8; 3]) -> bool {
    language.iter().all(u8::is_ascii_alphabetic)
}

fn normalized_language(language: [u8; 3]) -> [u8; 3] {
    if language_is_valid(language) {
        language
    } else {
        NORMALIZED_LANGUAGE
    }
}

fn binary_language(data: &[u8]) -> Option<[u8; 3]> {
    data.get(1..4).and_then(|value| value.try_into().ok())
}

fn language_neutral_binary_digest(frame_id: &str, data: &[u8]) -> String {
    if frame_id != "SYLT" || data.len() < 4 {
        return digest_bytes(data);
    }
    let mut canonical = data.to_vec();
    if let Some(language) = binary_language(&canonical) {
        canonical[1..4].copy_from_slice(&normalized_language(language));
    }
    digest_bytes(&canonical)
}

fn preservation_fingerprint(frame: &Frame<'_>) -> String {
    match frame {
        Frame::UnsynchronizedText(value) => digest_bytes(
            format!(
                "USLT|{:?}|{:?}|{:?}|{}:{}|{}:{}",
                value.encoding,
                frame.flags(),
                normalized_language(value.language),
                value.description.len(),
                digest_bytes(value.description.as_bytes()),
                value.content.len(),
                digest_bytes(value.content.as_bytes())
            )
            .as_bytes(),
        ),
        Frame::Comment(value) => digest_bytes(
            format!(
                "COMM|{:?}|{:?}|{:?}|{}:{}|{}:{}",
                value.encoding,
                frame.flags(),
                normalized_language(value.language),
                value.description.len(),
                digest_bytes(value.description.as_bytes()),
                value.content.len(),
                digest_bytes(value.content.as_bytes())
            )
            .as_bytes(),
        ),
        Frame::Binary(value) if frame.id_str() == "SYLT" => digest_bytes(
            format!(
                "SYLT|{:?}|{}:{}",
                frame.flags(),
                value.data.len(),
                language_neutral_binary_digest(frame.id_str(), &value.data)
            )
            .as_bytes(),
        ),
        _ => digest_bytes(format!("{frame:?}").as_bytes()),
    }
}

fn normalize_invalid_languages(tag: &mut Id3v2Tag) -> Vec<LanguageNormalization> {
    let mut normalizations = Vec::new();
    for frame_name in ["USLT", "COMM", "SYLT"] {
        let needs_normalization = (&*tag)
            .into_iter()
            .any(|frame| frame_has_invalid_language(frame, frame_name));
        if !needs_normalization {
            continue;
        }
        let frame_id = FrameId::Valid(Cow::Borrowed(frame_name));
        let mut frames = tag.remove(&frame_id).collect::<Vec<_>>();
        for frame in &mut frames {
            match frame {
                Frame::UnsynchronizedText(value) if !language_is_valid(value.language) => {
                    normalizations.push(LanguageNormalization {
                        frame_kind: "USLT".to_string(),
                        description: Some(value.description.to_string()),
                        from: value.language,
                        to: NORMALIZED_LANGUAGE,
                        content_sha256: digest_bytes(value.content.as_bytes()),
                    });
                    value.language = NORMALIZED_LANGUAGE;
                }
                Frame::Comment(value) if !language_is_valid(value.language) => {
                    normalizations.push(LanguageNormalization {
                        frame_kind: "COMM".to_string(),
                        description: Some(value.description.to_string()),
                        from: value.language,
                        to: NORMALIZED_LANGUAGE,
                        content_sha256: digest_bytes(value.content.as_bytes()),
                    });
                    value.language = NORMALIZED_LANGUAGE;
                }
                Frame::Binary(value) if frame_name == "SYLT" => {
                    let content_sha256 = language_neutral_binary_digest(frame_name, &value.data);
                    let data = value.data.to_mut();
                    if let Some(language) = binary_language(data) {
                        if !language_is_valid(language) {
                            normalizations.push(LanguageNormalization {
                                frame_kind: "SYLT".to_string(),
                                description: None,
                                from: language,
                                to: NORMALIZED_LANGUAGE,
                                content_sha256,
                            });
                            data[1..4].copy_from_slice(&NORMALIZED_LANGUAGE);
                        }
                    }
                }
                _ => {}
            }
        }
        for frame in frames {
            let _ = tag.insert(frame);
        }
    }
    sort_normalizations(&mut normalizations);
    normalizations
}

fn frame_has_invalid_language(frame: &Frame<'_>, frame_name: &str) -> bool {
    match frame {
        Frame::UnsynchronizedText(value) if frame_name == "USLT" => {
            !language_is_valid(value.language)
        }
        Frame::Comment(value) if frame_name == "COMM" => !language_is_valid(value.language),
        Frame::Binary(value) if frame_name == "SYLT" && frame.id_str() == "SYLT" => {
            binary_language(&value.data).is_some_and(|language| !language_is_valid(language))
        }
        _ => false,
    }
}

fn sort_normalizations(values: &mut [LanguageNormalization]) {
    values.sort_by(|left, right| {
        (
            &left.frame_kind,
            &left.description,
            left.from,
            &left.content_sha256,
        )
            .cmp(&(
                &right.frame_kind,
                &right.description,
                right.from,
                &right.content_sha256,
            ))
    });
}

fn sort_language_summaries(values: &mut [LanguageFrameSummary]) {
    values.sort_by(|left, right| {
        (
            &left.kind,
            &left.description,
            &left.encoding,
            &left.flags,
            &left.sha256,
            left.bytes,
            left.language,
        )
            .cmp(&(
                &right.kind,
                &right.description,
                &right.encoding,
                &right.flags,
                &right.sha256,
                right.bytes,
                right.language,
            ))
    });
}

fn normalized_language_summaries(values: &[LanguageFrameSummary]) -> Vec<LanguageFrameSummary> {
    let mut normalized = values.to_vec();
    for value in &mut normalized {
        if let Some(language) = value.language {
            value.language = Some(normalized_language(language));
        }
    }
    sort_language_summaries(&mut normalized);
    normalized
}

fn expected_language_normalizations(summary: &SnapshotSummary) -> Vec<LanguageNormalization> {
    let mut expected = summary
        .lyrics
        .iter()
        .chain(summary.comments.iter())
        .filter_map(|value| {
            let language = value.language?;
            (!language_is_valid(language)).then(|| LanguageNormalization {
                frame_kind: value.kind.clone(),
                description: value.description.clone(),
                from: language,
                to: NORMALIZED_LANGUAGE,
                content_sha256: value.sha256.clone(),
            })
        })
        .collect::<Vec<_>>();
    sort_normalizations(&mut expected);
    expected
}

fn digest_bytes(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut output = String::with_capacity(64);
    for byte in digest {
        use std::fmt::Write as _;
        let _ = write!(&mut output, "{byte:02x}");
    }
    output
}

fn digest_joined(values: &[String]) -> String {
    let mut hasher = Sha256::new();
    for value in values {
        hasher.update((value.len() as u64).to_le_bytes());
        hasher.update(value.as_bytes());
    }
    digest_bytes(&hasher.finalize())
}

fn edit_and_validate(
    path: &Path,
    desired_work: Option<&str>,
    desired_group: Option<&str>,
) -> Result<ValidationReport, String> {
    let before = inspect_mp3(path)?;
    let mut file = OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .map_err(|error| format!("以读写方式打开 MP3 失败 ({}): {error}", path.display()))?;
    let mut mpeg = <MpegFile as AudioFile>::read_from(&mut file, ParseOptions::default())
        .map_err(|error| format!("写入前解析 MP3 失败 ({}): {error}", path.display()))?;
    let mut id3v2 = mpeg
        .id3v2_mut()
        .map(std::mem::take)
        .unwrap_or_else(Id3v2Tag::new);
    if let Some(work) = desired_work {
        let _ = insert_work_if_missing(&mut id3v2, work);
    }
    if let Some(group) = desired_group {
        id3v2.insert_user_text(GROUP_DESCRIPTION.to_string(), group.to_string());
    }
    let language_normalizations = normalize_invalid_languages(&mut id3v2);
    mpeg.set_id3v2(id3v2);
    mpeg.save_to(&mut file, WriteOptions::default())
        .map_err(|error| format!("写入 ID3v2 失败 ({}): {error}", path.display()))?;
    file.sync_all()
        .map_err(|error| format!("刷新 MP3 失败 ({}): {error}", path.display()))?;
    drop(file);
    restore_external_tail(path, &before.external_tail)?;

    let after = inspect_mp3(path)?;
    Ok(validate_snapshots(
        before,
        after,
        desired_work,
        desired_group,
        language_normalizations,
    ))
}

fn validate_snapshots(
    before: TagSnapshot,
    after: TagSnapshot,
    desired_work: Option<&str>,
    desired_group: Option<&str>,
    language_normalizations: Vec<LanguageNormalization>,
) -> ValidationReport {
    let mut checks = Vec::new();
    if before.summary.title != after.summary.title {
        checks.push("title 发生变化".to_string());
    }
    if before.summary.artist != after.summary.artist {
        checks.push("artist 发生变化".to_string());
    }
    if before.summary.album != after.summary.album {
        checks.push("album 发生变化".to_string());
    }
    if before.summary.duration_micros != after.summary.duration_micros {
        checks.push("音频时长发生变化".to_string());
    }
    if before.summary.covers != after.summary.covers {
        checks.push("封面数量、属性或原始字节发生变化".to_string());
    }
    if normalized_language_summaries(&before.summary.lyrics)
        != normalized_language_summaries(&after.summary.lyrics)
    {
        checks.push("歌词数量、内容、description、编码或帧属性发生变化".to_string());
    }
    if normalized_language_summaries(&before.summary.comments)
        != normalized_language_summaries(&after.summary.comments)
    {
        checks.push("评论数量、内容、description、编码或帧属性发生变化".to_string());
    }
    if after
        .summary
        .lyrics
        .iter()
        .chain(after.summary.comments.iter())
        .filter_map(|value| value.language)
        .any(|language| !language_is_valid(language))
    {
        checks.push("写入后仍存在非法的 ID3 三字节 language".to_string());
    }
    let expected_normalizations = expected_language_normalizations(&before.summary);
    if expected_normalizations != language_normalizations {
        checks.push("实际 language 规范化记录与写入前非法帧不一致".to_string());
    }
    if before.summary.unknown_binary_frame_count != after.summary.unknown_binary_frame_count
        || before.summary.unknown_binary_frames_sha256 != after.summary.unknown_binary_frames_sha256
    {
        checks.push("未知/二进制 ID3v2 帧发生变化".to_string());
    }
    if before.external_tail.id3v1 != after.external_tail.id3v1 {
        checks.push("ID3v1 尾部 128 字节未精确保持".to_string());
    }
    if before.external_tail.apev2 != after.external_tail.apev2 {
        checks.push("APEv2 非目标标签原始字节未精确保持".to_string());
    }
    if before.preserved_frame_fingerprints != after.preserved_frame_fingerprints {
        checks.push("除 WORK/ARMUSIC_GROUP 外的 ID3v2 帧发生变化".to_string());
    }
    if let Some(work) = desired_work {
        if !after
            .semantic_work_values
            .iter()
            .any(|value| value == work.trim())
        {
            checks.push("WORK 语义值写入后校验不一致".to_string());
        }
    }
    if !before.work_semantic_duplicate && after.work_semantic_duplicate {
        checks.push("写入引入了等值 TXXX:WORK + TIT1/ContentGroup 重复".to_string());
    }
    if !before.work_semantic_conflict && after.work_semantic_conflict {
        checks.push("写入引入了 TXXX:WORK + TIT1/ContentGroup 语义冲突".to_string());
    }
    if let Some(group) = desired_group {
        if after.group.as_deref() != Some(group) {
            checks.push("TXXX:ARMUSIC_GROUP 写入后校验不一致".to_string());
        }
    }

    ValidationReport {
        passed: checks.is_empty(),
        checks,
        language_normalizations,
        before: before.summary,
        after: after.summary,
    }
}

fn run_preflight(plan: &PlannedWrite) -> PreflightReport {
    let token = unique_token();
    let temp_dir = env::temp_dir().join(format!("armusic-work-tag-preflight-{token}"));
    let test_name = plan
        .path
        .file_name()
        .map(|value| value.to_owned())
        .unwrap_or_else(|| "preflight.mp3".into());
    let test_path = temp_dir.join(test_name);
    let mut validation = None;
    let mut error = None;

    let operation = (|| -> Result<ValidationReport, String> {
        fs::create_dir(&temp_dir)
            .map_err(|cause| format!("创建临时验证目录失败 ({}): {cause}", temp_dir.display()))?;
        fs::copy(&plan.path, &test_path).map_err(|cause| {
            format!(
                "复制临时验证 MP3 失败 ({} -> {}): {cause}",
                plan.path.display(),
                test_path.display()
            )
        })?;
        let validation =
            edit_and_validate(&test_path, plan.work.as_deref(), plan.group.as_deref())?;
        if !validation.passed {
            return Ok(validation);
        }
        Ok(validation)
    })();

    match operation {
        Ok(result) => validation = Some(result),
        Err(cause) => error = Some(cause),
    }

    let mut removed = true;
    if test_path.exists() {
        if let Err(cause) = fs::remove_file(&test_path) {
            removed = false;
            let cleanup = format!("删除临时验证 MP3 失败: {cause}");
            error = Some(match error {
                Some(existing) => format!("{existing}; {cleanup}"),
                None => cleanup,
            });
        }
    }
    if temp_dir.exists() {
        if let Err(cause) = fs::remove_dir(&temp_dir) {
            removed = false;
            let cleanup = format!("删除临时验证目录失败: {cause}");
            error = Some(match error {
                Some(existing) => format!("{existing}; {cleanup}"),
                None => cleanup,
            });
        }
    }

    let passed =
        removed && error.is_none() && validation.as_ref().is_some_and(|result| result.passed);
    PreflightReport {
        source: plan.path.display().to_string(),
        temporary_file: test_path.display().to_string(),
        temporary_file_removed: removed,
        passed,
        error,
        validation,
    }
}

fn transactional_write(plan: &PlannedWrite) -> Result<(ValidationReport, Option<String>), String> {
    let parent = plan
        .path
        .parent()
        .ok_or_else(|| format!("MP3 没有父目录: {}", plan.path.display()))?;
    let file_name = plan
        .path
        .file_name()
        .map(|value| value.to_string_lossy().to_string())
        .ok_or_else(|| format!("MP3 没有文件名: {}", plan.path.display()))?;
    let token = unique_token();
    let stage_path = parent.join(format!(".{file_name}.armusic-work-stage-{token}"));
    let backup_path = parent.join(format!(".{file_name}.armusic-work-backup-{token}"));
    if stage_path.exists() || backup_path.exists() {
        return Err("临时路径意外存在，为避免覆盖已停止".to_string());
    }

    let metadata_before =
        fs::metadata(&plan.path).map_err(|error| format!("读取原 MP3 属性失败: {error}"))?;
    fs::copy(&plan.path, &stage_path)
        .map_err(|error| format!("创建同目录写入副本失败 ({}): {error}", stage_path.display()))?;

    let validation =
        match edit_and_validate(&stage_path, plan.work.as_deref(), plan.group.as_deref()) {
            Ok(validation) if validation.passed => validation,
            Ok(validation) => {
                let _ = fs::remove_file(&stage_path);
                return Err(format!(
                    "写入副本保真验证失败: {}",
                    validation.checks.join("；")
                ));
            }
            Err(error) => {
                let _ = fs::remove_file(&stage_path);
                return Err(error);
            }
        };

    let metadata_now = fs::metadata(&plan.path)
        .map_err(|error| format!("替换前重新读取原 MP3 属性失败: {error}"))?;
    if metadata_before.len() != metadata_now.len()
        || metadata_before.modified().ok() != metadata_now.modified().ok()
    {
        let _ = fs::remove_file(&stage_path);
        return Err("写入副本期间原 MP3 被其他程序修改，已停止替换".to_string());
    }

    fs::rename(&plan.path, &backup_path).map_err(|error| {
        let _ = fs::remove_file(&stage_path);
        format!("为原 MP3 创建短暂备份失败: {error}")
    })?;

    if let Err(error) = fs::rename(&stage_path, &plan.path) {
        let rollback = fs::rename(&backup_path, &plan.path);
        let _ = fs::remove_file(&stage_path);
        return Err(match rollback {
            Ok(()) => format!("替换 MP3 失败，已恢复原文件: {error}"),
            Err(rollback_error) => format!(
                "替换 MP3 失败，且自动恢复失败。原文件仍在 {}: {error}; {rollback_error}",
                backup_path.display()
            ),
        });
    }

    let final_snapshot = inspect_mp3(&plan.path);
    let final_validation = final_snapshot.map(|after| {
        validate_snapshots(
            plan.before.clone(),
            after,
            plan.work.as_deref(),
            plan.group.as_deref(),
            validation.language_normalizations.clone(),
        )
    });
    match final_validation {
        Ok(result) if result.passed => {}
        Ok(result) => {
            rollback_after_replace(&plan.path, &backup_path)?;
            return Err(format!(
                "替换后终验失败，已恢复原文件: {}",
                result.checks.join("；")
            ));
        }
        Err(error) => {
            rollback_after_replace(&plan.path, &backup_path)?;
            return Err(format!("替换后无法重新读取，已恢复原文件: {error}"));
        }
    }

    let warning = fs::remove_file(&backup_path).err().map(|error| {
        format!(
            "MP3 已正确替换，但短暂备份未删除 ({}): {error}",
            backup_path.display()
        )
    });
    Ok((validation, warning))
}

fn rollback_after_replace(current: &Path, backup: &Path) -> Result<(), String> {
    let failed_path = current.with_extension(format!("armusic-failed-{}", unique_token()));
    fs::rename(current, &failed_path)
        .map_err(|error| format!("回滚时移开失败的新文件失败: {error}"))?;
    if let Err(error) = fs::rename(backup, current) {
        let _ = fs::rename(&failed_path, current);
        return Err(format!(
            "回滚原 MP3 失败，备份仍在 {}: {error}",
            backup.display()
        ));
    }
    let _ = fs::remove_file(failed_path);
    Ok(())
}

fn unique_token() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_nanos())
        .unwrap_or_default();
    format!("{}-{nanos}", process::id())
}

#[cfg(test)]
mod tests {
    use super::{
        basename_key, insert_work_if_missing, nonempty, normalize_invalid_languages,
        portable_basename, preservation_fingerprint, read_external_tail, restore_external_tail,
        unique_token, work_semantics, NORMALIZED_LANGUAGE, WORK_DESCRIPTION,
    };
    use lofty::id3::v2::{
        BinaryFrame, CommentFrame, Frame, FrameId, Id3v2Tag, TextInformationFrame,
        UnsynchronizedTextFrame,
    };
    use lofty::TextEncoding;
    use std::borrow::Cow;
    use std::{env, fs};

    #[test]
    fn extracts_android_and_windows_basenames() {
        assert_eq!(
            portable_basename("/storage/emulated/0/Music/ARMusic/a.mp3").as_deref(),
            Some("a.mp3")
        );
        assert_eq!(
            portable_basename(r"C:\\Music\\b.mp3").as_deref(),
            Some("b.mp3")
        );
    }

    #[test]
    fn basename_match_is_case_insensitive() {
        assert_eq!(basename_key(" Song.MP3 "), "song.mp3");
    }

    #[test]
    fn empty_labels_are_ignored() {
        assert_eq!(nonempty(Some("   ")), None);
        assert_eq!(nonempty(Some("  work  ")).as_deref(), Some("work"));
    }

    #[test]
    fn content_group_satisfies_work_without_adding_equal_txxx() {
        let mut tag = Id3v2Tag::new();
        let _ = tag.insert(Frame::Text(TextInformationFrame::new(
            FrameId::Valid(Cow::Borrowed("TIT1")),
            TextEncoding::UTF8,
            "超时空辉夜姬",
        )));

        assert!(!insert_work_if_missing(&mut tag, "超时空辉夜姬"));
        let semantics = work_semantics(Some(&tag));
        assert_eq!(semantics.content_group.as_deref(), Some("超时空辉夜姬"));
        assert_eq!(semantics.txxx_work, None);
        assert!(!semantics.duplicate);

        let _ = tag.insert_user_text(WORK_DESCRIPTION.to_string(), "超时空辉夜姬".to_string());
        assert!(work_semantics(Some(&tag)).duplicate);
    }

    #[test]
    fn restores_id3v1_and_exact_apev2_bytes_after_writer_changes_them() {
        let path = env::temp_dir().join(format!("armusic-tail-test-{}.bin", unique_token()));
        let prefix = b"unchanged-audio-and-other-tail";
        let mut original_ape = Vec::new();
        for flags in [0xA000_0000_u32, 0x8000_0000_u32] {
            let mut block = [0_u8; 32];
            block[..8].copy_from_slice(b"APETAGEX");
            block[8..12].copy_from_slice(&2000_u32.to_le_bytes());
            block[12..16].copy_from_slice(&32_u32.to_le_bytes());
            block[20..24].copy_from_slice(&flags.to_le_bytes());
            original_ape.extend_from_slice(&block);
        }
        let mut id3v1 = [0_u8; 128];
        id3v1[..3].copy_from_slice(b"TAG");
        id3v1[3..7].copy_from_slice(b"test");
        let mut original = prefix.to_vec();
        original.extend_from_slice(&original_ape);
        original.extend_from_slice(&id3v1);
        fs::write(&path, &original).expect("写入尾部标签测试文件");
        let desired = read_external_tail(&path).expect("读取原尾部").snapshot;

        let mut changed = prefix.to_vec();
        let mut changed_ape = original_ape.clone();
        changed_ape[20..24].copy_from_slice(&0xE000_0000_u32.to_le_bytes());
        changed_ape[52..56].copy_from_slice(&0xC000_0000_u32.to_le_bytes());
        changed.extend_from_slice(&changed_ape);
        fs::write(&path, changed).expect("模拟写入器修改 APEv2/删除 ID3v1");

        restore_external_tail(&path, &desired).expect("恢复尾部标签");
        assert_eq!(fs::read(&path).expect("读取恢复结果"), original);
        fs::remove_file(path).expect("清理尾部标签测试文件");
    }

    #[test]
    fn normalizes_only_invalid_language_bytes_and_preserves_payloads() {
        let mut tag = Id3v2Tag::new();
        let _ = tag.insert(Frame::UnsynchronizedText(UnsynchronizedTextFrame::new(
            TextEncoding::UTF8,
            *b"   ",
            "lyrics-description",
            "[00:01.00]歌词内容",
        )));
        let _ = tag.insert(Frame::Comment(CommentFrame::new(
            TextEncoding::UTF8,
            [0, 1, 2],
            "comment-description",
            "comment-content",
        )));
        let sylt_data = vec![3, b' ', b' ', b' ', 2, 1, 0, 1, 2, 3, 4, 5];
        let _ = tag.insert(Frame::Binary(BinaryFrame::new(
            FrameId::Valid(Cow::Borrowed("SYLT")),
            sylt_data,
        )));

        let mut before = (&tag)
            .into_iter()
            .map(preservation_fingerprint)
            .collect::<Vec<_>>();
        before.sort();
        let normalized = normalize_invalid_languages(&mut tag);
        let mut after = (&tag)
            .into_iter()
            .map(preservation_fingerprint)
            .collect::<Vec<_>>();
        after.sort();

        assert_eq!(normalized.len(), 3);
        assert_eq!(before, after);
        for frame in &tag {
            match frame {
                Frame::UnsynchronizedText(value) => {
                    assert_eq!(value.language, NORMALIZED_LANGUAGE);
                    assert_eq!(&*value.description, "lyrics-description");
                    assert_eq!(&*value.content, "[00:01.00]歌词内容");
                }
                Frame::Comment(value) => {
                    assert_eq!(value.language, NORMALIZED_LANGUAGE);
                    assert_eq!(&*value.description, "comment-description");
                    assert_eq!(&*value.content, "comment-content");
                }
                Frame::Binary(value) if frame.id_str() == "SYLT" => {
                    assert_eq!(&value.data[1..4], &NORMALIZED_LANGUAGE);
                    assert_eq!(&value.data[4..], &[2, 1, 0, 1, 2, 3, 4, 5]);
                }
                _ => {}
            }
        }
    }
}
