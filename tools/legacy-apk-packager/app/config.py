from pathlib import Path

# Base directories
BASE_DIR = Path(__file__).resolve().parent.parent

# Path to the bundled LMusic Android project.
LMUSIC_ROOT = BASE_DIR / "lmusic" / "LMusic-main" / "LMusic-main"
GRADLEW = LMUSIC_ROOT / "gradlew.bat"

# Assets inside the Android project
ASSETS_DIR = LMUSIC_ROOT / "app" / "src" / "main" / "assets"
ASSETS_MUSIC = ASSETS_DIR / "music"
ASSETS_LYRICS = ASSETS_DIR / "lyrics"
ASSETS_COVERS = ASSETS_DIR / "covers"
SONGS_JSON = ASSETS_DIR / "songs.json"

# Local input/output (kept inside this repo to avoid touching the Android project until copy time)
DEFAULT_SONG_DIR = BASE_DIR / "input" / "music"
DEFAULT_LYRIC_DIR = BASE_DIR / "input" / "lyrics"
DEFAULT_COVER_DIR = BASE_DIR / "input" / "covers"
OUTPUT_APK_DIR = BASE_DIR / "output"


def ensure_paths() -> None:
    """Create local input/output folders (does not touch LMusic assets)."""
    for path in (DEFAULT_SONG_DIR, DEFAULT_LYRIC_DIR, DEFAULT_COVER_DIR, OUTPUT_APK_DIR):
        path.mkdir(parents=True, exist_ok=True)
