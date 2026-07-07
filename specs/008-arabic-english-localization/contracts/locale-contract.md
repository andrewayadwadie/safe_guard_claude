# Contract: LocaleHelper & Localization Rules

**Feature**: 008-arabic-english-localization

## LocaleHelper API (util/LocaleHelper.kt — object, no DI)

| Function | Signature | Behavior |
|----------|-----------|----------|
| `getLanguage` | `(context: Context): String` | Returns `"en"` or `"ar"` per resolution rules in data-model.md. Synchronous; safe pre-Hilt. |
| `setLanguage` | `(context: Context, lang: String)` | Persists to SharedPreferences; `require(lang in setOf("en","ar"))`; API 33+: mirrors to `LocaleManager.applicationLocales`. Does NOT recreate — caller's job. |
| `wrap` | `(base: Context): Context` | `createConfigurationContext` with mapped locale (`en` / `ar-u-nu-latn`) + `setLayoutDirection`. Called from `MainActivity.attachBaseContext`. |
| `localizedContext` | `(context: Context): Context` | Same wrap for background consumers (workers, services, notification builders). |
| `isRtl` | `(context: Context): Boolean` | Convenience: current language == `"ar"`. |

## Behavioral contracts

1. **Switch flow (SettingsScreen)**: dialog select → `viewModel.setLanguage(lang)` → persist + sync → screen callback runs `activity.recreate()` → visible screen re-renders in new language ≤ 2 s (SC-002).
2. **Every Compose screen**: user-facing text only via `stringResource`. Lint rule of thumb enforced by review: no string literal inside `Text(...)`, `contentDescription = ...`, `label/placeholder/title` params except `translatable=false` technical values.
3. **Workers/Services**: strings only via `localizedContext(...).getString(...)`. Applies to notification title/body, channel names/descriptions, and alert title/message sent to `POST /alerts`.
4. **Directional icons**: apply `Modifier.mirrorInRtl()` (designsystem) to ArrowBack/forward/chevron/send icons only. `HarisLogo`, shields, warnings: never mirrored.
5. **Digits**: all user-visible numbers render Western (0-9) in both languages — guaranteed by `ar-u-nu-latn`; manually constructed formatters (e.g., `SimpleDateFormat`) must use the wrapped context's locale, or `Locale.US` digits explicitly.
6. **LegalDocScreen**: append `?lang=<current>` to asset URL (best-effort, R9).

## Parity gate (tools/check_strings_parity.py)

- Input: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ar/strings.xml`
- FAIL (exit 1) if: key present in one file only (excluding `translatable="false"`), or format-arg count/type mismatch per key, or empty Arabic value.
- Run manually and in quickstart validation; zero mismatches required (SC-006).
