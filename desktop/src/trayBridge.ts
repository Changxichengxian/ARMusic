export interface DesktopBehaviorPreferences {
  closeToTray: boolean;
  launchAtStartup: boolean;
}

export interface TrayPlayerSnapshot {
  title: string;
  artist: string;
  coverUrl?: string;
  isPlaying: boolean;
  hasTrack: boolean;
  positionSeconds: number;
  durationSeconds: number;
}

export type TrayPlayerAction = "previous" | "playPause" | "next" | `seek:${number}`;

function available() {
  return Boolean(window.__TAURI_INTERNALS__);
}

async function invoke<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  const { invoke: tauriInvoke } = await import("@tauri-apps/api/core");
  return tauriInvoke<T>(command, args);
}

export async function getDesktopBehaviorPreferences(): Promise<DesktopBehaviorPreferences | undefined> {
  if (!available()) return undefined;
  return invoke<DesktopBehaviorPreferences>("get_desktop_behavior_preferences");
}

export async function saveCloseToTray(enabled: boolean): Promise<DesktopBehaviorPreferences | undefined> {
  if (!available()) return undefined;
  return invoke<DesktopBehaviorPreferences>("set_close_to_tray", { enabled });
}

export async function saveLaunchAtStartup(enabled: boolean): Promise<DesktopBehaviorPreferences | undefined> {
  if (!available()) return undefined;
  return invoke<DesktopBehaviorPreferences>("set_launch_at_startup", { enabled });
}

export async function publishTrayPlayerState(player: TrayPlayerSnapshot): Promise<void> {
  if (!available()) return;
  await invoke("update_tray_player_state", { player });
}

export async function listenToTrayPlayerActions(handler: (action: TrayPlayerAction) => void): Promise<() => void> {
  if (!available()) return () => undefined;
  const { listen } = await import("@tauri-apps/api/event");
  return listen<TrayPlayerAction>("armusic://tray-player-action", (event) => handler(event.payload));
}
