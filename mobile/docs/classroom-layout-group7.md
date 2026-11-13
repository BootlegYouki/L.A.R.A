# Classroom layout, Group 7 — Part of #266

Group 7 restyles existing class management and supported app settings. It adds no routes, destinations, Room fields, APIs or account preferences. Base: `origin/staging` at `16dd2e7`; branch: `mobile/266-group7-class-menu-settings`.

## Entry points and components

- `ClassManagementHost.kt`: existing Manage classes entry, selected-class details, People/Class settings tabs, owner-only edit/archive/co-teacher controls. The promotional heading is removed so saved classes and their controls appear earlier. Details label the existing name and section.
- `ClassroomActionRow.kt`: shared full-width, wrapping action row with a decorative Phosphor icon, title and optional explanation; minimum 56dp. Used for owner controls, Change Hub and Sign out.
- `ClassFormDialog.kt`: existing full-screen, scrollable form reused for rename and adding a co-teacher. It retains Android back/cancel and input scrolling for the keyboard. Its disabled action now explicitly uses Border/TextMuted tokens. The short Add action avoids squeezing the title at 200% text size.
- `SettingsScreen.kt`: section-based Hub/version information, connection icon plus text, Change Hub and separated Sign out. Existing offline/error, loading, no-saved-Hub and queued-work confirmation states remain.
- `RoleNavGraphs.kt`: token-shaped overflow menu and consistent icon/divider treatment for the existing teacher Grades item. Destinations and quiz lockout are unchanged.

## Data flow and behavior

The existing ViewModels, repositories, Room flows, authorization checks and callbacks are unchanged. Class actions remain online-only, owner-only where applicable, and followed by the existing authoritative refresh. Cancel does not submit. Archived classes remain read-only in Manage classes. Co-teachers retain their existing access restrictions.

Settings still reads the saved Hub, preserves queued work on sign-out, and routes Change Hub through the existing sign-out/confirmation logic. No new network calls are made by the visual components. All added visible text is in `values/strings.xml`; colors, Nunito, dimensions and Phosphor assets are bundled tokens/assets.

## Design deviations

- Student 18 supplies the name/section hierarchy within existing teacher class details. There is no new learner About destination, room/subject/description field or Meet link.
- Student 12/33–35 and Teacher 45 show unsupported Hide, Unenroll, separate Archived, Restore and Delete flows. None are added. Existing archived records remain visible in Manage classes.
- Teacher 12 supplies the edit form structure. Only existing name and section fields are editable. Code reset, invitation links, stream preferences, grade settings and Meet settings are omitted.
- Teacher 47 supplies the Settings section hierarchy. Existing Hub details, Change Hub, inline About/version and Sign out are retained. Account editing, language, theme, notifications, cellular sync, beta enrollment and external help/feedback from Teacher 48–58 are omitted.
- Existing Manage classes and People/Class settings navigation stays intact. Rename and co-teacher input use the existing full-screen form component; archive remains a consequence-confirmation dialog and identifies the affected class.
- L.A.R.A. retains its dark-green bars, flat token surfaces, Nunito, Phosphor, 52dp minimum/56dp primary actions and offline explanations instead of Classroom branding.

## Verification and limitations

[Phone screenshots, checks and reproduction](evidence/266-group-7/README.md). Tests use a separate `org.lara.app.group7` app on the USB Infinix X6731 (Android 14), preserving the regular installation. UI/UX Pro Max's local Jetpack Compose accessibility/semantics guidance was applied beneath repository rules and the approved design system.

The user explicitly requested mock-only verification during this session. Real-Hub verification remains deferred; this is not a claim that the Lead's pre-PR real-Hub gate has passed. No commit, push or PR was made. Groups 4, 6 and 8 were not started.
