# Tasks: Arabic & English Localization with RTL Support

**Input**: Design documents from `/specs/008-arabic-english-localization/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/locale-contract.md, quickstart.md

**Organization**: Tasks grouped by user story. US1 = language toggle + RTL (P1), US2 = full string sweep (P2), US3 = background/notification localization (P3).

**Conventions**: All paths relative to repo root. `strings EN+AR` means adding the referenced keys to BOTH `app/src/main/res/values/strings.xml` and `app/src/main/res/values-ar/strings.xml` in the same task (parity always maintained). Key naming per data-model.md: `feature_element_purpose`, shared `common_*`.

## Phase 1: Setup

- [X] T001 Create `app/src/main/java/com/safeguard/parentalcontrol/util/LocaleHelper.kt` — object with `getLanguage`, `setLanguage` (validates `en|ar`, persists SharedPreferences key `app_language`, API 33+ mirrors via `LocaleManager.applicationLocales`), `wrap(base)`, `localizedContext(context)` (both `createConfigurationContext` with `en` / `ar-u-nu-latn` + `setLayoutDirection`), `isRtl`. Contract: specs/008-arabic-english-localization/contracts/locale-contract.md
- [X] T002 [P] Create `app/src/main/java/com/safeguard/parentalcontrol/presentation/designsystem/MirrorInRtl.kt` — `Modifier.mirrorInRtl()` applying `graphicsLayer(scaleX = -1f)` when `LocalLayoutDirection.current == LayoutDirection.Rtl`
- [X] T003 [P] Create `tools/check_strings_parity.py` — compare key sets + format-arg counts of `values/strings.xml` vs `values-ar/strings.xml`, skip `translatable="false"`, fail on empty Arabic values, exit 1 on mismatch

## Phase 2: Foundational (blocking all user stories)

- [X] T004 Modify `app/src/main/java/com/safeguard/parentalcontrol/presentation/MainActivity.kt` — override `attachBaseContext(newBase)` → `super.attachBaseContext(LocaleHelper.wrap(newBase))`
- [X] T005 [P] Modify `app/src/main/java/com/safeguard/parentalcontrol/presentation/lockscreen/LockScreenActivity.kt` — same `attachBaseContext` override (second activity must localize too)
- [X] T006 Seed `app/src/main/res/values/strings.xml` + create `app/src/main/res/values-ar/strings.xml` — carry over existing ~25 EN keys with Arabic translations, add `common_*` set (`common_cancel`, `common_back`, `common_save`, `common_ok`, `common_retry`, `common_loading`), mark `app_name`/URLs `translatable="false"` as appropriate

**Checkpoint**: app builds, launches unchanged in English; Arabic device locale resolves `values-ar` for seeded keys.

## Phase 3: User Story 1 — Switch App Language from Settings (P1) 🎯 MVP

**Goal**: Language row + radio dialog in Settings; pick العربية → whole app re-renders Arabic RTL ≤ 2 s; persists.

**Independent Test**: quickstart.md §1 — toggle both directions on Settings screen, force-close + reboot persistence, RTL layout flip.

- [X] T007 [US1] Add language state to `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsViewModel.kt` — `currentLanguage` in UiState (`_uiState.update { it.copy(...) }` pattern), `setLanguage(context, lang)` delegating to `LocaleHelper.setLanguage`
- [X] T008 [US1] Add Language row + selection dialog to `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsScreen.kt` — row shows "English"/"العربية" per current value; tap opens Material3 AlertDialog with 2 radio options; on select: `viewModel.setLanguage(...)` then `(context as Activity).recreate()`
- [X] T009 [US1] strings EN+AR for language UI in `app/src/main/res/values/strings.xml` + `app/src/main/res/values-ar/strings.xml` — `settings_language_title`, `settings_language_english`, `settings_language_arabic`, dialog title/confirm keys ("English"/"العربية" values are `translatable="false"`, each language named in itself)
- [X] T010 [US1] Convert remaining hardcoded strings of `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsScreen.kt` to `stringResource` (settings_* keys, strings EN+AR) so the MVP screen demos fully in both languages; apply `Modifier.mirrorInRtl()` to its back arrow

**Checkpoint**: US1 fully demoable — Settings bilingual, RTL flips, choice survives restart/reboot.

## Phase 4: User Story 2 — Every Screen Fully Translated (P2)

**Goal**: Zero hardcoded user-facing literals anywhere in presentation layer; both catalogs complete; RTL icon mirroring applied.

**Independent Test**: quickstart.md §2 full-screen sweep in both languages; `python tools/check_strings_parity.py` exit 0.

Each task: replace literals with `stringResource(R.string.…)` (ViewModel error messages → `R.string` ids resolved in screen, or move message building into screen layer per constitution), add keys EN+AR, apply `mirrorInRtl()` to directional icons in touched files.

- [X] T011 [P] [US2] Auth cluster: `presentation/auth/LoginScreen.kt`, `presentation/auth/RegisterScreen.kt`, `presentation/auth/AuthViewModel.kt`, `presentation/auth/components/GoogleSignInButton.kt`, `presentation/auth/components/RoleSelectionDialog.kt` (keys `auth_*`)
- [X] T012 [P] [US2] Setup cluster: `presentation/setup/ConsentScreen.kt`, `presentation/setup/PermissionsSetupScreen.kt` + their ViewModels (keys `consent_*`, `permissions_*`)
- [X] T013 [P] [US2] Dashboard: `presentation/dashboard/DashboardScreen.kt` + `DashboardViewModel.kt` (keys `dashboard_*`)
- [X] T014 [P] [US2] Alerts: `presentation/alerts/AlertsScreen.kt` + `AlertsViewModel.kt` (keys `alerts_*`)
- [X] T015 [P] [US2] Children + Devices: `presentation/children/ChildrenScreen.kt`, `presentation/devices/DevicesScreen.kt` + ViewModels (keys `children_*`, `devices_*`)
- [X] T016 [P] [US2] Device setup + Link parent: `presentation/devicesetup/DeviceSetupScreen.kt`, `presentation/linkparent/LinkParentScreen.kt` + ViewModels (keys `devicesetup_*`, `linkparent_*`; expiry `SimpleDateFormat` must use wrapped-context locale per contract rule 5)
- [X] T017 [P] [US2] Screen time + Lock screen: `presentation/screentimelimits/ScreenTimeLimitsScreen.kt`, `presentation/lockscreen/LockScreenActivity.kt`, `presentation/lockscreen/LockOverlayController.kt` + ViewModel (keys `screentime_*`, `lock_*`)
- [X] T018 [P] [US2] Filtering cluster: `presentation/blacklist/BlacklistScreen.kt`, `presentation/wordlist/WordListScreen.kt`, `presentation/settings/TextMonitoringSettingsScreen.kt` + ViewModels (keys `blacklist_*`, `wordlist_*`, `textmon_*`)
- [X] T019 [P] [US2] Review cluster: `presentation/imagereview/ImageReviewScreen.kt`, `presentation/textreview/TextReviewScreen.kt` + ViewModels (keys `imagereview_*`, `textreview_*`)
- [X] T020 [P] [US2] Shared components: all files in `presentation/components/` (Buttons, Cards, ChildFriendlyComponents, CircularProgressDisplay, EmptyStates, EnhancedComponents, LoadingStates, ParentPinDialog, ParentPinGate, ParentPinViewModel, PullToRefresh) (keys `common_*`, `pin_*`, `empty_*`)
- [X] T021 [P] [US2] Remaining screens: `presentation/splash/SplashScreen.kt`, `presentation/settings/ChangePasswordScreen.kt` + ViewModel, `presentation/settings/LegalDocScreen.kt` (titles passed from `presentation/navigation/NavGraph.kt` → move to string resources), designsystem `contentDescription`s (`HarisLogo.kt` etc.) (keys `splash_*`, `changepw_*`, `legal_*`)
- [X] T022 [US2] `presentation/settings/LegalDocScreen.kt` — append `?lang=<LocaleHelper.getLanguage(context)>` to asset URL (research R9)
- [X] T023 [US2] Run `python tools/check_strings_parity.py` + grep sweep for leftover literals in `Text(`/`contentDescription`/`label`/`title` params across `app/src/main/java/com/safeguard/parentalcontrol/presentation/`; fix all findings

**Checkpoint**: full bilingual walkthrough passes (quickstart §2); parity exit 0.

## Phase 5: User Story 3 — Notifications & Background Alerts Localized (P3)

**Goal**: All text produced off-screen (notifications, channels, backend alert title/message) uses selected language via `LocaleHelper.localizedContext`.

**Independent Test**: quickstart.md §3 — trigger monitoring + tamper notifications in each language.

- [X] T024 [P] [US3] `worker/TamperAlertWorker.kt` — move all Triple title/message literals to string resources (keys `tamper_*`, format args for device name), fetch via `LocaleHelper.localizedContext(applicationContext).getString(...)`; strings EN+AR
- [X] T025 [P] [US3] `worker/ProtectionMonitorWorker.kt` — same treatment (keys `protection_*`); strings EN+AR
- [X] T026 [P] [US3] `service/MonitoringService.kt` — foreground notification title/body + channel name/description localized (keys `notif_monitoring_*`); strings EN+AR
- [X] T027 [P] [US3] Remaining background text: `service/ContentFilterVpnService.kt`, `service/SafeGuardFirebaseMessagingService.kt`, `worker/ImageScanWorker.kt`, `worker/SyncWorker.kt`, `receiver/TamperDetectionReceiver.kt` — localize any notification/user-visible strings found (keys `notif_*`); strings EN+AR

**Checkpoint**: notifications bilingual; alert messages to backend built from localized resources.

## Phase 6: Polish & Cross-Cutting

- [X] T028 [P] Unit tests `app/src/test/java/com/safeguard/parentalcontrol/util/LocaleHelperTest.kt` — default resolution (no pref + ar system → ar; no pref + other → en), tag mapping (`ar` → `ar-u-nu-latn`), setLanguage validation
- [X] T029 Final gates: `python tools/check_strings_parity.py` exit 0, `./gradlew assembleDebug` clean, `./gradlew testDebugUnitTest --tests "*LocaleHelper*"` green
- [ ] T030 Manual device validation per `specs/008-arabic-english-localization/quickstart.md` §§1–4 (toggle/RTL/persistence, full sweep both languages, notifications, edge checks incl. Arabic-system first launch)

## Dependencies

```text
Phase 1 (T001–T003) → Phase 2 (T004–T006) → US1 (T007–T010) → US2 (T011–T023) → US3 (T024–T027) → Polish (T028–T030)
```

- T001 blocks T004/T005/T007; T006 blocks all strings-adding tasks.
- US2 tasks T011–T021 all [P] (different files) BUT all append to the two shared strings.xml files — run truly in parallel only with merge discipline, otherwise sequential is safest. T023 last in US2.
- US3 independent of US2 content-wise (could start after Phase 2), placed after per priority.

## Parallel Example (US2)

```text
# Different-file conversions in parallel (strings.xml appends coordinated):
T011 auth | T013 dashboard | T014 alerts | T019 review cluster
```

## Implementation Strategy

**MVP = Phase 1 + 2 + US1** (T001–T010): working bilingual toggle with RTL on a fully-translated Settings screen. Ship/demo, then US2 sweep incrementally (cluster by cluster, each independently verifiable), then US3 background, then polish gates.
