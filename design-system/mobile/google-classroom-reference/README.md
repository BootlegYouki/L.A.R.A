# Google Classroom mobile screens (layout reference)

Screenshots of the real Google Classroom Android app, one folder per side: `teacher/` (63 screens) and `student/` (35 screens). The number in each file name is the order the screens were taken in, so they read as a walkthrough.

Use them as **inspiration** for the layout of the L.A.R.A mobile app: where things sit on each screen, which tab or menu holds which action, and how a flow moves from one screen to the next. Users already know Classroom, so matching its structure means they find each thing where they expect it (`design-system.md` section 9).

They are not a spec to copy pixel for pixel:
- Colors, type, icons and spacing come from L.A.R.A's tokens (`design-system/mobile/`), never from these images.
- Leave out what L.A.R.A doesn't have: Google sign-in and accounts, Meet links, Drive, Calendar, email notifications, Google feedback, invite links.
- The rules still win: English only, 52dp targets (56dp primary actions and quiz options), offline, empty and loading states on every screen, and no personal data shown that the contract doesn't allow.

When your screen differs from these on purpose, say what and why in the PR.
