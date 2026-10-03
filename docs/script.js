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
  // ── Intro sound: plays once per page load ──
  // Browsers block autoplay with sound, so we try immediately and fall back
  // to playing on the first tap/click/keypress. Only counts as played after
  // play() actually succeeds, so a failed tap retries on the next one.
  try {
    var intro = new Audio("assets/intro-sound.mp3");
    intro.preload = "auto";
    try { intro.load(); } catch (e) {}
    var played = false;
    function arm() {
      ["pointerdown", "touchend", "keydown"].forEach(function (ev) {
        window.addEventListener(ev, playIntro, { passive: true });
      });
    }
    function disarm() {
      ["pointerdown", "touchend", "keydown"].forEach(function (ev) {
        window.removeEventListener(ev, playIntro);
      });
    }
    function playIntro() {
      if (played) return;
      var p = null;
      try { p = intro.play(); } catch (e) { return; }
      if (p && p.then) {
        p.then(function () { played = true; disarm(); })
         .catch(function () { /* not yet — next tap retries */ });
      } else {
        played = true;
        disarm();
      }
    }
    var first = null;
    try { first = intro.play(); } catch (e) { first = null; }
    if (first && first.then) {
      first.then(function () { played = true; })
           .catch(function () { arm(); });
    } else {
      arm();
    }
  } catch (e) { /* audio unsupported — site works fine without it */ }
})();
