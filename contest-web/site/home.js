"use strict";

const byId = (id) => document.getElementById(id);
const WORKS = Array.isArray(window.ARMUSIC_WORKS) ? window.ARMUSIC_WORKS : [];
const ENTRIES = WORKS
  .map((work) => ({ work, track: work && Array.isArray(work.tracks) ? work.tracks[0] : null }))
  .filter((entry) => entry.track && Array.isArray(entry.track.versions) && entry.track.versions.length > 0);

const PLAY_ICON = '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M8 5.5v13l10-6.5Z" fill="currentColor"></path></svg>';
const audio = byId("audio");
const content = document.querySelector(".content");
const cardByWork = new Map();

const historyKey = "armusic-demo-history-v1";
let recentWorks = [];
try {
  const stored = JSON.parse(localStorage.getItem(historyKey) || "[]");
  if (Array.isArray(stored)) recentWorks = stored.filter(id => WORKS.some(work => work.id === id));
} catch {}

let currentWork = null;
let currentTrack = null;
let featuredWork = null;
let selectedVersion = null;
let pendingPosition = null;
let revision = 0;

/* first-visit hint */
const intro = byId("intro");
const help = byId("help");
const dismiss = byId("dismiss");
const introKey = "armusic-intro-seen-v1";

try {
  if (localStorage.getItem(introKey) === "yes") intro.hidden = true;
} catch {}

function setIntro(open, focusDismiss) {
  intro.hidden = !open;
  help.setAttribute("aria-expanded", String(open));
  if (open && focusDismiss) {
    intro.scrollIntoView({ block: "nearest" });
    dismiss.focus();
  }
}

setIntro(!intro.hidden, false);

help.addEventListener("click", () => setIntro(true, true));
dismiss.addEventListener("click", () => {
  setIntro(false, false);
  try { localStorage.setItem(introKey, "yes"); } catch {}
  const firstCard = byId("wall").querySelector("button");
  if (firstCard) firstCard.focus();
});

/* transport helpers */
function formatTime(seconds) {
  const safe = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
  return Math.floor(safe / 60) + ":" + String(safe % 60).padStart(2, "0");
}

function setPlayButton(button, playing, playLabel, pauseLabel, labelNode) {
  const playIcon = button.querySelector(".icon-play");
  const pauseIcon = button.querySelector(".icon-pause");
  if (playIcon) playIcon.hidden = playing;
  if (pauseIcon) pauseIcon.hidden = !playing;
  button.setAttribute("aria-label", playing ? pauseLabel : playLabel);
  if (labelNode) labelNode.textContent = playing ? "暂停" : "播放";
}

function updatePlayState() {
  const playing = Boolean(audio.src) && !audio.paused && !audio.ended;
  const title = currentTrack ? currentTrack.title : "";
  setPlayButton(byId("play-toggle"), playing, "播放" + (title ? "《" + title + "》" : ""), "暂停" + (title ? "《" + title + "》" : ""));
  const featuredTrack = featuredWork && featuredWork.tracks ? featuredWork.tracks[0] : null;
  const sameAsCurrent = Boolean(featuredTrack && featuredTrack === currentTrack);
  const featuredButton = byId("featured-play");
  setPlayButton(
    featuredButton,
    sameAsCurrent && playing,
    "播放" + (featuredTrack ? "《" + featuredTrack.title + "》" : "选段"),
    "暂停" + (featuredTrack ? "《" + featuredTrack.title + "》" : "选段"),
    featuredButton.querySelector(".label")
  );
}

function syncTime() {
  const duration = Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : 0;
  const position = Number.isFinite(audio.currentTime) ? audio.currentTime : 0;
  const seek = byId("seek");
  const max = duration || 1;
  seek.max = String(max);
  seek.value = String(Math.min(position, max));
  seek.style.setProperty("--seek-progress", (duration ? Math.min(100, (position / duration) * 100) : 0) + "%");
  byId("time-current").textContent = formatTime(position);
  byId("time-total").textContent = formatTime(duration);
}

function setStatus(message) {
  byId("status").textContent = message;
}

function updateVersionButtons() {
  if (!selectedVersion) return;
  for (const button of byId("versions").children) {
    button.setAttribute("aria-pressed", String(button.dataset.id === selectedVersion.id));
  }
}

function listen(version, position, playing) {
  if (!version) return;
  const request = ++revision;
  const restore = Number.isFinite(position) && position > 0 ? position : 0;
  selectedVersion = version;
  pendingPosition = restore;
  audio.onloadedmetadata = () => {
    if (request !== revision) return;
    const duration = Number.isFinite(audio.duration) ? audio.duration : 0;
    const limit = duration > 0 ? Math.max(0, duration - 0.1) : restore;
    try { audio.currentTime = Math.min(restore, limit); } catch {}
    pendingPosition = null;
    syncTime();
  };
  setStatus("");
  audio.src = version.src;
  audio.load();
  updateVersionButtons();
  if (playing) {
    const attempt = audio.play();
    if (attempt && typeof attempt.catch === "function") {
      attempt.catch(() => {
        if (request === revision && !audio.error) setStatus("点上方播放键继续试听。");
      });
    }
  }
  updatePlayState();
}

function buildVersions(track) {
  const group = byId("versions");
  group.replaceChildren();
  for (const version of track.versions) {
    const button = document.createElement("button");
    button.type = "button";
    button.dataset.id = version.id;
    button.textContent = version.title;
    button.setAttribute("aria-pressed", String(Boolean(selectedVersion && selectedVersion.id === version.id)));
    button.addEventListener("click", () => {
      if (selectedVersion === version) return;
      listen(version, pendingPosition ?? audio.currentTime, !audio.paused);
    });
    group.append(button);
  }
}

function markCurrentCard() {
  for (const card of byId("wall").querySelectorAll(".card")) {
    card.setAttribute("aria-pressed", String(Boolean(currentWork && card.dataset.workId === currentWork.id)));
  }
}

function setCurrent(work, track) {
  currentWork = work;
  currentTrack = track;
  byId("tb-cover").src = work.cover;
  byId("tb-title").textContent = track.title;
  byId("tb-sub").textContent = work.title + " · " + work.genre;
  byId("inspector-cover").src = work.cover;
  byId("inspector-wash").src = work.cover;
  byId("current").textContent = track.title;
  byId("work-name").textContent = work.title + " · " + work.genre;
  byId("description").textContent = work.description || "";
  byId("version-count").textContent = track.versions.length + " 个";
  buildVersions(track);
  markCurrentCard();
}

function setFeatured(work) {
  const entry = ENTRIES.find((item) => item.work === work);
  if (!entry) return;
  featuredWork = work;
  const track = entry.track;
  byId("featured").dataset.work = work.id;
  byId("featured-title").textContent = track.title;
  byId("featured-sub").textContent = work.title + " · " + work.genre;
  byId("featured-wash").src = work.cover;
  byId("featured-cover").src = work.cover;
  const meta = byId("featured-meta");
  meta.replaceChildren();
  for (const text of [work.genre, track.versions.length + " 个版本"]) {
    const pill = document.createElement("span");
    pill.textContent = text;
    meta.append(pill);
  }
  for (const pick of byId("picks").querySelectorAll(".pick")) {
    pick.setAttribute("aria-pressed", String(pick.dataset.workId === work.id));
  }
  updatePlayState();
}

function openWork(work, card) {
  if (!work || !work.tracks || !work.tracks[0]) return;
  const track = work.tracks[0];
  setCurrent(work, track);
  setFeatured(work);
  for (const button of byId("wall").querySelectorAll(".card")) {
    button.setAttribute("aria-pressed", String(button === card));
  }
  if (card) card.setAttribute("aria-pressed", "true");
  listen(track.versions[0], 0, true);
  byId("current").focus({ preventScroll: true });
}

function togglePlay() {
  if (!audio.src) return;
  if (audio.paused) {
    const attempt = audio.play();
    if (attempt && typeof attempt.catch === "function") {
      attempt.catch(() => { if (!audio.error) setStatus("点上方播放键继续试听。"); });
    }
  } else {
    audio.pause();
  }
}

function moveTrack(direction) {
  if (!ENTRIES.length) return;
  const index = Math.max(0, ENTRIES.findIndex((entry) => entry.work === currentWork));
  const next = ENTRIES[(index + direction + ENTRIES.length) % ENTRIES.length];
  openWork(next.work, cardByWork.get(next.work.id));
}

byId("play-toggle").addEventListener("click", togglePlay);
byId("prev").addEventListener("click", () => moveTrack(-1));
byId("next").addEventListener("click", () => moveTrack(1));
byId("seek").addEventListener("input", (event) => {
  const value = Number(event.target.value);
  if (!Number.isFinite(value)) return;
  const duration = Number.isFinite(audio.duration) ? audio.duration : 0;
  try { audio.currentTime = Math.min(value, Math.max(duration, 0)); } catch {}
  syncTime();
});
byId("featured-play").addEventListener("click", () => {
  const target = featuredWork && featuredWork.tracks ? featuredWork.tracks[0] : null;
  if (!target) return;
  if (target === currentTrack) togglePlay();
  else openWork(featuredWork, cardByWork.get(featuredWork.id));
});

const volume = byId("volume");
audio.volume = Number(volume.value);
volume.addEventListener("input", () => { audio.volume = Number(volume.value); });

audio.addEventListener("playing", () => {
  if (!currentWork || recentWorks[0] === currentWork.id) return;
  recentWorks = [currentWork.id, ...recentWorks.filter(id => id !== currentWork.id)];
  try { localStorage.setItem(historyKey, JSON.stringify(recentWorks)); } catch {}
  buildRecent();
  applyFilter();
});

audio.addEventListener("play", () => {
  if (!audio.error) setStatus("");
  updatePlayState();
});
audio.addEventListener("pause", updatePlayState);
audio.addEventListener("timeupdate", syncTime);
audio.addEventListener("durationchange", syncTime);
audio.addEventListener("loadedmetadata", syncTime);
audio.addEventListener("ended", () => moveTrack(1));
audio.addEventListener("error", () => {
  setStatus("音频加载失败，请检查素材文件。");
  updatePlayState();
});

/* shelves */
function buildPicks() {
  const picks = byId("picks");
  for (const { work, track } of ENTRIES) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "pick";
    button.dataset.workId = work.id;
    button.setAttribute("aria-label", "选择《" + track.title + "》");
    const image = document.createElement("img");
    image.src = work.cover;
    image.alt = "";
    image.width = 40;
    image.height = 40;
    const label = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = track.title;
    const small = document.createElement("small");
    small.textContent = work.title;
    label.append(strong, small);
    button.append(image, label);
    button.addEventListener("click", () => setFeatured(work));
    picks.append(button);
  }
}

function buildWall() {
  const wall = byId("wall");
  for (const { work, track } of ENTRIES) {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "card";
    card.dataset.workId = work.id;
    card.dataset.search = [work.title, work.genre, track.title, work.description]
      .concat(track.versions.map((version) => version.title))
      .join(" ")
      .toLowerCase();
    card.setAttribute("aria-pressed", "false");
    card.setAttribute("aria-label", "试听作品《" + work.title + "》");
    const image = document.createElement("img");
    image.src = work.cover;
    image.alt = "";
    image.width = 80;
    image.height = 80;
    const meta = document.createElement("span");
    meta.className = "work-meta";
    const strong = document.createElement("strong");
    strong.textContent = work.title;
    const small = document.createElement("small");
    small.textContent = work.genre + " · " + track.versions.length + " 个版本";
    meta.append(strong, small);
    const play = document.createElement("span");
    play.className = "card-play";
    play.setAttribute("aria-hidden", "true");
    play.innerHTML = PLAY_ICON;
    card.append(image, meta, play);
    card.addEventListener("click", () => openWork(work, card));
    wall.append(card);
    cardByWork.set(work.id, card);
  }
}

function buildRecent() {
  const grid = byId("recent-grid");
  grid.replaceChildren();
  for (const id of recentWorks) {
    const { work, track } = ENTRIES.find(entry => entry.work.id === id);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "track-card";
    button.dataset.workId = work.id;
    button.dataset.search = [work.title, work.genre, track.title, work.description]
      .concat(track.versions.map((version) => version.title))
      .join(" ")
      .toLowerCase();
    button.setAttribute("aria-label", "打开《" + work.title + "》并试听");
    const art = document.createElement("span");
    art.className = "track-card__art";
    const image = document.createElement("img");
    image.src = work.cover;
    image.alt = "";
    image.width = 240;
    image.height = 240;
    const play = document.createElement("span");
    play.className = "track-card__play";
    play.setAttribute("aria-hidden", "true");
    play.innerHTML = PLAY_ICON;
    art.append(image, play);
    const meta = document.createElement("span");
    meta.className = "track-card__meta";
    const strong = document.createElement("strong");
    strong.textContent = track.title;
    const small = document.createElement("small");
    small.textContent = work.title + " · " + work.genre;
    meta.append(strong, small);
    button.append(art, meta);
    button.addEventListener("click", () => openWork(work, cardByWork.get(work.id)));
    grid.append(button);
  }
}

/* counts, search, navigation */
function updateCounts() {
  const versionCount = ENTRIES.reduce((sum, entry) => sum + entry.track.versions.length, 0);
  byId("stat-tracks").textContent = ENTRIES.length;
  byId("stat-works").textContent = WORKS.length;
  byId("stat-versions").textContent = versionCount;
  byId("rail-track-count").textContent = ENTRIES.length;
  byId("rail-work-count").textContent = WORKS.length;
}

const search = byId("search");
function applyFilter() {
  const query = search.value.trim().toLocaleLowerCase();
  let visibleWorks = 0;
  let visibleRecent = 0;
  for (const card of document.querySelectorAll("#wall .card")) {
    const show = !query || card.dataset.search.includes(query);
    card.hidden = !show;
    if (show) visibleWorks += 1;
  }
  for (const card of document.querySelectorAll(".track-card")) {
    const show = !query || card.dataset.search.includes(query);
    card.hidden = !show;
    if (show) visibleRecent += 1;
  }
  byId("works-empty").hidden = visibleWorks !== 0;
  byId("recent-empty").textContent = query ? "没有匹配的曲目" : "试听过的曲目会显示在这里";
  byId("recent-empty").hidden = visibleRecent !== 0;
}

search.addEventListener("input", applyFilter);
search.addEventListener("search", applyFilter);

const navButtons = Array.from(document.querySelectorAll(".nav-button"));
function activateNav(name) {
  for (const button of navButtons) {
    const active = button.dataset.nav === name;
    button.classList.toggle("is-active", active);
    if (active) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  }
}

for (const button of navButtons) {
  button.addEventListener("click", () => {
    const name = button.dataset.nav;
    activateNav(name);
    if (name === "home") {
      search.value = "";
      applyFilter();
      if (content && typeof content.scrollTo === "function") content.scrollTo({ top: 0, behavior: "smooth" });
      window.scrollTo({ top: 0, behavior: "smooth" });
      return;
    }
    const target = byId(name === "recent" ? "recent" : "works");
    if (target) target.scrollIntoView({ behavior: "smooth", block: "start" });
  });
}

function init() {
  if (!ENTRIES.length) {
    setStatus("没有可试听的示例曲目。");
    return;
  }
  updateCounts();
  buildPicks();
  buildWall();
  buildRecent();
  setCurrent(ENTRIES[0].work, ENTRIES[0].track);
  setFeatured(ENTRIES[0].work);
  listen(ENTRIES[0].track.versions[0], 0, false);
  syncTime();
  applyFilter();
}

init();
