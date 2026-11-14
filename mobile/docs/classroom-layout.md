# Classroom phone layout — MOBILE 3.1.1 (#244)

Started from staging `3d0836d`, containing merged MOBILE 3.1 (#243), then refreshed to `886b061` with non-overlapping desktop/mock updates. This is a mobile presentation/navigation-layout change; endpoints, sync, Room entities, authentication and enrollment authorization remain unchanged.

## Shared presentation

`ui/components/ClassroomUi.kt` contains `ClassroomSurface` (white, bordered, navy-tinted card), `ClassroomCard` (dark green class header, subject icon, teacher name when cached, text/icon status and Open), and `ClassroomTopBar`. Home lists and reading screens use 12dp outer gutters; card interiors remain 16/20dp. Nunito, existing Phosphor vectors and L.A.R.A tokens are reused. No new dependency or decorative image.

`LocalClassChrome` supplies the selected class name, section and Back action to the existing role graphs. Learner membership checks still run in `LearnerEnrollmentNavigation`. `TeacherClassroomNavigation` lays out the existing class selection as Home cards before opening the same role shell; class selection and Back are saveable presentation state. Class ownership remains derived from the same cached teacher IDs. The existing teacher roster/session owner remains responsible for live approvals and refresh.

Sign-in shows Hub selection before account fields and the masked system-keyboard PIN field. The Hub-required message uses an informational step, not an error tone. Existing validation and PIN behavior remain unchanged. Settings, pending approvals and teacher tools reuse the card styling. People still uses existing class-management roster cards; future gradebook/other unimplemented feature destinations are not built here.

Teacher controls stay composed inside a clipped zero-height container while hidden, preserving any open management dialog. The class shell reserves keyboard insets, temporarily yields bottom-navigation/teacher-tool space while typing, and keeps a compact icon/text offline status visible. A focused composer requests its whole field-and-Save group into the visible viewport when keyboard height changes, so Save remains reachable, including at 200% text. The reader shows “Showing the lesson text” for a failed file download/render when cached chunks exist; files with no readable fallback retain the useful recovery message and retry action. Archived classes remain readable but keep the existing active-class-only approval guard.

## Reference and boundaries

Compared the installed Google Classroom Android Home and class Stream read-only. Its card hierarchy, class banner, source/date post header, divider and flat comments guide the layout. Google account/class data is never copied into this repository. Classroom reference screenshots are not evidence assets here. L.A.R.A keeps the documented green class bar, Nunito, solid fills, Phosphor icons and four bottom destinations; no Google branding, imagery, Meet or Drive controls.

Design interpretations to disclose: #244 explicitly requests a dark-green Home class header, so the shared card uses that header with a white status/footer area, rather than only the small green tile shown by the desktop Home reference. Fixed banner heights are avoided to support long names and 200% text. Teacher/People management and Grades retain their existing scope; no new roster or gradebook feature is invented. Where cached teacher names are unavailable the card omits the name rather than inventing one.

## Verification

Before/after physical-phone evidence and final results live in `docs/evidence/244/`. `ClassroomUiEvidenceTest` renders the same production components and synthetic presentation state before and after. `ReadingScreenDeviceTest` checks real components, valid native-file fixtures, large text and first-name privacy, plus a keyboard-open Save-button bounds assertion and calm lesson-text fallback. All fixture instrumentation requires isolated `org.lara.app.sprint31reading`; never run it over the production app.

For UI-only changes, the Tech Lead’s updated instruction removes the real-Hub prerequisite: real-Hub testing was not performed for Group 8 and is not required for that UI scope. Mock/device, lint, unit and invariant results are reported as executed, including the open #276 schema-parity failure. Ready for review does not imply required CI checks passed. This layout step does not work around missing endpoints. Physical WAN-unplugged and budget-device certification are recorded separately from Wi-Fi/fixture checks.
