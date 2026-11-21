"use strict";

function createARMusicPlayer(root) {
  const workName = root.querySelector("#work-name");
  const genre = root.querySelector("#work-genre");
  const description = root.querySelector("#description");
  const trackList = root.querySelector("#tracks");
  const currentTrack = root.querySelector("#current");
  const versionList = root.querySelector("#versions");
  const status = root.querySelector("#status");
  const audio = root.querySelector("audio");

  let work;
  let track;
  let version;
  let pendingPosition = null;
  // 版本请求失败后留下的位置，等新版本真正定位完成再清掉。
  let retryPosition = null;
  let pendingToken = 0;
  let loadToken = 0;
  let restoreNotice = null;

  function formatTime(seconds) {
    const total = Math.max(0, Math.floor(seconds));
    const minutes = Math.floor(total / 60);
    const rest = String(total % 60).padStart(2, "0");
    return `${minutes}:${rest}`;
  }

  function reportRestore() {
    if (!restoreNotice || restoreNotice.token !== loadToken) return;
    if (audio.error || pendingPosition !== null || !restoreNotice.seeked) return;
    if (audio.readyState < HTMLMediaElement.HAVE_FUTURE_DATA) return;
    if (!restoreNotice.started && (restoreNotice.play || !audio.paused)) return;
    if (!Number.isFinite(restoreNotice.position)) return;
    const position = formatTime(restoreNotice.position);
    status.textContent = restoreNotice.play
      ? `已切换到「${restoreNotice.version.title}」，从 ${position} 继续播放。`
      : `已切换到「${restoreNotice.version.title}」，已定位到 ${position}，仍停在这里，按播放键继续。`;
    restoreNotice = null;
  }

  audio.addEventListener("loadedmetadata", () => {
    if (pendingPosition === null || pendingToken !== loadToken) return;
    const requested = Number.isFinite(pendingPosition) ? pendingPosition : 0;
    const target = Number.isFinite(audio.duration)
      ? Math.min(Math.max(0, requested), audio.duration)
      : Math.max(0, requested);
    const needsSeek = Math.abs(audio.currentTime - target) > 0.05;
    pendingPosition = null;
    if (restoreNotice && restoreNotice.token === loadToken) {
      restoreNotice.position = target;
      restoreNotice.seeked = !needsSeek;
    }
    if (needsSeek) audio.currentTime = target;
    retryPosition = null;
  });
  audio.addEventListener("seeked", () => {
    if (restoreNotice && restoreNotice.token === loadToken) {
      restoreNotice.seeked = true;
    }
    reportRestore();
  });
  audio.addEventListener("canplay", reportRestore);
  audio.addEventListener("playing", () => {
    if (restoreNotice && restoreNotice.token === loadToken) {
      restoreNotice.started = true;
    }
    reportRestore();
  });
  audio.addEventListener("error", () => {
    if (pendingToken !== loadToken) return;
    const carried = Number.isFinite(pendingPosition)
      ? pendingPosition
      : (Number.isFinite(audio.currentTime) ? audio.currentTime : 0);
    retryPosition = carried;
    pendingPosition = null;
    restoreNotice = null;
    status.textContent = "这段音频暂时无法播放。可以重新点一次当前版本，或换一首试听。";
  });

  function selectButton(list, id) {
    for (const button of list.children) {
      button.setAttribute("aria-pressed", String(button.dataset.id === id));
    }
  }

  function renderButtons(list, entries, select) {
    list.replaceChildren(...entries.map((entry) => {
      const button = document.createElement("button");
      button.type = "button";
      button.dataset.id = entry.id;
      button.textContent = entry.title;
      button.onclick = () => select(entry);
      return button;
    }));
  }

  function loadVersion(next, position, play, announce) {
    const requestToken = ++loadToken;
    version = next;
    pendingPosition = position;
    pendingToken = requestToken;
    restoreNotice = announce ? {
      token: requestToken,
      version: next,
      position,
      play,
      started: false,
      seeked: false,
    } : null;
    status.textContent = "";
    selectButton(versionList, next.id);
    audio.preload = announce ? "auto" : "metadata";
    audio.src = next.src;
    audio.load();

    if (play) {
      audio.play().catch((error) => {
        if (requestToken === loadToken && error.name !== "AbortError" && !audio.error) {
          status.textContent = "请点播放键开始试听。";
        }
      });
    }
  }

  function carriedPosition() {
    return pendingPosition ?? retryPosition ?? audio.currentTime;
  }

  function switchVersion(next) {
    const retrying = next === version && Boolean(audio.error);
    if (next === version && !retrying) return;
    loadVersion(next, carriedPosition(), retrying || !audio.paused, true);
  }

  function openTrack(next) {
    if (track === next && !audio.error) return;
    track = next;
    currentTrack.textContent = track.title;
    selectButton(trackList, track.id);
    renderButtons(versionList, track.versions, switchVersion);
    loadVersion(track.versions[0], 0, true);
  }

  return {
    openWork(next) {
      root.hidden = false;
      if (work !== next) {
        work = next;
        workName.textContent = work.title;
        genre.textContent = work.genre + "原声";
        description.textContent = work.description;
        renderButtons(trackList, work.tracks, openTrack);
        openTrack(work.tracks[0]);
      } else if (audio.error) {
        loadVersion(version, carriedPosition(), true);
      }
      workName.focus({ preventScroll: true });
      root.scrollIntoView({ block: "nearest" });
    },
  };
}
