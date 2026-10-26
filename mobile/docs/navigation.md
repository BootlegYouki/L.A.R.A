# Role navigation shell — MOBILE 2.1 (#24)

`LaraNavigation(state, sessionKey, content)` selects `StudentNavGraph` or
`TeacherNavGraph` from the injected `ShellState.role`. Authentication (#49) must
map `AuthSession.user.role` to `ShellRole`; ADMIN must never enter either graph.
Pass the authenticated user id as `sessionKey` so a changed account discards the
previous navigation stack. A null state keeps the existing unauthenticated
scaffold. There is no role picker or pretend authenticated account.

## Files and data flow

- `ui/navigation/ShellState.kt`: role, Hub availability, loading and active-attempt inputs.
- `ui/navigation/RoleNavGraphs.kt`: separate graph entry points, type-safe routes,
  teacher-only People and Grades destinations, loading skeletons and empty states.
- `ui/components/BottomNavBar.kt`: four 68dp tabs, bundled regular and fill
  Phosphor icons, green selection indicator and purple tutor icon.
- `ui/navigation/LaraNavigation.kt`: entry point and account/role composition key.

The eight `ic_{chats,books,exam,sparkle}_{regular,fill}.xml` vectors are converted
from the official `phosphor-icons/core` assets (256-unit SVG paths); licensing is
bundled at `app/src/main/assets/licenses/PHOSPHOR-MIT-LICENSE.txt`.

The shell never queries a DAO or the network. Later ViewModels must supply state
from Room-backed repositories and reachability from the Hub connection service.
The `content` slot receives the selected classroom destination and can compose
its Room-backed screen. Default content is explicitly empty; no data is invented.
People and Grades are navigation placeholders for their owning issues; authoring,
approval and quiz management controls belong to the later feature screens.

The approved scope adjustment is to make these inputs injectable before #49,
rather than implement authentication in this issue. Room, session persistence,
classroom selection and QuizLockout are not implemented here.

## Quiz safety and offline behavior

`hasInProgressAttempt` must reflect any IN_PROGRESS learner attempt, including
cached attempts when the Hub is unreachable. An active attempt disables the tutor
tab, exposes its explanation visually and through accessibility semantics, and
guards the content slot before composition. An AI route open or restored when
lockout begins is replaced with Quizzes. Unlocking does not automatically reopen
AI. Actual chat UI remains owned by the AI evaluation and later tutor issues.

Offline state is nonblocking, with an icon and text; the selected content remains
available. Loading shows skeletons. Tab switching preserves navigation state.
System bars are inset separately from the 68dp navigation area. All text comes
from matching English and Filipino resources.

## How to try after authentication wiring (or in a Compose host)

1. Supply `ShellState(ShellRole.STUDENT)` and a user id to `LaraNavigation`.
   Switch all four tabs; each has an empty state and the offline banner remains.
2. Supply TEACHER instead. People and Grades appear and open separate destinations;
   they are absent from the learner graph.
3. Supply custom `content` showing locally saved classroom data. Toggle
   `isHubReachable` false; content stays mounted with the offline banner.
4. Open AI Tutor, then set `hasInProgressAttempt = true`. Quizzes replaces AI,
   the disabled tab explains why, and the AI content slot is never invoked.
5. Set `isLoading = true` to see skeletons; repeat in Filipino and at large font
   settings. Switch account ids to confirm the navigation stack resets.

Run `gradlew.bat test lint`, repository unit tests and `verify_invariants.py`.
Mock-Hub login, persisted offline content, emulator screenshots, physical-device
performance and real quiz lockout integration require the later dependencies;
they are not proven by the shell policy unit tests.
