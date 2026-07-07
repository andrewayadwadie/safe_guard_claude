/* Haris legal sites — self-contained enhancement (no external requests).
   Language toggle (EN/AR) + direction switch + persistence, ToC active-state
   highlight, back-to-top. All content is readable without this script. */
(function () {
  "use strict";
  var KEY = "haris-legal-lang";
  var root = document.documentElement;

  // ---- Language toggle ------------------------------------------------------
  var toggle = document.getElementById("lang-toggle");
  var notice = document.getElementById("lang-notice");

  function labelFor(lang) { return lang === "ar" ? "English" : "العربية"; }

  function applyLang(lang) {
    root.lang = lang;
    root.dir = lang === "ar" ? "rtl" : "ltr";
    if (notice) notice.hidden = lang !== "ar";
    if (toggle) toggle.textContent = labelFor(lang);
    // Swap any chrome labels that carry both translations.
    var nodes = document.querySelectorAll("[data-en][data-ar]");
    for (var i = 0; i < nodes.length; i++) {
      var v = nodes[i].getAttribute("data-" + lang);
      if (v != null) {
        if (nodes[i].hasAttribute("aria-label")) nodes[i].setAttribute("aria-label", v);
        else nodes[i].textContent = v;
      }
    }
  }

  var current = "en";
  try { if (localStorage.getItem(KEY) === "ar") current = "ar"; } catch (e) {}
  applyLang(current);

  if (toggle) {
    toggle.addEventListener("click", function () {
      current = current === "ar" ? "en" : "ar";
      applyLang(current);
      try { localStorage.setItem(KEY, current); } catch (e) {}
    });
  }

  // ---- ToC active-section highlight ----------------------------------------
  var links = Array.prototype.slice.call(document.querySelectorAll("#toc a[href^='#']"));
  var sections = links
    .map(function (a) { return document.getElementById(a.getAttribute("href").slice(1)); })
    .filter(Boolean);

  if ("IntersectionObserver" in window && sections.length) {
    var byId = {};
    links.forEach(function (a) { byId[a.getAttribute("href").slice(1)] = a; });
    var current2 = null;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          if (current2) current2.removeAttribute("aria-current");
          current2 = byId[en.target.id];
          if (current2) current2.setAttribute("aria-current", "true");
        }
      });
    }, { rootMargin: "0px 0px -75% 0px", threshold: 0 });
    sections.forEach(function (s) { io.observe(s); });
  }

  // ---- Back to top ----------------------------------------------------------
  var btt = document.getElementById("back-to-top");
  if (btt) {
    var onScroll = function () {
      btt.hidden = window.pageYOffset < window.innerHeight * 2;
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    btt.addEventListener("click", function () {
      window.scrollTo({ top: 0, behavior: "smooth" });
    });
  }
})();
