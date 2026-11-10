# Draft: feat(mobile): align class tabs, banner and topic filters with Classroom

## Summary of Changes

Part of #266

Group 3 only: put Stream, Classwork and People in the bottom bar for both roles, show the class banner only in Stream, and add a full-page topic selector over existing Room-backed classwork. Learner People shows teachers only. Teacher People retains existing roster/approval actions. Existing Quizzes, AI Tutor and teacher Grades remain reachable through Class actions, including Tutor quiz lockout.

Group 2 (#272) is merged. Base: `staging` `0199e34`. Groups 1, 2 and 4 are not reimplemented. No API, ViewModel, repository, Room, migration or sync change. This is a draft PR for review; do not merge or close #266.

## Subsystems Touched

- [x] Mobile UI, English resources, tests and docs/evidence
- [ ] Desktop, Server, contracts or Lead-owned design files

## How to Test

1. Run the mock Hub. Install the mobile debug APK, connect to the Hub and sign in with the repository's synthetic learner account. Open Science 4.
2. Switch between Stream, Classwork and People. The banner appears in Stream; People shows teachers and no classmates or LRNs. Back returns through the existing class navigation.
3. In Classwork, open Filter by topic. Select a topic containing work, then All topics. Existing assignments and standalone materials belong under their topic headings; opening them retains the existing behavior. Switching to another tab and back retains the chosen filter.
4. As the synthetic teacher, open the same class. Check the three tabs, People roster and pending-request entry. Existing teacher controls remain subject to Hub availability and permissions.
5. Open Class actions. Quizzes and AI Tutor retain their existing destinations; teachers also have Grades. With an active quiz, Tutor is disabled with its existing explanation and chat is not composed.
6. After syncing, disconnect the Hub and cold-launch each account. Saved classes, teacher identities and topic options remain readable; teacher changes remain disabled. Restore the Hub connection.
7. Set Android text to 200%. Confirm the banner grows, bottom labels wrap, topic options remain reachable and content can scroll. Restore the original setting.

For the real Hub, use a fresh synthetic seed directory and create/approve the classroom first. Its topic-creation route currently returns HTTP 404; populated topic filtering uses the documented Room fixtures. See [reproduction instructions](README.md#reproduction).

## Verification & Testing

- [x] Physical Infinix X6731, Android 14: both mock and real-staging Hub role flows
- [x] Real synchronized caches cold-launched with both Hub transport ports disconnected, then restored
- [x] Topic filtering, All topics reset, mixed assignment/material groups, retained selection, teachers-only privacy and Tutor lockout assertions
- [x] Real Android font scale 200%, restored to the original 0.85
- [x] Normal and isolated debug APKs and test APK build
- [x] Lint: 0 errors, 43 warnings
- [x] Repository tests: 69 pass; invariants and whitespace/source checks pass
- [ ] Full Gradle unit gate: both variants have the pre-existing schema-parity failure; each has 194 pass, 6 opt-in skips, 1 failure
- [ ] WAN-unplugged classroom router, spoken TalkBack and 3–4 GB memory qualification

The baseline worktree at untouched `0199e34` reproduces `SchemaParityTest`: contract PR #273 adds `enrollments.created_at` but Room's v3 schema lacks it. The test and schema are intentionally unchanged. Resolve this in a separate mobile schema update before merge.

## Screens and Evidence

[Reference / BEFORE / AFTER comparisons, real-Hub captures, empty/loading/offline states, 200% text, commands and logs](README.md).

## Design deviations

- Use issue #266's three-tab phone layout over older four-tab prose in design-system 5.13/9.12.
- Preserve Quizzes/Tutor and teacher Grades in Class actions; the user approved choosing that placement while the design system leaves the Tutor entry open.
- Keep L.A.R.A's solid green, Nunito, Phosphor and initials. The Stream banner has a 144dp growing minimum with a right-hand icon; omit unsupported Customize, Google actions and pictures.
- Learner People lists teachers only, per the privacy decision in design-system 9.6/9.16.
- Topic selection is immediate and includes All topics. Teacher authoring, posting, new quiz entries and person-specific work filters are not added.
- Preserve existing teacher roster/approval cards and management operations. Embedded People uses the shared offline banner and avoids duplicate system insets.

Full reasoning and file map: [implementation notes](../../classroom-layout-group3.md).

## Not Verified

Real-Hub topic authoring returns 404 rather than the mock's supported route; populated-topic selection is fixture-covered. Server team @OpenYaji @meliodaff: the real Hub returns HTTP 404 for topic creation; no client workaround was added. No spoken TalkBack session, WAN-unplugged router run, budget-memory profile or unrelated camera/video/quiz execution was repeated. Existing lint/compiler warnings remain. Unit tests are not all green because of the independently reproduced schema mismatch.

## Contract Changes

None.

## Non-Regression Checklist

- [x] No runtime cloud/CDN dependency or new dependency
- [x] Mobile docs and authentic phone evidence
- [x] No new secret, foreign LRN or answer-key surface
- [x] Existing AI quiz lockout retained and checked
- [ ] Warning-free build and fully passing unit gate (limitations above)

Keep this PR in draft. Do not merge, close #266 or start Group 4. `Part of #266` is intentional.
