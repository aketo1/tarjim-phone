"use strict";

/*
 * The look of the page. Behind everything sits the chosen game's own picture (art/, made by
 * launcher/fetch_art.py from the publishers' store art), and while the translation runs, the
 * live picture the program itself receives from the console, a few times a second.
 * Two layouts share one page: "cinema" (the picture fills the screen, few controls on clear
 * glass) and "shelf" (the games in a row of covers, as on a console's home screen).
 */
const UI = (() => {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode: the default is fine */ } },
  };

  /* ------------------------------ a game's own pictures ------------------------------ */

  const norm = t => String(t || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
  function find(name, title) {
    const A = window.ART || {};
    if (A[name]) return name;
    const t = norm(title);
    if (!t) return null;
    const ids = Object.keys(A);
    const exact = ids.find(k => norm(A[k].t) === t || norm(k) === t);
    if (exact) return exact;
    if (t.length < 6) return null;
    // "Red Dead Redemption 2 PS5" is Red Dead Redemption 2 (the longest title it starts with)
    const longer = ids.filter(k => { const a = norm(A[k].t); return a.length >= 6 && t.startsWith(a); })
      .sort((x, y) => norm(A[y].t).length - norm(A[x].t).length);
    if (longer.length) return longer[0];
    // "Final Fantasy VII Remake" is the one library title that starts with it
    const shorter = ids.filter(k => norm(A[k].t).startsWith(t));
    return shorter.length === 1 ? shorter[0] : null;
  }
  function art(name, title) {
    const id = find(name, title);
    if (!id) return null;
    const a = window.ART[id];
    return {
      id, x: a.x, ar: a.ar || "", en: a.t,
      bg: `art/${id}-bg.webp`, tall: `art/${id}-tall.webp`, cover: `art/${id}-cover.webp`,
      logo: a.logo ? `art/${id}-logo.webp` : null,
    };
  }

  /* ------------------------------ the picture behind everything ------------------------------ */

  const tall = matchMedia("(max-aspect-ratio: 1/1)");
  let shown = null, artTurn = 0;
  function backdrop(a) {
    shown = a;
    const turn = ++artTurn;
    const imgs = [$("#art-a"), $("#art-b")];
    const front = imgs.find(i => i.classList.contains("on"));
    const back = imgs.find(i => i !== front) || imgs[0];
    document.body.classList.toggle("no-art", !a);
    if (!a) { imgs.forEach(i => i.classList.remove("on")); return; }
    const src = tall.matches ? a.tall : a.bg;
    if (front && front.dataset.src === src) return;
    back.style.objectPosition = tall.matches ? "50% 50%" : `${a.x}% 50%`;
    back.dataset.src = src;
    back.onload = () => {
      if (turn !== artTurn) return;  // another game was chosen meanwhile
      back.style.zIndex = "2";  // the new picture fades in over the old one
      if (front) front.style.zIndex = "1";
      back.classList.add("on");
      setTimeout(() => { if (turn === artTurn && front) front.classList.remove("on"); }, 700);
    };
    back.onerror = () => { if (turn === artTurn) document.body.classList.toggle("no-art", !front); };
    back.src = src;
  }
  tall.addEventListener("change", () => backdrop(shown));

  /* ------------------------------ the live picture ------------------------------ */
  // Measured in the program's window on the laptop (i5-12450H, 12 threads), with the translation
  // working beside it: 4 pictures a second cost the window about 8% of one core and 3% of the
  // graphics chip, and a subtitle line took as long as without them (136 ms median). A soft
  // fade between pictures cost three times as much, so each picture simply replaces the last.
  // None are asked for while the window is hidden or the phone's screen is dimmed.

  const LIVE = { on: false, url: null, every: 250, timer: 0, misses: 0, busy: false };
  function live(on, url, every) {
    LIVE.on = !!on;
    if (url) LIVE.url = url;
    if (every) LIVE.every = every;
    clearTimeout(LIVE.timer);
    if (LIVE.on) liveTick();
    else liveOff();
  }
  function liveOff() {
    document.body.classList.remove("live-pic");
    for (const img of [$("#live-a"), $("#live-b")]) {
      img.classList.remove("on");
      if (img.dataset.url) { const u = img.dataset.url; setTimeout(() => URL.revokeObjectURL(u), 800); img.dataset.url = ""; }
    }
  }
  function paused() {
    return document.hidden || !$("#rest-screen")?.hidden || document.body.classList.contains("still");
  }
  async function liveTick() {
    if (!LIVE.on) return;
    if (paused() || LIVE.busy) { LIVE.timer = setTimeout(liveTick, 600); return; }
    LIVE.busy = true;
    const started = performance.now();
    try {
      const res = await fetch(LIVE.url(), { cache: "no-store" });
      if (!res.ok) throw new Error(String(res.status));
      const blob = await res.blob();
      if (!LIVE.on) return;
      const imgs = [$("#live-a"), $("#live-b")];
      const front = imgs.find(i => i.classList.contains("on"));
      const back = imgs.find(i => i !== front) || imgs[0];
      const url = URL.createObjectURL(blob);
      back.src = url;
      await back.decode().catch(() => {});
      if (!LIVE.on) { URL.revokeObjectURL(url); return; }
      back.classList.add("on");
      front?.classList.remove("on");
      const old = back.dataset.url;
      back.dataset.url = url;
      if (old) URL.revokeObjectURL(old);
      document.body.classList.add("live-pic");
      LIVE.misses = 0;
    } catch {
      // no picture yet (still connecting) or the program is busy: keep the game's own picture
      if (++LIVE.misses >= 6) liveOff();
    } finally {
      LIVE.busy = false;
    }
    if (LIVE.on) LIVE.timer = setTimeout(liveTick, Math.max(40, LIVE.every - (performance.now() - started)));
  }
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && LIVE.on) { clearTimeout(LIVE.timer); liveTick(); }
  });

  /* ------------------------------ the two layouts ------------------------------ */

  function layout(name) {
    name = name === "shelf" ? "shelf" : "cinema";
    document.body.dataset.layout = name;
    $$("[data-layout-pick]").forEach(b => b.classList.toggle("on", b.dataset.layoutPick === name));
    return name;
  }
  function liveWanted() { return store.get("tarjim-live-bg") !== "0"; }

  /* ------------------------------ small helpers ------------------------------ */

  // sliders: the part before the thumb is filled (the page is right-to-left, so is every slider)
  function fillRange(r) {
    const min = Number(r.min) || 0, max = Number(r.max) || 100;
    r.style.setProperty("--p", `${((Number(r.value) - min) / (max - min)) * 100}%`);
  }
  function ranges() {
    $$('input[type="range"]').forEach(r => {
      fillRange(r);
      if (!r.dataset.fill) { r.dataset.fill = "1"; r.addEventListener("input", () => fillRange(r)); }
    });
  }

  // the bar's menu: which layout, and the live picture
  function menu(onChange) {
    const pop = $("#ui-pop"), btn = $("#ui-menu");
    const place = () => {
      const r = btn.getBoundingClientRect();
      const w = pop.offsetWidth || 300;
      const left = Math.max(12, Math.min(innerWidth - w - 12, r.left));
      pop.style.left = `${left}px`;
      pop.style.top = `${r.bottom + 10}px`;
    };
    pop.addEventListener("toggle", e => { if (e.newState === "open") place(); });
    addEventListener("resize", () => { try { if (pop.matches(":popover-open")) place(); } catch { /* no popovers */ } });
    $$("[data-layout-pick]", pop).forEach(b => b.addEventListener("click", () => {
      store.set("tarjim-layout", layout(b.dataset.layoutPick));
      onChange?.("layout");
    }));
    const sw = $("#live-bg");
    sw.checked = liveWanted();
    sw.addEventListener("change", () => { store.set("tarjim-live-bg", sw.checked ? "1" : "0"); onChange?.("live"); });
  }

  const q = new URLSearchParams(location.search);
  layout(q.get("layout") || store.get("tarjim-layout") || "cinema");

  return { art, backdrop, live, layout, liveWanted, ranges, menu, store };
})();
