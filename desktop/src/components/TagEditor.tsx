import {
  FileAudio2,
  ImagePlus,
  LoaderCircle,
  RotateCcw,
  Save,
  Search,
  Trash2,
  X,
} from "lucide-react";
import { useEffect, useRef, useState, type ChangeEvent, type MouseEvent } from "react";
import type {
  ARMusicBridge,
  CoverAction,
  TagSaveResult,
  Track,
  TrackTagData,
  UpdateTrackTagsRequest,
} from "../types";
import { useI18n } from "../i18n";
import "./TagEditor.css";

const maxCoverBytes = 20 * 1024 * 1024;

type TagEditorBridge = Pick<ARMusicBridge, "getTrackTags" | "saveTrackTags">;

interface TagEditorProps {
  track: Track;
  bridge: TagEditorBridge;
  workLabel?: string;
  onClose: () => void;
  onSaved: (result: TagSaveResult) => void;
  onNotice?: (message: string) => void;
}

interface EditableFields {
  title: string;
  artist: string;
  album: string;
  work: string;
  sameSongGroup: string;
  genre: string;
  date: string;
  lyrics: string;
}

interface OnlineTagCandidate {
  id: string;
  source: "iTunes" | "LRCLIB";
  title: string;
  artist: string;
  album: string;
  genre?: string;
  date?: string;
  coverUrl?: string;
  lyrics?: string;
}

interface ITunesSearchResponse {
  results?: Array<{
    trackId?: number;
    trackName?: string;
    artistName?: string;
    collectionName?: string;
    primaryGenreName?: string;
    releaseDate?: string;
    artworkUrl100?: string;
  }>;
}

interface LrcLibResult {
  id?: number;
  trackName?: string;
  artistName?: string;
  albumName?: string;
  syncedLyrics?: string;
  plainLyrics?: string;
}

function searchITunes(query: string): Promise<OnlineTagCandidate[]> {
  return new Promise((resolve, reject) => {
    const callbackName = `__armusicItunes${Date.now()}${Math.random().toString(36).slice(2)}`;
    const script = document.createElement("script");
    const callbacks = window as unknown as Record<string, unknown>;
    let timeout = 0;
    const finish = (error?: Error, payload?: ITunesSearchResponse) => {
      window.clearTimeout(timeout);
      script.remove();
      delete callbacks[callbackName];
      if (error) reject(error);
      else resolve((payload?.results ?? []).map((item, index) => ({
        id: `itunes-${item.trackId ?? index}`,
        source: "iTunes" as const,
        title: item.trackName ?? "",
        artist: item.artistName ?? "",
        album: item.collectionName ?? "",
        genre: item.primaryGenreName,
        date: item.releaseDate?.slice(0, 10),
        coverUrl: item.artworkUrl100?.replace(/100x100bb/, "1000x1000bb"),
      })));
    };
    timeout = window.setTimeout(() => finish(new Error("iTunes search timed out")), 10000);
    callbacks[callbackName] = (payload: ITunesSearchResponse) => finish(undefined, payload);
    script.onerror = () => finish(new Error("iTunes search failed"));
    script.src = `https://itunes.apple.com/search?term=${encodeURIComponent(query)}&media=music&entity=song&limit=16&callback=${callbackName}`;
    document.head.appendChild(script);
  });
}

async function searchLrcLib(query: string): Promise<OnlineTagCandidate[]> {
  const response = await fetch(`https://lrclib.net/api/search?q=${encodeURIComponent(query)}`);
  if (!response.ok) throw new Error(`LRCLIB ${response.status}`);
  const results = await response.json() as LrcLibResult[];
  return results.slice(0, 16).map((item, index) => ({
    id: `lrclib-${item.id ?? index}`,
    source: "LRCLIB",
    title: item.trackName ?? "",
    artist: item.artistName ?? "",
    album: item.albumName ?? "",
    lyrics: item.syncedLyrics || item.plainLyrics || "",
  }));
}

function dataUrlFromBlob(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => typeof reader.result === "string" ? resolve(reader.result) : reject(new Error("image unreadable"));
    reader.onerror = () => reject(reader.error ?? new Error("image unreadable"));
    reader.readAsDataURL(blob);
  });
}

function editableFields(data: TrackTagData): EditableFields {
  return {
    title: data.title,
    artist: data.artist,
    album: data.album,
    work: data.work,
    sameSongGroup: data.sameSongGroup,
    genre: data.genre,
    date: data.date,
    lyrics: data.lyrics,
  };
}

function errorMessage(error: unknown, fallback: string): string {
  if (typeof error === "string") return error;
  if (error instanceof Error) return error.message;
  return fallback;
}

export function TagEditor({ track, bridge, workLabel, onClose, onSaved, onNotice }: TagEditorProps) {
  const { t } = useI18n();
  const titleRef = useRef<HTMLInputElement>(null);
  const coverInputRef = useRef<HTMLInputElement>(null);
  const [data, setData] = useState<TrackTagData | null>(null);
  const [fields, setFields] = useState<EditableFields | null>(null);
  const [coverAction, setCoverAction] = useState<CoverAction>("keep");
  const [coverPreview, setCoverPreview] = useState<string>();
  const [coverDataBase64, setCoverDataBase64] = useState<string>();
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<OnlineTagCandidate[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [searchMessage, setSearchMessage] = useState("");

  useEffect(() => {
    let active = true;
    setIsLoading(true);
    setError("");
    void bridge
      .getTrackTags(track.syncId)
      .then((next) => {
        if (!active) return;
        setData(next);
        setFields(editableFields(next));
        setCoverPreview(next.coverDataUrl);
        window.setTimeout(() => titleRef.current?.focus(), 0);
      })
      .catch((nextError) => active && setError(errorMessage(nextError, t("tag.loadFailed"))))
      .finally(() => active && setIsLoading(false));
    return () => {
      active = false;
    };
  }, [bridge, t, track.syncId]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !isSaving) onClose();
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        document.querySelector<HTMLButtonElement>("[data-tag-editor-save]")?.click();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [isSaving, onClose]);

  function update<K extends keyof EditableFields>(key: K, value: EditableFields[K]) {
    setFields((current) => (current ? { ...current, [key]: value } : current));
  }

  function closeFromBackdrop(event: MouseEvent<HTMLDivElement>) {
    if (event.target === event.currentTarget && !isSaving) onClose();
  }

  function chooseCover(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      setError(t("tag.imageOnly"));
      return;
    }
    if (file.size > maxCoverBytes) {
      setError(t("tag.coverLimit"));
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : "";
      const separator = result.indexOf(",");
      if (!result.startsWith("data:") || separator < 0) {
        setError(t("tag.imageUnreadable"));
        return;
      }
      setCoverAction("replace");
      setCoverPreview(result);
      setCoverDataBase64(result.slice(separator + 1));
      setError("");
    };
    reader.onerror = () => setError(t("tag.imageUnreadable"));
    reader.readAsDataURL(file);
  }

  function removeCover() {
    setCoverAction("remove");
    setCoverPreview(undefined);
    setCoverDataBase64(undefined);
    setError("");
  }

  function resetCover() {
    setCoverAction("keep");
    setCoverPreview(data?.coverDataUrl);
    setCoverDataBase64(undefined);
    setError("");
  }

  function toggleSearch() {
    setSearchOpen((current) => {
      if (!current && !searchQuery.trim()) setSearchQuery([fields?.title, fields?.artist].filter(Boolean).join(" "));
      return !current;
    });
  }

  async function runOnlineSearch() {
    const query = searchQuery.trim();
    if (!query || isSearching) return;
    setIsSearching(true);
    setSearchMessage("");
    const responses = await Promise.allSettled([searchITunes(query), searchLrcLib(query)]);
    const found = responses.flatMap((response) => response.status === "fulfilled" ? response.value : []);
    setSearchResults(found);
    setSearchMessage(found.length ? "" : responses.every((response) => response.status === "rejected") ? t("tag.searchFailed") : t("tag.searchEmpty"));
    setIsSearching(false);
  }

  async function applyOnlineResult(candidate: OnlineTagCandidate) {
    setFields((current) => current ? {
      ...current,
      title: candidate.title || current.title,
      artist: candidate.artist || current.artist,
      album: candidate.album || current.album,
      genre: candidate.genre || current.genre,
      date: candidate.date || current.date,
      lyrics: candidate.lyrics || current.lyrics,
    } : current);
    if (candidate.coverUrl) {
      try {
        const response = await fetch(candidate.coverUrl);
        if (!response.ok) throw new Error(String(response.status));
        const result = await dataUrlFromBlob(await response.blob());
        const separator = result.indexOf(",");
        if (separator < 0) throw new Error("image unreadable");
        setCoverAction("replace");
        setCoverPreview(result);
        setCoverDataBase64(result.slice(separator + 1));
      } catch {
        setError(t("tag.coverDownloadFailed"));
      }
    }
    setSearchMessage(t("tag.appliedResult"));
  }

  async function save() {
    if (!fields || !data || isSaving) return;
    const request: UpdateTrackTagsRequest = {
      syncId: data.syncId,
      ...fields,
      coverAction,
      coverDataBase64: coverAction === "replace" ? coverDataBase64 : undefined,
    };
    setIsSaving(true);
    setError("");
    try {
      const result = await bridge.saveTrackTags(request);
      onSaved(result);
      onNotice?.(result.warning || t("tag.saved"));
      onClose();
    } catch (nextError) {
      setError(errorMessage(nextError, t("tag.saveFailed")));
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <div className="tag-editor-backdrop" role="presentation" onMouseDown={closeFromBackdrop}>
      <section
        className="tag-editor"
        role="dialog"
        aria-modal="true"
        aria-labelledby="tag-editor-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="tag-editor__header">
          <div>
            <span>{t("tag.eyebrow")}</span>
            <h2 id="tag-editor-title">{t("tag.title")}</h2>
          </div>
          <div className="tag-editor__header-actions">
            <button type="button" className={searchOpen ? "is-active" : ""} onClick={toggleSearch} aria-label={t("tag.searchOnline")}><Search size={18} /></button>
            <button type="button" onClick={onClose} disabled={isSaving} aria-label={t("tag.close")}><X size={19} /></button>
          </div>
        </header>

        {isLoading ? (
          <div className="tag-editor__loading">
            <LoaderCircle className="spin" size={24} />
            <span>{t("tag.loading")}</span>
          </div>
        ) : fields && data ? (
          <div className="tag-editor__body">
            <aside className="tag-editor__cover-column">
              <button
                type="button"
                className={`tag-editor__cover ${coverPreview ? "has-picture" : ""}`}
                onClick={() => coverInputRef.current?.click()}
                aria-label={t("tag.replaceCover")}
              >
                {coverPreview ? <img src={coverPreview} alt={t("tag.coverPreview")} /> : <ImagePlus size={28} />}
                <span>{coverPreview ? t("tag.replaceCover") : t("tag.addCover")}</span>
              </button>
              <input
                ref={coverInputRef}
                type="file"
                accept="image/jpeg,image/png,image/gif,image/bmp,image/tiff"
                hidden
                onChange={chooseCover}
              />
              <div className="tag-editor__cover-actions">
                <button type="button" onClick={() => coverInputRef.current?.click()}>
                  <ImagePlus size={15} />{t("tag.replace")}
                </button>
                <button type="button" onClick={removeCover} disabled={!coverPreview && coverAction !== "replace"}>
                  <Trash2 size={15} />{t("tag.remove")}
                </button>
                {coverAction !== "keep" ? (
                  <button type="button" onClick={resetCover}>
                    <RotateCcw size={15} />{t("tag.restore")}
                  </button>
                ) : null}
              </div>
              <div className="tag-editor__file">
                <FileAudio2 size={16} />
                <span><strong>{data.fileName}</strong><small>{data.relativePath}</small></span>
              </div>
              <p>{t("tag.safety")}</p>
            </aside>

            <div className={`tag-editor__form ${searchOpen ? "has-online-search" : ""}`}>
              {searchOpen ? (
                <section className="tag-editor__online-search">
                  <form onSubmit={(event) => { event.preventDefault(); void runOnlineSearch(); }}>
                    <Search size={17} />
                    <input autoFocus value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} placeholder={t("tag.searchPlaceholder")} />
                    <button type="submit" disabled={isSearching || !searchQuery.trim()}>{isSearching ? <LoaderCircle className="spin" size={15} /> : null}{t("tag.searchAction")}</button>
                  </form>
                  {searchMessage ? <p>{searchMessage}</p> : null}
                  {searchResults.length ? <div className="tag-editor__search-results">{searchResults.map((candidate) => (
                    <button type="button" key={candidate.id} onClick={() => void applyOnlineResult(candidate)}>
                      {candidate.coverUrl ? <img src={candidate.coverUrl} alt="" /> : <span><FileAudio2 size={16} /></span>}
                      <span><strong>{candidate.title || t("tag.untitled")}</strong><small>{[candidate.artist, candidate.album].filter(Boolean).join(" · ")}</small></span>
                      <em>{candidate.source}{candidate.lyrics ? ` · ${t("tag.hasLyrics")}` : ""}</em>
                    </button>
                  ))}</div> : null}
                </section>
              ) : null}
              <div className="tag-editor__fields">
                <label className="tag-editor__field tag-editor__field--wide">
                  <span>{t("tag.songTitle")}</span>
                  <input ref={titleRef} value={fields.title} maxLength={4096} onChange={(event) => update("title", event.target.value)} />
                </label>
                <label className="tag-editor__field">
                  <span>{t("tag.artist")}</span>
                  <input value={fields.artist} maxLength={4096} onChange={(event) => update("artist", event.target.value)} />
                </label>
                <label className="tag-editor__field">
                  <span>{t("tag.album")}</span>
                  <input value={fields.album} maxLength={4096} onChange={(event) => update("album", event.target.value)} />
                </label>
                <label className="tag-editor__field">
                  <span>{workLabel || t("tag.work")}</span>
                  <input value={fields.work} maxLength={4096} onChange={(event) => update("work", event.target.value)} placeholder={t("tag.workPlaceholder")} />
                </label>
                <label className="tag-editor__field">
                  <span>{t("tag.sameSongGroup")}</span>
                  <input value={fields.sameSongGroup} maxLength={4096} onChange={(event) => update("sameSongGroup", event.target.value)} placeholder={t("tag.sameSongGroupPlaceholder")} />
                </label>
                <label className="tag-editor__field">
                  <span>{t("tag.genre")}</span>
                  <input value={fields.genre} maxLength={4096} onChange={(event) => update("genre", event.target.value)} />
                </label>
                <label className="tag-editor__field">
                  <span>{t("tag.date")}</span>
                  <input value={fields.date} maxLength={19} onChange={(event) => update("date", event.target.value)} placeholder={t("tag.datePlaceholder")} />
                </label>
              </div>
              <label className="tag-editor__field tag-editor__lyrics">
                <span>{t("tag.lyrics")} <small>{t("tag.lyricsHint")}</small></span>
                <textarea value={fields.lyrics} maxLength={4 * 1024 * 1024} onChange={(event) => update("lyrics", event.target.value)} placeholder={t("tag.lyricsPlaceholder")} />
              </label>
            </div>
          </div>
        ) : null}

        <footer className="tag-editor__footer">
          <div>{error ? <span className="tag-editor__error">{error}</span> : <span>{t("tag.shortcut")}</span>}</div>
          <button type="button" className="tag-editor__cancel" onClick={onClose} disabled={isSaving}>{t("tag.cancel")}</button>
          <button
            type="button"
            className="tag-editor__save"
            onClick={save}
            disabled={isLoading || !fields || isSaving}
            data-tag-editor-save
          >
            {isSaving ? <LoaderCircle className="spin" size={16} /> : <Save size={16} />}
            {isSaving ? t("tag.saving") : t("tag.save")}
          </button>
        </footer>
      </section>
    </div>
  );
}
