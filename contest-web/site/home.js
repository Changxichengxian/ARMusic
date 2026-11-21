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

const player = createARMusicPlayer(el("player"));
function openWork(work, card) {
  for (const button of el("wall").children) {
    button.setAttribute("aria-pressed", String(button === card));
  }
  player.openWork(work);
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
