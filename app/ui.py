"""
Qt UI for the EXE tool: select folders, fetch lyrics (online), prepare assets, and build APK.
Android 端仍保持离线；在线歌词仅在 PC 侧生成本地 .lrc。
"""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

from PySide6.QtWidgets import (
    QApplication,
    QFileDialog,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from config import (
    DEFAULT_COVER_DIR,
    DEFAULT_LYRIC_DIR,
    DEFAULT_SONG_DIR,
    ensure_paths,
)
from lyrics_fetcher import fetch_and_save
from packer import (
    build_apk,
    copy_assets,
    generate_songs_json,
    generate_tracks_from_dirs,
)


class MainWindow(QWidget):
    def __init__(self) -> None:
        super().__init__()
        ensure_paths()
        self.setWindowTitle("Elder Music EXE - 离线歌单打包")

        # Inputs
        self.music_edit = QLineEdit(str(DEFAULT_SONG_DIR))
        self.lyric_edit = QLineEdit(str(DEFAULT_LYRIC_DIR))
        self.cover_edit = QLineEdit(str(DEFAULT_COVER_DIR))

        music_btn = QPushButton("选择歌曲目录")
        music_btn.clicked.connect(lambda: self._choose_dir(self.music_edit))

        lyric_btn = QPushButton("选择歌词目录")
        lyric_btn.clicked.connect(lambda: self._choose_dir(self.lyric_edit))

        cover_btn = QPushButton("选择封面目录")
        cover_btn.clicked.connect(lambda: self._choose_dir(self.cover_edit))

        # Actions
        fetch_btn = QPushButton("在线补歌词（lrclib）")
        fetch_btn.clicked.connect(self._fetch_lyrics)

        prepare_btn = QPushButton("生成 songs.json 并复制资产")
        prepare_btn.clicked.connect(self._prepare_assets)

        build_debug_btn = QPushButton("打包 Debug APK")
        build_debug_btn.clicked.connect(lambda: self._build("debug"))

        build_release_btn = QPushButton("打包 Release APK")
        build_release_btn.clicked.connect(lambda: self._build("release"))

        # Log view
        self.log = QTextEdit()
        self.log.setReadOnly(True)

        # Layouts
        grid = QGridLayout()
        grid.addWidget(QLabel("歌曲目录"), 0, 0)
        grid.addWidget(self.music_edit, 0, 1)
        grid.addWidget(music_btn, 0, 2)

        grid.addWidget(QLabel("歌词目录"), 1, 0)
        grid.addWidget(self.lyric_edit, 1, 1)
        grid.addWidget(lyric_btn, 1, 2)

        grid.addWidget(QLabel("封面目录"), 2, 0)
        grid.addWidget(self.cover_edit, 2, 1)
        grid.addWidget(cover_btn, 2, 2)

        actions = QHBoxLayout()
        actions.addWidget(fetch_btn)
        actions.addWidget(prepare_btn)
        actions.addWidget(build_debug_btn)
        actions.addWidget(build_release_btn)

        root = QVBoxLayout()
        root.addLayout(grid)
        root.addLayout(actions)
        root.addWidget(QLabel("日志"))
        root.addWidget(self.log)

        self.setLayout(root)
        self.resize(900, 500)

    # Helpers
    def _choose_dir(self, line: QLineEdit) -> None:
        start_dir = line.text() or str(Path.cwd())
        directory = QFileDialog.getExistingDirectory(self, "选择目录", start_dir)
        if directory:
            line.setText(directory)

    def _append(self, msg: str) -> None:
        self.log.append(msg)
        self.log.verticalScrollBar().setValue(self.log.verticalScrollBar().maximum())

    def _error(self, msg: str) -> None:
        QMessageBox.critical(self, "错误", msg)
        self._append(f"[ERROR] {msg}")

    def _paths(self) -> tuple[Path, Path, Path]:
        music_dir = Path(self.music_edit.text()).expanduser()
        lyric_dir = Path(self.lyric_edit.text()).expanduser()
        cover_dir = Path(self.cover_edit.text()).expanduser()
        return music_dir, lyric_dir, cover_dir

    # Actions
    def _fetch_lyrics(self) -> None:
        music_dir, lyric_dir, _ = self._paths()
        if not music_dir.exists():
            self._error("歌曲目录不存在")
            return
        lyric_dir.mkdir(parents=True, exist_ok=True)

        tracks = generate_tracks_from_dirs(music_dir, lyric_dir=lyric_dir)
        missing = [t for t in tracks if not t.get("lyric")]
        if not missing:
            self._append("没有缺失歌词的歌曲。")
            return

        fetched = 0
        for t in missing:
            stem = Path(t["audio"]).name
            stem = Path(stem).stem
            dest = lyric_dir / f"{stem}.lrc"
            try:
                saved = fetch_and_save(t["title"], t.get("artist", ""), dest)
                if saved:
                    fetched += 1
            except Exception as e:  # noqa: BLE001
                self._append(f"[WARN] 补歌词失败 {stem}: {e}")
        self._append(f"歌词补全完成：{fetched}/{len(missing)}")

    def _prepare_assets(self) -> None:
        music_dir, lyric_dir, cover_dir = self._paths()
        if not music_dir.exists():
            self._error("歌曲目录不存在")
            return
        lyric_dir.mkdir(parents=True, exist_ok=True)
        cover_dir.mkdir(parents=True, exist_ok=True)

        tracks = generate_tracks_from_dirs(music_dir, lyrics_dir=lyric_dir, covers_dir=cover_dir)
        if not tracks:
            self._error("歌曲目录下没有可识别的音频文件。")
            return

        generate_songs_json(tracks)
        copy_assets(music_dir, lyric_dir, cover_dir)
        self._append(f"已生成 songs.json，曲目数 {len(tracks)}，并复制资产到 LMusic。")

    def _build(self, build_type: str) -> None:
        try:
            apk_path: Optional[Path] = build_apk(build_type)
        except Exception as e:  # noqa: BLE001
            self._error(f"打包失败：{e}")
            return
        self._append(f"{build_type} 打包完成：{apk_path}")


def run_ui() -> None:
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    run_ui()
