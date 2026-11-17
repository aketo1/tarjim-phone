"use strict";

/*
 * The look's moving parts, kept apart from the program logic in app.js:
 *   - the night sky behind everything (stars drawn once per window size),
 *   - liquid glass: a lens-like refraction at the rim of the floating controls
 *     (an SVG displacement map per size, used through backdrop-filter; Chromium only,
 *     which is what both the laptop window (WebView2) and the phone app (WebView) are),
 *   - the dock's sliding lens, the segmented controls' thumbs and the sliders' fill,
 *   - the start button's shapes (one ring of points, so the browser can morph between them),
 *   - a soft light that follows the mouse over glass and cards.
 * Nothing here talks to the program.
 */
const UI = (() => {
  const root = document.documentElement;
  const NS = "http://www.w3.org/2000/svg";
  const fine = matchMedia("(pointer: fine)").matches;

  /* ------------------------------ the stars ------------------------------ */

  const rng = seed => () => (seed = (seed * 16807) % 2147483647) / 2147483647;

  function drawStars() {
    const c = document.getElementById("stars");
    if (!c) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = innerWidth, h = innerHeight;
    c.width = Math.round(w * dpr);
    c.height = Math.round(h * dpr);
    const g = c.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    const r = rng(1117);
    const star = (x, y, size, a, warm) => {
      g.fillStyle = warm ? `rgba(255, 228, 196, ${a})` : `rgba(236, 240, 255, ${a})`;
      g.beginPath();
      g.arc(x, y, size, 0, Math.PI * 2);
      g.fill();
    };
    // scattered stars, thinning towards the horizon
    const count = Math.round(Math.min(420, (w * h) / 4200));
    for (let i = 0; i < count; i++) {
      const x = r() * w, y = Math.pow(r(), 1.35) * h * 0.8;
      const big = r() > 0.9;
      const size = big ? 1 + r() * 0.9 : 0.35 + r() * 0.65;
      const a = (0.3 + r() * 0.7) * (1 - (y / h) * 0.75);
      if (big) {
        const halo = g.createRadialGradient(x, y, 0, x, y, size * 5);
        halo.addColorStop(0, `rgba(220, 225, 255, ${a * 0.35})`);
        halo.addColorStop(1, "rgba(220, 225, 255, 0)");
        g.fillStyle = halo;
        g.fillRect(x - size * 5, y - size * 5, size * 10, size * 10);
      }
      star(x, y, size, a, r() > 0.8);
    }
    // the milky way: a band of dust from the lower left to the upper right
    const x0 = -0.05 * w, y0 = 0.62 * h, x1 = 1.05 * w, y1 = -0.05 * h;
    const dust = Math.round(count * 1.6);
    for (let i = 0; i < dust; i++) {
      const t = r();
      const spread = (r() + r() + r() - 1.5) * 0.11 * Math.max(w, h);
      const nx = -(y1 - y0), ny = x1 - x0, len = Math.hypot(nx, ny);
      const x = x0 + (x1 - x0) * t + (nx / len) * spread, y = y0 + (y1 - y0) * t + (ny / len) * spread;
      star(x, y, 0.3 + r() * 0.45, 0.12 + r() * 0.3, r() > 0.7);
    }
  }

  /* ------------------------------ liquid glass ------------------------------ */

  const defs = document.getElementById("glass-defs");
  const made = new Map();

  /** How far a point inside a rounded rectangle samples towards the middle: strongest at the rim. */
  function displacementMap(w, h, r, bezel) {
    const c = document.createElement("canvas");
    c.width = w;
    c.height = h;
    const g = c.getContext("2d");
    const img = g.createImageData(w, h);
    const d = img.data;
    const hw = w / 2, hh = h / 2;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const px = x + 0.5 - hw, py = y + 0.5 - hh;
        const qx = Math.abs(px) - (hw - r), qy = Math.abs(py) - (hh - r);
        let dist, nx, ny;
        if (qx > 0 && qy > 0) {
          const l = Math.hypot(qx, qy) || 1;
          dist = l - r; nx = qx / l; ny = qy / l;
        } else if (qx > qy) {
          dist = qx - r; nx = 1; ny = 0;
        } else {
          dist = qy - r; nx = 0; ny = 1;
        }
        const t = Math.min(1, Math.max(0, -dist / bezel));
        const m = (1 - t) * (1 - t) * (1 - t * 0.35);
        const i = (y * w + x) * 4;
        d[i] = 128 - Math.sign(px) * nx * m * 127;
        d[i + 1] = 128 - Math.sign(py) * ny * m * 127;
        d[i + 2] = 128;
        d[i + 3] = 255;
      }
    }
    g.putImageData(img, 0, 0);
    return c.toDataURL();
  }

  function filterFor(w, h, r, o) {
    w = Math.max(8, Math.round(w / 2) * 2);
    h = Math.max(8, Math.round(h / 2) * 2);
    r = Math.max(0, Math.min(Math.round(r), w / 2, h / 2));
    const bezel = Math.min(o.bezel, h / 2, w / 2);
    const key = [w, h, r, bezel, o.scale, o.blur, o.sat].join("x");
    if (made.has(key)) return made.get(key);
    const id = `lg${made.size + 1}`;
    const f = document.createElementNS(NS, "filter");
    f.setAttribute("id", id);
    f.setAttribute("x", "0");
    f.setAttribute("y", "0");
    f.setAttribute("width", String(w));
    f.setAttribute("height", String(h));
    f.setAttribute("filterUnits", "userSpaceOnUse");
    f.setAttribute("color-interpolation-filters", "sRGB");
    f.innerHTML =
      `<feGaussianBlur in="SourceGraphic" stdDeviation="${o.blur}" result="soft"/>` +
      `<feImage href="${displacementMap(w, h, r, bezel)}" x="0" y="0" width="${w}" height="${h}" preserveAspectRatio="none" result="map"/>` +
      `<feDisplacementMap in="soft" in2="map" scale="${o.scale}" xChannelSelector="R" yChannelSelector="G" result="bent"/>` +
      `<feColorMatrix in="bent" type="saturate" values="${o.sat}"/>`;
    defs.append(f);
    made.set(key, id);
    return id;
  }

  const lensOf = new WeakMap();
  const sized = new ResizeObserver(entries => entries.forEach(e => fit(e.target)));

  function fit(el) {
    const o = lensOf.get(el);
    const w = el.offsetWidth, h = el.offsetHeight;
    if (!o || !w || !h) return;
    const r = parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0;
    el.style.setProperty("--lg", `url(#${filterFor(w, h, r, o)})`);
  }

  /** Make an element real liquid glass. Others with class "lg" get the frosted version from the stylesheet. */
  function glass(el, opt) {
    if (!el || lensOf.has(el)) return;
    lensOf.set(el, { bezel: 18, scale: 54, blur: 2.5, sat: 1.7, ...opt });
    sized.observe(el);
    fit(el);
  }

  /* ------------------------------ the dock's lens ------------------------------ */

  function moveLens() {
    const lens = document.getElementById("lens");
    if (!lens) return;
    const on = [...document.querySelectorAll(".dock .nav.on")].find(b => b.offsetParent);
    if (!on) { lens.style.opacity = "0"; return; }
    const a = lens.parentElement.getBoundingClientRect(), b = on.getBoundingClientRect();
    const x = b.left - a.left, y = b.top - a.top;
    const was = lens.dataset.x;
    lens.style.width = `${b.width}px`;
    lens.style.height = `${b.height}px`;
    lens.style.transform = `translate(${x}px, ${y}px)`;
    lens.style.opacity = "1";
    if (was !== undefined && Math.abs(Number(was) - x) > 2) {
      // a drop of glass stretches while it travels, then settles
      lens.classList.remove("moving");
      void lens.offsetWidth;
      lens.classList.add("moving");
    }
    lens.dataset.x = String(x);
  }

  /* ------------------------------ segmented controls ------------------------------ */

  function syncSeg(seg) {
    let t = seg.querySelector(":scope > .seg-thumb");
    if (!t) {
      t = document.createElement("i");
      t.className = "seg-thumb";
      t.setAttribute("aria-hidden", "true");
      seg.prepend(t);
    }
    const on = seg.querySelector(":scope > button.on");
    if (!on || !on.offsetParent) { t.style.opacity = "0"; return; }
    t.style.opacity = "1";
    t.style.width = `${on.offsetWidth}px`;
    t.style.height = `${on.offsetHeight}px`;
    t.style.transform = `translate(${on.offsetLeft}px, ${on.offsetTop}px)`;
  }

  /* ------------------------------ sliders ------------------------------ */

  function fillRange(r) {
    const min = Number(r.min) || 0, max = Number(r.max) || 100;
    const p = max > min ? ((Number(r.value) - min) / (max - min)) * 100 : 0;
    r.style.setProperty("--p", `${Math.max(0, Math.min(100, p))}%`);
  }
  function ranges() {
    document.querySelectorAll('input[type="range"]').forEach(fillRange);
  }
  document.addEventListener("input", e => { if (e.target.type === "range") fillRange(e.target); }, true);

  /* ------------------------------ the start button's shapes ------------------------------ */

  // Every shape is the same ring of points, so the browser can morph one into the next.
  function shape(radius, n = 144) {
    let d = "";
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2 - Math.PI / 2;
      const rr = radius(a);
      d += `${i ? "L" : "M"}${(100 + rr * Math.cos(a)).toFixed(2)} ${(100 + rr * Math.sin(a)).toFixed(2)}`;
    }
    return `path("${d}Z")`;
  }
  function shapes() {
    const squircle = a => 78 / Math.pow(Math.pow(Math.abs(Math.cos(a)), 4) + Math.pow(Math.abs(Math.sin(a)), 4), 0.25);
    const set = {
      "--s-cookie": shape(a => 86 * (1 + 0.055 * Math.cos(9 * a))),
      "--s-burst": shape(a => 84 * (1 + 0.12 * Math.cos(12 * a))),
      "--s-flower": shape(a => 84 * (1 + 0.1 * Math.cos(5 * a))),
      "--s-pill": shape(a => 80 * (1 + 0.14 * Math.cos(2 * a))),
      "--s-squircle": shape(squircle),
    };
    for (const [k, v] of Object.entries(set)) root.style.setProperty(k, v);
  }

  /* ------------------------------ light under the mouse ------------------------------ */

  let lit = null, frame = 0, mx = 0, my = 0;
  function light(e) {
    if (e.pointerType && e.pointerType !== "mouse") return;
    mx = e.clientX;
    my = e.clientY;
    const t = e.target.closest?.(".lg, .card, .btn, .preset, .font, .stat, .lib-card, .deck");
    if (t !== lit) {
      lit?.classList.remove("lit");
      lit = t;
      lit?.classList.add("lit");
    }
    if (lit && !frame) {
      frame = requestAnimationFrame(() => {
        frame = 0;
        if (!lit) return;
        const b = lit.getBoundingClientRect();
        lit.style.setProperty("--mx", `${mx - b.left}px`);
        lit.style.setProperty("--my", `${my - b.top}px`);
      });
    }
  }

  /* ------------------------------ keeping it all in place ------------------------------ */

  let queued = 0;
  function sync() {
    if (queued) return;
    queued = requestAnimationFrame(() => {
      queued = 0;
      moveLens();
      document.querySelectorAll(".seg").forEach(syncSeg);
    });
  }
  const watch = new MutationObserver(list => {
    for (const m of list) {
      const t = m.target;
      if (m.type === "attributes") {
        if (m.attributeName === "hidden" || t.classList?.contains("nav") || t.parentElement?.classList?.contains("seg") || t.classList?.contains("view")) return sync();
      } else if ([...m.addedNodes].some(n => n.nodeType === 1 && (n.matches(".seg, .view") || n.querySelector(".seg")))) {
        return sync();
      }
    }
  });

  function start() {
    shapes();
    drawStars();
    glass(document.querySelector(".dock"), { blur: 5, scale: 60 });
    document.querySelectorAll(".picker, .apk-banner").forEach(el => glass(el, { blur: 4 }));
    glass(document.querySelector(".badge"), { bezel: 12, scale: 34 });
    watch.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ["class", "hidden"] });
    let resizing;
    addEventListener("resize", () => {
      sync();
      clearTimeout(resizing);
      resizing = setTimeout(drawStars, 180);
    });
    document.fonts?.ready.then(sync);
    if (fine) document.addEventListener("pointermove", light, { passive: true });
    const main = document.querySelector("main");
    main?.addEventListener("scroll", () => document.body.classList.toggle("scrolled", main.scrollTop > 8), { passive: true });
    sync();
  }

  start();
  return { sync, ranges, glass };
})();
