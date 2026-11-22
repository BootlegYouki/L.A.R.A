# Full-width overview

The Overview shell removes the shared 1,024px container cap through the
`hub-shell-overview` state class and the main container's scoped Tailwind
variant. It fills the window with 24px side gutters, a fixed-width sidebar and
flexible content columns. No window size or maximized preference is forced.

Production UI build and the extended monitoring browser suite passed. At
720/960/1280/1920px, checks confirmed that the main container fills the viewport,
the content reaches the right gutter and no horizontal page overflow occurs.
At 1920px, each normal graph is at least 700px wide. Existing graph options,
warning controls, exports, session resets and failure/recovery checks passed.

See [1920px overview](overview-1920.png). Screenshots use synthetic readings.
