import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ArrowDown,
  ArrowUp,
  ChevronDown,
  ChevronRight,
  Check,
  ListMusic,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Search,
  Trash2,
  X,
} from "lucide-react";
import { useI18n } from "../i18n";
import { stableNumber } from "../lib/music";
import { editTrackFromContextMenu } from "../lib/trackContextMenu";
import type {
  ARMusicBridge,
  PlaylistData,
  PlaylistsPayload,
  Track,
} from "../types";
import { CoverArt } from "./CoverArt";
import "./PlaylistsView.css";

type PlaylistSortMode = "custom" | "name" | "count" | "updated" | "random";
type PlaylistTrackSortMode = "order" | "title" | "artist" | "album" | "random";
type SortDirection = "asc" | "desc";

const playlistUiCopy = {
  "zh-CN": {
    playlistSort: "歌单排序",
    trackSort: "歌曲排序",
    custom: "自定义",
    name: "歌单名称",
    count: "歌曲数量",
    updated: "最近更新",
    order: "歌单顺序",
    title: "歌曲名称",
    artist: "艺术家",
    album: "专辑",
    random: "随机",
    ascending: "升序",
    descending: "降序",
    songs: "歌曲",
  },
  ja: {
    playlistSort: "リストの並び順",
    trackSort: "曲の並び順",
    custom: "カスタム",
    name: "プレイリスト名",
    count: "曲数",
    updated: "最近の更新",
    order: "プレイリスト順",
    title: "曲名",
    artist: "アーティスト",
    album: "アルバム",
    random: "ランダム",
    ascending: "昇順",
    descending: "降順",
    songs: "曲",
  },
  en: {
    playlistSort: "Playlist order",
    trackSort: "Song order",
    custom: "Custom",
    name: "Playlist name",
    count: "Song count",
    updated: "Recently updated",
    order: "Playlist order",
    title: "Song title",
    artist: "Artist",
    album: "Album",
    random: "Random",
    ascending: "Ascending",
    descending: "Descending",
    songs: "Songs",
  },
} as const;

function SortMenu<T extends string>({
  label,
  value,
  options,
  direction,
  ascendingLabel,
  descendingLabel,
  onChange,
  onDirectionChange,
  compact = false,
}: {
  label: string;
  value: T;
  options: Array<{ value: T; label: string }>;
  direction: SortDirection;
  ascendingLabel: string;
  descendingLabel: string;
  onChange: (value: T) => void;
  onDirectionChange: (direction: SortDirection) => void;
  compact?: boolean;
}) {
  const current = options.find((option) => option.value === value)?.label ?? options[0]?.label ?? "";
  return (
    <div className={`playlist-sort${compact ? " playlist-sort--compact" : ""}`}>
      <details className="playlist-sort__menu">
        <summary aria-label={label}>
          <span>{current}</span>
          <ChevronDown size={15} />
        </summary>
        <div className="playlist-sort__popover" role="menu" aria-label={label}>
          <span>{label}</span>
          {options.map((option) => (
            <button
              type="button"
              role="menuitemradio"
              aria-checked={option.value === value}
              className={option.value === value ? "is-active" : ""}
              key={option.value}
              onClick={(event) => {
                onChange(option.value);
                event.currentTarget.closest("details")?.removeAttribute("open");
              }}
            >
              <span>{option.label}</span>
              {option.value === value ? <Check size={15} /> : null}
            </button>
          ))}
        </div>
      </details>
      <button
        type="button"
        className="playlist-sort__direction"
        title={direction === "asc" ? ascendingLabel : descendingLabel}
        aria-label={direction === "asc" ? ascendingLabel : descendingLabel}
        disabled={value === "random"}
        onClick={() => onDirectionChange(direction === "asc" ? "desc" : "asc")}
      >
        {direction === "asc" ? <ArrowUp size={16} /> : <ArrowDown size={16} />}
      </button>
    </div>
  );
}

interface PlaylistsViewProps {
  bridge?: ARMusicBridge;
  tracks: Track[];
  onPlay: (track: Track, queue?: Track[]) => void;
  onEdit: (track: Track) => void;
  onNotice: (message: string) => void;
}

const emptyPayload: PlaylistsPayload = {
  schema: "armusic-playlists-v1",
  deviceId: "desktop-browser",
  generatedAt: new Date(0).toISOString(),
  snapshotId: "",
  playlists: [],
};

function newPlaylistId(): string {
  return globalThis.crypto?.randomUUID?.()
    ?? `desktop-playlist-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function PlaylistsView({ bridge, tracks, onPlay, onEdit, onNotice }: PlaylistsViewProps) {
  const { language, t } = useI18n();
  const ui = playlistUiCopy[language];
  const [payload, setPayload] = useState<PlaylistsPayload>(emptyPayload);
  const [activeId, setActiveId] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerSearch, setPickerSearch] = useState("");
  const [editorMode, setEditorMode] = useState<"create" | "edit" | null>(null);
  const [editorTitle, setEditorTitle] = useState("");
  const [editorSubtitle, setEditorSubtitle] = useState("");
  const [deletePending, setDeletePending] = useState(false);
  const [playlistSort, setPlaylistSort] = useState<PlaylistSortMode>(() => {
    const saved = localStorage.getItem("armusic-playlist-list-sort");
    return saved === "name" || saved === "count" || saved === "updated" ? saved : "custom";
  });
  const [playlistSortDirection, setPlaylistSortDirection] = useState<SortDirection>(() => (localStorage.getItem("armusic-playlist-list-direction") as SortDirection) || "asc");
  const [trackSort, setTrackSort] = useState<PlaylistTrackSortMode>(() => {
    const saved = localStorage.getItem("armusic-playlist-track-sort");
    return saved === "title" || saved === "artist" || saved === "album" ? saved : "order";
  });
  const [trackSortDirection, setTrackSortDirection] = useState<SortDirection>(() => (localStorage.getItem("armusic-playlist-track-direction") as SortDirection) || "asc");
  const [playlistRandomSeed, setPlaylistRandomSeed] = useState(() => String(Date.now()));
  const [trackRandomSeed, setTrackRandomSeed] = useState(() => String(Date.now()));

  const trackById = useMemo(() => {
    const result = new Map<string, Track>();
    tracks.forEach((track) => {
      result.set(track.syncId, track);
      track.legacySyncIds?.forEach((id) => result.set(id, track));
    });
    return result;
  }, [tracks]);
  const active = payload.playlists.find((playlist) => playlist.id === activeId)
    ?? payload.playlists[0];
  const activeTracks = useMemo(
    () => active?.trackIds.map((id) => trackById.get(id)).filter((track): track is Track => Boolean(track)) ?? [],
    [active, trackById],
  );
  const sortedPlaylists = useMemo(() => {
    const indexed = payload.playlists.map((playlist, index) => ({ playlist, index }));
    const factor = playlistSortDirection === "asc" ? 1 : -1;
    indexed.sort((left, right) => {
      let result = 0;
      if (playlistSort === "custom") result = left.index - right.index;
      if (playlistSort === "name") result = left.playlist.title.localeCompare(right.playlist.title, language, { numeric: true, sensitivity: "base" });
      if (playlistSort === "count") result = left.playlist.trackIds.length - right.playlist.trackIds.length;
      if (playlistSort === "updated") result = left.playlist.modifyTime - right.playlist.modifyTime;
      if (playlistSort === "random") result = stableNumber(`${playlistRandomSeed}:${left.playlist.id}`) - stableNumber(`${playlistRandomSeed}:${right.playlist.id}`);
      return (result || left.index - right.index) * factor;
    });
    return indexed.map(({ playlist }) => playlist);
  }, [language, payload.playlists, playlistRandomSeed, playlistSort, playlistSortDirection]);
  const sortedActiveEntries = useMemo(() => {
    const entries = (active?.trackIds ?? []).map((trackId, index) => ({
      trackId,
      track: trackById.get(trackId),
      index,
    }));
    const factor = trackSortDirection === "asc" ? 1 : -1;
    entries.sort((left, right) => {
      if (trackSort === "random") {
        return stableNumber(`${trackRandomSeed}:${left.trackId}`) - stableNumber(`${trackRandomSeed}:${right.trackId}`);
      }
      if (trackSort === "order") return (left.index - right.index) * factor;
      const leftValue = trackSort === "title"
        ? left.track?.title
        : trackSort === "artist"
          ? left.track?.artist
          : left.track?.album;
      const rightValue = trackSort === "title"
        ? right.track?.title
        : trackSort === "artist"
          ? right.track?.artist
          : right.track?.album;
      const result = (leftValue ?? left.trackId).localeCompare(
        rightValue ?? right.trackId,
        language,
        { numeric: true, sensitivity: "base" },
      );
      return (result || left.index - right.index) * factor;
    });
    return entries;
  }, [active, language, trackById, trackRandomSeed, trackSort, trackSortDirection]);
  useEffect(() => {
    if (playlistSort !== "random") localStorage.setItem("armusic-playlist-list-sort", playlistSort);
    localStorage.setItem("armusic-playlist-list-direction", playlistSortDirection);
    if (trackSort !== "random") localStorage.setItem("armusic-playlist-track-sort", trackSort);
    localStorage.setItem("armusic-playlist-track-direction", trackSortDirection);
    localStorage.removeItem("armusic-playlist-list-random-seed");
    localStorage.removeItem("armusic-playlist-track-random-seed");
  }, [playlistSort, playlistSortDirection, trackSort, trackSortDirection]);

  const changePlaylistSort = (next: PlaylistSortMode) => {
    if (next === "random") setPlaylistRandomSeed(`${Date.now()}-${Math.random()}`);
    setPlaylistSort(next);
  };
  const changeTrackSort = (next: PlaylistTrackSortMode) => {
    if (next === "random") setTrackRandomSeed(`${Date.now()}-${Math.random()}`);
    setTrackSort(next);
  };
  const visibleActiveTracks = useMemo(
    () => sortedActiveEntries.map(({ track }) => track).filter((track): track is Track => Boolean(track)),
    [sortedActiveEntries],
  );
  const activeCoverTracks = activeTracks.slice(0, 4);
  const pickerTracks = useMemo(() => {
    const query = pickerSearch.trim().toLocaleLowerCase();
    const existing = new Set(active?.trackIds ?? []);
    return tracks.filter((track) => {
      if (existing.has(track.syncId) || track.legacySyncIds?.some((id) => existing.has(id))) return false;
      if (!query) return true;
      return [track.title, track.artist, track.album, track.work]
        .filter(Boolean)
        .some((value) => value!.toLocaleLowerCase().includes(query));
    });
  }, [active, pickerSearch, tracks]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const next = bridge ? await bridge.getPlaylists() : emptyPayload;
      setPayload(next);
      setActiveId((current) => next.playlists.some((item) => item.id === current)
        ? current
        : next.playlists[0]?.id ?? "");
    } catch {
      onNotice(t("playlists.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [bridge, onNotice, t]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (!editorMode && !deletePending) return;
    const closeOnEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setEditorMode(null);
      setDeletePending(false);
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [deletePending, editorMode]);

  const save = async (playlists: PlaylistData[], nextActiveId = activeId) => {
    if (!bridge || saving) return;
    setSaving(true);
    try {
      const next = await bridge.savePlaylists({
        expectedSnapshotId: payload.snapshotId,
        playlists,
      });
      setPayload(next);
      setActiveId(next.playlists.some((item) => item.id === nextActiveId)
        ? nextActiveId
        : next.playlists[0]?.id ?? "");
    } catch {
      onNotice(t("playlists.saveFailed"));
      await load();
    } finally {
      setSaving(false);
    }
  };

  const openCreatePlaylist = () => {
    setEditorTitle("");
    setEditorSubtitle("");
    setEditorMode("create");
  };

  const openEditPlaylist = () => {
    if (!active) return;
    setEditorTitle(active.title);
    setEditorSubtitle(active.subTitle);
    setEditorMode("edit");
  };

  const submitPlaylistEditor = async () => {
    const title = editorTitle.trim();
    if (!title) return;
    if (editorMode === "edit" && active) {
      await save(payload.playlists.map((item) => item.id === active.id
        ? { ...item, title, subTitle: editorSubtitle.trim(), modifyTime: Date.now() }
        : item));
      setEditorMode(null);
      return;
    }
    const now = Date.now();
    const created: PlaylistData = {
      id: newPlaylistId(),
      title,
      subTitle: editorSubtitle.trim(),
      coverUri: "",
      createTime: now,
      modifyTime: now,
      trackIds: [],
    };
    await save([...payload.playlists, created], created.id);
    setEditorMode(null);
  };

  const deletePlaylist = async () => {
    if (!active) return;
    await save(payload.playlists.filter((item) => item.id !== active.id), "");
    setDeletePending(false);
    setPickerOpen(false);
  };

  const addTrack = async (track: Track) => {
    if (!active) return;
    await save(payload.playlists.map((item) => item.id === active.id
      ? { ...item, trackIds: [...item.trackIds, track.syncId], modifyTime: Date.now() }
      : item));
  };

  const removeTrack = async (trackId: string) => {
    if (!active) return;
    await save(payload.playlists.map((item) => item.id === active.id
      ? { ...item, trackIds: item.trackIds.filter((id) => id !== trackId), modifyTime: Date.now() }
      : item));
  };

  return (
    <section className="playlists-page">
      <header className="playlists-page__heading">
        <div>
          <span>PLAYLISTS</span>
          <h1>{t("nav.playlists")}</h1>
          <p>{t("playlists.subtitle")}</p>
        </div>
        <button className="button button--primary" disabled={saving} onClick={openCreatePlaylist}>
          {saving ? <RefreshCw className="spin" size={17} /> : <Plus size={17} />}
          {t("playlists.new")}
        </button>
      </header>

      {loading ? (
        <div className="playlists-page__loading"><RefreshCw className="spin" size={22} />{t("playlists.loading")}</div>
      ) : payload.playlists.length === 0 ? (
        <div className="playlists-page__empty">
          <ListMusic size={38} />
          <h2>{t("playlists.emptyTitle")}</h2>
          <p>{t("playlists.emptyBody")}</p>
          <button className="button button--primary" onClick={openCreatePlaylist}><Plus size={17} />{t("playlists.new")}</button>
        </div>
      ) : (
        <div className="playlists-layout">
          <aside className="playlists-list" aria-label={t("nav.playlists")}>
            <div className="playlists-list__toolbar">
              <div className="playlists-list__summary">
                <span>{t("nav.playlists")}</span>
                <strong>{String(payload.playlists.length).padStart(2, "0")}</strong>
              </div>
              <SortMenu
                compact
                label={ui.playlistSort}
                value={playlistSort}
                direction={playlistSortDirection}
                ascendingLabel={ui.ascending}
                descendingLabel={ui.descending}
                onChange={changePlaylistSort}
                onDirectionChange={setPlaylistSortDirection}
                options={[
                  { value: "custom", label: ui.custom },
                  { value: "name", label: ui.name },
                  { value: "count", label: ui.count },
                  { value: "updated", label: ui.updated },
                  { value: "random", label: ui.random },
                ]}
              />
            </div>
            <div className="playlists-list__items">
              {sortedPlaylists.map((playlist) => {
                const coverTracks = playlist.trackIds
                  .map((id) => trackById.get(id))
                  .filter((track): track is Track => Boolean(track))
                  .slice(0, 4);
                return (
                  <button
                    key={playlist.id}
                    className={playlist.id === active?.id ? "is-active" : ""}
                    aria-pressed={playlist.id === active?.id}
                    onClick={() => { setActiveId(playlist.id); setPickerOpen(false); }}
                  >
                    {coverTracks.length
                      ? <span className={`playlists-list__covers count-${coverTracks.length}`}>{coverTracks.map((track, index) => <CoverArt key={`${track.syncId}-${index}`} track={track} decorative />)}</span>
                      : <span className="playlists-list__placeholder"><ListMusic size={19} /></span>}
                    <span className="playlists-list__copy"><strong>{playlist.title}</strong><small>{t("playlists.trackCount", { count: playlist.trackIds.length })}</small></span>
                    <span className="playlists-list__chevron"><ChevronRight size={16} /></span>
                  </button>
                );
              })}
            </div>
          </aside>

          {active ? (
            <article className="playlist-detail">
              <header className="playlist-detail__header">
                <div className="playlist-detail__identity">
                  {activeCoverTracks.length ? (
                    <span className={`playlist-detail__cover count-${activeCoverTracks.length}`}>
                      {activeCoverTracks.map((track, index) => <CoverArt key={`${track.syncId}-${index}`} track={track} decorative />)}
                    </span>
                  ) : (
                    <span className="playlist-detail__cover playlist-detail__cover--empty"><ListMusic size={28} /></span>
                  )}
                  <div className="playlist-detail__copy">
                    <span className="playlist-detail__eyebrow">AR MUSIC · {t("playlists.trackCount", { count: active.trackIds.length })}</span>
                    <h2>{active.title}</h2>
                    {active.subTitle ? <p>{active.subTitle}</p> : null}
                  </div>
                </div>
                <div className="playlist-detail__actions">
                  <button className="button button--soft" disabled={!visibleActiveTracks.length} onClick={() => visibleActiveTracks[0] && onPlay(visibleActiveTracks[0], visibleActiveTracks)}><Play size={16} />{t("playlists.playAll")}</button>
                  <button className="icon-button" title={t("playlists.edit")} onClick={openEditPlaylist}><Pencil size={17} /></button>
                  <button className="icon-button" title={t("wishlist.remove")} onClick={() => setDeletePending(true)}><Trash2 size={17} /></button>
                  <button className="button button--primary" onClick={() => setPickerOpen((value) => !value)}><Plus size={17} />{t("playlists.addSongs")}</button>
                </div>
              </header>

              {pickerOpen ? (
                <section className="playlist-picker">
                  <header><strong>{t("playlists.chooseSongs")}</strong><button onClick={() => setPickerOpen(false)} aria-label={t("tag.close")}><X size={18} /></button></header>
                  <label><Search size={17} /><input value={pickerSearch} onChange={(event) => setPickerSearch(event.target.value)} placeholder={t("top.search")} /></label>
                  <div className="playlist-picker__list">
                    {pickerTracks.map((track) => (
                      <button key={track.syncId} disabled={saving} onClick={() => void addTrack(track)} onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)}>
                        <CoverArt track={track} decorative />
                        <span><strong>{track.title}</strong><small>{track.artist}</small></span>
                        <Plus size={17} />
                      </button>
                    ))}
                    {!pickerTracks.length ? <p>{t("playlists.noSongsToAdd")}</p> : null}
                  </div>
                </section>
              ) : null}

              <div className="playlist-detail__toolbar">
                <div>
                  <span>{ui.songs}</span>
                  <strong>{String(active.trackIds.length).padStart(2, "0")}</strong>
                </div>
                <SortMenu
                  label={ui.trackSort}
                  value={trackSort}
                  direction={trackSortDirection}
                  ascendingLabel={ui.ascending}
                  descendingLabel={ui.descending}
                  onChange={changeTrackSort}
                  onDirectionChange={setTrackSortDirection}
                  options={[
                    { value: "order", label: ui.order },
                    { value: "title", label: ui.title },
                    { value: "artist", label: ui.artist },
                    { value: "album", label: ui.album },
                    { value: "random", label: ui.random },
                  ]}
                />
              </div>

              <div className="playlist-track-list">
                {sortedActiveEntries.map(({ trackId, track }, index) => {
                  return (
                    <div key={`${trackId}-${index}`} className={!track ? "is-missing" : ""} onContextMenu={track ? (event) => editTrackFromContextMenu(event, track, onEdit) : undefined}>
                      <span className="playlist-track-list__index">{String(index + 1).padStart(2, "0")}</span>
                      {track ? <CoverArt track={track} decorative /> : <span className="playlist-track-list__missing"><ListMusic size={16} /></span>}
                      <button className="playlist-track-list__title" disabled={!track} onClick={() => track && onPlay(track, visibleActiveTracks)} onContextMenu={track ? (event) => editTrackFromContextMenu(event, track, onEdit) : undefined}>
                        <strong>{track?.title ?? t("playlists.missingTrack")}</strong>
                        <small>{track?.artist ?? trackId}</small>
                      </button>
                      {track ? <small className="playlist-track-list__context" title={track.album || track.work}>{track.album || track.work}</small> : null}
                      <button className="playlist-track-list__remove" disabled={saving} onClick={() => void removeTrack(trackId)} aria-label={t("wishlist.remove")}><Trash2 size={16} /></button>
                    </div>
                  );
                })}
                {!active.trackIds.length ? <div className="playlist-detail__empty"><ListMusic size={28} /><strong>{t("playlists.emptyPlaylist")}</strong><span>{t("playlists.emptyPlaylistBody")}</span></div> : null}
              </div>
            </article>
          ) : null}
        </div>
      )}

      {editorMode ? (
        <div className="playlist-dialog-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && setEditorMode(null)}>
          <form className="playlist-dialog" role="dialog" aria-modal="true" aria-labelledby="playlist-editor-title" onSubmit={(event) => { event.preventDefault(); void submitPlaylistEditor(); }}>
            <header><div><span>AR MUSIC PLAYLIST</span><h2 id="playlist-editor-title">{editorMode === "create" ? t("playlists.new") : t("playlists.edit")}</h2></div><button type="button" onClick={() => setEditorMode(null)} aria-label={t("tag.close")}><X size={19} /></button></header>
            <label><span>{t("playlists.renamePrompt")}</span><input autoFocus value={editorTitle} onChange={(event) => setEditorTitle(event.target.value)} maxLength={200} /></label>
            <label><span>{t("playlists.subtitlePrompt")}</span><textarea value={editorSubtitle} onChange={(event) => setEditorSubtitle(event.target.value)} maxLength={1000} rows={3} /></label>
            <footer><button type="button" className="button button--soft" onClick={() => setEditorMode(null)}>{t("tag.cancel")}</button><button className="button button--primary" disabled={saving || !editorTitle.trim()}>{saving ? <RefreshCw className="spin" size={16} /> : <Check size={16} />}{t("tag.save")}</button></footer>
          </form>
        </div>
      ) : null}

      {deletePending && active ? (
        <div className="playlist-dialog-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && setDeletePending(false)}>
          <section className="playlist-dialog playlist-dialog--confirm" role="alertdialog" aria-modal="true">
            <header><div><span>PLAYLIST</span><h2>{t("wishlist.remove")}</h2></div><button onClick={() => setDeletePending(false)} aria-label={t("tag.close")}><X size={19} /></button></header>
            <p>{t("playlists.deleteConfirm", { title: active.title })}</p>
            <footer><button className="button button--soft" onClick={() => setDeletePending(false)}>{t("tag.cancel")}</button><button className="button playlist-dialog__danger" disabled={saving} onClick={() => void deletePlaylist()}><Trash2 size={16} />{t("wishlist.remove")}</button></footer>
          </section>
        </div>
      ) : null}
    </section>
  );
}

export function AddTrackToPlaylistDialog({
  bridge,
  track,
  onClose,
  onNotice,
}: {
  bridge: ARMusicBridge;
  track: Track;
  onClose: () => void;
  onNotice: (message: string) => void;
}) {
  const { t } = useI18n();
  const [payload, setPayload] = useState<PlaylistsPayload | null>(null);
  const [savingId, setSavingId] = useState("");
  const [newTitle, setNewTitle] = useState("");
  const trackAliases = useMemo(
    () => new Set([track.syncId, ...(track.legacySyncIds ?? [])]),
    [track.legacySyncIds, track.syncId],
  );

  useEffect(() => {
    let active = true;
    void bridge.getPlaylists()
      .then((next) => { if (active) setPayload(next); })
      .catch(() => { onNotice(t("playlists.loadFailed")); onClose(); });
    return () => { active = false; };
  }, [bridge, onClose, onNotice, t]);

  useEffect(() => {
    const closeOnEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [onClose]);

  const persist = async (playlists: PlaylistData[], chosenId: string) => {
    if (!payload || savingId) return;
    setSavingId(chosenId);
    try {
      await bridge.savePlaylists({ expectedSnapshotId: payload.snapshotId, playlists });
      onNotice(t("playlists.addedNotice", { title: track.title }));
      onClose();
    } catch {
      onNotice(t("playlists.saveFailed"));
      onClose();
    }
  };

  const addTo = (playlist: PlaylistData) => {
    if (playlist.trackIds.some((id) => trackAliases.has(id))) return;
    void persist(payload!.playlists.map((item) => item.id === playlist.id
      ? {
        ...item,
        trackIds: [...item.trackIds.filter((id) => !trackAliases.has(id)), track.syncId],
        modifyTime: Date.now(),
      }
      : item), playlist.id);
  };

  const createAndAdd = () => {
    const title = newTitle.trim();
    if (!title || !payload) return;
    const now = Date.now();
    const created: PlaylistData = {
      id: newPlaylistId(),
      title,
      subTitle: "",
      coverUri: "",
      createTime: now,
      modifyTime: now,
      trackIds: [track.syncId],
    };
    void persist([...payload.playlists, created], created.id);
  };

  return (
    <div className="playlist-dialog-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="playlist-dialog playlist-add-dialog" role="dialog" aria-modal="true" aria-label={t("playlists.addToPlaylist")}>
        <header><div><span>ADD TO PLAYLIST</span><h2>{t("playlists.addToPlaylist")}</h2></div><button onClick={onClose} aria-label={t("tag.close")}><X size={19} /></button></header>
        <div className="playlist-add-dialog__track"><CoverArt track={track} decorative /><span><strong>{track.title}</strong><small>{track.artist}</small></span></div>
        <div className="playlist-add-dialog__new"><input value={newTitle} onChange={(event) => setNewTitle(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") createAndAdd(); }} placeholder={t("playlists.namePrompt")} /><button className="button button--soft" disabled={!newTitle.trim() || Boolean(savingId)} onClick={createAndAdd}><Plus size={16} />{t("playlists.newAndAdd")}</button></div>
        <div className="playlist-add-dialog__list">
          {!payload ? <div className="playlists-page__loading"><RefreshCw className="spin" size={18} />{t("playlists.loading")}</div> : payload.playlists.map((playlist) => {
            const added = playlist.trackIds.some((id) => trackAliases.has(id));
            return <button key={playlist.id} disabled={added || Boolean(savingId)} onClick={() => addTo(playlist)}><span className="playlists-list__placeholder"><ListMusic size={17} /></span><span><strong>{playlist.title}</strong><small>{added ? t("playlists.alreadyAdded") : t("playlists.trackCount", { count: playlist.trackIds.length })}</small></span>{savingId === playlist.id ? <RefreshCw className="spin" size={17} /> : added ? <Check size={17} /> : <Plus size={17} />}</button>;
          })}
        </div>
      </section>
    </div>
  );
}
