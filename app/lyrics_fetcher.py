"""
Online lyrics fetcher for the EXE tool (Android app stays offline).
Uses lrclib.net public API.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional

import requests

LRCLIB_ENDPOINT = "https://lrclib.net/api/search"


def fetch_lyrics(title: str, artist: str = "", timeout: int = 8) -> Optional[str]:
    params = {"track_name": title, "artist_name": artist}
    resp = requests.get(LRCLIB_ENDPOINT, params=params, timeout=timeout)
    resp.raise_for_status()
    results = resp.json()
    if not results:
        return None
    first = results[0]
    text = first.get("syncedLyrics") or first.get("plainLyrics")
    if text:
        return text.strip()
    return None


def fetch_and_save(title: str, artist: str, dest: Path, timeout: int = 8) -> Optional[Path]:
    lyric = fetch_lyrics(title, artist, timeout=timeout)
    if not lyric:
        return None
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(lyric, encoding="utf-8")
    return dest


if __name__ == "__main__":
    sample = fetch_lyrics("童年", "罗大佑")
    print(sample[:120] + "...") if sample else print("No lyrics found")
