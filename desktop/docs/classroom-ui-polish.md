# Desktop classroom UI polish — 22 November 2026

The sign-in screen now uses six masked PIN slots with automatic submission on the sixth digit when the account ID is valid. Duplicate submission, disconnected Hub, and rate-limit guards remain in place. The centered sign-in screen includes Change Hub, returning to discovery until the user explicitly selects a Hub.

Classroom navigation uses alphabetical name/section ordering so refreshed snapshots cannot shuffle the sidebar. Opening a video preserves its originating Stream or Classwork tab, and the return button names that tab. Topic filters retain the same width with or without the icon-only topic menu. File selection displays one filename summary while retaining the native accessible file picker. Grades uses a classroom heading and owl empty state with View People when no learners are available; populated grade tables are unchanged.

Class banners use a fixed set of five transparent grayscale WebP owl assets. The class ID determines the variant consistently across reopening, renaming, and devices running this Desktop version. No Hub schema or other team's code changes are required.

Validation: 192 frontend tests, lint, TypeScript/Vite production build, 84 shared tests, and the offline invariant check passed. Native QA checks covered PIN entry and automatic sign-in, Change Hub, attachment picker presentation, Grades-to-People navigation, video return to Classwork, stable sidebar ordering, and transparent owl rendering against the real local QA Hub with synthetic accounts. The mascot regression exercises all five variants. Seven existing QA classes exercised four variants in the native app.

This update has no native Rust changes. Linux/macOS, a new installer, and physical multi-PC testing were not rerun for this UI patch. Unsupported submission and learner-posting additions remain outside this update.
