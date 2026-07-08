# ARMusic 1.3.7 architecture cleanup

## Goal

1.3.7 continues the cleanup started in 1.3.6. The goal is to keep names consistent, split large responsibilities, reduce avoidable runtime work, and leave a stable path for future features.

The Android `applicationId` is `com.armusic`. Compatibility actions and source-package handling for the temporary old ARMusic package were removed after the data migration was completed.

## Naming rules

ARMusic keeps its product name. The code naming style follows the clearer parts of ARBATOS:

- Use full role names instead of short local abbreviations: `SongsViewModel`, `SearchViewModel`.
- Name capability classes as `Feature + Role`: `ARMusicLanSyncClient`, `ARMusicAgentManager`, `ARMusicTrackUploader`.
- Use role suffixes consistently:
  - `Screen` for navigation entries.
  - `Content` for pure Compose content blocks.
  - `ViewModel` for screen state owners.
  - `State`, `Action`, `Event` for screen state flow.
  - `Manager`, `Repository`, `Client`, `Builder`, `Planner`, `Importer`, `Uploader`, `Downloader` for service objects.
- Use short verb functions for work: `start`, `stop`, `find`, `build`, `fetch`, `import`, `export`, `upload`, `download`.
- Avoid temporary names in permanent code: `new_screen`, `Wrapper`, `VM`.

## Screen layout

Screen packages now describe user-facing areas:

- `compose.screen.home`: home grid and entry panels.
- `compose.screen.songs`: song list and list actions.
- `compose.screen.search`: library search.
- `compose.screen.playing`: playback UI, portrait and phone landscape.
- `compose.screen.settings`: settings.
- `compose.screen.sync`: LAN sync.
- `compose.screen.wishlist`: wishlist.
- `compose.screen.tag`: tag editing.
- `compose.screen.lyric`: lyric search.
- `compose.screen.detail`: song detail screen.
- `compose.screen.detail.component`: detail screen components.
- `compose.shell`: app-level scaffold around navigation and player sheets.

## Runtime cleanup in this pass

- Search no longer builds work groups before it knows a search is active.
- Search poster wall only observes the full song list while the idle search page is visible.
- App scaffold naming is explicit, so future phone/tablet/player shell changes have one owner.
- Migration is split into preference, history, and backup-format classes.
- Agent commands are split into command dispatch, file handling, library export, and bundle import classes.
- The history view model now uses the full `HistoryViewModel` name.
- Settings update checks and tag-editor cover/lyric helpers live outside their main screen files.
- The Android build script repairs generated caches polluted by mixed real-path and subst-drive builds.

## Follow-up path

- Move large screen-local helper functions into screen-local component files when they keep growing.
- Keep data access out of frequently recomposed UI blocks when possible. Prefer view models or small display models.
- Keep new features under a clear screen or capability package first, then promote shared code only after it is reused.
- Remove the original LMusic migration path only when it is no longer useful for personal backups or comparisons.
