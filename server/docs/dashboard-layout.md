# Custom overview card positions

The Overview has one responsive grid for resource cards, workload panels, graphs,
warnings and reading statistics. Select **Arrange cards**, drag a named handle
onto another card, then select **Done arranging**. Cards retain their widths and
snap into grid order; positions adapt to the window rather than storing pixels.

Arrow buttons offer an alternative to dragging. A focused handle accepts arrow
keys, Home and End. Focus follows the moved card; Escape finishes arranging and
returns focus to Arrange cards. A live status announces the card and position.

`ui/src/pages/DashboardCards.tsx` owns interaction and preference persistence;
`ui/src/dashboardLayout.ts` validates stored IDs and performs immutable moves.
`pages/Monitoring.tsx` defines stable card IDs and sizes; `WorkloadPanel.tsx`
provides workload cards. Stable React keys preserve graph type and expanded state
when reordering or resetting. History and readings remain session-only.

Only card IDs/order are stored in `lara:overview-layout:v1` on this computer.
Unknown/duplicate IDs are discarded and new cards join the end. Corrupt storage
falls back to default order. Unavailable storage still permits arrangement for
the current session and shows a message. Sign-out clears readings, not layout.
**Reset card positions** removes only this preference and restores default order;
it does not reset warning limits or graph settings.

The reading statistics table uses a fixed column grid, right-aligned numeric
headers and cells, consistent row heights and tabular numerals. Its own scroll
region contains horizontal/vertical overflow while keeping the header visible.

## Verification

- Production TypeScript/Vite build and ten monitoring/layout logic tests pass.
- Edge/Playwright verifies native HTML dragging, cross-section keyboard movement,
  arrow buttons, retained focus and graph type, reset isolation, layout persistence
  after reload/sign-in, and aligned columns/equal row heights at 720?1920px.
- Existing browser checks cover readings, warning windows, unavailable/recovery,
  history/export controls, all graph types, sign-out and session expiry.
- Screenshots use synthetic metrics: `evidence/monitoring/arrange-cards.png` and
  `overview-*.png`. No nonlocal requests or page errors are allowed by the suite.

Physical touch dragging and Linux were not verified. Arrow buttons and keyboard
controls remain available without a drag gesture. No backend/API/schema changes
are needed for arrangement or statistics alignment.
