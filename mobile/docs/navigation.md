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
| `ui/navigation/LaraDestinations.kt` | `LaraRoutes` string constants and the `LaraTab` enum (route + label + accessible-name string resources). |
| `ui/navigation/LaraNavGraph.kt` | `NavHost`: `connect` landing → a home shell with the `NavigationBar` and the four placeholder tabs. |
| `ui/screens/ConnectScreen.kt` | Landing screen the app opens to; primary action is a navigation placeholder for discovery (#4). |
| `ui/screens/PlaceholderScreens.kt` | Stream / Classwork / Quizzes / Tutor placeholder bodies. |
| `ui/components/LaraButtons.kt` | `LaraPrimaryButton` (56dp) and `LaraButton` (52dp) — the touch-target floor baked in. |
| `ui/theme/*` | `Color/Theme/Type/Shape.kt` copied verbatim from `design-system/mobile/`; the single source of tokens. |

**Navigation flow:** `connect` is the start destination. "Find Hub" navigates to `stream` and pops
`connect` off the back stack. The four tabs are top-level routes; the shared `NavigationBar` is drawn
per destination and re-selects with `launchSingleTop` + `restoreState` so tab state survives switches.

The single home host is deliberately flat so #24 can split it into `StudentNavGraph` /
`TeacherNavGraph` by authenticated role without reworking the `NavController` contract.

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
- **Icons.** The bottom nav is text-labelled with explicit `contentDescription`s. The Phosphor icon
  set (design-system §4) is a feature-screen concern and lands with the role nav work (#24); it was
  intentionally not added here to avoid a premature icon dependency.
- **Launcher icon.** Adaptive-icon only (vector foreground + solid Primary Green background); no
  legacy density PNGs. Fine for `minSdk 26`, but lint emits a benign `IconMissingDensityFolder` note.
- **No blocking offline dialog.** The connection landing follows `README` §0.9 — it invites the user
  to find the Hub rather than showing a "No Connection" error. Real discovery/offline states are #4.
- **`local.properties`** (the machine SDK path) is gitignored; CI and each dev supply their own.

## 6. What replaces the placeholders

| Placeholder | Replaced by |
| :--- | :--- |
| `ConnectScreen` "Find Hub" action | mDNS discovery + manual IP + reconnect (#4, see `discovery.md`) |
| Single home host | `StudentNavGraph` / `TeacherNavGraph` role routing (#24) |
| Stream / Classwork / Quizzes / Tutor bodies | Feature screens across Sprints 3–5 |
| Text-labelled nav items | Phosphor Regular/Fill icons with the Light Green active indicator (#24) |
