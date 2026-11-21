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

  audio.addEventListener("loadedmetadata", () => {
    if (pendingPosition === null) return;
    if (Number.isFinite(audio.duration)) {
      audio.currentTime = Math.min(pendingPosition, audio.duration);
    }
    pendingPosition = null;
  });
  audio.addEventListener("error", () => {
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

  function loadVersion(next, position, play) {
    version = next;
    pendingPosition = position;
    status.textContent = "";
    selectButton(versionList, next.id);
    audio.src = next.src;
    audio.load();

    if (play) {
      audio.play().catch((error) => {
        if (version === next && error.name !== "AbortError" && !audio.error) {
          status.textContent = "请点播放键开始试听。";
        }
      });
    }
  }

  function switchVersion(next) {
    if (next === version && !audio.error) return;
    const position = pendingPosition ?? audio.currentTime;
    loadVersion(next, position, !audio.paused);
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
        loadVersion(version, pendingPosition ?? audio.currentTime, true);
      }
      workName.focus({ preventScroll: true });
      root.scrollIntoView({ block: "nearest" });
    },
  };
}
