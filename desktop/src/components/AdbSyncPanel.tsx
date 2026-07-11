import {
  AlertTriangle,
  ArrowDownToLine,
  ArrowRight,
  ArrowUpToLine,
  Check,
  Clock3,
  Computer,
  History,
  LoaderCircle,
  Music2,
  RefreshCw,
  Sparkles,
  Smartphone,
  Usb,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useI18n } from "../i18n";
import type {
  ARMusicBridge,
  AdbDevice,
  AdbSyncPreview,
  ConflictResolution,
  ExecuteAdbSyncResult,
  HistorySyncMode,
} from "../types";
import "./AdbSyncPanel.css";

interface AdbSyncPanelProps {
  bridge?: ARMusicBridge;
  onLibraryChanged: () => void | Promise<void>;
  onNotice: (message: string) => void;
}

type Translate = (key: string, variables?: Record<string, string | number>) => string;

export function AdbSyncPanel({
  bridge,
  onLibraryChanged,
  onNotice,
}: AdbSyncPanelProps) {
  const { t } = useI18n();
  // The adb.* messages live with the rest of the application dictionary. Keeping
  // the adapter local lets this isolated panel land independently of that file.
  const tr = t as Translate;
  const [devices, setDevices] = useState<AdbDevice[]>([]);
  const [selectedSerial, setSelectedSerial] = useState("");
  const [preview, setPreview] = useState<AdbSyncPreview | null>(null);
  // Destructive history behavior is deliberately opt-in again on every launch.
  const [historyMode, setHistoryMode] = useState<HistorySyncMode>("keepOnBoth");
  const [conflictResolutions, setConflictResolutions] = useState<
    Record<string, ConflictResolution>
  >({});
  const [isLoadingDevices, setIsLoadingDevices] = useState(false);
  const [isLoadingPreview, setIsLoadingPreview] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);
  const [errorKey, setErrorKey] = useState("");
  const [errorDetail, setErrorDetail] = useState("");
  const [lastResult, setLastResult] = useState<ExecuteAdbSyncResult | null>(null);

  const refreshDevices = useCallback(async () => {
    if (!bridge) {
      setDevices([]);
      setSelectedSerial("");
      setPreview(null);
      return;
    }

    setIsLoadingDevices(true);
    setErrorKey("");
    setErrorDetail("");
    try {
      const nextDevices = await bridge.listAdbDevices();
      setDevices(nextDevices);
      setSelectedSerial((current) => {
        if (nextDevices.some((device) => device.serial === current)) return current;
        return nextDevices[0]?.serial ?? "";
      });
      if (nextDevices.length === 0) setPreview(null);
    } catch (error) {
      setDevices([]);
      setSelectedSerial("");
      setPreview(null);
      setErrorKey("adb.devicesFailed");
      setErrorDetail(error instanceof Error ? error.message : typeof error === "string" ? error : "");
    } finally {
      setIsLoadingDevices(false);
    }
  }, [bridge]);

  useEffect(() => {
    void refreshDevices();
  }, [refreshDevices]);

  const refreshPreview = useCallback(async () => {
    if (!bridge || !selectedSerial) {
      setPreview(null);
      return;
    }

    setIsLoadingPreview(true);
    setErrorKey("");
    setErrorDetail("");
    try {
      const nextPreview = await bridge.previewAdbSync(selectedSerial);
      setPreview(nextPreview);
      setConflictResolutions(() => {
        const next: Record<string, ConflictResolution> = {};
        nextPreview.conflicts.forEach((conflict) => {
          next[conflict.syncId] = "skip";
        });
        return next;
      });
    } catch (error) {
      setPreview(null);
      setErrorKey("adb.previewFailed");
      setErrorDetail(error instanceof Error ? error.message : typeof error === "string" ? error : "");
    } finally {
      setIsLoadingPreview(false);
    }
  }, [bridge, selectedSerial]);

  useEffect(() => {
    void refreshPreview();
  }, [refreshPreview]);

  const selectHistoryMode = (mode: HistorySyncMode) => {
    setHistoryMode(mode);
  };

  const selectedDevice = useMemo(
    () => devices.find((device) => device.serial === selectedSerial),
    [devices, selectedSerial],
  );

  const executeSync = async () => {
    if (!bridge || !preview || !selectedSerial || isSyncing) return;

    let confirmDeletePhoneHistory = false;
    if (historyMode === "desktopOnly") {
      const phoneRowsToClear = Math.max(preview.phoneHistoryCount, preview.phoneRawHistoryCount);
      const firstConfirmed = window.confirm(
        tr("adb.desktopOnlyConfirmFirst", { count: phoneRowsToClear }),
      );
      if (!firstConfirmed) return;
      const secondConfirmed = window.confirm(
        tr("adb.desktopOnlyConfirmSecond", { count: phoneRowsToClear }),
      );
      if (!secondConfirmed) return;
      confirmDeletePhoneHistory = true;
    }

    setIsSyncing(true);
    setErrorKey("");
    setErrorDetail("");
    setLastResult(null);
    try {
      const result = await bridge.executeAdbSync({
        serial: selectedSerial,
        syncSongs: true,
        syncWishlist: true,
        syncPlaylists: true,
        historyMode,
        confirmDeletePhoneHistory,
        confirmedPhoneSnapshotId: historyMode === "desktopOnly"
          ? preview.phoneHistorySnapshotId
          : undefined,
        confirmedPhoneHistoryCount: historyMode === "desktopOnly"
          ? preview.phoneHistoryCount
          : undefined,
        confirmedPhoneRawSnapshotId: historyMode === "desktopOnly"
          ? preview.phoneRawSnapshotId
          : undefined,
        confirmedPhoneRawHistoryCount: historyMode === "desktopOnly"
          ? preview.phoneRawHistoryCount
          : undefined,
        // A timestamp is only a hint in the UI. Never overwrite a conflict until
        // the user has explicitly selected a side.
        applyNewerConflicts: false,
        conflictResolutions,
      });
      setLastResult(result);
      await onLibraryChanged();
      onNotice(
        tr("adb.completed", {
          uploaded: result.uploadedToPhone,
          downloaded: result.downloadedToDesktop,
          histories: result.desktopHistorySessions,
        }),
      );
      await refreshPreview();
    } catch (error) {
      setErrorKey("adb.executeFailed");
      setErrorDetail(error instanceof Error ? error.message : typeof error === "string" ? error : "");
      onNotice(tr("adb.executeFailed"));
    } finally {
      setIsSyncing(false);
    }
  };

  const isBusy = isLoadingDevices || isLoadingPreview || isSyncing;
  const refreshAll = () => {
    void refreshDevices();
    if (selectedSerial) void refreshPreview();
  };

  return (
    <section className="adb-sync-panel" aria-labelledby="adb-sync-title">
      <header className="adb-sync-panel__header">
        <div className="adb-sync-panel__heading">
          <span className="adb-sync-panel__eyebrow">
            <Usb size={13} aria-hidden="true" />
            {tr("adb.eyebrow")}
          </span>
          <h2 id="adb-sync-title">{tr("adb.title")}</h2>
          <p>{tr("adb.subtitle")}</p>
        </div>
        <button
          type="button"
          className="adb-sync-panel__refresh"
          disabled={!bridge || isBusy}
          onClick={refreshAll}
        >
          <RefreshCw className={isBusy ? "is-spinning" : ""} size={15} aria-hidden="true" />
          {isLoadingDevices ? tr("adb.refreshing") : tr("adb.refresh")}
        </button>
      </header>

      {!bridge ? (
        <div className="adb-sync-panel__empty">
          <Usb size={23} aria-hidden="true" />
          <strong>{tr("adb.desktopOnlyFeature")}</strong>
          <span>{tr("adb.desktopOnlyFeatureBody")}</span>
        </div>
      ) : devices.length === 0 && !isLoadingDevices ? (
        <div className="adb-sync-panel__empty">
          <Smartphone size={23} aria-hidden="true" />
          <strong>{tr("adb.noDevice")}</strong>
          <span>{tr("adb.noDeviceBody")}</span>
        </div>
      ) : (
        <>
          <div className="adb-sync-panel__device-row">
            <label htmlFor="adb-device-select">
              <Smartphone size={15} aria-hidden="true" />
              <span>{tr("adb.device")}</span>
            </label>
            <select
              id="adb-device-select"
              value={selectedSerial}
              disabled={isBusy}
              onChange={(event) => {
                setSelectedSerial(event.target.value);
                setLastResult(null);
              }}
            >
              {devices.map((device) => (
                <option key={device.serial} value={device.serial}>
                  {device.model || tr("adb.unknownDevice")} · {device.serial}
                </option>
              ))}
            </select>
            {selectedDevice && (
              <span className="adb-sync-panel__connected">
                <i aria-hidden="true" />
                {tr("adb.connected")}
              </span>
            )}
          </div>

          {isLoadingPreview && !preview ? (
            <div className="adb-sync-panel__loading" role="status">
              <LoaderCircle className="is-spinning" size={19} aria-hidden="true" />
              {tr("adb.previewing")}
            </div>
          ) : preview ? (
            <>
              <div className="adb-sync-panel__stats" aria-label={tr("adb.previewSummary")}>
                <article>
                  <span className="adb-sync-panel__stat-icon"><Computer size={17} /></span>
                  <div><strong>{preview.desktopTrackCount}</strong><span>{tr("adb.desktopTracks")}</span></div>
                </article>
                <article>
                  <span className="adb-sync-panel__stat-icon"><Smartphone size={17} /></span>
                  <div><strong>{preview.phoneTrackCount}</strong><span>{tr("adb.phoneTracks")}</span></div>
                </article>
                <article className="is-accent">
                  <span className="adb-sync-panel__stat-icon"><ArrowUpToLine size={17} /></span>
                  <div><strong>{preview.uploadToPhone.length}</strong><span>{tr("adb.toPhone")}</span></div>
                </article>
                <article className="is-accent">
                  <span className="adb-sync-panel__stat-icon"><ArrowDownToLine size={17} /></span>
                  <div><strong>{preview.downloadToDesktop.length}</strong><span>{tr("adb.toDesktop")}</span></div>
                </article>
                <article>
                  <span className="adb-sync-panel__stat-icon"><History size={17} /></span>
                  <div><strong>{preview.phoneHistoryCount}</strong><span>{tr("adb.phoneHistory")}</span></div>
                </article>
                <article className={preview.conflicts.length > 0 ? "is-warning" : ""}>
                  <span className="adb-sync-panel__stat-icon"><AlertTriangle size={17} /></span>
                  <div><strong>{preview.conflicts.length}</strong><span>{tr("adb.conflicts")}</span></div>
                </article>
              </div>

              <p className="adb-sync-panel__scope-note">
                <Music2 size={13} aria-hidden="true" />
                {tr("adb.syncScope", {
                  desktop: preview.desktopIgnoredSyncTrackCount,
                  phone: preview.phoneIgnoredSyncTrackCount,
                })}
              </p>

              <p className="adb-sync-panel__scope-note">
                <Sparkles size={13} aria-hidden="true" />
                {preview.wishlistSupported
                  ? tr("adb.wishlistPlan", {
                    desktopCategories: preview.desktopWishlistCategoryCount,
                    desktopItems: preview.desktopWishlistItemCount,
                    phoneCategories: preview.phoneWishlistCategoryCount,
                    phoneItems: preview.phoneWishlistItemCount,
                    desktopCategoriesAdded: preview.wishlistCategoriesAddToDesktop,
                    toDesktop: preview.wishlistItemsAddToDesktop,
                    phoneCategoriesAdded: preview.wishlistCategoriesAddToPhone,
                    toPhone: preview.wishlistItemsAddToPhone,
                  })
                  : tr("adb.wishlistUnsupported")}
              </p>

              <p className="adb-sync-panel__scope-note">
                <Music2 size={13} aria-hidden="true" />
                {preview.playlistsSupported
                  ? tr("adb.playlistsPlan", {
                    desktopPlaylists: preview.desktopPlaylistCount,
                    desktopTracks: preview.desktopPlaylistTrackCount,
                    phonePlaylists: preview.phonePlaylistCount,
                    phoneTracks: preview.phonePlaylistTrackCount,
                    toDesktopPlaylists: preview.playlistsAddToDesktop,
                    toDesktopTracks: preview.playlistTracksAddToDesktop,
                    toPhonePlaylists: preview.playlistsAddToPhone,
                    toPhoneTracks: preview.playlistTracksAddToPhone,
                    deleteDesktop: preview.playlistsDeleteFromDesktop,
                    deletePhone: preview.playlistsDeleteFromPhone,
                  })
                  : tr("adb.playlistsUnsupported")}
              </p>

              {preview.conflicts.length > 0 && (
                <section className="adb-sync-panel__conflicts" aria-labelledby="adb-conflicts-title">
                  <div className="adb-sync-panel__section-heading">
                    <span><AlertTriangle size={15} aria-hidden="true" /></span>
                    <div>
                      <h3 id="adb-conflicts-title">{tr("adb.conflictTitle")}</h3>
                      <p>{tr("adb.conflictSubtitle")}</p>
                    </div>
                  </div>
                  <div className="adb-sync-panel__conflict-list">
                    {preview.conflicts.map((conflict) => {
                      const resolution = conflictResolutions[conflict.syncId] ?? "skip";
                      return (
                        <article className="adb-sync-panel__conflict" key={conflict.syncId}>
                          <div className="adb-sync-panel__conflict-copy">
                            <Music2 size={15} aria-hidden="true" />
                            <span>
                              <strong>{conflict.title}</strong>
                              <small>{tr("adb.conflictDetail")}</small>
                            </span>
                          </div>
                          <div className="adb-sync-panel__resolution" role="group" aria-label={tr("adb.chooseResolution", { title: conflict.title })}>
                            {(["desktopToPhone", "phoneToDesktop", "skip"] as const).map((option) => {
                              const recommended = conflict.recommendedResolution === option;
                              return (
                                <button
                                  key={option}
                                  type="button"
                                  className={resolution === option ? "is-selected" : ""}
                                  disabled={isBusy}
                                  onClick={() => setConflictResolutions((current) => ({ ...current, [conflict.syncId]: option }))}
                                >
                                  {option === "desktopToPhone" && <Computer size={12} aria-hidden="true" />}
                                  {option === "desktopToPhone" && <ArrowRight size={11} aria-hidden="true" />}
                                  {option === "phoneToDesktop" && <Smartphone size={12} aria-hidden="true" />}
                                  {option === "phoneToDesktop" && <ArrowRight size={11} aria-hidden="true" />}
                                  {option === "skip" && <Clock3 size={12} aria-hidden="true" />}
                                  {tr(`adb.${option}`)}
                                  {recommended && <em>{tr("adb.recommended")}</em>}
                                </button>
                              );
                            })}
                          </div>
                        </article>
                      );
                    })}
                  </div>
                </section>
              )}

              <section className="adb-sync-panel__history" aria-labelledby="adb-history-title">
                <div className="adb-sync-panel__section-heading">
                  <span><History size={15} aria-hidden="true" /></span>
                  <div>
                    <h3 id="adb-history-title">{tr("adb.historyTitle")}</h3>
                    <p>{tr("adb.historySubtitle")}</p>
                  </div>
                </div>
                <div className="adb-sync-panel__mode-grid">
                  <button
                    type="button"
                    className={historyMode === "keepOnBoth" ? "is-selected" : ""}
                    disabled={isBusy}
                    onClick={() => selectHistoryMode("keepOnBoth")}
                  >
                    <span className="adb-sync-panel__radio">{historyMode === "keepOnBoth" && <Check size={11} />}</span>
                    <span><strong>{tr("adb.keepOnBoth")}</strong><small>{tr("adb.keepOnBothBody")}</small></span>
                  </button>
                  <button
                    type="button"
                    className={`is-danger ${historyMode === "desktopOnly" ? "is-selected" : ""}`}
                    disabled={isBusy}
                    onClick={() => selectHistoryMode("desktopOnly")}
                  >
                    <span className="adb-sync-panel__radio">{historyMode === "desktopOnly" && <Check size={11} />}</span>
                    <span><strong>{tr("adb.desktopOnly")}</strong><small>{tr("adb.desktopOnlyBody")}</small></span>
                  </button>
                </div>
                {historyMode === "desktopOnly" && (
                  <p className="adb-sync-panel__danger-note">
                    <AlertTriangle size={13} aria-hidden="true" />
                    {tr("adb.desktopOnlyDanger")}
                  </p>
                )}
              </section>

              {errorKey && (
                <p className="adb-sync-panel__error" role="alert">
                  <AlertTriangle size={14} aria-hidden="true" />
                  <span>{tr(errorKey)}{errorDetail && <small>{errorDetail}</small>}</span>
                </p>
              )}

              {lastResult && (
                <div className="adb-sync-panel__result" role="status">
                  <Check size={14} aria-hidden="true" />
                  <span>
                    <strong>{tr("adb.done")}</strong>
                    <small>{tr("adb.completed", {
                      uploaded: lastResult.uploadedToPhone,
                      downloaded: lastResult.downloadedToDesktop,
                      histories: lastResult.desktopHistorySessions,
                    })}</small>
                    {lastResult.phoneHistoryBackupPath && (
                      <small>{tr("adb.phoneBackup", { path: lastResult.phoneHistoryBackupPath })}</small>
                    )}
                    {lastResult.wishlistSynced && (
                      <small>{tr("adb.wishlistResult", {
                        categories: lastResult.wishlistCategories,
                        items: lastResult.wishlistItems,
                        toDesktop: lastResult.wishlistItemsAddedToDesktop,
                        toPhone: lastResult.wishlistItemsAddedToPhone,
                      })}</small>
                    )}
                    {lastResult.playlistsSynced && (
                      <small>{tr("adb.playlistsResult", {
                        playlists: lastResult.playlistCount,
                        tracks: lastResult.playlistTrackCount,
                        toDesktop: lastResult.playlistTracksAddedToDesktop,
                        toPhone: lastResult.playlistTracksAddedToPhone,
                        deleteDesktop: lastResult.playlistsDeletedFromDesktop,
                        deletePhone: lastResult.playlistsDeletedFromPhone,
                      })}</small>
                    )}
                    {lastResult.warnings.map((warning, index) => <small key={`${index}-${warning}`}>{warning}</small>)}
                  </span>
                </div>
              )}

              <footer className="adb-sync-panel__footer">
                <p>
                  {preview.uploadToPhone.length === 0
                    && preview.downloadToDesktop.length === 0
                    && preview.conflicts.length === 0
                    ? tr("adb.songsAligned")
                    : tr("adb.ready", {
                      upload: preview.uploadToPhone.length,
                      download: preview.downloadToDesktop.length,
                      conflicts: preview.conflicts.length,
                    })}
                </p>
                <button type="button" disabled={isBusy} onClick={() => void executeSync()}>
                  {isSyncing ? <LoaderCircle className="is-spinning" size={15} /> : <Usb size={15} />}
                  {isSyncing ? tr("adb.syncing") : tr("adb.syncNow")}
                </button>
              </footer>
            </>
          ) : null}
        </>
      )}

      {errorKey && !preview && (
        <p className="adb-sync-panel__error adb-sync-panel__error--standalone" role="alert">
          <AlertTriangle size={14} aria-hidden="true" />
          <span>{tr(errorKey)}{errorDetail && <small>{errorDetail}</small>}</span>
        </p>
      )}
    </section>
  );
}
