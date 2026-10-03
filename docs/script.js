// unibot site — reveals, count-ups, FAQ accordion. Respects reduced motion.
(function () {
  "use strict";
  var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // ── Reveal on scroll ──
  var revealEls = document.querySelectorAll(".reveal");
  if (reduced || !("IntersectionObserver" in window)) {
    revealEls.forEach(function (el) { el.classList.add("in"); });
  } else {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) {
          // stagger siblings slightly
          var siblings = Array.prototype.slice.call(e.target.parentNode.children)
            .filter(function (c) { return c.classList && c.classList.contains("reveal"); });
          var idx = siblings.indexOf(e.target);
          e.target.style.transitionDelay = Math.min(idx * 70, 350) + "ms";
          e.target.classList.add("in");
          io.unobserve(e.target);
        }
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -40px 0px" });
    revealEls.forEach(function (el) { io.observe(el); });
  }

  // ── Animated count-ups ──
  var counters = document.querySelectorAll(".count");
  function setFinal(el) { el.textContent = el.getAttribute("data-count"); }
  if (reduced || !("IntersectionObserver" in window)) {
    counters.forEach(setFinal);
  } else {
    var cio = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting) return;
        cio.unobserve(e.target);
        var el = e.target, target = parseInt(el.getAttribute("data-count"), 10);
        var start = null, dur = 1400;
        function tick(t) {
          if (!start) start = t;
          var p = Math.min((t - start) / dur, 1);
          var eased = 1 - Math.pow(1 - p, 3); // easeOutCubic
          el.textContent = Math.round(target * eased);
          if (p < 1) requestAnimationFrame(tick);
        }
        requestAnimationFrame(tick);
      });
    }, { threshold: 0.5 });
    counters.forEach(function (el) { cio.observe(el); });
  }

  // ── FAQ accordion ──
  document.querySelectorAll(".qa").forEach(function (qa) {
    var btn = qa.querySelector(".q"), ans = qa.querySelector(".a");
    btn.addEventListener("click", function () {
      var open = qa.classList.contains("open");
      document.querySelectorAll(".qa.open").forEach(function (o) {
        o.classList.remove("open");
        o.querySelector(".a").style.maxHeight = null;
      });
      if (!open) {
        qa.classList.add("open");
        ans.style.maxHeight = ans.scrollHeight + "px";
      }
    });
  });

  // ── Nav shadow on scroll ──
  var nav = document.getElementById("nav");
  function onScroll() {
    nav.style.boxShadow = window.scrollY > 10 ? "0 8px 30px rgba(0,0,0,.5)" : "none";
  }
  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();
})();

(function () {
"use strict";
  // ── Welcome gate: OK tap plays the intro sound and enters the site ──
  // Browsers only allow sound after a user gesture, so the welcome overlay's
  // OK button doubles as that gesture: one tap → sound plays → overlay fades.
  try {
    var overlay = document.getElementById("welcome");
    var okBtn = document.getElementById("welcomeOk");
    if (!overlay || !okBtn) return;
    var intro = new Audio("assets/intro-sound.mp3");
    intro.preload = "auto";
    try { intro.load(); } catch (e) {}
    var done = false;
    var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    function heroRevealed() {
      document.querySelectorAll(".hero .reveal").forEach(function (el) {
        el.classList.add("in");
      });
    }
    function endIntro() {
      document.body.classList.remove("intro");
      heroRevealed();
    }
    function enter() {
      if (done) return;
      done = true;
      // Start the 9s choreography in sync with the sound.
      if (!reduced) document.body.classList.add("intro");
      heroRevealed();
      try {
        var p = intro.play();
        if (p && p.catch) p.catch(function () {});
      } catch (e) {}
      overlay.classList.add("hide");
      setTimeout(function () {
        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
      }, 500);
      // Hand control back to the scroll-reveal system when the sound ends
      // (or after 9.5s as a backstop).
      intro.addEventListener("ended", function onEnd() {
        intro.removeEventListener("ended", onEnd);
        endIntro();
      });
      setTimeout(endIntro, 9500);
    }
    okBtn.addEventListener("click", enter);
    // Keyboard users: Enter/Space on the focused button clicks it natively,
    // but also allow Escape to dismiss quietly.
    document.addEventListener("keydown", function (ev) {
      if (ev.key === "Escape") {
        if (done) return;
        done = true;
        overlay.classList.add("hide");
        setTimeout(function () {
          if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }, 500);
      }
    });
  } catch (e) { /* audio unsupported — site works fine without it */ }
})();

/* ── Interaction layer: tilt, progress, active nav, to-top, parallax, magnetic ──
   Mouse-driven effects run only on fine pointers and bail when the user
   prefers reduced motion. The intro movie (body.intro) owns the hero until
   it finishes, so tilt/parallax stay parked while it plays. */
(function () {
"use strict";
  var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var finePointer = window.matchMedia("(pointer: fine)").matches;

  function introPlaying() {
    return document.body.classList.contains("intro") ||
      !!document.getElementById("welcome");
  }

  // ── Scroll progress bar + back-to-top visibility ──
  var progress = document.getElementById("progress");
  var toTop = document.getElementById("toTop");
  function onScroll() {
    var h = document.documentElement;
    var max = h.scrollHeight - h.clientHeight;
    var pct = max > 0 ? (h.scrollTop / max) * 100 : 0;
    if (progress) progress.style.width = pct + "%";
    if (toTop) toTop.classList.toggle("show", h.scrollTop > 700);
  }
  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();
  if (toTop) toTop.addEventListener("click", function () {
    window.scrollTo({ top: 0, behavior: reduced ? "auto" : "smooth" });
  });

  // ── Active nav link ──
  var navAnchors = {};
  document.querySelectorAll(".nav-links a[href^='#']").forEach(function (a) {
    navAnchors[a.getAttribute("href").slice(1)] = a;
  });
  if ("IntersectionObserver" in window) {
    var sectionIO = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        var link = navAnchors[e.target.id];
        if (!link) return;
        if (e.isIntersecting) {
          Object.keys(navAnchors).forEach(function (k) {
            navAnchors[k].classList.remove("active");
          });
          link.classList.add("active");
        }
      });
    }, { rootMargin: "-40% 0px -55% 0px" });
    Object.keys(navAnchors).forEach(function (id) {
      var sec = document.getElementById(id);
      if (sec) sectionIO.observe(sec);
    });
  }

  if (reduced || !finePointer) return; // mouse-only effects below

  // ── 3D tilt on the hero phone ──
  var heroPhone = document.querySelector(".hero-phone .phone");
  var hero = document.querySelector(".hero");
  if (heroPhone && hero) {
    var raf = null;
    hero.addEventListener("mousemove", function (ev) {
      if (introPlaying() || raf) return;
      raf = requestAnimationFrame(function () {
        raf = null;
        var r = hero.getBoundingClientRect();
        var x = (ev.clientX - r.left) / r.width - 0.5;
        var y = (ev.clientY - r.top) / r.height - 0.5;
        heroPhone.style.transform =
          "rotateY(" + (x * 14).toFixed(2) + "deg) rotateX(" +
          (-y * 12).toFixed(2) + "deg) translateZ(12px)";
      });
    });
    hero.addEventListener("mouseleave", function () {
      heroPhone.style.transform = "";
    });
  }

  // ── Aurora parallax (blobs drift against the cursor) ──
  var blobs = document.querySelectorAll(".hero .blob");
  if (blobs.length && hero) {
    var pRaf = null;
    hero.addEventListener("mousemove", function (ev) {
      if (introPlaying() || pRaf) return;
      pRaf = requestAnimationFrame(function () {
        pRaf = null;
        var r = hero.getBoundingClientRect();
        var x = (ev.clientX - r.left) / r.width - 0.5;
        var y = (ev.clientY - r.top) / r.height - 0.5;
        blobs.forEach(function (b, i) {
          var depth = (i + 1) * 14;
          b.style.translate = (-x * depth).toFixed(1) + "px " + (-y * depth).toFixed(1) + "px";
        });
      });
    });
    hero.addEventListener("mouseleave", function () {
      blobs.forEach(function (b) { b.style.translate = ""; });
    });
  }

  // ── Magnetic primary buttons (gentle pull toward the cursor) ──
  document.querySelectorAll(".btn.primary").forEach(function (btn) {
    var mRaf = null;
    btn.addEventListener("mousemove", function (ev) {
      if (mRaf) return;
      mRaf = requestAnimationFrame(function () {
        mRaf = null;
        var r = btn.getBoundingClientRect();
        var x = ev.clientX - (r.left + r.width / 2);
        var y = ev.clientY - (r.top + r.height / 2);
        btn.style.transform =
          "translate(" + (x * 0.08).toFixed(1) + "px," + (y * 0.12).toFixed(1) + "px)";
      });
    });
    btn.addEventListener("mouseleave", function () { btn.style.transform = ""; });
  });
})();
