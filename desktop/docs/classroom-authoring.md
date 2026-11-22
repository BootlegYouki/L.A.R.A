# Classroom authoring

Desktop teacher controls now create announcements in Stream and assignments, materials, MCQ quizzes and topics in Classwork. The Create menu uses the existing green theme and working contract routes. Dialogs preserve failed drafts, prevent duplicate submissions, use bounded requests and require an online writable teacher/co-teacher class. Uploaded files use multipart HTTP. Quiz creation currently has no topic selector because the sync contract omits quiz topic links. Quiz answer keys remain transient and are never emitted by the cached classroom read. SQLite is the only classroom read source; successful creation triggers existing sync and account refresh.

Classwork includes cached assignments and quizzes alongside materials. A real topic selector on assignment/material composers sends topic_id; the Classwork topic filter uses canonical stored links. The native command exposes only display metadata, not paths or answer keys. Stream empty state is compact, Teaching/Enrolled headings are absent without available classes, the class-header Home button is removed, and only the global + is hidden inside a class (account and Hub controls remain).

## Verification

- 133 frontend tests, lint and TypeScript/Vite build passed. New tests cover teacher/class/archive validation, invalid receipts, multiline content, server-generated question IDs, co-teacher quizzes, topic filtering, multipart validation and StrictMode request cancellation.
- Native library: 27 passed, 3 existing opt-in hardware/credential checks skipped. Exact LAN authoring route allow/deny checks pass.
- Shared contracts/mock suite: 73 passed. Offline invariant scanner passes.
- Production native executable against the real local Hub with synthetic data: announcement, assignment, topic, PDF material upload and quiz creation all succeeded and appeared through SQLite sync. Assignment topic selection succeeded. Initial quiz response mismatch exposed server-generated question IDs; fixed and retested with regression coverage.
- Stopped the real QA Hub: authoring controls disabled while cached assignments stayed visible. Restarted it: automatic reconnect restored authoring. The Hub was restored after testing.
- Native dialog autofocus/Escape and 720px layout passed. Test executable is unbundled; no installer was produced/published.

## Contract and verification limits

Class banners use five transparent grayscale WebP owls directly on the green background: reading, waving, cheerful, hearts, and graduate. The last three are image-editor background extractions saved as separate `*-transparent.webp` assets; the originals remain unchanged. A stable hash of the permanent classroom ID selects from the fixed asset list, so reopening, renaming, and switching classes preserve the mascot without adding a shared field or local preference. Existing and newly created classes use the same rule. Keep the asset list stable to preserve those assignments.

OpenAPI and the real Hub permit only teachers to create announcement parents. Learner announcement creation requires separate contract/Hub permission work; no Desktop bypass was introduced. Learner replies are a separate comments feature. Standalone Question and Reuse post have no supported operation and are not shown as pretend actions. Quizzes here support MCQ creation, not starting/grading a session. Quiz topic filtering awaits a sync-contract addition; quizzes appear in All topics. No Linux/macOS, physical second-PC/WAN unplug, large-upload stress or fresh installer-upgrade result is claimed. Functional authoring must be scheduled by the lead under the feature-freeze announcement; no merge approval is implied.
