/* newtube.org: small progressive enhancements, no dependencies. Every page works without them.
   The release links, sizes and star count are written into the pages at build time (build.py),
   so this file makes no network requests.
   1. The chapter demo: chapters seek the clip and fill as it plays. The clip loads and plays
      only when it is on screen, and never plays by itself with reduced motion or Save-Data on.
   2. Copy buttons.
   3. Links to the old one-page site's #anchors forward to the pages that replaced them.
   4. A link to a FAQ answer opens it.
   5. The speed numbers run like a stopwatch, in real time: 0.24 s takes 0.24 s to count. */
(function () {
  "use strict";

  var LANG = (document.documentElement.lang || "en").slice(0, 2);

  /* ---------- 3. Old anchors ---------- */
  (function forwardOldAnchors() {
    var path = location.pathname.replace(/index\.html$/, "");
    if (path !== "/") return;
    var map = {
      "#download": "/download/",
      "#trust": "/trust/",
      "#faq": "/faq/",
      "#sign-in": "/faq/#sign-in",
      "#how-fast": "#speed",
      "#smarttube": "/faq/#smarttube-on-phones",
      "#smarttube-for-phones": "/faq/#smarttube-on-phones",
      "#built-on-smarttube": "/faq/#smarttube-on-phones"
    };
    var to = map[location.hash];
    if (!to) return;
    if (to.charAt(0) === "#") {
      history.replaceState(null, "", to);
      var el = document.querySelector(to);
      if (el) el.scrollIntoView();
    } else {
      location.replace(to);
    }
  })();

  /* ---------- 1. Chapter demo ---------- */
  function setupDemo() {
    var root = document.querySelector("[data-demo]");
    if (!root) return;
    var video = root.querySelector("[data-demo-video]");
    var toggle = root.querySelector("[data-demo-toggle]");
    var buttons = root.querySelectorAll(".chapters button");
    if (!video || !buttons.length) return;

    var words = LANG === "es" ? { play: "Reproducir", pause: "Pausa" } : { play: "Play", pause: "Pause" };
    var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    var saveData = navigator.connection && navigator.connection.saveData;
    var autoplay = !reduce && !saveData;
    var userPaused = false;
    var visible = false;

    var chapters = [];
    for (var i = 0; i < buttons.length; i++) {
      chapters.push({
        el: buttons[i],
        fill: buttons[i].querySelector(".ch-bar i"),
        start: parseFloat(buttons[i].getAttribute("data-start")) || 0,
        end: parseFloat(buttons[i].getAttribute("data-end")) || 0
      });
    }

    function setToggle() {
      if (!toggle) return;
      var playing = !video.paused;
      toggle.textContent = playing ? words.pause : words.play;
      toggle.setAttribute("aria-pressed", playing ? "true" : "false");
    }

    function play() {
      if (video.preload === "none") video.preload = "auto";
      var p = video.play();
      if (p && p.catch) p.catch(function () { setToggle(); });
    }

    var raf = 0;
    function paint() {
      var t = video.currentTime;
      for (var c = 0; c < chapters.length; c++) {
        var ch = chapters[c];
        var p = t >= ch.end ? 1 : t <= ch.start ? 0 : (t - ch.start) / (ch.end - ch.start);
        if (ch.fill) ch.fill.style.width = (p * 100).toFixed(2) + "%";
        var on = t >= ch.start && t < ch.end;
        ch.el.parentNode.classList.toggle("on", on);
        if (on) ch.el.setAttribute("aria-current", "true"); else ch.el.removeAttribute("aria-current");
      }
      raf = video.paused ? 0 : requestAnimationFrame(paint);
    }

    video.addEventListener("play", function () { setToggle(); if (!raf) raf = requestAnimationFrame(paint); });
    video.addEventListener("pause", function () { setToggle(); paint(); });
    video.addEventListener("seeked", paint);

    for (var b = 0; b < chapters.length; b++) {
      (function (ch) {
        ch.el.addEventListener("click", function () {
          if (video.preload === "none") video.preload = "auto";
          video.currentTime = ch.start + 0.05;
          userPaused = false;
          play();
        });
      })(chapters[b]);
    }

    if (toggle) {
      toggle.addEventListener("click", function () {
        if (video.paused) { userPaused = false; play(); } else { userPaused = true; video.pause(); }
      });
    }

    if ("IntersectionObserver" in window) {
      new IntersectionObserver(function (entries) {
        visible = entries[0].isIntersecting;
        if (visible && autoplay && !userPaused) play();
        else if (!visible && !video.paused) video.pause();
      }, { threshold: 0.35 }).observe(video);
    }
    setToggle();
    paint();
  }

  /* ---------- 2. Copy buttons ---------- */
  function setupCopy() {
    if (!navigator.clipboard || !window.isSecureContext) return;
    var status = document.querySelector("[data-copy-status]");
    var buttons = document.querySelectorAll("[data-copy]");
    var words = LANG === "es" ? { copy: "Copiar", done: "Copiado", said: "Copiado al portapapeles." }
                              : { copy: "Copy", done: "Copied", said: "Copied to the clipboard." };
    for (var i = 0; i < buttons.length; i++) {
      (function (btn) {
        var target = document.getElementById(btn.getAttribute("data-copy"));
        var label = btn.querySelector("span");
        if (!target || !label) return;
        btn.hidden = false;
        var reset = null;
        btn.addEventListener("click", function () {
          navigator.clipboard.writeText(target.textContent.trim()).then(function () {
            label.textContent = words.done;
            if (status) status.textContent = words.said;
            clearTimeout(reset);
            reset = setTimeout(function () {
              label.textContent = words.copy;
              if (status) status.textContent = "";
            }, 2000);
          }, function () { /* clipboard refused: nothing to do */ });
        });
      })(buttons[i]);
    }
  }

  /* ---------- 4. A link to a FAQ answer opens it ---------- */
  function openTarget() {
    var id = location.hash.slice(1);
    if (!id) return;
    var el = document.getElementById(id);
    if (el && el.tagName === "DETAILS") el.open = true;
  }

  /* ---------- 5. Stopwatch ---------- */
  function setupStopwatch() {
    var list = document.querySelector(".stats");
    if (!list || !("IntersectionObserver" in window) || !window.requestAnimationFrame) return;
    if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    var sep = LANG === "es" ? "," : ".";
    var stats = [].map.call(list.querySelectorAll(".stat .n"), function (el) {
      return { el: el, box: el.parentNode, end: parseFloat(el.textContent.replace(",", ".")), text: el.textContent };
    }).filter(function (s) { return s.end > 0; });
    if (!stats.length) return;

    function show(s, v) { s.el.textContent = v.toFixed(2).replace(".", sep); }
    function run() {
      var t0 = null;
      stats.forEach(function (s) { show(s, 0); s.box.classList.add("ticking"); });
      requestAnimationFrame(function frame(now) {
        if (t0 === null) t0 = now;
        var elapsed = (now - t0) / 1000, running = false;
        stats.forEach(function (s) {
          if (elapsed < s.end) { show(s, elapsed); running = true; }
          else if (s.box.classList.contains("ticking")) { s.el.textContent = s.text; s.box.classList.remove("ticking"); }
        });
        if (running) requestAnimationFrame(frame);
      });
    }
    var io = new IntersectionObserver(function (entries) {
      if (!entries[0].isIntersecting) return;
      io.disconnect();
      run();
    }, { threshold: 0.6 });
    io.observe(list);
  }

  setupDemo();
  setupCopy();
  setupStopwatch();
  openTarget();
  window.addEventListener("hashchange", openTarget);
})();
