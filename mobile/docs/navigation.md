# Navigation & Project Shell

> Scope: `[MOBILE 1.1]` (#23) — the project scaffold and the navigation shell it ships with.
> Role-aware graphs (Student vs Teacher) and real screens arrive in later issues; this document
> describes the shell those issues build on. See [`TECH_SPEC.md`](./TECH_SPEC.md) §2 for the full
> target architecture.

## 1. Purpose

Stand up the native Android project so every later Sprint 1+ issue has a compiling, lintable base:
a Gradle Kotlin DSL build with Jetpack Compose + Room, the L.A.R.A design tokens wired into a
`LaraTheme`, and a navigation shell that launches to a connection landing and exposes the four
Student bottom-nav destinations as placeholders (Stream, Classwork, Quizzes, AI Tutor).

This is foundation only. No networking, persistence, auth or AI logic is included here.

## 2. Key files and entry points

Start at the entry point and follow the composition down:

| File | Role |
| :--- | :--- |
| `MainActivity.kt` | Single-Activity entry point; calls `setContent { LaraApp() }`, enables edge-to-edge. |
| `LaraApplication.kt` | Process `Application`; intentionally thin — later wires Room/OkHttp/WorkManager. |
| `ui/LaraApp.kt` | Compose root. Applies `LaraTheme` once and hosts the nav graph in a `Surface`. |
| `ui/navigation/LaraDestinations.kt` | `LaraRoutes` string constants (incl. `HOME`) and the `LaraTab` enum (route + label string resource). |
| `ui/navigation/LaraNavGraph.kt` | Outer `NavHost` (`connect` → `home`); `HomeShell` holds one `Scaffold` + `NavigationBar` over an inner tab `NavHost`. |
| `ui/screens/ConnectScreen.kt` | Landing screen the app opens to; primary action is a navigation placeholder for discovery (#4). |
| `ui/screens/PlaceholderScreens.kt` | Stream / Classwork / Quizzes / Tutor placeholder bodies. |
| `ui/components/LaraButtons.kt` | `LaraPrimaryButton` (56dp) and `LaraButton` (52dp) — the touch-target floor baked in. |
| `ui/theme/*` | `Color/Theme/Type/Shape.kt` copied verbatim from `design-system/mobile/`; the single source of tokens. |

**Navigation flow:** `connect` is the outer start destination. "Find Hub" navigates to `home` and
pops `connect` with `inclusive = true`, so Back from the shell exits the app (it does not return to
Connect). `home` renders `HomeShell`: a single `Scaffold` whose `NavigationBar` drives an inner
`NavHost` (start = `stream`) over the four tabs. The bar is composed once; switching tabs recomposes
only the content. Tabs re-select with `popUpTo(startDestination){saveState}` + `launchSingleTop` +
`restoreState` so each tab's state is preserved and the inner back stack stays single-entry.

`HomeShell`'s inner host is the seam #24 replaces with role-aware `StudentNavGraph` /
`TeacherNavGraph`; the outer host and the `connect → home` transition stay as the entry contract.

## 3. Data flow and local state

None yet. The shell holds no app state beyond the `NavController` back stack. Nothing is read from
or written to Room in this PR (the Room schema and DAOs are #41, delta-sync is #52). All user-facing
text is resolved from string resources, never hard-coded.

## 4. Build stack & decisions

| Choice | Value | Why |
| :--- | :--- | :--- |
| AGP / Gradle | 8.11.1 / 8.13 | AGP 8.11 compiles cleanly against `compileSdk 36` (the SDK platform available in this environment) and needs Gradle 8.13+. |
| Kotlin | 2.0.21 | Kotlin 2.x per `README` §1; uses the Compose Compiler Gradle plugin (no separate compiler-version pinning). |
| compileSdk / minSdk / targetSdk | 36 / 26 / 35 | `minSdk 26`–`targetSdk 35` match the `README` §1 SDK range (Android 8.0–15). `compileSdk 36` is a build-time choice only; it does not change the shipped API range. |
| Compose BOM | 2024.12.01 | Pins every Compose artifact to one coherent set. |
| Room | 2.6.1 (via KSP) | Mandated persistence stack; schema export is enabled now so the #41 schema test has a location to diff against. |
| Dependency management | Gradle version catalog (`gradle/libs.versions.toml`) | One place for versions so the Dev A / Dev B slots never drift. |

Package is `org.lara.app` throughout (per the handoff review note).

## 5. Gotchas and edge cases

- **`gradlew` line endings.** `mobile/.gitattributes` forces `gradlew` to LF. On a CRLF checkout the
  Linux CI runner fails with a `bad interpreter` error. Fonts (`*.ttf`) and the wrapper jar are
  marked `binary` so EOL conversion never corrupts them.
- **Icon + text slot (for #24).** Text is in the `NavigationBarItem` **label** slot; the **icon**
  slot is a deliberate empty placeholder. #24 must put the Phosphor icon (Regular, Fill when active)
  in the icon slot — the design system requires icon **and** text (§4, §5.13) — and reintroduce the
  per-tab `contentDescription` accessible names that were removed here while the nav is text-only.
- **Do not copy a per-tab Scaffold.** An earlier revision gave each tab its own Scaffold, which
  rebuilt the bottom bar on every switch and dropped tab state. The shell now hoists one Scaffold +
  one inner `NavHost` (see §2). #24 should keep this single-Scaffold shape, not reintroduce the
  per-destination pattern.
- **Back behavior to verify on device.** With `connect` popped `inclusive = true`, Back from a tab
  should exit the app, not return to Connect or stack tabs. This is correct by construction but
  unverified on hardware in this PR (no device/emulator available); confirm during #24.
- **Launcher icon.** Adaptive-icon only (vector foreground + solid Primary Green background); no
  legacy density PNGs. Fine for `minSdk 26`, but lint emits a benign `IconMissingDensityFolder` note.
- **No blocking offline dialog.** The connection landing follows `README` §0.9 — it invites the user
  to find the Hub rather than showing a "No Connection" error. Real discovery/offline states are #4.
- **`local.properties`** (the machine SDK path) is gitignored; CI and each dev supply their own.

## 6. What replaces the placeholders

| Placeholder | Replaced by |
| :--- | :--- |
| `ConnectScreen` "Find Hub" action | mDNS discovery + manual IP + reconnect (#4, see `discovery.md`) |
| `HomeShell` inner host | `StudentNavGraph` / `TeacherNavGraph` role routing (#24) |
| Stream / Classwork / Quizzes / Tutor bodies | Feature screens across Sprints 3–5 |
| Empty icon slot + text label | Phosphor Regular/Fill icon in the icon slot, Light Green active indicator, restored content descriptions (#24) |
