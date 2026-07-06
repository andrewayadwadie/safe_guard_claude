# Splash Logo Draw-On Animation — Design

**Date:** 2026-06-28
**Feature:** Animate the centered Haris logo on app launch.

## Goal

Replace the static splash logo with a short (~0.8s) minimal fade animation: the
logo fades in, holds briefly, then fades out, after which the app navigates to
its real start screen.

> Updated 2026-06-28: simplified from the original "draw-on" line-art trace to a
> plain fade in/out per user request.

## Constraints / context

- Logo asset is `res/drawable/splash_logo.png` (raster, gradients). No SVG/vector
  source exists in the repo.
- The native Android system splash (`androidx.core:core-splashscreen`,
  `windowSplashScreenAnimatedIcon`) only animates an `AnimatedVectorDrawable`,
  which a PNG cannot supply. So rich animation must happen in a Compose screen
  shown immediately after the (brief) system splash.
- App start destination is currently chosen by `tokenManager.isLoggedIn()`
  (Dashboard vs Login) in `MainActivity.SafeGuardApp`.

## Flow

1. Native system splash (unchanged): `splash_background` + static `splash_logo`,
   shown only during process start.
2. New Compose `SplashScreen` = the nav graph's start destination. Runs the
   animation, then calls back to navigate to the real start route.
3. Runs only on Activity creation (cold launch / recreate). Warm resume does not
   replay it.

## Animation (~0.8s, "minimal")

A single `splash_logo.png` `Image` whose alpha is driven by one `Animatable`:

- `0.0–0.3s` — fade in (alpha 0→1).
- `0.3–0.5s` — hold at full opacity.
- `0.5–0.8s` — fade out (alpha 1→0), then `onFinished()`.

## Components

- `presentation/splash/SplashScreen.kt` (new): the animated composable. Inputs:
  `onFinished: () -> Unit`. Owns one alpha `Animatable` + a `LaunchedEffect`
  timeline. Background uses `colorScheme.background` (matches `splash_background`).
- `presentation/navigation/NavGraph.kt`: add `Screen.Splash` route; add its
  `composable` that renders `SplashScreen` and, on finish, navigates to
  `Dashboard` or `Login` based on `isLoggedIn`, with
  `popUpTo(Splash){ inclusive = true }`. `SafeGuardNavGraph` gains an
  `isLoggedIn: Boolean` param; `startDestination` becomes `Screen.Splash.route`.
- `MainActivity.SafeGuardApp`: pass `isLoggedIn` into the graph; start at Splash.

## Theming

Light and dark both supported via `MaterialTheme.colorScheme` (background +
primary), consistent with the day/night `splash_background` already in place.

## Testing

- Compose UI test: SplashScreen renders, and `onFinished` is invoked after the
  animation completes.
- Manual: run on emulator in light and dark mode, confirm trace → reveal →
  navigate to the correct start screen for logged-in and logged-out states.

## Out of scope

- Replaying on warm resume.
- Converting the PNG to a full gradient VectorDrawable.
- Changing the native system splash beyond what already exists.
