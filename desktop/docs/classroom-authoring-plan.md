# Classroom authoring implementation and verification

Scope: Desktop only. Preserve L.A.R.A. theme, compact owl empty state and fixed navigation. Hide Teaching when no available classes, remove class-header Home and hide only global + while in a class. Keep account and Hub status controls.

Implement online teacher creation with existing contracts: announcement composer; Classwork Create menu with assignment, material upload, MCQ quiz and topic dialogs. Read results from canonical SQLite through existing sync; never mirror REST response objects directly onto classroom screens. No API/schema/server edits. No answer-key draft persistence.

Contract limits: learner announcements are currently forbidden by both OpenAPI and real Hub authorization. Standalone Question and Reuse post have no supported operation. These require separate cross-team contract work and are not simulated by disabled/decorative controls.

Verification: service role/ownership/archive/input/receipt tests; UI visibility and cached assignment tests; route capability allow/deny tests; full frontend tests/lint/build; native library tests; production executable against isolated real-Hub teacher fixture for each creation route, sync visibility, offline viewing and posting-disabled behavior. Verify dialog keyboard/focus and narrow layout. Record failures and gaps. Keep functional authoring separate from UI-only freeze changes; draft review until lead schedules it.
