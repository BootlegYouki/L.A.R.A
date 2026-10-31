# Role navigation shell — MOBILE 2.1 (#24)

`LaraNavigation(discovery, state, sessionKey, content)` keeps the Sprint 1.2 Hub discovery graph as the unauthenticated entry. When authentication supplies a non-null `ShellState`, it selects `StudentNavGraph` or `TeacherNavGraph` from the authenticated role. MOBILE 2.2 `AuthHost` maps the validated cached profile role to `ShellRole`; `ADMIN` must never enter either graph. Pass the authenticated user ID as `sessionKey` so an account change discards the previous navigation stack.

## Files and data flow

- `ui/navigation/ShellState.kt`: role, Hub availability, loading, and active-attempt inputs.
- `ui/navigation/RoleNavGraphs.kt`: separate graph entry points, type-safe routes, teacher-only People and Grades destinations, loading skeletons, and empty states.
- `ui/components/BottomNavBar.kt`: four 68dp tabs, bundled regular and fill Phosphor icons, green selection indicator, and purple tutor icon.
- `ui/navigation/LaraNavigation.kt`: discovery-first unauthenticated graph and account/role composition boundary.

Sprint 1.2 owns `HubDiscovery`, its screen, and endpoint persistence. The shell reuses that graph instead of replacing it. Sprint 1.3 owns the Room database and `QuizRepository.hasInProgressAttempt(studentId)`. MOBILE 2.2 `AuthHost` collects that Room flow for the authenticated user and maps it to `ShellState.hasInProgressAttempt`; navigation does not query a DAO directly.

The shell content slot receives the selected classroom destination and can compose a Room-backed screen. Default content is explicitly empty. People and Grades are navigation placeholders for their owning issues; authoring, approval, and quiz-management controls belong to later feature screens.

## Quiz safety and offline behavior

`hasInProgressAttempt` reflects any cached `IN_PROGRESS` learner attempt. An active attempt disables the tutor tab, exposes its explanation visually and through accessibility semantics, and guards the content slot before composition. An AI route open or restored when lockout begins is replaced with Quizzes. Unlocking does not automatically reopen AI.

Discovery and the shell share `ui/components/DiscoveryBanner.kt`; the shell selects its amber warning state with a warning icon and polite accessibility announcement. The offline banner is nonblocking and leaves selected content mounted. Loading uses solid skeleton blocks. Tab switching preserves navigation state. All text comes from matching English and Filipino resources.

## Verification

1. Launch without an authenticated state and verify Sprint 1.2 discovery remains the start destination, including manual Hub entry and offline feedback.
2. Inject learner and teacher states and verify their graphs differ.
3. Feed `QuizRepository.hasInProgressAttempt(studentId)` into the state host; while true, verify AI content is never composed.
4. Stop the Hub and confirm saved Room content remains visible under the banner.
5. Run `gradlew.bat test lint`, repository tests, and `verify_invariants.py`.

MOBILE 2.2 wires this boundary from `AuthHost`; callers and tests can still inject a shell state. Normal app startup now restores the saved session or presents the sign-in screen with access to discovery.
