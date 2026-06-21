# SafeGuard Android — Reference Snapshot (for the Flutter rebuild)

This is a **read-only reference copy** of the shipped Kotlin/Jetpack Compose SafeGuard app.
It exists so you can see how the native parental-control features actually work while you
rebuild the app **from scratch in Flutter** against the existing FastAPI backend. It is *not*
a project to continue developing — treat it as documentation you can compile and step through.

## What this is / isn't

- **Is:** the production (`prod-snapshot`) Android source, resources, Gradle config, and the
  on-device ML models.
- **Isn't:** no git history, no backend, no internal planning docs, and **no secrets** —
  the Firebase config (`google-services.json`), signing keystore, and `local.properties`
  were deliberately removed.

## To make it build (you supply these)

1. **`local.properties`** at the repo root with your SDK path: `sdk.dir=/path/to/Android/Sdk`
2. **`app/google-services.json`** — your *own* Firebase project's config (the original was stripped).
   FCM is wired but optional; the app runs without push.
3. **JDK 21** is required (Gradle 8.13 / AGP 8.5.2 reject newer JDKs):
   `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`
4. Build: `./gradlew assembleDebug`. (Release signing config is not included — debug is enough
   to read/run the reference.)

## How to read it — the parts that matter for the port

The app is **one app, two roles**: PARENT (a thin REST client) and CHILD (the native OS
enforcement agent). The backend is just the policy store + sync hub. Almost all the hard,
port-worthy logic is in the **child enforcement modules**:

| Feature | Where to look | Notes for Flutter |
|---|---|---|
| VPN DNS content filter | `ContentFilterVpnService.kt` (~1900 LOC) | The big one. Local DNS sinkhole. iOS needs a NetworkExtension — scope carefully. |
| Text monitoring | `AccessibilityService` + `TextPatternMatcher` | Reads on-screen text, matches word lists + ML. Android-only API. |
| Screen-time + lock | `MonitoringService`, `LockScreenActivity`, receivers | Enforcement + remote lock (driven by `screen-time-rules`, not FCM). |
| Image (NSFW) monitoring | `MediaFileObserver` + `ImageBlurManager` | Watches Downloads/Screenshots, scores saved images, blurs in place. |
| On-device ML | `ml/TFLiteImageClassifier.kt`, `ml/…text…` | Ports to `tflite_flutter`. **Read `app/src/main/assets/ML_MODELS_README.md` first** — it has the exact input shapes, normalization, and class order you must replicate. |

The platform-channel / bridge contracts you'll rebuild against, the full 56-endpoint API
reference, auth/refresh choreography, and offline-sync rules are in the **handover document**
(sent separately). Use the **Postman collection** (also sent) to hit the live test server while
you build — it auto-captures tokens; just run Login → Register device → everything else.

## Heads-up on scope

Active screen-capture / live-frame NSFW scanning was **deferred for app-store compliance** and
never built. NSFW detection only runs on *saved* images. Don't plan a Flutter feature around
live screen capture; the `screenshot_captured` alert type in the backend is dormant.
