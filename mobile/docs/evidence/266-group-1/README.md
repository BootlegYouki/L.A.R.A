# Group 1 screenshot evidence

All PNGs are actual full-screen captures from an Infinix X6731 running Android 14. Baseline: staging `f8dfbfac`; after: working Group 1 branch. No screenshots were generated, composited or edited. All accounts and lesson data are synthetic. The original reference images remain in the Lead-owned directory.

## Compare

| Screen / role | Classroom reference | Before L.A.R.A. | After L.A.R.A. |
|---|---|---|---|
| Home / Learner | [Student 5](../../../../design-system/mobile/google-classroom-reference/student/5-%20CLASSES_.jpg) | [Home](before/learner-home.png) | [Home](after/final-learner-home.png) |
| Home / Teacher | [Teacher 5](../../../../design-system/mobile/google-classroom-reference/teacher/5-%20CLASSES_.jpg) | [Home](before/teacher-home.png) | [Home](after/final-teacher-home.png) |
| Drawer / Learner | [Student 4](../../../../design-system/mobile/google-classroom-reference/student/4-%20CLASSES_.jpg) | [Home before drawer existed](before/learner-home.png) | [Drawer](after/final-learner-drawer.png) |
| Drawer / Teacher | [Student 4 structure](../../../../design-system/mobile/google-classroom-reference/student/4-%20CLASSES_.jpg) | [Home before drawer existed](before/teacher-home.png) | [Drawer](after/final-teacher-drawer.png) |
| Plus / Learner | [Student 29](../../../../design-system/mobile/google-classroom-reference/student/29-%20PLUS%20SIGN%20%28JOIN_%20CREATE%29.jpg) | [Inline Join button](before/learner-home.png) | [Action sheet](after/final-learner-plus-sheet.png) |
| Plus / Teacher | [Student 29 sheet](../../../../design-system/mobile/google-classroom-reference/student/29-%20PLUS%20SIGN%20%28JOIN_%20CREATE%29.jpg) | [Manage classes entry](before/teacher-home.png) | [Action sheet](after/final-teacher-plus-sheet.png) |
| Existing Settings destination / Learner | S4 drawer Settings entry; screen itself not redesigned | [Settings](before/learner-settings.png) | [Settings](after/final-learner-settings.png) |
| Existing Settings destination / Teacher | S4 drawer Settings entry; screen itself not redesigned | [Settings](before/teacher-settings.png) | [Settings](after/final-teacher-settings.png) |

Teacher 7 was opened: it depicts a class Stream, not Home. It informs Group 3 and is intentionally not presented as a matching Home screenshot.

## States and device configurations

- `after/final-*`: final APK, real current-staging Rust Hub, both roles, Home/drawer/plus/unchanged forms/Settings. The device test also opens a class and returns Home; menu and FAB targets are asserted at 52dp/56dp.
- `after/learner-{empty,loading,error,pending,offline}.png`: explicitly injected presentation-state fixtures using production Composables, rendered on the phone. These are labeled rendering evidence, not fabricated live errors.
- `after/actual-cached-offline*.png`: learner's actual mock-backed cached session after both USB port forwards were removed. Cached class navigation and unavailable Join were verified.
- `after/actual-teacher-cached-offline*.png`: teacher's actual real-Hub cached session with both forwards removed; Create remains unavailable and the cached class opens.
- `after/font200-*`: actual Android `font_scale=2.0`, mock-backed live role navigation; original `0.85` restored afterward. Labels and actions remain available. `after/learner-font200.png` separately uses a Compose density override.
- `after/landscape-*`: real-Hub flows at 2400×1080, normal device font scale, including scrolling Settings. Portrait files are 1080×2400. Original rotation settings restored.
- `after/landscape-states/`: production Home fixtures at short landscape height, including 200% Compose text, drawer and disabled offline action sheet. These are separate from portrait fixtures.
- Other `after/*` PNGs retain earlier passing mock/real-Hub captures for traceability. No claim of a live error, pending request or empty server is based on an injected state.

Layout differences, preservation of functionality and the full screen inventory are in [the implementation notes](../../classroom-layout-group1.md). Main deliberate differences: L.A.R.A white/token-based cards, no Google images/accounts, one permitted action per role, no unsupported drawer destinations, and existing teacher approval controls retained.

Automated accessibility checks exercise Android accessibility labels and minimum menu/FAB hit bounds. Spoken TalkBack traversal, classroom Wi-Fi discovery and a WAN-unplugged router test were not performed.
