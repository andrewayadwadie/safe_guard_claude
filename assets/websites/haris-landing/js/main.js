/* ============================================================================
   Haris landing — vanilla JS. No frameworks, no external requests.
   i18n toggle + persistence · scroll reveals · count-up · back-to-top.
   Everything degrades gracefully: content is fully readable with JS disabled.
   ============================================================================ */
(function () {
  "use strict";

  var root = document.documentElement;
  var LANG_KEY = "haris-lang";
  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---------------------------------------------------------------- i18n ---- */
  var I18N = {
    en: {
      skip: "Skip to content",
      nav_features: "Features", nav_how: "How it works", nav_privacy: "Privacy",
      nav_faq: "FAQ", nav_cta: "Get the app",
      hero_eyebrow: "On-device AI · Arabic-first",
      hero_tagline: "Peace of mind for every family",
      hero_sub: "A parental-control app that helps you guide screen time, filter the web, and stay alerted — while your child's data stays private, processed right on their device.",
      hero_cta_primary: "See what Haris does",
      hero_cta_store: "Google Play",
      proof_ondevice: "On-device", proof_features: "Core features", proof_modes: "Modes: parent & child",
      mock_hi: "Today", mock_screen: "screen time", mock_alert: "Word-list match blocked",
      trust_ondevice: "On-device AI", trust_arabic: "Arabic-first",
      trust_privacy: "Privacy-respecting", trust_tamper: "Tamper-resistant",
      features_kicker: "Features", features_title: "One app, everything a parent needs",
      features_lead: "Built for real families — practical controls, honest privacy, no clutter.",
      f1_t: "Parent & child roles", f1_d: "One app, two modes. Secure device pairing and a guided setup for each side.",
      f2_t: "Screen-time limits", f2_d: "Daily and per-app limits, bedtime windows, and instant remote lock.",
      f3_t: "Content filtering", f3_d: "On-device DNS/VPN web filtering. Block social platforms with a tap.",
      f4_t: "Text monitoring", f4_d: "On-device ML scans on-screen text against your custom word lists.",
      f5_t: "Image safety", f5_d: "Inappropriate images are detected and blurred locally on the device.",
      f6_t: "Smart alerts", f6_d: "Real-time alerts to the parent, ranked by severity so you act on what matters.",
      f7_t: "Custom word lists", f7_d: "Define your own watch terms — your family, your rules.",
      f8_t: "Tamper protection", f8_d: "Detects if the app is disabled, force-stopped, or filtering is switched off.",
      how_kicker: "How it works", how_title: "Set up in three steps",
      s1_t: "Create an account & pick a role", s1_d: "Sign up in minutes and choose parent or child mode on each device.",
      s2_t: "Pair the child device", s2_d: "A secure code links the two devices. Guided permissions, no guesswork.",
      s3_t: "Set rules & get alerts", s3_d: "Choose limits and word lists, then relax — Haris keeps you posted.",
      privacy_kicker: "Privacy & safety",
      privacy_title: "Your child's data stays on your child's device",
      privacy_p1: "Haris runs its machine-learning checks — text and image safety — locally. The content being analysed does not leave the device to be scanned in the cloud.",
      privacy_p2: "We build for families first: clear controls, honest defaults, and transparency about what the app does and why.",
      privacy_l1: "On-device ML — analysis happens locally",
      privacy_l2: "Transparent, family-first design",
      privacy_l3: "Full Privacy Policy & Data Safety details available",
      privacy_link_pp: "Read the Privacy Policy", privacy_link_terms: "Terms & Conditions",
      privacy_badge: "Processed on device",
      show_kicker: "Inside the app", show_title: "Designed to be glanceable",
      show_1: "Dashboard", show_2: "Screen time", show_3: "Alerts",
      faq_kicker: "FAQ", faq_title: "Questions parents ask",
      q1: "Is my child's data private?",
      a1: "Yes. The text and image safety checks run with on-device machine learning, so the content being analysed stays on the device rather than being sent to a server for scanning.",
      q2: "Does it need root access?",
      a2: "No. Haris works through standard Android permissions and does not require rooting the device.",
      q3: "Which devices are supported?",
      a3: "Haris is an Android app. Support details will be listed on the Google Play listing at launch.",
      q4: "Is it free?",
      a4: "Pricing will be confirmed on the store listing. The app is still preparing for launch.",
      q5: "How do I remove it?",
      a5: "A parent can unpair and uninstall from the parent device. Tamper protection means the child can't quietly disable it without the parent being alerted.",
      q6: "Can I choose what gets flagged?",
      a6: "Yes. Custom word lists let you define your own watch terms, and alerts are ranked by severity so you focus on what matters.",
      cta_title: "Ready when you are",
      cta_sub: "Haris is preparing for launch on Google Play. Guide your family with confidence.",
      cta_btn: "Google Play",
      soon: "Coming soon", store_get: "Get it on",
      footer_blurb: "Peace of mind for every family. On-device AI parental controls, Arabic-first.",
      footer_privacy: "Privacy Policy", footer_terms: "Terms",
      footer_delete: "Account & Data Deletion", footer_support: "Support",
      footer_rights: "All rights reserved."
    },
    ar: {
      skip: "تخطَّ إلى المحتوى",
      nav_features: "المميزات", nav_how: "طريقة العمل", nav_privacy: "الخصوصية",
      nav_faq: "الأسئلة الشائعة", nav_cta: "حمّل التطبيق",
      hero_eyebrow: "ذكاء اصطناعي على الجهاز · بالعربية أولاً",
      hero_tagline: "راحة بال لكل أسرة",
      hero_sub: "تطبيق رقابة أبوية يساعدك على تنظيم وقت الشاشة وتصفية الإنترنت والبقاء على اطلاع — مع بقاء بيانات طفلك خاصة تُعالَج على جهازه مباشرة.",
      hero_cta_primary: "اكتشف ما يقدّمه حارس",
      hero_cta_store: "جوجل بلاي",
      proof_ondevice: "على الجهاز", proof_features: "مميزات أساسية", proof_modes: "وضعان: الأب والطفل",
      mock_hi: "اليوم", mock_screen: "وقت الشاشة", mock_alert: "حظر تطابق قائمة الكلمات",
      trust_ondevice: "ذكاء على الجهاز", trust_arabic: "بالعربية أولاً",
      trust_privacy: "يحترم الخصوصية", trust_tamper: "مقاوم للتلاعب",
      features_kicker: "المميزات", features_title: "تطبيق واحد يجمع كل ما يحتاجه الأب",
      features_lead: "مصمَّم للأسر الحقيقية — أدوات عملية، خصوصية صادقة، بلا تعقيد.",
      f1_t: "وضعا الأب والطفل", f1_d: "تطبيق واحد بوضعين. اقتران آمن للأجهزة وإعداد موجَّه لكل طرف.",
      f2_t: "حدود وقت الشاشة", f2_d: "حدود يومية ولكل تطبيق، أوقات نوم، وقفل عن بُعد فوري.",
      f3_t: "تصفية المحتوى", f3_d: "تصفية ويب عبر DNS/VPN على الجهاز. احظر منصات التواصل بلمسة.",
      f4_t: "مراقبة النصوص", f4_d: "ذكاء على الجهاز يفحص النص الظاهر مقابل قوائم كلماتك المخصصة.",
      f5_t: "أمان الصور", f5_d: "يُكتشف المحتوى غير اللائق وتُطمَس الصور محلياً على الجهاز.",
      f6_t: "تنبيهات ذكية", f6_d: "تنبيهات فورية للأب مرتبة حسب الخطورة لتتصرف فيما يهم.",
      f7_t: "قوائم كلمات مخصصة", f7_d: "حدّد مصطلحات المراقبة الخاصة بك — أسرتك، قواعدك.",
      f8_t: "الحماية من التلاعب", f8_d: "يكتشف تعطيل التطبيق أو إيقافه القسري أو إطفاء التصفية.",
      how_kicker: "طريقة العمل", how_title: "الإعداد في ثلاث خطوات",
      s1_t: "أنشئ حساباً واختر الدور", s1_d: "سجّل في دقائق واختر وضع الأب أو الطفل على كل جهاز.",
      s2_t: "اقرن جهاز الطفل", s2_d: "رمز آمن يربط الجهازين. أذونات موجَّهة بلا تخمين.",
      s3_t: "اضبط القواعد واستقبل التنبيهات", s3_d: "اختر الحدود وقوائم الكلمات ثم اطمئن — حارس يبقيك على اطلاع.",
      privacy_kicker: "الخصوصية والأمان",
      privacy_title: "بيانات طفلك تبقى على جهاز طفلك",
      privacy_p1: "يشغّل حارس فحوصات التعلّم الآلي — أمان النصوص والصور — محلياً. المحتوى الذي يُحلَّل لا يغادر الجهاز ليُفحَص في السحابة.",
      privacy_p2: "نبني للأسرة أولاً: أدوات واضحة، إعدادات صادقة، وشفافية حول ما يفعله التطبيق ولماذا.",
      privacy_l1: "تعلّم آلي على الجهاز — التحليل يتم محلياً",
      privacy_l2: "تصميم شفاف يضع الأسرة أولاً",
      privacy_l3: "سياسة الخصوصية وتفاصيل أمان البيانات متاحة بالكامل",
      privacy_link_pp: "اقرأ سياسة الخصوصية", privacy_link_terms: "الشروط والأحكام",
      privacy_badge: "تُعالَج على الجهاز",
      show_kicker: "داخل التطبيق", show_title: "مصمَّم لتراه بلمحة",
      show_1: "اللوحة", show_2: "وقت الشاشة", show_3: "التنبيهات",
      faq_kicker: "الأسئلة الشائعة", faq_title: "أسئلة يطرحها الآباء",
      q1: "هل بيانات طفلي خاصة؟",
      a1: "نعم. فحوصات أمان النصوص والصور تعمل بتعلّم آلي على الجهاز، فيبقى المحتوى الذي يُحلَّل على الجهاز بدلاً من إرساله إلى خادم للفحص.",
      q2: "هل يحتاج صلاحيات روت؟",
      a2: "لا. يعمل حارس عبر أذونات أندرويد القياسية ولا يتطلب عمل روت للجهاز.",
      q3: "ما الأجهزة المدعومة؟",
      a3: "حارس تطبيق أندرويد. ستُدرج تفاصيل الدعم في صفحة جوجل بلاي عند الإطلاق.",
      q4: "هل هو مجاني؟",
      a4: "سيُؤكَّد السعر في صفحة المتجر. التطبيق ما زال يستعد للإطلاق.",
      q5: "كيف أزيله؟",
      a5: "يمكن للأب فك الاقتران وإلغاء التثبيت من جهاز الأب. الحماية من التلاعب تعني أن الطفل لا يستطيع تعطيله بهدوء دون تنبيه الأب.",
      q6: "هل أختار ما يُرصَد؟",
      a6: "نعم. قوائم الكلمات المخصصة تتيح لك تحديد مصطلحات المراقبة، والتنبيهات مرتبة حسب الخطورة لتركّز على ما يهم.",
      cta_title: "جاهز حين تكون مستعداً",
      cta_sub: "يستعد حارس للإطلاق على جوجل بلاي. وجّه أسرتك بثقة.",
      cta_btn: "جوجل بلاي",
      soon: "قريباً", store_get: "احصل عليه من",
      footer_blurb: "راحة بال لكل أسرة. رقابة أبوية بذكاء على الجهاز، بالعربية أولاً.",
      footer_privacy: "سياسة الخصوصية", footer_terms: "الشروط",
      footer_delete: "حذف الحساب والبيانات", footer_support: "الدعم",
      footer_rights: "جميع الحقوق محفوظة."
    }
  };

  function applyLang(lang) {
    var dict = I18N[lang] || I18N.en;
    root.lang = lang;
    root.dir = lang === "ar" ? "rtl" : "ltr";
    var nodes = document.querySelectorAll("[data-i18n]");
    for (var i = 0; i < nodes.length; i++) {
      var key = nodes[i].getAttribute("data-i18n");
      if (dict[key] != null) nodes[i].textContent = dict[key];
    }
  }

  var saved = "en";
  try { if (localStorage.getItem(LANG_KEY) === "ar") saved = "ar"; } catch (e) {}
  applyLang(saved);
  var current = saved;

  var toggle = document.getElementById("lang-toggle");
  if (toggle) {
    toggle.addEventListener("click", function () {
      current = current === "ar" ? "en" : "ar";
      applyLang(current);
      try { localStorage.setItem(LANG_KEY, current); } catch (e) {}
    });
  }

  /* -------------------------------------------------------- scroll reveal --- */
  var reveals = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && !reduceMotion) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add("in-view"); io.unobserve(en.target); }
      });
    }, { threshold: 0.14, rootMargin: "0px 0px -8% 0px" });
    reveals.forEach(function (el) { io.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add("in-view"); });
  }

  /* ------------------------------------------------------------- count-up --- */
  var counters = document.querySelectorAll("[data-count]");
  function countUp(el) {
    var target = parseFloat(el.getAttribute("data-count"));
    var suffix = el.hasAttribute("data-i18n-suffix") ? el.textContent.replace(/[0-9.]/g, "") : "";
    if (reduceMotion) { el.textContent = target + suffix; return; }
    var start = null, dur = 1400;
    function frame(ts) {
      if (start === null) start = ts;
      var p = Math.min((ts - start) / dur, 1);
      var eased = 1 - Math.pow(1 - p, 3);              // ease-out-cubic
      el.textContent = Math.round(target * eased) + suffix;
      if (p < 1) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }
  if ("IntersectionObserver" in window) {
    var cio = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { countUp(en.target); cio.unobserve(en.target); }
      });
    }, { threshold: 0.6 });
    counters.forEach(function (el) { cio.observe(el); });
  }

  /* --------------------------------------------------- accordion (single) --- */
  var faqs = document.querySelectorAll(".faq");
  faqs.forEach(function (d) {
    d.addEventListener("toggle", function () {
      if (d.open) faqs.forEach(function (o) { if (o !== d) o.open = false; });
    });
  });

  /* --------------------------------------------------------- back to top --- */
  var toTop = document.getElementById("to-top");
  if (toTop) {
    var onScroll = function () {
      if (window.pageYOffset > window.innerHeight) toTop.removeAttribute("hidden");
      else toTop.setAttribute("hidden", "");
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    toTop.addEventListener("click", function () {
      window.scrollTo({ top: 0, behavior: reduceMotion ? "auto" : "smooth" });
    });
  }

  /* ---------------------------------------------------------------- year --- */
  var yr = document.getElementById("year");
  if (yr) yr.textContent = new Date().getFullYear();
})();
