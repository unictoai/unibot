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
      setTimeout(function () {
        if (window.__heroWordReveal) window.__heroWordReveal(false);
      }, reduced ? 0 : 1300);
      heroRevealed();
      try {
        var p = intro.play();
        if (p && p.catch) p.catch(function () {});
      } catch (e) {}
      overlay.classList.add("leaving");
      setTimeout(function () {
        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
      }, 900);
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
        if (window.__heroWordReveal) window.__heroWordReveal(true);
        overlay.classList.add("leaving");
        setTimeout(function () {
          if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }, 900);
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

/* ── Next-level motion: header parallax + card spotlight ──
   Parallax uses the CSS `translate` property (not `transform`) so it
   composes with the scroll-reveal transforms instead of fighting them.
   Everything here is transform/opacity-only and fully reduced-motion gated. */
(function () {
"use strict";
  var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduced) return;

  // ── Scroll-driven parallax on section headers + ledes ──
  var parallaxEls = document.querySelectorAll(".section h2, .section .lede");
  var ticking = false;
  function parallax() {
    ticking = false;
    var vh = window.innerHeight;
    parallaxEls.forEach(function (el) {
      var r = el.getBoundingClientRect();
      if (r.bottom < -80 || r.top > vh + 80) {
        if (el.style.translate) el.style.translate = "";
        return; // off-screen: park it
      }
      // -1 (top edge) … +1 (bottom edge) → gentle ±18px drift
      var p = (r.top + r.height / 2 - vh / 2) / (vh / 2);
      var y = Math.max(-1, Math.min(1, p)) * -18;
      el.style.translate = "0 " + y.toFixed(1) + "px";
    });
  }
  function requestParallax() {
    if (!ticking) { ticking = true; requestAnimationFrame(parallax); }
  }
  window.addEventListener("scroll", requestParallax, { passive: true });
  window.addEventListener("resize", requestParallax);
  requestParallax();

  // ── Cursor spotlight follows the pointer across superpower cards ──
  if (window.matchMedia("(pointer: fine)").matches) {
    document.querySelectorAll(".power").forEach(function (card) {
      var raf = null;
      card.addEventListener("mousemove", function (ev) {
        if (raf) return;
        raf = requestAnimationFrame(function () {
          raf = null;
          var r = card.getBoundingClientRect();
          card.style.setProperty("--mx", (ev.clientX - r.left).toFixed(0) + "px");
          card.style.setProperty("--my", (ev.clientY - r.top).toFixed(0) + "px");
        });
      });
    });
  }
})();

/* ── Active Theory motion language: masked word reveals, lerped physics,
   cursor-as-force glow, velocity-reactive nav, preloader counter ── */
(function () {
"use strict";
  var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var finePointer = window.matchMedia("(pointer: fine)").matches;

  // ── Split an element's text into masked words (their split-text pattern) ──
  function splitWords(el) {
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    var nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(function (node) {
      if (!node.textContent.trim()) return;
      var frag = document.createDocumentFragment();
      node.textContent.split(/(\s+)/).forEach(function (part) {
        if (!part) return;
        if (/^\s+$/.test(part)) { frag.appendChild(document.createTextNode(" ")); return; }
        var mask = document.createElement("span"); mask.className = "sl-mask";
        var w = document.createElement("span"); w.className = "sl-word";
        w.textContent = part;
        mask.appendChild(w); frag.appendChild(mask);
      });
      node.parentNode.replaceChild(frag, node);
    });
    return el.querySelectorAll(".sl-word");
  }
  function revealWords(el, words, instant) {
    if (!el || el.classList.contains("split-in")) return;
    words.forEach(function (w, i) {
      w.style.transitionDelay = instant ? "0ms" : (i * 55) + "ms";
    });
    // next frame so delays register before the class flips
    requestAnimationFrame(function () {
      requestAnimationFrame(function () { el.classList.add("split-in"); });
    });
  }

  // ── Hero headline: driven by the welcome-gate intro timeline ──
  var heroH1 = document.querySelector(".hero-copy h1");
  var heroWords = heroH1 ? splitWords(heroH1) : [];
  window.__heroWordReveal = function (instant) {
    revealWords(heroH1, heroWords, instant);
  };
  // Safety nets: reduced motion, missing overlay, or gate that never fires
  if (reduced || !document.getElementById("welcome")) {
    window.__heroWordReveal(true);
  } else {
    setTimeout(function () { window.__heroWordReveal(true); }, 6000);
  }

  // ── Section headlines: masked word reveal on scroll ──
  document.querySelectorAll("section h2").forEach(function (h2) {
    var words = splitWords(h2);
    h2.classList.remove("reveal"); // the word masks own this entrance now
    words.forEach(function (w, i) { w.style.transitionDelay = (i * 50) + "ms"; });
    if (reduced || !("IntersectionObserver" in window)) {
      h2.classList.add("split-in");
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) {
          revealWords(h2, words, false);
          io.disconnect();
        }
      });
    }, { threshold: 0.4 });
    io.observe(h2);
  });

  // ── Preloader counter (theater while assets load; OK is always clickable) ──
  var loadNum = document.getElementById("loadNum");
  var loadBar = document.getElementById("loadBar");
  function paint(v) {
    if (!loadNum) return;
    loadNum.textContent = String(Math.floor(v)).padStart(2, "0");
    if (loadBar) loadBar.style.width = v + "%";
  }
  if (loadNum && !reduced) {
    var n = 0, finished = false;
    var iv = setInterval(function () {
      n = Math.min(n + 4 + Math.random() * 10, 94);
      paint(n);
      if (n >= 94) clearInterval(iv);
    }, 90);
    var finish = function () {
      if (finished) return; finished = true;
      clearInterval(iv); paint(100);
    };
    window.addEventListener("load", finish);
    setTimeout(finish, 3000);
  } else {
    paint(100);
  }

  if (reduced) return; // lerped pointer physics below are motion-only

  // ── One lerp engine: pointer + scroll velocity drive everything ──
  // (their rule: lerp ~0.08, nothing snaps)
  var glow = document.getElementById("cursorGlow");
  var navInner = document.querySelector(".nav-inner");
  var hero = document.querySelector(".hero");
  var heroPhone = document.querySelector(".hero-phone .phone");
  var blobs = document.querySelectorAll(".hero .blob");
  var tx = window.innerWidth / 2, ty = window.innerHeight / 2;
  var px = tx, py = ty;
  var lastSY = window.scrollY, vel = 0;
  var glowOn = false;

  if (finePointer) {
    window.addEventListener("mousemove", function (e) {
      tx = e.clientX; ty = e.clientY;
      if (!glowOn && glow) { glow.classList.add("on"); glowOn = true; }
    }, { passive: true });
    // glow swells over interactive elements
    document.addEventListener("mouseover", function (e) {
      if (glow) glow.classList.toggle("big",
        !!e.target.closest("a, button, .tile, .card, .why-card"));
    });
  }

  (function loop() {
    px += (tx - px) * 0.08;
    py += (ty - py) * 0.08;
    var sy = window.scrollY;
    vel += ((sy - lastSY) - vel) * 0.1;
    lastSY = sy;

    // cursor-as-force glow
    if (glow && glowOn) {
      glow.style.transform = "translate3d(" + px.toFixed(1) + "px," + py.toFixed(1) + "px,0)";
    }
    // velocity-reactive nav (their pillbox idea, kept subtle)
    if (navInner) {
      var sk = Math.max(-2.5, Math.min(2.5, vel * 0.05));
      navInner.style.transform = "skewY(" + (-sk).toFixed(2) + "deg)";
    }
    // hero pointer physics — parked while the intro movie plays
    var introOn = document.body.classList.contains("intro") ||
      !!document.getElementById("welcome");
    if (finePointer && hero && !introOn && (blobs.length || heroPhone)) {
      var r = hero.getBoundingClientRect();
      if (r.bottom > 0 && r.top < window.innerHeight) {
        var nx = (px - (r.left + r.width / 2)) / r.width;
        var ny = (py - (r.top + r.height / 2)) / r.height;
        if (heroPhone) {
          heroPhone.style.transform =
            "rotateY(" + (nx * 16).toFixed(2) + "deg) rotateX(" +
            (-ny * 14).toFixed(2) + "deg) translateZ(12px)";
        }
        blobs.forEach(function (b, i) {
          var d = (i + 1) * 30;
          b.style.translate = (-nx * d).toFixed(1) + "px " + (-ny * d).toFixed(1) + "px";
        });
      }
    }
    requestAnimationFrame(loop);
  })();
})();
