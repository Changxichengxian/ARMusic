use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{App, AppHandle, Emitter, Manager, PhysicalPosition, State, Window, WindowEvent};

const PLAYER_EVENT: &str = "armusic://tray-player-action";
const PLAYER_STATE_EVENT: &str = "armusic://tray-player-state";
const TRAY_FOCUS_GRACE: Duration = Duration::from_millis(320);
const TRAY_REFOCUS_DELAY: Duration = Duration::from_millis(90);
const TRAY_TOGGLE_BLUR_GRACE: Duration = Duration::from_millis(260);

static TRAY_SHOWN_AT: Mutex<Option<Instant>> = Mutex::new(None);
static TRAY_AUTO_HIDDEN_AT: Mutex<Option<Instant>> = Mutex::new(None);

pub struct TrayPlayerStateStore {
    close_to_tray: AtomicBool,
    player: Mutex<TrayPlayerState>,
}

impl Default for TrayPlayerStateStore {
    fn default() -> Self {
        Self {
            close_to_tray: AtomicBool::new(
                startup::load_close_to_tray().ok().flatten().unwrap_or(true),
            ),
            player: Mutex::new(TrayPlayerState::default()),
        }
    }
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrayPlayerState {
    pub title: String,
    pub artist: String,
    pub cover_url: Option<String>,
    pub is_playing: bool,
    pub has_track: bool,
    #[serde(default)]
    pub position_seconds: f64,
    #[serde(default)]
    pub duration_seconds: f64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopBehaviorPreferences {
    pub close_to_tray: bool,
    pub launch_at_startup: bool,
}

impl TrayPlayerStateStore {
    pub fn close_to_tray(&self) -> bool {
        self.close_to_tray.load(Ordering::Acquire)
    }
}

#[tauri::command]
pub fn get_desktop_behavior_preferences(
    state: State<'_, TrayPlayerStateStore>,
) -> Result<DesktopBehaviorPreferences, String> {
    Ok(DesktopBehaviorPreferences {
        close_to_tray: state.close_to_tray(),
        launch_at_startup: startup::is_enabled()?,
    })
}

#[tauri::command]
pub fn set_close_to_tray(
    enabled: bool,
    state: State<'_, TrayPlayerStateStore>,
) -> Result<DesktopBehaviorPreferences, String> {
    startup::save_close_to_tray(enabled)?;
    state.close_to_tray.store(enabled, Ordering::Release);
    Ok(DesktopBehaviorPreferences {
        close_to_tray: enabled,
        launch_at_startup: startup::is_enabled().unwrap_or(false),
    })
}

#[tauri::command]
pub fn set_launch_at_startup(
    enabled: bool,
    state: State<'_, TrayPlayerStateStore>,
) -> Result<DesktopBehaviorPreferences, String> {
    startup::set_enabled(enabled)?;
    Ok(DesktopBehaviorPreferences {
        close_to_tray: state.close_to_tray(),
        launch_at_startup: startup::is_enabled()?,
    })
}

#[tauri::command]
pub fn update_tray_player_state(
    mut player: TrayPlayerState,
    state: State<'_, TrayPlayerStateStore>,
    app: AppHandle,
) -> Result<TrayPlayerState, String> {
    player.title = bounded_text(player.title, 2_048);
    player.artist = bounded_text(player.artist, 2_048);
    player.cover_url = player
        .cover_url
        .map(|value| bounded_text(value, 16_384))
        .filter(|value| !value.is_empty());
    player.duration_seconds = finite_non_negative(player.duration_seconds);
    player.position_seconds =
        finite_non_negative(player.position_seconds).min(player.duration_seconds.max(0.0));
    update_taskbar_progress(&app, &player);
    *state
        .player
        .lock()
        .map_err(|_| "托盘播放状态暂时不可用".to_string())? = player.clone();
    let _ = app.emit_to("tray", PLAYER_STATE_EVENT, &player);
    Ok(player)
}

#[tauri::command]
pub fn get_tray_player_state(
    state: State<'_, TrayPlayerStateStore>,
) -> Result<TrayPlayerState, String> {
    state
        .player
        .lock()
        .map(|value| value.clone())
        .map_err(|_| "托盘播放状态暂时不可用".to_string())
}

#[tauri::command]
pub fn tray_player_action(action: String, app: AppHandle) -> Result<(), String> {
    match action.as_str() {
        "previous" | "playPause" | "next" => app
            .emit_to("main", PLAYER_EVENT, action)
            .map_err(|error| format!("发送托盘播放指令失败：{error}")),
        "showMain" => show_main_window(&app),
        "quit" => {
            app.exit(0);
            Ok(())
        }
        _ if valid_seek_action(&action) => app
            .emit_to("main", PLAYER_EVENT, action)
            .map_err(|error| format!("发送托盘播放指令失败：{error}")),
        _ => Err("未知的托盘播放指令".to_string()),
    }
}

pub fn show_main_window(app: &AppHandle) -> Result<(), String> {
    if let Some(tray) = app.get_webview_window("tray") {
        let _ = tray.hide();
    }
    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "主窗口尚未创建".to_string())?;
    window.show().map_err(|error| error.to_string())?;
    let _ = window.unminimize();
    window.set_focus().map_err(|error| error.to_string())
}

pub fn initialize(app: &mut App) -> Result<(), Box<dyn std::error::Error>> {
    let mut builder = TrayIconBuilder::with_id("armusic-player")
        .tooltip("ARMusic")
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| {
            let TrayIconEvent::Click {
                position,
                button,
                button_state: MouseButtonState::Up,
                ..
            } = event
            else {
                return;
            };
            if button == MouseButton::Middle {
                return;
            }
            let _ = toggle_tray_window(tray.app_handle(), position);
        });
    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }
    builder.build(app)?;

    if let Some(main) = app.get_webview_window("main") {
        if launched_in_background() {
            let _ = main.hide();
        } else {
            // The main window starts hidden in tauri.conf.json so Windows login startup
            // cannot briefly flash a frame before setup processes --background.
            let _ = main.show();
            let _ = main.set_focus();
        }
    }
    Ok(())
}

pub fn handle_window_event(window: &Window, event: &WindowEvent) {
    match event {
        WindowEvent::CloseRequested { api, .. } if window.label() == "main" => {
            api.prevent_close();
            let app = window.app_handle();
            if window.state::<TrayPlayerStateStore>().close_to_tray() {
                let _ = window.hide();
                if let Some(tray) = app.get_webview_window("tray") {
                    let _ = tray.hide();
                }
            } else {
                app.exit(0);
            }
        }
        WindowEvent::Focused(false) if window.label() == "tray" => {
            // Windows' hidden-icons flyout can deliver a stale blur just after the tray popup
            // was focused. Ignore that transition and focus once more after the flyout closes.
            if happened_recently(&TRAY_SHOWN_AT, TRAY_FOCUS_GRACE) {
                let tray = window.clone();
                std::thread::spawn(move || {
                    std::thread::sleep(TRAY_REFOCUS_DELAY);
                    if tray.is_visible().unwrap_or(false) {
                        let _ = tray.set_focus();
                    }
                });
            } else {
                remember_now(&TRAY_AUTO_HIDDEN_AT);
                let _ = window.hide();
            }
        }
        _ => {}
    }
}

fn toggle_tray_window(app: &AppHandle, click: PhysicalPosition<f64>) -> Result<(), String> {
    let window = app
        .get_webview_window("tray")
        .ok_or_else(|| "托盘播放窗口尚未创建".to_string())?;
    if window.is_visible().map_err(|error| error.to_string())? {
        return window.hide().map_err(|error| error.to_string());
    }
    // Clicking the tray icon itself first blurs the popup. If that blur already hid it,
    // this mouse-up completes the intended close instead of immediately reopening it.
    if happened_recently(&TRAY_AUTO_HIDDEN_AT, TRAY_TOGGLE_BLUR_GRACE) {
        return Ok(());
    }

    let size = window.outer_size().map_err(|error| error.to_string())?;
    if let Some(monitor) = window
        .monitor_from_point(click.x, click.y)
        .map_err(|error| error.to_string())?
    {
        let work = monitor.work_area();
        let padding = (8.0 * monitor.scale_factor()).round() as i32;
        let width = size.width.min(i32::MAX as u32) as i32;
        let height = size.height.min(i32::MAX as u32) as i32;
        let min_x = work.position.x.saturating_add(padding);
        let max_x = work
            .position
            .x
            .saturating_add(work.size.width.min(i32::MAX as u32) as i32)
            .saturating_sub(width)
            .saturating_sub(padding)
            .max(min_x);
        let min_y = work.position.y.saturating_add(padding);
        let max_y = work
            .position
            .y
            .saturating_add(work.size.height.min(i32::MAX as u32) as i32)
            .saturating_sub(height)
            .saturating_sub(padding)
            .max(min_y);
        let click_x = click.x.round() as i32;
        let click_y = click.y.round() as i32;
        let work_mid_y = work
            .position
            .y
            .saturating_add((work.size.height / 2) as i32);
        let x = click_x.saturating_sub(width / 2).clamp(min_x, max_x);
        let desired_y = if click_y >= work_mid_y {
            click_y.saturating_sub(height).saturating_sub(padding)
        } else {
            click_y.saturating_add(padding)
        };
        window
            .set_position(PhysicalPosition::new(x, desired_y.clamp(min_y, max_y)))
            .map_err(|error| error.to_string())?;
    }
    remember_now(&TRAY_SHOWN_AT);
    window.show().map_err(|error| error.to_string())?;
    window.set_focus().map_err(|error| error.to_string())
}

fn remember_now(slot: &Mutex<Option<Instant>>) {
    if let Ok(mut value) = slot.lock() {
        *value = Some(Instant::now());
    }
}

fn happened_recently(slot: &Mutex<Option<Instant>>, window: Duration) -> bool {
    slot.lock()
        .ok()
        .and_then(|value| *value)
        .is_some_and(|instant| instant.elapsed() <= window)
}

fn launched_in_background() -> bool {
    std::env::args_os().any(|argument| argument == "--background")
}

fn bounded_text(value: String, max_bytes: usize) -> String {
    if value.len() <= max_bytes {
        return value;
    }
    let mut end = max_bytes;
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_string()
}

fn finite_non_negative(value: f64) -> f64 {
    if value.is_finite() {
        value.max(0.0)
    } else {
        0.0
    }
}

fn valid_seek_action(action: &str) -> bool {
    action
        .strip_prefix("seek:")
        .and_then(|value| value.parse::<f64>().ok())
        .is_some_and(|value| value.is_finite() && (0.0..=31_536_000.0).contains(&value))
}

fn update_taskbar_progress(app: &AppHandle, player: &TrayPlayerState) {
    use tauri::window::{ProgressBarState, ProgressBarStatus};

    let progress = playback_progress_percent(
        player.has_track,
        player.position_seconds,
        player.duration_seconds,
    );
    let Some(main) = app.get_webview_window("main") else {
        return;
    };
    let _ = main.set_progress_bar(ProgressBarState {
        status: Some(if progress.is_some() {
            ProgressBarStatus::Normal
        } else {
            ProgressBarStatus::None
        }),
        progress,
    });
}

fn playback_progress_percent(
    has_track: bool,
    position_seconds: f64,
    duration_seconds: f64,
) -> Option<u64> {
    if !has_track
        || !position_seconds.is_finite()
        || !duration_seconds.is_finite()
        || duration_seconds <= 0.0
    {
        return None;
    }
    Some(((position_seconds.max(0.0) / duration_seconds).clamp(0.0, 1.0) * 100.0).round() as u64)
}

#[cfg(windows)]
mod startup {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use std::path::Path;
    use windows_sys::Win32::Foundation::{ERROR_FILE_NOT_FOUND, ERROR_SUCCESS};
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegCreateKeyExW, RegDeleteValueW, RegOpenKeyExW, RegQueryValueExW,
        RegSetValueExW, HKEY, HKEY_CURRENT_USER, KEY_QUERY_VALUE, KEY_SET_VALUE,
        REG_OPTION_NON_VOLATILE, REG_SZ,
    };

    const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
    const RUN_VALUE_NAME: &str = "ARMusicPortable_app.armusic.desktop";
    const PREFERENCES_KEY: &str = r"Software\ARMusic\Desktop";
    const CLOSE_TO_TRAY_VALUE_NAME: &str = "CloseToTray";

    struct RegistryKey(HKEY);

    impl Drop for RegistryKey {
        fn drop(&mut self) {
            unsafe {
                RegCloseKey(self.0);
            }
        }
    }

    pub fn is_enabled() -> Result<bool, String> {
        let Some(command) = read_value(RUN_KEY, RUN_VALUE_NAME)? else {
            return Ok(false);
        };
        let expected = startup_command()?;
        Ok(command.eq_ignore_ascii_case(&expected))
    }

    pub fn set_enabled(enabled: bool) -> Result<(), String> {
        if enabled {
            write_value(RUN_KEY, RUN_VALUE_NAME, &startup_command()?)
        } else {
            delete_value(RUN_KEY, RUN_VALUE_NAME)
        }
    }

    pub fn load_close_to_tray() -> Result<Option<bool>, String> {
        Ok(
            read_value(PREFERENCES_KEY, CLOSE_TO_TRAY_VALUE_NAME)?.and_then(|value| {
                match value.as_str() {
                    "1" | "true" => Some(true),
                    "0" | "false" => Some(false),
                    _ => None,
                }
            }),
        )
    }

    pub fn save_close_to_tray(enabled: bool) -> Result<(), String> {
        write_value(
            PREFERENCES_KEY,
            CLOSE_TO_TRAY_VALUE_NAME,
            if enabled { "1" } else { "0" },
        )
    }

    fn read_value(subkey: &str, value_name: &str) -> Result<Option<String>, String> {
        let key = match open_key(subkey, KEY_QUERY_VALUE) {
            Ok(key) => key,
            Err(ERROR_FILE_NOT_FOUND) => return Ok(None),
            Err(error) => return Err(registry_error("打开注册表项", error)),
        };
        let value_name = wide(value_name);
        let mut value_type = 0_u32;
        let mut byte_count = 0_u32;
        let size_result = unsafe {
            RegQueryValueExW(
                key.0,
                value_name.as_ptr(),
                std::ptr::null(),
                &mut value_type,
                std::ptr::null_mut(),
                &mut byte_count,
            )
        };
        if size_result == ERROR_FILE_NOT_FOUND {
            return Ok(None);
        }
        if size_result != ERROR_SUCCESS || value_type != REG_SZ {
            return Err(format!(
                "读取开机自启设置失败，Windows 错误码 {size_result}"
            ));
        }
        let mut buffer = vec![0_u16; (byte_count as usize / 2).saturating_add(1)];
        let read_result = unsafe {
            RegQueryValueExW(
                key.0,
                value_name.as_ptr(),
                std::ptr::null(),
                &mut value_type,
                buffer.as_mut_ptr().cast::<u8>(),
                &mut byte_count,
            )
        };
        if read_result != ERROR_SUCCESS {
            return Err(format!(
                "读取开机自启命令失败，Windows 错误码 {read_result}"
            ));
        }
        let end = buffer
            .iter()
            .position(|value| *value == 0)
            .unwrap_or(buffer.len());
        Ok(Some(String::from_utf16_lossy(&buffer[..end])))
    }

    fn write_value(subkey: &str, value_name: &str, value: &str) -> Result<(), String> {
        let key = create_key(subkey, KEY_SET_VALUE)
            .map_err(|error| registry_error("创建注册表项", error))?;
        let value_name = wide(value_name);
        let value = wide(value);
        let result = unsafe {
            RegSetValueExW(
                key.0,
                value_name.as_ptr(),
                0,
                REG_SZ,
                value.as_ptr().cast::<u8>(),
                (value.len() * std::mem::size_of::<u16>()) as u32,
            )
        };
        if result != ERROR_SUCCESS {
            return Err(registry_error("写入注册表值", result));
        }
        Ok(())
    }

    fn delete_value(subkey: &str, value_name: &str) -> Result<(), String> {
        let key = match open_key(subkey, KEY_SET_VALUE) {
            Ok(key) => key,
            Err(ERROR_FILE_NOT_FOUND) => return Ok(()),
            Err(error) => return Err(registry_error("打开注册表项", error)),
        };
        let value_name = wide(value_name);
        let result = unsafe { RegDeleteValueW(key.0, value_name.as_ptr()) };
        if result != ERROR_SUCCESS && result != ERROR_FILE_NOT_FOUND {
            return Err(registry_error("删除注册表值", result));
        }
        Ok(())
    }

    fn open_key(subkey: &str, access: u32) -> Result<RegistryKey, u32> {
        let subkey = wide(subkey);
        let mut key = std::ptr::null_mut();
        let result =
            unsafe { RegOpenKeyExW(HKEY_CURRENT_USER, subkey.as_ptr(), 0, access, &mut key) };
        if result != ERROR_SUCCESS {
            return Err(result);
        }
        Ok(RegistryKey(key))
    }

    fn create_key(subkey: &str, access: u32) -> Result<RegistryKey, u32> {
        let subkey = wide(subkey);
        let mut key = std::ptr::null_mut();
        let mut disposition = 0_u32;
        let result = unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                subkey.as_ptr(),
                0,
                std::ptr::null(),
                REG_OPTION_NON_VOLATILE,
                access,
                std::ptr::null(),
                &mut key,
                &mut disposition,
            )
        };
        if result != ERROR_SUCCESS {
            return Err(result);
        }
        Ok(RegistryKey(key))
    }

    fn registry_error(action: &str, code: u32) -> String {
        format!("{action}失败，Windows 错误码 {code}")
    }

    fn startup_command() -> Result<String, String> {
        let executable = std::env::current_exe()
            .map_err(|error| format!("无法读取 ARMusic 程序路径：{error}"))?;
        Ok(format!("\"{}\" --background", display_path(&executable)))
    }

    fn display_path(path: &Path) -> String {
        path.as_os_str().to_string_lossy().to_string()
    }

    fn wide(value: impl AsRef<OsStr>) -> Vec<u16> {
        value
            .as_ref()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect()
    }
}

#[cfg(not(windows))]
mod startup {
    pub fn is_enabled() -> Result<bool, String> {
        Ok(false)
    }

    pub fn set_enabled(_enabled: bool) -> Result<(), String> {
        Err("此平台暂不支持开机自启".to_string())
    }

    pub fn load_close_to_tray() -> Result<Option<bool>, String> {
        Ok(None)
    }

    pub fn save_close_to_tray(_enabled: bool) -> Result<(), String> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_text_never_splits_utf8() {
        assert_eq!(bounded_text("甲乙丙".to_string(), 4), "甲");
        assert_eq!(bounded_text("abc".to_string(), 4), "abc");
    }

    #[test]
    fn background_flag_is_an_exact_argument() {
        assert_ne!(std::ffi::OsStr::new("--background-no"), "--background");
    }

    #[test]
    fn recent_tray_transition_expires() {
        let slot = Mutex::new(Some(Instant::now() - Duration::from_secs(1)));
        assert!(!happened_recently(&slot, Duration::from_millis(10)));
        remember_now(&slot);
        assert!(happened_recently(&slot, Duration::from_millis(10)));
    }

    #[test]
    fn seek_action_only_accepts_a_bounded_finite_position() {
        assert!(valid_seek_action("seek:12.5"));
        assert!(valid_seek_action("seek:0"));
        assert!(!valid_seek_action("seek:-1"));
        assert!(!valid_seek_action("seek:NaN"));
        assert!(!valid_seek_action("seek:inf"));
        assert!(!valid_seek_action("seek:999999999"));
        assert!(!valid_seek_action("seek:12:5"));
    }

    #[test]
    fn taskbar_progress_is_clamped_and_clears_without_a_track() {
        assert_eq!(playback_progress_percent(true, 25.0, 100.0), Some(25));
        assert_eq!(playback_progress_percent(true, 120.0, 100.0), Some(100));
        assert_eq!(playback_progress_percent(true, -5.0, 100.0), Some(0));
        assert_eq!(playback_progress_percent(false, 25.0, 100.0), None);
        assert_eq!(playback_progress_percent(true, 25.0, 0.0), None);
    }
}
