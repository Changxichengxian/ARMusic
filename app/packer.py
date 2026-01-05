"""
Helpers for packing assets and invoking Gradle.
 """
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from typing import Iterable

from config import (
    ASSETS_COVERS,
    ASSETS_LYRICS,
    ASSETS_MUSIC,
    GRADLEW,
    LMUSIC_ROOT,
    OUTPUT_APK_DIR,
    SONGS_JSON,
)

AUDIO_EXTS = {".mp3", ".flac", ".ape", ".wav", ".m4a", ".aac", ".ogg"}


def _copy_dir(src: Path, dst: Path, exts: set[str] | None = None) -> None:
    """
    Copy files from src to dst (no recursive glob), overwriting same-name files.
    Does not delete existing files in dst to stay non-destructive.
    """
    dst.mkdir(parents=True, exist_ok=True)
    if not src.exists():
        return
    for item in src.iterdir():
        if item.is_file() and (exts is None or item.suffix.lower() in exts):
            shutil.copy2(item, dst / item.name)


def generate_tracks_from_dirs(
    music_dir: Path, lyrics_dir: Path | None = None, covers_dir: Path | None = None
) -> list[dict]:
    """
    Build a simple track list from given folders (1:1 by stem name).
    Duration is not computed here; UI/CLI can fill it later if needed.
    """
    tracks: list[dict] = []
    lyrics_dir = lyrics_dir or music_dir
    covers_dir = covers_dir or music_dir

    for audio in sorted(music_dir.glob("*")):
        if not audio.is_file() or audio.suffix.lower() not in AUDIO_EXTS:
            continue
        stem = audio.stem
        lyric = lyrics_dir / f"{stem}.lrc"
        cover_jpg = covers_dir / f"{stem}.jpg"
        cover_png = covers_dir / f"{stem}.png"
        cover = cover_jpg if cover_jpg.exists() else cover_png if cover_png.exists() else None

        tracks.append(
            {
                "title": stem,
                "artist": "Unknown",
                "durationMs": 0,
                "audio": f"asset:///music/{audio.name}",
                "lyric": f"asset:///lyrics/{lyric.name}" if lyric.exists() else "",
                "cover": f"asset:///covers/{cover.name}" if cover else "",
            }
        )
    return tracks


def generate_songs_json(tracks: Iterable[dict]) -> None:
    """Write track metadata to songs.json (UTF-8)."""
    SONGS_JSON.parent.mkdir(parents=True, exist_ok=True)
    with SONGS_JSON.open("w", encoding="utf-8") as f:
        json.dump(list(tracks), f, ensure_ascii=False, indent=2)


def copy_assets(src_music: Path, src_lyrics: Path, src_covers: Path) -> None:
    """Copy user-selected assets into LMusic assets folder (non-destructive overwrite)."""
    _copy_dir(src_music, ASSETS_MUSIC, exts=AUDIO_EXTS)
    _copy_dir(src_lyrics, ASSETS_LYRICS, exts={".lrc"})
    _copy_dir(src_covers, ASSETS_COVERS, exts={".jpg", ".jpeg", ".png"})


def build_apk(build_type: str = "debug") -> Path:
    """Invoke Gradle wrapper to assemble APK (debug/release). Returns APK path if found."""
    build_type_cap = build_type.lower().capitalize()  # Debug / Release
    cmd = [str(GRADLEW), f"assemble{build_type_cap}"]
    subprocess.run(cmd, cwd=LMUSIC_ROOT, check=True)

    # Try to locate the output APK (standard Android Gradle layout)
    apk_dir = LMUSIC_ROOT / "app" / "build" / "outputs" / "apk" / build_type.lower()
    apk_candidates = sorted(apk_dir.glob("*.apk"))
    if apk_candidates:
        OUTPUT_APK_DIR.mkdir(parents=True, exist_ok=True)
        apk_out = OUTPUT_APK_DIR / apk_candidates[-1].name
        shutil.copy2(apk_candidates[-1], apk_out)
        return apk_out
    return apk_dir


if __name__ == "__main__":
    print("Pack helper ready. Call from CLI/UI to generate songs.json and build APK.")
