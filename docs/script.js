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
