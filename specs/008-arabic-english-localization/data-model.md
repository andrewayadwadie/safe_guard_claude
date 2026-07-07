# Data Model: Arabic & English Localization

**Feature**: 008-arabic-english-localization | **Date**: 2026-07-07

## Entity: Language Preference

Device-local UI setting. Never transmitted to backend.

| Field | Type | Constraints |
|-------|------|-------------|
| `app_language` | `String` in `SharedPreferences` (default prefs file used by `LocaleHelper`) | Allowed values: `"en"`, `"ar"`. Absent = no explicit choice yet. |

**Resolution rules** (in `LocaleHelper.getLanguage(context)`):

1. Stored value present → use it.
2. Absent → `"ar"` if device system language is Arabic, else `"en"` (FR-007). Not persisted until user explicitly chooses (keeps following system until first explicit choice).

**Locale mapping**:

| Stored value | Configuration locale tag | Layout direction | Resource folder |
|--------------|--------------------------|------------------|-----------------|
| `en` | `en` | LTR | `values/` |
| `ar` | `ar-u-nu-latn` (Western digits forced) | RTL | `values-ar/` |

**State transitions**:

- `absent → en` / `absent → ar`: user picks in Settings dialog → persist → API33+ `LocaleManager.setApplicationLocales` sync → `Activity.recreate()`.
- `en ↔ ar`: same path. No other writers. Survives restarts/reboots/updates (SharedPreferences).

## Entity: Text Resource Set

Two parallel string catalogs; English is source of truth for key set.

| Attribute | Rule |
|-----------|------|
| Key naming | `feature_element_purpose` (e.g., `settings_language_title`); shared keys `common_*` |
| Placeholders | Positional only: `%1$s`, `%2$d` — identical count & types across both files (FR-008) |
| Parity | `values-ar/strings.xml` key set == `values/strings.xml` key set; enforced by `tools/check_strings_parity.py` (SC-006) |
| Non-translatable | `translatable="false"` for brand/technical literals (e.g., "Haris" wordmark if kept Latin, URLs) |
| Plurals | `<plurals>` where counts appear in sentences |

**Consumers**:

- Compose screens → `stringResource(R.string.key, args...)`
- Workers/Services/notifications → `LocaleHelper.localizedContext(appContext).getString(R.string.key, args...)`
- Never: hardcoded user-facing literals in Kotlin code (FR-005).
