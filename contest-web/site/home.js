"use strict";
const el = id => document.getElementById(id);
const intro = el("intro");
const help = el("help");
const storageKey = "armusic-intro-seen-v1";
try { intro.hidden = localStorage.getItem(storageKey) === "yes"; }
catch { intro.hidden = false; }
function setIntro(open) {
  intro.hidden = !open;
  help.setAttribute("aria-expanded", String(open));
}
setIntro(!intro.hidden);
help.onclick = () => { setIntro(true); el("dismiss").focus(); };
el("dismiss").onclick = () => {
  setIntro(false);
  try { localStorage.setItem(storageKey, "yes"); } catch {}
  el("wall").querySelector("button")?.focus();
};

const audio = document.querySelector("audio");
let revision = 0;
let selectedVersion;
let pendingPosition = null;
audio.addEventListener("error", () => {
  el("status").textContent = "音频加载失败，请重新选择作品或检查素材文件。";
});
function listen(version, position, playing) {
  const request = ++revision;
  selectedVersion = version;
  pendingPosition = position;
  audio.onloadedmetadata = () => {
    if (request !== revision) return;
    audio.currentTime = Math.min(position, Math.max(0, audio.duration - .1));
    pendingPosition = null;
  };
  el("status").textContent = "";
  audio.src = version.src;
  audio.load();
  if (playing) audio.play().catch(() => {
    if (request === revision && !audio.error) el("status").textContent = "点播放器的播放键继续试听。";
  });
  for (const b of el("versions").children) b.setAttribute("aria-pressed", String(b.dataset.id === version.id));
}
function openWork(work, card) {
  const track = work.tracks[0];
  for (const b of el("wall").children) b.setAttribute("aria-pressed", String(b === card));
  el("work-name").textContent = work.title + " / " + work.genre;
  el("current").textContent = track.title;
  el("description").textContent = work.description;
  el("versions").replaceChildren();
  for (const version of track.versions) {
    const b = document.createElement("button");
    b.type = "button";
    b.dataset.id = version.id;
    b.textContent = version.title;
    b.onclick = () => {
      if (selectedVersion !== version) listen(version, pendingPosition ?? audio.currentTime, !audio.paused);
    };
    el("versions").append(b);
  }
  el("player").hidden = false;
  listen(track.versions[0], 0, true);
  el("current").focus({preventScroll:true});
  el("player").scrollIntoView({block:"nearest"});
}
for (const work of window.ARMUSIC_WORKS) {
  const card = document.createElement("button");
  card.className = "card";
  card.type = "button";
  card.setAttribute("aria-pressed", "false");
  card.setAttribute("aria-label", "试听《" + work.title + "》");
  const cover = new Image();
  cover.src = work.cover;
  cover.alt = "";
  const meta = document.createElement("span");
  meta.className = "meta";
  meta.innerHTML = '<strong></strong><span class="play" aria-hidden="true">▶</span>';
  meta.firstElementChild.textContent = work.title;
  const label = document.createElement("span");
  label.className = "genre";
  label.textContent = work.genre + "原声 · " + work.tracks[0].versions.length + " 个版本";
  card.append(cover, meta, label);
  card.onclick = () => openWork(work, card);
  el("wall").append(card);
}
