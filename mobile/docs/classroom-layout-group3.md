# Classroom layout, Group 3

Part of #266. Branch: `feat/266-mobile-classroom-layout-part-3`, based on `origin/staging` `0199e34`.

Group 2 is merged in PR #272 (`20098247`). GitHub issue #266 has no comments; no open PR or remote Group 3 branch was visible when work began. Updated Announcements were read, including #242's requirement to test the real Hub. The original worktree was clean; existing branches and other worktrees were preserved. The implementation was prepared without committing or publishing; publication was subsequently authorized as a draft PR only.

## Scope and file map

- `RoleNavGraphs.kt`, `ShellState.kt`, `BottomNavBar.kt`: Stream / Classwork / People bottom tabs for both roles. Existing Quizzes and AI Tutor routes remain in Class actions; teachers retain Grades there. Tutor remains disabled during an active attempt, and a restored tutor route is redirected to Quizzes.
- `ClassroomUi.kt`: optional top-bar action slot; existing back and Settings actions remain.
- `ClassroomLearningHost.kt`, `ClassTabComponents.kt`: shorter Stream-only banner, teachers-only learner People, full-page topic selection, and topic sections containing the existing learner assignment and standalone material rows. All topics restores the complete list. A removed selected topic falls back to All topics. Selection belongs to the class and survives bottom-tab switching.
- `TeacherClassroomNavigation.kt`, `ClassManagementHost.kt`: reuse existing teacher roster/approval/management; approval actions move to People, and embedded management no longer adds system insets a second time.
- Bundled Phosphor users Regular/Fill, funnel and dots-three-vertical drawables; English resource strings.
- `ClassTabsDeviceTest.kt`: opt-in physical-device evidence and assertions, restricted to the isolated `org.lara.app.group3` package. `ShellStateTest` also checks that People remains accessible during a quiz.

No ViewModel, repository, Hub endpoint, Room entity, migration, sync implementation, Server, Desktop, contract or design-system file changed. Groups 1, 2 and 4 are outside this change.

## Data flow and failure behavior

Class selection still comes through the existing learner approval gate or teacher-owned class selection. Stream, classwork, public teacher names and roster data still come from the existing Room-backed flows. Topic selection filters only the displayed rows; it sends no network request and changes no saved work. Materials and assignments still open their existing readers and homework screen. Teacher roster actions still use their existing online checks and server authorization.

The learner People view iterates only the class's teacher IDs and resolves only cached public teacher names; it never enumerates learner names, IDs or LRNs. Missing teacher names use the Teacher fallback. Existing loading skeletons and the shared Hub-unreachable banner remain; classwork has a resource-based empty state, including an empty selected topic. Teachers retain their cached roster and disabled offline management actions.

## Design deviations

- Issue #266 and the supplied Android references require three bottom tabs. Older design-system sections 5.13/9.12 still describe four. Follow the explicit Group 3 request and document this for Lead review; no Lead-owned file was edited.
- Preserve Quizzes and AI Tutor in Class actions, plus teacher Grades. The user approved choosing their placement. This keeps existing destinations while the design system leaves the tutor entry open. It adds no new quiz or tutor functionality.
- Keep L.A.R.A's solid green header/banner, Nunito, Phosphor, initials, local assets and English chrome. No Google branding, photos, Meet, invite links, reposting or unsupported actions.
- The phone banner has a 144dp minimum and grows with text, with the icon to the right. It appears only in Stream, matching the phone reference structure; no unsupported Customize/About behavior is added.
- Learner People shows teachers only, as required by design-system 9.6/9.16, unlike Classroom's visible classmates.
- The topic selector has All topics for clearing the filter and selects immediately. The entry is a labeled funnel button above the existing classwork list. Untopiced/orphaned work remains visible under No topic.
- Teacher People reuses existing approval and management cards, including pending requests and removal controls. Posting forms and new authoring actions belong to Group 4 and were not added.
- Assignment instructions and material readers keep their existing behavior. Teacher assignments and quizzes are not newly implemented by this UI pass.
- After review, labels stay on one line and shrink only when needed; targets remain at least 52dp. Compose Foundation 1.7.6 lacks native TextAutoSize, so onTextLayout reduces overflowing labels in bounded 0.5sp steps without upgrading dependencies. The topic selector and class actions scroll instead of truncating options.

## Verification and review evidence

See [the comparison and result index](evidence/266-group-3/README.md) and [PR #279](https://github.com/BootlegYouki/L.A.R.A/pull/279). Evidence distinguishes live mock/real-Hub data from explicitly labeled local presentation fixtures.

The full Gradle gate is blocked by the same schema-parity failure on untouched `staging`: contract PR #273 added `enrollments.created_at`, while Room's version-3 export has no such column. A separate detached baseline build reproduces it. This UI-only task leaves the schema and parity test intact; a separate mobile schema update is required before the gate can be green.

Review fixes for PR #279: locked Tutor text/icon now inherit Material disabled colors; enabled Tutor remains AiDark. See [updated actual-200% phone evidence and targeted checks](evidence/266-group-3/review-fixes/README.md). Known issues #122 and #276 were not investigated or modified.
