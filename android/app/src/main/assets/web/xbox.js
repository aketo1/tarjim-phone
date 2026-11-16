"use strict";

/*
 * Receives the Xbox picture in this window and hands it to the program.
 *
 * Xbox home streaming is WebRTC, which a browser window speaks natively. The
 * open-source xbox-xcloud-player library (MIT, xCloudPlayer.min.js) does the
 * protocol; its API calls (/v5/..., /v6/...) go to the program's local server,
 * which forwards them to Xbox with the sign-in token added.
 *
 * Tarjim only looks: no controller input is ever sent (no input driver is given
 * anything to send), and the stream's sound is muted.
 *
 * Experimental: follows the library's own example (www/stream.js) step by step,
 * but has not been run against a console yet.
 */
const Xbox = (() => {
  let client = null, api = null, video = null, timer = null, watchdog = null;
  let busy = false, canvas = null, options = {}, active = false;

  const report = (state, error) => {
    const headers = { "Content-Type": "application/json" };
    if (typeof TOKEN !== "undefined" && TOKEN) headers["X-Tarjim-Token"] = TOKEN;
    return fetch("/api/xbox/state", { method: "POST", headers, body: JSON.stringify({ state, error: error || "" }) }).catch(() => {});
  };

  function library() {
    return new Promise((resolve, reject) => {
      if (window.xCloudPlayer) return resolve();
      const s = document.createElement("script");
      s.src = "xCloudPlayer.min.js";
      s.onload = () => resolve();
      s.onerror = () => reject(new Error("xCloudPlayer.min.js did not load"));
      document.head.append(s);
    });
  }

  function holder() {
    let h = document.getElementById("xbox-holder");
    if (!h) {
      h = document.createElement("div");
      h.id = "xbox-holder";
      // On screen (a hidden element's video is not decoded) but practically invisible and never clickable.
      h.style.cssText = "position:fixed;left:0;bottom:0;width:320px;height:180px;opacity:.01;pointer-events:none;z-index:-1;overflow:hidden";
      document.body.append(h);
    }
    return h;
  }

  /** One frame: the whole picture (laptop) or only the reading area (phone), as JPEG, to the program. */
  function sendFrame() {
    if (busy || !video || !video.videoWidth) return;
    const vw = video.videoWidth, vh = video.videoHeight;
    const c = options.crop;
    const sx = c ? Math.round(vw * c.left) : 0, sy = c ? Math.round(vh * c.top) : 0;
    const sw = c ? Math.round(vw * (c.right - c.left)) : vw, sh = c ? Math.round(vh * (c.bottom - c.top)) : vh;
    // Everything downstream is tuned for a 1080p picture: scale to that, whatever the stream's size.
    const scale = 1080 / vh;
    const dw = Math.max(2, Math.round(sw * scale)), dh = Math.max(2, Math.round(sh * scale));
    canvas = canvas || document.createElement("canvas");
    if (canvas.width !== dw || canvas.height !== dh) { canvas.width = dw; canvas.height = dh; }
    canvas.getContext("2d", { alpha: false }).drawImage(video, sx, sy, sw, sh, 0, 0, dw, dh);
    busy = true;
    canvas.toBlob(blob => {
      if (!blob || !active) { busy = false; return; }
      const q = c ? `?x=${Math.round(sx * scale)}&y=${Math.round(sy * scale)}&fw=${Math.round(vw * scale)}&fh=1080` : "";
      const headers = { "Content-Type": "image/jpeg" };
      if (typeof TOKEN !== "undefined" && TOKEN) headers["X-Tarjim-Token"] = TOKEN;
      fetch(`/api/xbox/frame${q}`, { method: "POST", headers, body: blob }).catch(() => {}).finally(() => { busy = false; });
    }, "image/jpeg", 0.9);
  }

  function watch() {
    const h = holder();
    h.querySelectorAll("audio, video").forEach(m => { m.muted = true; m.volume = 0; });
    video = h.querySelector("video");
    if (video && !timer) {
      video.play().catch(() => {});
      timer = setInterval(sendFrame, options.every || 66);
    }
  }

  async function start(serverId, opts = {}) {
    stop();
    options = opts;
    active = true;
    try {
      await library();
      holder();
      client = new xCloudPlayer.default("xbox-holder", { ui_systemui: [], ui_touchenabled: false, input_legacykeyboard: false });
      client.bind();
      api = new xCloudPlayer.xCloudPlayerBackend();
      client.getEventBus().on("connectionstate", event => {
        if (!active) return;
        if (event.state === "connected") { report("connected"); watch(); }
        else if (event.state === "closed" || event.state === "failed") report(event.state === "failed" ? "failed" : "closed", "انقطع اتصال الإكس بوكس.");
      });
      client.setSdpHandler((c, offer) => {
        api.sendSDPChatOffer(offer).then(answer => c.setRemoteOffer(JSON.parse(answer.exchangeResponse).sdp)).catch(() => {});
      });
      await api.startSession("home", serverId);
      const offer = await client.createOffer();
      const sdp = await api.sendSDPOffer(offer);
      client.setRemoteOffer(JSON.parse(sdp.exchangeResponse).sdp);
      const ice = await api.sendICECandidates(client.getIceCandidates());
      client.setIceCandidates(JSON.parse(ice.exchangeResponse));
      // The media elements appear when the tracks arrive; keep looking for them and keep them muted.
      watchdog = setInterval(() => { if (active) watch(); }, 1000);
    } catch (e) {
      const text = (e && (e.message || e.error?.message)) || "ما قدرت أبدأ بث الإكس بوكس.";
      report("failed", String(text).slice(0, 180));
      stop();
    }
  }

  function stop() {
    active = false;
    clearInterval(timer); clearInterval(watchdog);
    timer = watchdog = null;
    video = null;
    busy = false;
    try { client?.close(); } catch { /* already closed */ }
    client = api = null;
    document.getElementById("xbox-holder")?.replaceChildren();
  }

  return { start, stop };
})();
