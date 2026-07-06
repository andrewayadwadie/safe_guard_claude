# Contract: Haris Design-System Components

Package: `com.safeguard.parentalcontrol.presentation.designsystem`. All components are **logic-free** (render state, forward actions via lambdas), **token-only** (no hardcoded hex), and ship light+dark `@Preview`s with realistic sample data. Signatures are contracts (param names/intent stable); exact modifier plumbing is an implementation detail.

## HarisPrimaryButton
```kotlin
@Composable
fun HarisPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
)
```
- Shape `PillShape`; container `primary`, content `onPrimary`; height 56.dp; text style `labelLarge`.
- Press feedback: subtle scale via `graphicsLayer` (deferred read). Disabled state uses M3 disabled tokens.

## HarisSecondaryButton
```kotlin
@Composable
fun HarisSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)
```
- `PillShape`; container `surfaceContainerHigh`; content `secondary` (or `tertiary` variant).

## HarisGhostButton
```kotlin
@Composable
fun HarisGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)
```
- Transparent container; content `tertiary`.

## HarisTextField
```kotlin
@Composable
fun HarisTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    isError: Boolean = errorText != null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
)
```
- Filled `surfaceContainerHigh`; 1.dp `outlineVariant`; 12.dp radius; label rendered **above** the field; helper + error slots below (error supersedes helper).

## HarisCard
```kotlin
enum class HarisCardStatus { None, Safe, Warning }

@Composable
fun HarisCard(
    modifier: Modifier = Modifier,
    status: HarisCardStatus = HarisCardStatus.None,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
)
```
- 16.dp radius; container `surfaceContainer`; 1.dp `outlineVariant` border in dark theme. Top-right status slot: `Safe` → `SemanticColors.success`; `Warning` → `SemanticColors.warning`/`error`.

## HarisChip
```kotlin
@Composable
fun HarisChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: HarisChipVariant = HarisChipVariant.Pill, // Pill | Filter(sm radius)
    leadingIcon: (@Composable () -> Unit)? = null,
)
```
- `Pill` default; `Filter` variant uses `extraSmall` (4.dp) radius for Apps/Websites/Location filters. Selected → `primaryContainer`/`onPrimaryContainer`.

## HarisSwitch
```kotlin
@Composable
fun HarisSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)
```
- On-state track/thumb use brand `primary` (teal).

## HarisListItem
```kotlin
@Composable
fun HarisListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
)
```
- Min height `listItemMinHeight` (56.dp); divider inset `listSeparatorInset` (16.dp) from both edges.

## HarisGradientHeader
```kotlin
@Composable
fun HarisGradientHeader(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
)
```
- Brand gradient `#0E6A86 → #2799A5` (`SemanticColors.gradientPrimary`) banner for dashboard summary. Gold accent never in the gradient.

## Cross-cutting contract
- Every component: light `@Preview` + dark `@Preview` wrapped in `HarisTheme`/`SafeGuardTheme`.
- Decorative icons `contentDescription = null`; actionable icons use `stringResource`.
- RTL-correct (use start/end, not left/right).
