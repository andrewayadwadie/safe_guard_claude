# Phase 1 Data Model: Haris Design-System Restyle

This feature has **no runtime/persisted data model** (no entities, no storage). The "model" here is the **design-token model** — the structured set of brand values that every UI surface references. All values are authoritative inputs from the spec/source brief.

## Entity: Brand color values (`Haris*` vals in `Theme.kt`)

Renamed from `SafeGuard*`; references updated in-file. Old names removed (no obsolete duplicates).

| Token | Value |
|---|---|
| `HarisPetrol` | `#168BB6` |
| `HarisPetrolDark` | `#0E6A86` |
| `HarisPetrolLight` | `#A4DFF4` |
| `HarisAqua` | `#2799A5` |
| `HarisAquaLight` | `#ACE5EC` |
| `HarisGold` | `#C8941E` |
| `HarisGoldLight` | `#F1DCA7` |
| `HarisGoldDark` | `#AF861D` |
| `GradientStart` | `#0E6A86` |
| `GradientEnd` | `#2799A5` |

## Entity: LightColorScheme (`lightColorScheme`)

| Role | Value | | Role | Value |
|---|---|---|---|---|
| primary | `#168BB6` | | onPrimary | `#FFFFFF` |
| primaryContainer | `#C9ECF8` | | onPrimaryContainer | `#072A36` |
| inversePrimary | `#A4DFF4` | | secondary | `#AF861D` |
| onSecondary | `#FFFFFF` | | secondaryContainer | `#F6EACA` |
| onSecondaryContainer | `#352809` | | tertiary | `#2799A5` |
| onTertiary | `#FFFFFF` | | tertiaryContainer | `#CDF0F3` |
| onTertiaryContainer | `#0C2E32` | | error | `#BA1A1A` |
| onError | `#FFFFFF` | | errorContainer | `#FFDAD6` |
| onErrorContainer | `#410002` | | background | `#FAFCFC` |
| onBackground | `#1C2022` | | surface | `#FFFFFF` |
| onSurface | `#1C2022` | | surfaceVariant | `#DCE3E5` |
| onSurfaceVariant | `#3C4E53` | | surfaceTint | `#168BB6` |
| inverseSurface | `#2E3538` | | inverseOnSurface | `#F1F3F4` |
| outline | `#6B8C94` | | outlineVariant | `#C4D1D4` |
| scrim | `#000000` | | surfaceContainerLowest | `#FFFFFF` |
| surfaceContainerLow | `#F5F9FA` | | surfaceContainer | `#F0F5F7` |
| surfaceContainerHigh | `#E8F0F3` | | surfaceContainerHighest | `#E2EBEE` |
| surfaceBright | `#FFFFFF` | | surfaceDim | `#DDE5E8` |

## Entity: DarkColorScheme (`darkColorScheme`) — canonical

| Role | Value | | Role | Value |
|---|---|---|---|---|
| primary | `#A4DFF4` | | onPrimary | `#0B465B` |
| primaryContainer | `#10617F` | | onPrimaryContainer | `#C9ECF8` |
| inversePrimary | `#168BB6` | | secondary | `#F1DCA7` |
| onSecondary | `#58430E` | | secondaryContainer | `#7B5E14` |
| onSecondaryContainer | `#F6EACA` | | tertiary | `#ACE5EC` |
| onTertiary | `#134C53` | | tertiaryContainer | `#1B6B74` |
| onTertiaryContainer | `#CDF0F3` | | error | `#FFB4AB` |
| onError | `#690005` | | errorContainer | `#93000A` |
| onErrorContainer | `#FFDAD6` | | background | `#0F1719` |
| onBackground | `#DDE1E3` | | surface | `#0F1719` |
| onSurface | `#DDE1E3` | | surfaceVariant | `#3C4E53` |
| onSurfaceVariant | `#C4D1D4` | | surfaceTint | `#A4DFF4` |
| inverseSurface | `#DDE1E3` | | inverseOnSurface | `#2E3538` |
| outline | `#8FA7AE` | | outlineVariant | `#3C4E53` |
| scrim | `#000000` | | surfaceContainerLowest | `#0A1012` |
| surfaceContainerLow | `#161F22` | | surfaceContainer | `#1A2427` |
| surfaceContainerHigh | `#242F33` | | surfaceContainerHighest | `#2E3A3E` |
| surfaceBright | `#343B3E` | | surfaceDim | `#0F1719` |

## Entity: SemanticColors (recolor existing object; keep property names + helper fns)

| Field | Value |
|---|---|
| success | `#2E9E8F` |
| warning | `#C8941E` |
| info | `#168BB6` |
| gradientPrimary | `listOf(#0E6A86, #2799A5)` |
| statusOnline | `#2E9E8F` |
| statusSuspended | `#C8941E` |
| statusOffline | `#6B8C94` |
| severityLow / Container | `#2E9E8F` / `#CDEFE8` |
| severityMedium / Container | `#C8941E` / `#F6EACA` |
| severityHigh / Container | `#E8833A` / `#FBE0CC` |
| severityCritical / Container | `#D2483F` / `#FBD9D5` |

Helper functions retained, signatures unchanged: `getScreenTimeColor`, `getAlertSeverityColor`, `getDeviceStatusColor`, `getAlertSeverityContainerColor`, `getScreenTimeGradient`. Only referenced values change. The gold `#C8941E` is a solid accent only and never appears inside `gradientPrimary`.

## Entity: Typography (15 M3 styles, Inter base)

| Style | size/line/weight | | Style | size/line/weight |
|---|---|---|---|---|
| displayLarge | 30/38/700 | | titleMedium | 16/24/600 |
| displayMedium | (proportional) | | titleSmall | (proportional) |
| displaySmall | (proportional) | | bodyLarge | 15/22/400 |
| headlineLarge | 24/32/700 | | bodyMedium | 14/20/400 |
| headlineMedium | 20/28/600 | | bodySmall | 13/18/400 |
| headlineSmall | (proportional) | | labelLarge | 16/24/600 |
| titleLarge | 17/24/600 | | labelMedium | 12/16/600 |
| | | | labelSmall | 11/16/600 |

Families: `Inter` + `Cairo` (`GoogleFont`, weights 400/600/700/800). `AppFontFamily = Inter`. Selector: `rememberAppFontFamily()` → Cairo for RTL/Arabic else Inter.

## Entity: Shape tokens (`SafeGuardShapes`)

| Token | Value |
|---|---|
| extraSmall | 4.dp |
| small | 8.dp |
| medium | 12.dp |
| large | 16.dp |
| extraLarge | 24.dp |
| `PillShape` | `RoundedCornerShape(percent = 50)` |
| `BottomSheetShape` | `RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)` |

## Entity: Dimension tokens (`SafeGuardDimens` — add new, keep existing)

| New token | Value |
|---|---|
| screenPadding | 20.dp |
| gutter | 16.dp |
| stackSm | 8.dp |
| stackMd | 16.dp |
| stackLg | 24.dp |
| listItemMinHeight | 56.dp |
| listSeparatorInset | 16.dp |

Existing icon/avatar/button-height/progress/elevation tokens are **kept** unchanged.

## Entity: Responsive (`Responsive.kt`)

- `enum ScreenWidth { Compact, Medium, Expanded }`
- `rememberScreenWidth(): ScreenWidth`
- `Modifier.responsiveContentWidth(width)` → cap 600.dp + center on Medium/Expanded
- `responsiveScreenPadding(width)` → Compact 20.dp / Medium 24.dp / Expanded 32.dp

## Entity: Design-system component set (`presentation/designsystem/`)

Full slot APIs in [contracts/components.md](./contracts/components.md): `HarisPrimaryButton`, `HarisSecondaryButton`, `HarisGhostButton`, `HarisTextField`, `HarisCard`, `HarisChip`, `HarisSwitch`, `HarisListItem`, `HarisGradientHeader`. Each is logic-free, token-only, with light+dark `@Preview` + realistic sample data.

## Validation rules (cross-cutting)

- No `Color(0xFF…)` literal or `#` hex may appear in screens/components (tokens only).
- Body-text/background pairings meet WCAG AA in both schemes.
- Actionable icons/images expose localized `contentDescription`; decorative ones expose `null`.
- Existing public names unchanged; only additive `HarisTheme` typealias + new `Haris*` symbols.
