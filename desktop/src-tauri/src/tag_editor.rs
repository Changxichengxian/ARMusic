use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use lofty::config::{ParseOptions, WriteOptions};
use lofty::file::AudioFile;
use lofty::id3::v2::{Frame, FrameId, Id3v2Tag, TextInformationFrame, UnsynchronizedTextFrame};
use lofty::mpeg::MpegFile;
use lofty::picture::{Picture, PictureType};
use lofty::tag::{items::Timestamp, Accessor};
use lofty::TextEncoding;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::borrow::Cow;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

const WORK_DESCRIPTION: &str = "WORK";
const GROUP_DESCRIPTION: &str = "ARMUSIC_GROUP";
const NORMALIZED_LANGUAGE: [u8; 3] = *b"XXX";
const MAX_COVER_BYTES: usize = 20 * 1024 * 1024;

const TITLE_ID: FrameId<'static> = FrameId::Valid(Cow::Borrowed("TIT2"));
const ARTIST_ID: FrameId<'static> = FrameId::Valid(Cow::Borrowed("TPE1"));
const ALBUM_ID: FrameId<'static> = FrameId::Valid(Cow::Borrowed("TALB"));
const GENRE_ID: FrameId<'static> = FrameId::Valid(Cow::Borrowed("TCON"));
const WORK_FALLBACK_ID: FrameId<'static> = FrameId::Valid(Cow::Borrowed("TIT1"));

#[derive(Clone, Debug)]
pub(crate) struct TagFallback {
    pub title: String,
    pub artist: String,
    pub album: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TrackTagData {
    pub sync_id: String,
    pub file_name: String,
    pub relative_path: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub work: String,
    pub same_song_group: String,
    pub genre: String,
    pub date: String,
    pub lyrics: String,
    pub has_embedded_cover: bool,
    pub cover_data_url: Option<String>,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase")]
pub(crate) enum CoverAction {
    Keep,
    Remove,
    Replace,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UpdateTrackTagsRequest {
    pub sync_id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub work: String,
    pub same_song_group: String,
    pub genre: String,
    pub date: String,
    pub lyrics: String,
    pub cover_action: CoverAction,
    pub cover_data_base64: Option<String>,
}

#[derive(Clone, Debug)]
struct SemanticMetadata {
    title: String,
    artist: String,
    album: String,
    work: String,
    same_song_group: String,
    genre: String,
    date: String,
    lyrics: String,
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

#[derive(Clone, Debug)]
struct FileSnapshot {
    semantic: SemanticMetadata,
    duration_micros: u64,
    audio_sha256: String,
    preserved_frames: Vec<String>,
    pictures: Vec<String>,
    lyrics: Vec<String>,
    external_tail: ExternalTailSnapshot,
}

#[derive(Clone, Debug)]
struct PreparedUpdate {
    request: UpdateTrackTagsRequest,
    replacement_picture: Option<Picture>,
    replacement_picture_fingerprint: Option<String>,
}

pub(crate) fn read_track_tags(
    path: &Path,
    sync_id: String,
    relative_path: String,
    fallback: TagFallback,
) -> Result<TrackTagData, String> {
    ensure_mp3(path)?;
    let mpeg = read_mpeg(path)?;
    let semantic = semantic_metadata(mpeg.id3v2(), &fallback);
    let picture = displayed_picture(mpeg.id3v2());
    let cover_data_url = picture.map(|picture| {
        let mime = picture
            .mime_type()
            .map(|value| value.as_str())
            .unwrap_or("application/octet-stream");
        format!("data:{mime};base64,{}", BASE64.encode(picture.data()))
    });

    Ok(TrackTagData {
        sync_id,
        file_name: path
            .file_name()
            .map(|value| value.to_string_lossy().to_string())
            .unwrap_or_default(),
        relative_path,
        title: semantic.title,
        artist: semantic.artist,
        album: semantic.album,
        work: semantic.work,
        same_song_group: semantic.same_song_group,
        genre: semantic.genre,
        date: semantic.date,
        lyrics: semantic.lyrics,
        has_embedded_cover: picture.is_some(),
        cover_data_url,
    })
}

pub(crate) fn save_track_tags(
    path: &Path,
    request: UpdateTrackTagsRequest,
) -> Result<Option<String>, String> {
    ensure_mp3(path)?;
    let prepared = prepare_update(request)?;
    transactional_write(path, &prepared)
}

fn ensure_mp3(path: &Path) -> Result<(), String> {
    if !path.is_file() {
        return Err("歌曲文件已经不存在".to_string());
    }
    let is_mp3 = path
        .extension()
        .and_then(|value| value.to_str())
        .is_some_and(|value| value.eq_ignore_ascii_case("mp3"));
    if !is_mp3 {
        return Err("目前只支持把标签安全写回 MP3 文件".to_string());
    }
    Ok(())
}

fn read_mpeg(path: &Path) -> Result<MpegFile, String> {
    let mut file = File::open(path)
        .map_err(|error| format!("打开 MP3 失败（{}）：{error}", path.display()))?;
    <MpegFile as AudioFile>::read_from(&mut file, ParseOptions::default())
        .map_err(|error| format!("读取 MP3 标签失败（{}）：{error}", path.display()))
}

fn semantic_metadata(tag: Option<&Id3v2Tag>, fallback: &TagFallback) -> SemanticMetadata {
    let title = tag
        .and_then(|value| value.title())
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| fallback.title.clone());
    let artist = tag
        .and_then(|value| value.artist())
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| fallback.artist.clone());
    let album = tag
        .and_then(|value| value.album())
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| fallback.album.clone());
    let work = tag
        .and_then(|value| value.get_user_text(WORK_DESCRIPTION))
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .or_else(|| {
            tag.and_then(|value| value.get_text(&WORK_FALLBACK_ID))
                .map(str::trim)
                .filter(|value| !value.is_empty())
        })
        .unwrap_or_default()
        .to_string();
    let same_song_group = tag
        .and_then(|value| value.get_user_text(GROUP_DESCRIPTION))
        .map(str::trim)
        .unwrap_or_default()
        .to_string();
    let genre = tag
        .and_then(|value| value.genre())
        .map(|value| value.trim().to_string())
        .unwrap_or_default();
    let date = tag
        .and_then(|value| value.date())
        .map(|value| value.to_string())
        .unwrap_or_default();
    let lyrics = tag.and_then(primary_lyrics).unwrap_or_default().to_string();

    SemanticMetadata {
        title,
        artist,
        album,
        work,
        same_song_group,
        genre,
        date,
        lyrics,
    }
}

fn primary_lyrics(tag: &Id3v2Tag) -> Option<&str> {
    tag.unsync_text()
        .find(|value| value.description.trim().is_empty())
        .or_else(|| tag.unsync_text().next())
        .map(|value| value.content.as_ref())
}

fn displayed_picture(tag: Option<&Id3v2Tag>) -> Option<&Picture> {
    let tag = tag?;
    tag.into_iter()
        .find_map(|frame| match frame {
            Frame::Picture(value) if value.picture.pic_type() == PictureType::CoverFront => {
                Some(value.picture.as_ref())
            }
            _ => None,
        })
        .or_else(|| {
            tag.into_iter().find_map(|frame| match frame {
                Frame::Picture(value) => Some(value.picture.as_ref()),
                _ => None,
            })
        })
}

fn prepare_update(request: UpdateTrackTagsRequest) -> Result<PreparedUpdate, String> {
    if request.title.len() > 4096
        || request.artist.len() > 4096
        || request.album.len() > 4096
        || request.work.len() > 4096
        || request.same_song_group.len() > 4096
        || request.genre.len() > 4096
        || request.date.len() > Timestamp::MAX_LENGTH
    {
        return Err("某个标签字段太长，已拒绝写入".to_string());
    }
    if request.lyrics.len() > 4 * 1024 * 1024 {
        return Err("歌词超过 4 MB，已拒绝写入".to_string());
    }

    let mut request = request;
    request.title = request.title.trim().to_string();
    request.artist = request.artist.trim().to_string();
    request.album = request.album.trim().to_string();
    request.work = request.work.trim().to_string();
    request.same_song_group = request.same_song_group.trim().to_string();
    request.genre = request.genre.trim().to_string();
    request.date = request.date.trim().to_string();
    request.lyrics = request.lyrics.replace("\r\n", "\n").replace('\r', "\n");

    if !request.date.is_empty() {
        let parsed = request
            .date
            .parse::<Timestamp>()
            .map_err(|_| "日期格式无法识别，请使用 2026、2026-07-10 或完整 ISO 日期".to_string())?;
        let canonical = parsed.to_string();
        if canonical != request.date {
            return Err("日期格式不完整，请使用 2026、2026-07-10 或完整 ISO 日期".to_string());
        }
        request.date = canonical;
    }

    let replacement_picture = match request.cover_action {
        CoverAction::Replace => {
            let encoded = request
                .cover_data_base64
                .as_deref()
                .ok_or_else(|| "没有收到要写入的封面".to_string())?;
            let bytes = BASE64
                .decode(encoded)
                .map_err(|_| "封面数据无法识别".to_string())?;
            if bytes.is_empty() || bytes.len() > MAX_COVER_BYTES {
                return Err("封面必须小于 20 MB".to_string());
            }
            let mut reader = bytes.as_slice();
            let mut picture = Picture::from_reader(&mut reader)
                .map_err(|_| "请选择 JPEG、PNG、GIF、BMP 或 TIFF 图片".to_string())?;
            picture.set_pic_type(PictureType::CoverFront);
            picture.set_description(Some("ARMusic Cover".to_string()));
            Some(picture)
        }
        CoverAction::Keep | CoverAction::Remove => None,
    };
    let replacement_picture_fingerprint = replacement_picture.as_ref().map(picture_fingerprint);

    Ok(PreparedUpdate {
        request,
        replacement_picture,
        replacement_picture_fingerprint,
    })
}

fn set_text(tag: &mut Id3v2Tag, id: &FrameId<'static>, desired: &str, current: &str) {
    if desired == current.trim() {
        return;
    }
    drop(tag.remove(id));
    if !desired.is_empty() {
        let _ = tag.insert(Frame::Text(TextInformationFrame::new(
            id.clone(),
            TextEncoding::UTF8,
            desired.to_string(),
        )));
    }
}

fn set_user_text(tag: &mut Id3v2Tag, description: &str, desired: &str, current: &str) {
    if desired == current.trim() {
        return;
    }
    tag.retain(|frame| {
        !matches!(frame, Frame::UserText(value) if value.description.eq_ignore_ascii_case(description))
    });
    if !desired.is_empty() {
        let _ = tag.insert_user_text(description.to_string(), desired.to_string());
    }
}

fn apply_update(mpeg: &mut MpegFile, prepared: &PreparedUpdate) {
    let fallback = TagFallback {
        title: String::new(),
        artist: String::new(),
        album: String::new(),
    };
    let current = semantic_metadata(mpeg.id3v2(), &fallback);
    let mut tag = mpeg.id3v2_mut().map(std::mem::take).unwrap_or_default();

    set_text(&mut tag, &TITLE_ID, &prepared.request.title, &current.title);
    set_text(
        &mut tag,
        &ARTIST_ID,
        &prepared.request.artist,
        &current.artist,
    );
    set_text(&mut tag, &ALBUM_ID, &prepared.request.album, &current.album);
    set_text(&mut tag, &GENRE_ID, &prepared.request.genre, &current.genre);

    if prepared.request.work != current.work {
        tag.retain(|frame| {
            !matches!(frame, Frame::UserText(value) if value.description.eq_ignore_ascii_case(WORK_DESCRIPTION))
                && frame.id_str() != WORK_FALLBACK_ID.as_str()
        });
        if !prepared.request.work.is_empty() {
            let _ =
                tag.insert_user_text(WORK_DESCRIPTION.to_string(), prepared.request.work.clone());
        }
    }
    set_user_text(
        &mut tag,
        GROUP_DESCRIPTION,
        &prepared.request.same_song_group,
        &current.same_song_group,
    );

    if prepared.request.date != current.date {
        tag.remove_date();
        if let Ok(date) = prepared.request.date.parse::<Timestamp>() {
            tag.set_date(date);
        }
    }

    if prepared.request.lyrics != current.lyrics {
        tag.retain(|frame| !matches!(frame, Frame::UnsynchronizedText(_)));
        if !prepared.request.lyrics.is_empty() {
            let _ = tag.insert(Frame::UnsynchronizedText(UnsynchronizedTextFrame::new(
                TextEncoding::UTF8,
                NORMALIZED_LANGUAGE,
                "".to_string(),
                prepared.request.lyrics.clone(),
            )));
        }
    }

    match prepared.request.cover_action {
        CoverAction::Keep => {}
        CoverAction::Remove | CoverAction::Replace => {
            remove_displayed_pictures(&mut tag);
            if let Some(picture) = prepared.replacement_picture.clone() {
                let _ = tag.insert_picture(picture);
            }
        }
    }

    normalize_invalid_languages(&mut tag);
    mpeg.set_id3v2(tag);
}

fn remove_displayed_pictures(tag: &mut Id3v2Tag) {
    let has_front = (&*tag).into_iter().any(
        |frame| matches!(frame, Frame::Picture(value) if value.picture.pic_type() == PictureType::CoverFront),
    );
    let mut removed_first = false;
    tag.retain(|frame| {
        let Frame::Picture(value) = frame else {
            return true;
        };
        if has_front {
            return value.picture.pic_type() != PictureType::CoverFront;
        }
        if !removed_first {
            removed_first = true;
            return false;
        }
        true
    });
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

fn normalize_invalid_languages(tag: &mut Id3v2Tag) {
    for frame_name in ["USLT", "COMM", "SYLT"] {
        let frame_id = FrameId::Valid(Cow::Borrowed(frame_name));
        let has_invalid = (&*tag).into_iter().any(|frame| match frame {
            Frame::UnsynchronizedText(value) if frame_name == "USLT" => {
                !language_is_valid(value.language)
            }
            Frame::Comment(value) if frame_name == "COMM" => !language_is_valid(value.language),
            Frame::Binary(value) if frame_name == "SYLT" => {
                binary_language(&value.data).is_some_and(|language| !language_is_valid(language))
            }
            _ => false,
        });
        if !has_invalid {
            continue;
        }

        let mut frames = tag.remove(&frame_id).collect::<Vec<_>>();
        for frame in &mut frames {
            match frame {
                Frame::UnsynchronizedText(value) if !language_is_valid(value.language) => {
                    value.language = NORMALIZED_LANGUAGE;
                }
                Frame::Comment(value) if !language_is_valid(value.language) => {
                    value.language = NORMALIZED_LANGUAGE;
                }
                Frame::Binary(value) if frame_name == "SYLT" => {
                    let data = value.data.to_mut();
                    if binary_language(data).is_some_and(|language| !language_is_valid(language)) {
                        data[1..4].copy_from_slice(&NORMALIZED_LANGUAGE);
                    }
                }
                _ => {}
            }
        }
        for frame in frames {
            let _ = tag.insert(frame);
        }
    }
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

fn frame_fingerprint(frame: &Frame<'_>) -> String {
    match frame {
        Frame::UnsynchronizedText(value) => digest_bytes(
            format!(
                "USLT|{:?}|{:?}|{:?}|{}|{}",
                value.encoding,
                frame.flags(),
                normalized_language(value.language),
                value.description,
                value.content
            )
            .as_bytes(),
        ),
        Frame::Comment(value) => digest_bytes(
            format!(
                "COMM|{:?}|{:?}|{:?}|{}|{}",
                value.encoding,
                frame.flags(),
                normalized_language(value.language),
                value.description,
                value.content
            )
            .as_bytes(),
        ),
        Frame::Binary(value) if frame.id_str() == "SYLT" => digest_bytes(
            format!(
                "SYLT|{:?}|{}|{}",
                frame.flags(),
                value.data.len(),
                language_neutral_binary_digest(frame.id_str(), &value.data)
            )
            .as_bytes(),
        ),
        _ => digest_bytes(format!("{frame:?}").as_bytes()),
    }
}

fn is_edited_frame(frame: &Frame<'_>) -> bool {
    match frame {
        Frame::UserText(value) => {
            value.description.eq_ignore_ascii_case(WORK_DESCRIPTION)
                || value.description.eq_ignore_ascii_case(GROUP_DESCRIPTION)
        }
        Frame::UnsynchronizedText(_) | Frame::Picture(_) => true,
        _ => matches!(
            frame.id_str(),
            "TIT2" | "TPE1" | "TALB" | "TCON" | "TIT1" | "TDRC"
        ),
    }
}

fn picture_fingerprint(picture: &Picture) -> String {
    digest_bytes(
        format!(
            "{:?}|{:?}|{:?}|{}|{}",
            picture.pic_type(),
            picture.mime_type().map(|value| value.as_str()),
            picture.description(),
            picture.data().len(),
            digest_bytes(picture.data())
        )
        .as_bytes(),
    )
}

fn inspect_file(path: &Path, fallback: &TagFallback) -> Result<FileSnapshot, String> {
    let layout = read_external_tail(path)?;
    let mpeg = read_mpeg(path)?;
    let tag = mpeg.id3v2();
    let mut preserved_frames = Vec::new();
    let mut pictures = Vec::new();
    let mut lyrics = Vec::new();
    if let Some(tag) = tag {
        for frame in tag {
            if !is_edited_frame(frame) {
                preserved_frames.push(frame_fingerprint(frame));
            }
            match frame {
                Frame::Picture(value) => pictures.push(picture_fingerprint(&value.picture)),
                Frame::UnsynchronizedText(_) => lyrics.push(frame_fingerprint(frame)),
                _ => {}
            }
        }
    }
    preserved_frames.sort();
    pictures.sort();
    lyrics.sort();

    Ok(FileSnapshot {
        semantic: semantic_metadata(tag, fallback),
        duration_micros: u64::try_from(mpeg.properties().duration().as_micros())
            .unwrap_or(u64::MAX),
        audio_sha256: audio_payload_sha256(path, &layout)?,
        preserved_frames,
        pictures,
        lyrics,
        external_tail: layout.snapshot,
    })
}

fn expected_picture_fingerprints(
    before: &FileSnapshot,
    action: CoverAction,
    replacement: Option<&str>,
    path: &Path,
) -> Result<Vec<String>, String> {
    if action == CoverAction::Keep {
        return Ok(before.pictures.clone());
    }
    let mpeg = read_mpeg(path)?;
    let tag = mpeg.id3v2();
    let has_front = tag.into_iter().flatten().any(
        |frame| matches!(frame, Frame::Picture(value) if value.picture.pic_type() == PictureType::CoverFront),
    );
    let mut skipped_first = false;
    let mut expected = Vec::new();
    if let Some(tag) = tag {
        for frame in tag {
            let Frame::Picture(value) = frame else {
                continue;
            };
            let is_displayed = if has_front {
                value.picture.pic_type() == PictureType::CoverFront
            } else if !skipped_first {
                skipped_first = true;
                true
            } else {
                false
            };
            if !is_displayed {
                expected.push(picture_fingerprint(&value.picture));
            }
        }
    }
    if let Some(replacement) = replacement {
        expected.push(replacement.to_string());
    }
    expected.sort();
    Ok(expected)
}

fn validate_update(
    before: &FileSnapshot,
    after: &FileSnapshot,
    prepared: &PreparedUpdate,
    expected_pictures: &[String],
) -> Result<(), String> {
    let desired = &prepared.request;
    let mut errors = Vec::new();
    if before.audio_sha256 != after.audio_sha256 {
        errors.push("音频主体发生变化");
    }
    if before.duration_micros != after.duration_micros {
        errors.push("音频时长发生变化");
    }
    if before.preserved_frames != after.preserved_frames {
        errors.push("非目标 ID3v2 标签发生变化");
    }
    if before.external_tail != after.external_tail {
        errors.push("ID3v1 或 APEv2 尾部标签发生变化");
    }
    if after.semantic.title != desired.title
        || after.semantic.artist != desired.artist
        || after.semantic.album != desired.album
        || after.semantic.work != desired.work
        || after.semantic.same_song_group != desired.same_song_group
        || after.semantic.genre != desired.genre
        || after.semantic.date != desired.date
        || after.semantic.lyrics != desired.lyrics
    {
        errors.push("写入后的标签内容与编辑值不一致");
    }
    if after.pictures != expected_pictures {
        errors.push("封面写入后校验不一致");
    }
    if desired.lyrics == before.semantic.lyrics && before.lyrics != after.lyrics {
        errors.push("未编辑的歌词帧发生变化");
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors.join("；"))
    }
}

fn edit_stage(
    path: &Path,
    before: &FileSnapshot,
    prepared: &PreparedUpdate,
    expected_pictures: &[String],
) -> Result<FileSnapshot, String> {
    let mut file = OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .map_err(|error| format!("打开临时 MP3 失败：{error}"))?;
    let mut mpeg = <MpegFile as AudioFile>::read_from(&mut file, ParseOptions::default())
        .map_err(|error| format!("写入前解析 MP3 失败：{error}"))?;
    apply_update(&mut mpeg, prepared);
    mpeg.save_to(&mut file, WriteOptions::default())
        .map_err(|error| format!("写入 ID3v2 标签失败：{error}"))?;
    file.sync_all()
        .map_err(|error| format!("刷新临时 MP3 失败：{error}"))?;
    drop(file);
    restore_external_tail(path, &before.external_tail)?;

    let fallback = TagFallback {
        title: String::new(),
        artist: String::new(),
        album: String::new(),
    };
    let after = inspect_file(path, &fallback)?;
    validate_update(before, &after, prepared, expected_pictures)?;
    Ok(after)
}

fn transactional_write(path: &Path, prepared: &PreparedUpdate) -> Result<Option<String>, String> {
    let fallback = TagFallback {
        title: String::new(),
        artist: String::new(),
        album: String::new(),
    };
    let before = inspect_file(path, &fallback)?;
    let expected_pictures = expected_picture_fingerprints(
        &before,
        prepared.request.cover_action,
        prepared.replacement_picture_fingerprint.as_deref(),
        path,
    )?;
    let parent = path
        .parent()
        .ok_or_else(|| "歌曲文件没有父目录".to_string())?;
    let file_name = path
        .file_name()
        .map(|value| value.to_string_lossy().to_string())
        .ok_or_else(|| "歌曲文件名无法识别".to_string())?;
    let token = unique_token();
    let stage_path = parent.join(format!(".{file_name}.armusic-tag-stage-{token}"));
    let backup_path = parent.join(format!(".{file_name}.armusic-tag-backup-{token}"));
    if stage_path.exists() || backup_path.exists() {
        return Err("标签编辑临时文件意外存在，为避免覆盖已停止".to_string());
    }

    let metadata_before =
        fs::metadata(path).map_err(|error| format!("读取原 MP3 属性失败：{error}"))?;
    let original_identity = crate::sync_identity::create_audio_identity_uncached(path)?;
    fs::copy(path, &stage_path).map_err(|error| format!("创建同目录写入副本失败：{error}"))?;
    fs::set_permissions(&stage_path, metadata_before.permissions())
        .map_err(|error| format!("复制文件权限失败：{error}"))?;

    let stage_result = edit_stage(&stage_path, &before, prepared, &expected_pictures);
    if let Err(error) = stage_result {
        let _ = fs::remove_file(&stage_path);
        return Err(format!("安全副本校验失败，原文件没有改动：{error}"));
    }

    let metadata_now =
        fs::metadata(path).map_err(|error| format!("替换前重新读取原 MP3 属性失败：{error}"))?;
    if metadata_before.len() != metadata_now.len()
        || metadata_before.modified().ok() != metadata_now.modified().ok()
    {
        let _ = fs::remove_file(&stage_path);
        return Err("编辑期间原 MP3 被其他程序修改，已停止替换".to_string());
    }
    let final_original_identity = crate::sync_identity::create_audio_identity_uncached(path)?;
    if final_original_identity != original_identity {
        let _ = fs::remove_file(&stage_path);
        return Err("编辑期间原 MP3 字节发生变化，已停止替换".to_string());
    }
    let staged_identity = crate::sync_identity::create_audio_identity_uncached(&stage_path)?;
    let replace_result = crate::atomic_replace_with_backup(&stage_path, path, &backup_path);
    let current_identity =
        crate::sync_identity::create_audio_identity_uncached(path).map_err(|error| {
            format!(
                "标签原子替换后无法确认目标状态，程序没有盲目回滚；交换瞬间备份在 {}：{error}",
                backup_path.display()
            )
        })?;
    if current_identity != staged_identity {
        if current_identity == original_identity {
            let _ = fs::remove_file(&stage_path);
            return Err(format!(
                "标签原子替换没有发生，原 MP3 保持不变：{}",
                replace_result
                    .err()
                    .unwrap_or_else(|| "目标仍是旧版本".to_string())
            ));
        }
        return Err(format!(
            "标签原子替换后目标又发生变化，程序没有覆盖该变化；交换瞬间备份在 {}",
            backup_path.display()
        ));
    }

    let final_snapshot = inspect_file(path, &fallback)
        .and_then(|after| validate_update(&before, &after, prepared, &expected_pictures));
    if let Err(error) = final_snapshot {
        let still_staged = crate::sync_identity::create_audio_identity_uncached(path)
            .map(|identity| identity == staged_identity)
            .unwrap_or(false);
        if !still_staged {
            return Err(format!(
                "替换后的最终校验失败，且目标随后发生变化；程序没有盲目回滚，备份在 {}：{error}",
                backup_path.display()
            ));
        }
        let captured_identity = crate::sync_identity::create_audio_identity_uncached(&backup_path)?;
        let failed_path = parent.join(format!(".{file_name}.armusic-failed-new-{token}"));
        // On Windows the audio backend can still be reading the old file through the path that
        // ReplaceFileW assigned to `backup_path`. Moving that occupied backup back would stop the
        // rollback with ERROR_SHARING_VIOLATION. Restore from a synced byte-for-byte copy instead:
        // the playback handle keeps reading the original backup while the target is exchanged.
        let restore_path = parent.join(format!(".{file_name}.armusic-tag-restore-{token}"));
        crate::copy_file_synced(&backup_path, &restore_path).map_err(|copy_error| {
            format!(
                "最终校验失败；旧文件已安全保存在 {}，但创建恢复副本失败，当前文件未自动改动：{error}；{copy_error}",
                backup_path.display()
            )
        })?;
        if let Ok(metadata) = fs::metadata(&backup_path) {
            let _ = fs::set_permissions(&restore_path, metadata.permissions());
        }
        crate::atomic_replace_with_backup(&restore_path, path, &failed_path).map_err(
            |rollback| {
                let _ = fs::remove_file(&restore_path);
                format!(
                    "最终校验失败且原子恢复失败；旧文件仍安全保存在 {}，当前文件没有被再次覆盖：{error}；{rollback}",
                    backup_path.display()
                )
            },
        )?;
        let restored = crate::sync_identity::create_audio_identity_uncached(path)?;
        if restored != captured_identity {
            return Err(format!(
                "最终校验失败，恢复后的文件也无法验证；failed-new 保存在 {}",
                failed_path.display()
            ));
        }
        return Err(format!(
            "替换后的最终校验失败，已从交换瞬间的安全备份原子恢复；failed-new 保存在 {}，播放中的旧句柄未中断：{error}",
            failed_path.display(),
        ));
    }

    let captured_identity = crate::sync_identity::create_audio_identity_uncached(&backup_path)?;
    if captured_identity != original_identity {
        return Ok(Some(format!(
            "标签已经保存；检测到替换瞬间还有外部编辑，较新的外部版本已完整保存在 {}",
            backup_path.display()
        )));
    }
    Ok(Some(format!(
        "标签已经保存；为防止仍持有旧文件句柄的外部程序随后写入，交换瞬间备份会保留在 {}",
        backup_path.display()
    )))
}

fn read_external_tail(path: &Path) -> Result<ExternalTailLayout, String> {
    let mut file = File::open(path).map_err(|error| format!("读取尾部标签失败：{error}"))?;
    let file_len = file
        .metadata()
        .map_err(|error| format!("读取文件长度失败：{error}"))?
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
                    .map_err(|_| "APEv2 长度字段错误".to_string())?,
            ) as u64;
            if size < 32 || size > tail_end {
                return Err("APEv2 长度非法，已拒绝编辑".to_string());
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
            apev2 = Some(read_range(&mut file, start, tail_end - start)?);
            apev2_start = Some(start);
        }
    }

    Ok(ExternalTailLayout {
        snapshot: ExternalTailSnapshot { id3v1, apev2 },
        apev2_start,
    })
}

fn read_range(file: &mut File, offset: u64, len: u64) -> Result<Vec<u8>, String> {
    let len = usize::try_from(len).map_err(|_| "标签数据太大".to_string())?;
    let mut bytes = vec![0_u8; len];
    file.seek(SeekFrom::Start(offset))
        .map_err(|error| format!("定位标签数据失败：{error}"))?;
    file.read_exact(&mut bytes)
        .map_err(|error| format!("读取标签数据失败：{error}"))?;
    Ok(bytes)
}

fn restore_external_tail(path: &Path, desired: &ExternalTailSnapshot) -> Result<(), String> {
    let current = read_external_tail(path)?;
    if &current.snapshot == desired {
        return Ok(());
    }
    let file_len = fs::metadata(path)
        .map_err(|error| format!("读取待恢复文件长度失败：{error}"))?
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
            return Err("APEv2 不是连续尾部，已拒绝修改".to_string());
        }
        start
    } else {
        without_id3v1
    };
    let mut file = OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .map_err(|error| format!("打开待恢复文件失败：{error}"))?;
    file.set_len(content_end)
        .map_err(|error| format!("截断待恢复尾部失败：{error}"))?;
    file.seek(SeekFrom::End(0))
        .map_err(|error| format!("定位待恢复尾部失败：{error}"))?;
    if let Some(apev2) = &desired.apev2 {
        file.write_all(apev2)
            .map_err(|error| format!("恢复 APEv2 失败：{error}"))?;
    }
    if let Some(id3v1) = &desired.id3v1 {
        file.write_all(id3v1)
            .map_err(|error| format!("恢复 ID3v1 失败：{error}"))?;
    }
    file.sync_all()
        .map_err(|error| format!("刷新尾部标签失败：{error}"))
}

fn id3v2_end(file: &mut File, file_len: u64) -> Result<u64, String> {
    if file_len < 10 {
        return Ok(0);
    }
    let header = read_range(file, 0, 10)?;
    if &header[..3] != b"ID3" {
        return Ok(0);
    }
    if header[6..10].iter().any(|value| value & 0x80 != 0) {
        return Err("ID3v2 头部长度字段非法".to_string());
    }
    let body_len = ((header[6] as u64) << 21)
        | ((header[7] as u64) << 14)
        | ((header[8] as u64) << 7)
        | header[9] as u64;
    let footer_len = if header[3] == 4 && header[5] & 0x10 != 0 {
        10
    } else {
        0
    };
    let end = 10_u64
        .checked_add(body_len)
        .and_then(|value| value.checked_add(footer_len))
        .ok_or_else(|| "ID3v2 长度溢出".to_string())?;
    if end > file_len {
        return Err("ID3v2 长度超过文件大小".to_string());
    }
    Ok(end)
}

fn audio_payload_sha256(path: &Path, layout: &ExternalTailLayout) -> Result<String, String> {
    let mut file = File::open(path).map_err(|error| format!("读取音频主体失败：{error}"))?;
    let file_len = file
        .metadata()
        .map_err(|error| format!("读取文件长度失败：{error}"))?
        .len();
    let start = id3v2_end(&mut file, file_len)?;
    let without_id3v1 = file_len
        - layout
            .snapshot
            .id3v1
            .as_ref()
            .map_or(0, |value| value.len() as u64);
    let end = layout.apev2_start.unwrap_or(without_id3v1);
    if end < start {
        return Err("音频主体范围非法，已拒绝编辑".to_string());
    }
    file.seek(SeekFrom::Start(start))
        .map_err(|error| format!("定位音频主体失败：{error}"))?;
    let mut remaining = end - start;
    let mut buffer = vec![0_u8; 128 * 1024];
    let mut hash = Sha256::new();
    while remaining > 0 {
        let wanted = usize::try_from(remaining.min(buffer.len() as u64)).unwrap_or(buffer.len());
        file.read_exact(&mut buffer[..wanted])
            .map_err(|error| format!("读取音频主体失败：{error}"))?;
        hash.update(&buffer[..wanted]);
        remaining -= wanted as u64;
    }
    Ok(format!("{:x}", hash.finalize()))
}

fn digest_bytes(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn unique_token() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_nanos())
        .unwrap_or_default();
    format!("{}-{nanos}", std::process::id())
}

#[cfg(test)]
mod tests {
    use super::{
        displayed_picture, inspect_file, prepare_update, read_mpeg, save_track_tags, CoverAction,
        TagFallback, UpdateTrackTagsRequest,
    };
    use std::fs;

    fn request() -> UpdateTrackTagsRequest {
        UpdateTrackTagsRequest {
            sync_id: "track".to_string(),
            title: "  标题  ".to_string(),
            artist: "歌手".to_string(),
            album: String::new(),
            work: String::new(),
            same_song_group: String::new(),
            genre: String::new(),
            date: "2026-07-01".to_string(),
            lyrics: "第一行\r\n第二行".to_string(),
            cover_action: CoverAction::Keep,
            cover_data_base64: None,
        }
    }

    #[test]
    fn normalizes_form_values_before_writing() {
        let prepared = prepare_update(request()).expect("valid request");
        assert_eq!(prepared.request.title, "标题");
        assert_eq!(prepared.request.date, "2026-07-01");
        assert_eq!(prepared.request.lyrics, "第一行\n第二行");
    }

    #[test]
    fn rejects_invalid_dates() {
        let mut request = request();
        request.date = "July someday".to_string();
        assert!(prepare_update(request).is_err());
    }

    #[test]
    fn edits_a_real_fixture_copy_transactionally_when_provided() {
        let Ok(source) = std::env::var("ARMUSIC_TAG_EDITOR_FIXTURE") else {
            return;
        };
        let source = std::path::Path::new(&source);
        let test_path = std::env::temp_dir().join(format!(
            "armusic-tag-editor-test-{}-{}.mp3",
            std::process::id(),
            super::unique_token()
        ));
        fs::copy(source, &test_path).expect("copy fixture");

        let fallback = TagFallback {
            title: String::new(),
            artist: String::new(),
            album: String::new(),
        };
        let before = inspect_file(&test_path, &fallback).expect("inspect before");
        let request = UpdateTrackTagsRequest {
            sync_id: "fixture".to_string(),
            title: "ARMusic 标签编辑保真测试".to_string(),
            artist: before.semantic.artist.clone(),
            album: before.semantic.album.clone(),
            work: "测试作品".to_string(),
            same_song_group: "测试同曲组".to_string(),
            genre: before.semantic.genre.clone(),
            date: "2026-07-10".to_string(),
            lyrics: "[00:01.00]第一行\n[00:02.00]第二行".to_string(),
            cover_action: CoverAction::Replace,
            cover_data_base64: Some(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
                    .to_string(),
            ),
        };
        save_track_tags(&test_path, request).expect("transactional edit");
        let after = inspect_file(&test_path, &fallback).expect("inspect after");

        assert_eq!(before.audio_sha256, after.audio_sha256);
        assert_eq!(before.duration_micros, after.duration_micros);
        assert_eq!(before.external_tail, after.external_tail);
        assert_eq!(before.preserved_frames, after.preserved_frames);
        assert_eq!(after.semantic.title, "ARMusic 标签编辑保真测试");
        assert_eq!(after.semantic.work, "测试作品");
        assert_eq!(after.semantic.same_song_group, "测试同曲组");
        assert_eq!(after.semantic.date, "2026-07-10");
        assert_eq!(after.semantic.lyrics, "[00:01.00]第一行\n[00:02.00]第二行");
        assert!(displayed_picture(read_mpeg(&test_path).unwrap().id3v2()).is_some());

        fs::remove_file(test_path).expect("remove fixture copy");
    }
}
