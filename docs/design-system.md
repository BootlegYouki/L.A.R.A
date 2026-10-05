# L.A.R.A. Design System and Component Specification

> Canonical design rules for every L.A.R.A. surface: the Android pupil app (Kotlin, Jetpack Compose), the Teacher Desktop Hub (React + Tailwind), and the Server Web Portal (React + Tailwind).
> Mental model: Google Classroom, with a custom visual brand for Filipino elementary pupils.
> Interactive reference with live previews and copy-ready code for both platforms: `docs/design-system-showcase.html`.

---

## 0. Read This First (Rules for Agents)

These rules apply to every UI change. If a request conflicts with them, follow the rules and mention the conflict in your summary.

### Invariants (never violate)

1. **Solid fills only.** No gradients, radial glows, blurred color blobs, or shimmer animations. Loading states use an opacity pulse on solid blocks.
2. **Tokens only.** Use the named tokens in section 1. Do not invent hex values, shades, or opacity-tinted variants of brand colors. The only permitted transparency is the white 20% overlay on dark green headers and the navy scrim on modals and sheets.
3. **Purple means AI.** `#5145E5` and its variants appear only on Socratic AI surfaces (tutor chat, AI tab, AI buttons, grounding tags). Never use purple for decoration, links, or generic emphasis.
4. **Phosphor Icons only.** Regular weight by default, Fill only for the active navigation item, Bold only inside checkboxes. Do not mix icon families.
5. **52 minimum touch target.** Every interactive control on pupil-facing screens is at least 52dp (Compose) or 52px (web). Quiz options are at least 56. The 40px compact size is allowed on the Teacher Desktop Hub only.
6. **Nunito everywhere.** Bundled locally in the Android app. Never load fonts from a CDN on mobile because the app must work offline.
7. **Never rely on color alone.** Every status needs an icon and a text label as well as a color.
8. **Offline first.** Every screen must have a defined offline state. Show sync status with the standard badges and banners. Never describe the classroom connection as "internet"; say "Hub" or "classroom network".
9. **AI is locked during assessments.** While a quiz is active, the AI tab and AI buttons are disabled with an explanation, and the Hub rejects tutor calls (HTTP 403, code `QUIZ_IN_PROGRESS`; on WebSocket an `EVENT_ERROR` with the same code). The chat composables are never composed while the quiz runs.
10. **Tutor never gives final answers.** It replies with guiding questions and always shows a lesson grounding tag.

### Platform mapping rules

| Concern | Android (Compose) | Web (React + Tailwind) |
| :--- | :--- | :--- |
| Colors | `LaraColors.*` from `ui/theme/Color.kt`. Never write `Color(0xFF...)` in screens. | `lara-*` colors from `tailwind.config.js`. Legacy `bg-[#2E9B4B]` is equivalent but prefer tokens. |
| Typography | `MaterialTheme.typography` mapped to the scale in section 2. Use `sp`. | Utility map in section 2. Use `rem` or Tailwind sizes so user font scaling works. |
| Shapes | `LaraShapes` and `RoundedCornerShape(n.dp)` using the radii in section 3. | `rounded-lg/xl/2xl/full` mapped to the same radii. |
| Elevation | `Modifier.laraShadow(...)` (navy tinted). Do not use default gray shadows. | `shadow-card`, `shadow-card-hover`, `shadow-elevated`, `shadow-toast`, `shadow-modal`. |
| Icons | `com.adamglin:phosphor-icon` (`PhosphorIcons.Regular.*`) | `@phosphor-icons/react` (or `ph-*` classes in static HTML) |
| Tables | Use a list of cards. Do not render tables on phones. | Use a table with horizontal scroll. |
| Dialogs | `AlertDialog` / `ModalBottomSheet` | Radix Dialog (focus trap) / `vaul` drawer on small screens |

---

## 1. Color Tokens

### 1.1 Brand

| Token | Hex | Compose | Tailwind | Role |
| :--- | :--- | :--- | :--- | :--- |
| Primary Green | `#2E9B4B` | `LaraColors.Primary` | `lara-primary` | Primary actions, active tabs, progress fill |
| Dark Green | `#176B36` | `LaraColors.PrimaryDark` | `lara-primary-dark` | Hover and pressed, app bar, class card header |
| Light Green | `#E4F6E8` | `LaraColors.PrimaryLight` | `lara-primary-light` | Selected states, success fills, tag backgrounds |
| Primary Purple | `#5145E5` | `LaraColors.Ai` | `lara-ai` | Socratic AI actions, AI tab, AI FAB |
| Dark Purple | `#4035C9` | `LaraColors.AiDark` | `lara-ai-dark` | AI pressed state, AI text on light purple |
| Light Purple | `#EEEAFE` | `LaraColors.AiLight` | `lara-ai-light` | Tutor chat bubble surface |

### 1.2 Surfaces and text

| Token | Hex | Compose | Tailwind | Role |
| :--- | :--- | :--- | :--- | :--- |
| Canvas | `#F7FBFA` | `LaraColors.Canvas` | `lara-canvas` | Screen and window background |
| Surface | `#FFFFFF` | `LaraColors.Surface` | `white` | Cards, dialogs, sheets, inputs |
| Surface Subtle | `#F2F6F5` | `LaraColors.SurfaceSubtle` | `lara-subtle` | Table headers, segmented control track, disabled fields |
| Border | `#E4EAF0` | `LaraColors.Border` | `lara-border` | Card and input outlines, dividers, progress track |
| Text Primary | `#17213D` | `LaraColors.TextPrimary` | `lara-ink` | Headings, questions, body |
| Text Secondary | `#667085` | `LaraColors.TextSecondary` | `lara-ink-2` | Metadata, timestamps, captions |
| Text Muted | `#98A2B3` | `LaraColors.TextMuted` | `lara-ink-3` | Placeholders and disabled content only |

### 1.3 Status

| Token | Hex | Light fill | Text on light fill | Border | Used for |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Success | `#22A447` | `#E4F6E8` | `#176B36` | `#C4ECCB` | SYNCED, passed, correct |
| Warning | `#F5B82E` | `#FEF0C7` | `#B54708` (banner text `#93370D`) | `#FEDF89` | QUEUED_OFFLINE, due soon, timer under 5 minutes |
| Danger | `#EF5350` | `#FEE4E2` | `#B42318` for text, `#EF5350` for icons | `#FECDCA` | Missing, errors, lockout, timer under 1 minute |
| Info | `#3B82F6` | `#E0F2FE` | `#0369A1` | `#BAE6FD` | DepEd categories, neutral information |

Additional fixed values used by AI surfaces: border `#D6CCFC`, bubble text `#2D237A`, chat background `#FAF9FE`.

Contrast notes: Text Muted is never used for essential content. White on Primary Green is permitted for bold text of 14px or larger.

---

## 2. Typography (Nunito)

| Style | Size / line height | Weight | Compose | Tailwind | Use |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Display | 48 / 60 | ExtraBold 800 | `displayLarge` | `text-5xl leading-[60px] font-extrabold` | Welcome and onboarding only |
| Heading 1 | 32 / 40 | ExtraBold 800 | `headlineLarge` | `text-[32px] leading-10 font-extrabold` | Subject and class headers |
| Heading 2 | 24 / 32 | Bold 700 | `headlineMedium` | `text-2xl leading-8 font-bold` | Section titles ("Mga Pagsusulit", "Stream") |
| Heading 3 | 20 / 28 | Bold 700 | `titleMedium` | `text-xl leading-7 font-bold` | Quiz questions, card titles |
| Body | 16 / 24 | Medium 500 | `bodyLarge` | `text-base leading-6 font-medium` | Announcements, instructions, chat |
| Label | 14 / 20 | ExtraBold 800 | `labelLarge` | `text-sm leading-5 font-extrabold` | Buttons, form labels, nav items |
| Caption | 12 / 16 | Bold 700 | `bodySmall` | `text-xs leading-4 font-bold` | Timestamps, badges, metadata |

Rules:
- Minimum body size on pupil screens is 16.
- Use at most three sizes on one screen.
- Use weight, not size, to create hierarchy inside cards.
- Badges and short labels may be uppercase. Sentences never are.
- Respect system font scaling on both platforms.

---

## 3. Spacing, Radii, Elevation, Motion

### 3.1 Spacing (4 base grid)

`4, 8, 12, 16, 20, 24, 32, 48`. Every padding, gap and margin is one of these values. Mobile screen gutter is 16. Keep at least 8 between adjacent touch targets.

### 3.2 Touch targets

| Element | Minimum |
| :--- | :--- |
| Buttons, inputs, selects, list rows, nav items, selection rows | 52 |
| Primary action ("Start Quiz") and AI FAB | 56 |
| Quiz option cards | 56 |
| Attendance toggle buttons | 44 square, minimum 8 apart (teacher tablet or desktop only) |
| Compact button (Teacher Hub desktop only) | 40 |

### 3.3 Radii

| Name | Value | Used on |
| :--- | :--- | :--- |
| Small | 8 | Badges, tags, avatar-adjacent chips |
| Medium | 12 | Buttons, inputs, quiz options, banners |
| Large | 16 | Cards, toasts, stat cards |
| Extra large | 24 | Dialogs and bottom sheet top corners |
| Pill | 9999 | Timer pill, filter chips, search bar, FAB, status pills |

### 3.4 Shadows (navy tinted, two layers)

Shadows use `rgba(23, 33, 61, a)` so they never look gray or muddy.

| Token | CSS | Use |
| :--- | :--- | :--- |
| `--shadow-card` | `0 1px 3px 0 rgba(23,33,61,.05), 0 1px 2px -1px rgba(23,33,61,.05)` | Resting cards, buttons, inputs |
| `--shadow-card-hover` | `0 4px 6px -1px rgba(23,33,61,.07), 0 2px 4px -2px rgba(23,33,61,.05)` | Hover and active cards |
| `--shadow-elevated` | `0 10px 15px -3px rgba(23,33,61,.08), 0 4px 6px -4px rgba(23,33,61,.04)` | Quiz card, AI chat panel, phone mockups |
| `--shadow-toast` | `0 12px 28px -4px rgba(23,33,61,.12), 0 4px 8px -2px rgba(23,33,61,.06)` | Toasts |
| `--shadow-modal` | `0 24px 48px -12px rgba(23,33,61,.22), 0 8px 16px -4px rgba(23,33,61,.08)` | Dialogs and bottom sheets |

Compose has a single blur, so use `Modifier.laraShadow(elevation, shape)` which sets `ambientColor = Color(0x1417213D)` and `spotColor = Color(0x2017213D)`. Do not stack shadows on nested cards.

### 3.5 Motion

- Press feedback: scale to 0.98, 150ms.
- Toast and dialog enter: 200ms ease-out, 10px upward slide with fade.
- Bottom sheet: 250ms ease.
- Skeleton: opacity pulse between 1 and 0.45, 1.4s on web, 700ms reversing on Android.
- Honor reduced motion: disable the pulse and slide animations when the system setting is on.

---

## 4. Iconography (Phosphor)

- Sizes: 20 inside buttons, 24 in navigation, 22 in banners and list leading boxes, 28 or larger in empty states.
- Weight: Regular by default. Fill only for the active nav item and the selected quiz option check. Bold only for the check mark inside checkboxes.
- Decorative icons are hidden from assistive technology (`contentDescription = null`, `aria-hidden`). Icon-only buttons need an accessible name.

Standard glyphs:

| Area | Icons |
| :--- | :--- |
| Navigation | `list`, `house`, `chat-circle-dots` (Stream), `notebook` (Classwork), `timer` (Quizzes), `sparkle` (AI Tutor), `users`, `gear`, `bell`, `arrow-left` |
| Actions | `check`, `check-circle`, `circle`, `x`, `plus`, `trash`, `pencil-simple`, `download-simple`, `upload-simple`, `paperclip`, `magnifying-glass`, `arrow-right`, `paper-plane-tilt`, `arrows-clockwise` |
| Status | `wifi-slash`, `cloud-slash` (queued), `broadcast` (Hub), `lock`, `lock-key`, `warning-circle`, `info` |
| Quiz and AI | `timer`, `book-open`, `sparkle`, `camera`, `clipboard-text`, `calendar-check`, `usb` (export), `trophy` |

---

## 5. Component Specifications

Each component below has live previews and copy-ready React and Kotlin code in the showcase. This section is the rule summary an agent needs before building one. Reuse an existing component before creating a new one.

### 5.1 Buttons
- Variants: Primary (green), AI (purple), Secondary (white, 2px border), Ghost (transparent, dark green text), Destructive (light red fill, red text and border).
- One Primary per view. Label with a verb plus object ("Start quiz").
- Disabled: `#E4EAF0` fill with Muted text, and nearby text must explain why. Loading: spinner replaces icon, button stays in place.
- Icon-only buttons are 52 by 52 (40 by 40 compact) and need an accessible name.
- Tailwind core: `min-h-[52px] px-[22px] rounded-xl font-extrabold shadow-card active:scale-[0.98]`.

### 5.2 Inputs
- 52 minimum height, 2px border, radius 12, label always visible above the field, never placeholder-only.
- Focus border Primary Green. Error border Danger with message below (icon plus text). Disabled uses Surface Subtle.
- LRN, PIN and class code use the numeric keyboard (`inputMode="numeric"` / `KeyboardType.NumberPassword`).
- PIN boxes are 52 by 60 with auto-advance and backspace-to-previous.
- Selects use the native element on web and `ExposedDropdownMenuBox` on Android, for 4 to 12 options. Fewer than 4 options use radio rows.

### 5.3 Selection controls
- Checkbox, radio and switch render as full-width 52px rows where the entire row is the hit area.
- Switch is for settings that apply immediately. Do not use it in a form with a Save button.
- Roles: `checkbox`, `radio`, `switch` with `aria-checked`.

### 5.4 Segmented control, tabs, filter chips
- Segmented control has at most four items, 44 minimum item height, active item is white with Dark Green text.
- Filter chips are pill shaped, 40 high, selected state is Light Green fill, Primary Green border, check icon.

### 5.5 Status badges and Hub beacon
- Variants: Synced (green), Queued (amber), DepEd category (blue), AI (purple), Missing (red), Draft (neutral).
- Always include an icon and a label of 22 characters or fewer, uppercase.
- Hub beacon: white pill with a 10px dot. Green dot connected, amber dot searching, muted dot offline. Always followed by text.
- Tailwind core: `px-3 py-1 rounded-full text-xs font-extrabold border`.

### 5.6 Alert banners
- Variants: Warning (offline), Success (Hub connected), Danger (exam lockout), Info.
- Format: bold title, colon, one sentence. Use `role="alert"` for lockout.
- One banner at a time, ordered by severity. Do not make a banner dismissible while its condition still applies.

### 5.7 Toasts
- 16 radius, white, border, `shadow-toast`, 28px circular icon container, title (14 ExtraBold) and one-line description (12 Secondary).
- Auto-dismiss after 4 seconds with a visible close button. Maximum three stacked. Bottom right on desktop, above the bottom nav on mobile.
- Tones: Success (green), Warning (amber, `cloud-slash`), Danger (red, `lock`), AI (purple, `sparkle`).
- Use a dialog, not a toast, for errors that block progress.

### 5.8 Dialog and bottom sheet
- Dialog: radius 24, `shadow-modal`, navy scrim at 50%. Title states the action, body states the consequence. Primary action on the right. Trap focus, close on Escape and Android back.
- Bottom sheet is mobile only, at most four actions, drag handle, tap scrim to dismiss, visible Cancel button. Use a dialog on desktop.

### 5.9 Progress
- Linear: 12px tall pill. Green by default, purple only for AI sessions, amber for time running out.
- Score ring: 96px, 10px stroke. Green at 75% or above, amber from 60% to 74%, red below 60%. Always show the number and text, never color alone.
- Expose value, minimum and maximum. Announce completion rather than every percent change.

### 5.10 Skeleton and empty states
- Skeleton: solid `#E4EAF0` blocks matching the final layout, opacity pulse, `role="status"` container. Replace with an empty state if loading completes with no data.
- Empty state: 80px Light Green circle with a 40px icon, Heading 3 title, one explanatory sentence, one optional secondary action. Do not blame the user.

### 5.11 Avatars, lists, stat cards, tables
- Avatar: initials, sizes 32, 40, 56, tone derived from the name so it stays stable. Purple tone is reserved for the AI tutor. No photos of pupils.
- List item: minimum 64 high, 44px leading icon box with radius 12, two text lines truncated with ellipsis, one trailing element.
- Stat card: label (12 uppercase), value (30 Black), delta line with an arrow icon and text.
- Gradebook: table on desktop with `scope="col"` headers and a scroll wrapper. Show the raw score and the transmuted grade with the Passed or Failed label. Failed or missing items must not rely on red alone. On Android use a card list.

### 5.12 Classroom UI
- **Classroom card:** Dark Green header (`#176B36`, white text, class code in a white 20% tag), white body with teacher avatar, cache progress bar, pill "Open class" button.
- **Stream post:** white card, radius 16, avatar, timestamp with source ("Classroom Wi-Fi Hub"), sync badge, flat comments (one level), comment input pill. Show the sync state on every post created offline.
- **Assignment card:** icon box, title, meta line (due date, points, duration), status badge, offline-capable primary action. Overdue meta line is red and states the number of days. Do not show Start after the due date unless late work is allowed.
- **Attendance row:** avatar, name, three 44px P/A/L buttons acting as a radio group, Present is green, Absent red, Late amber. Do not pre-mark pupils unless the teacher chooses to.

### 5.13 Navigation
- **Mobile top app bar:** Dark Green, white title (Black), 40px icon buttons.
- **Mobile bottom nav:** 68 high, four destinations (Stream, Classwork, Quizzes, AI Tutor), Fill icon and Light Green indicator on the active item, AI Tutor uses purple. Disable the AI tab during an assessment and say why.
- **Desktop sidebar:** 260 wide, 12 radius items, active item Light Green with Dark Green text, red count dots for pending items.
- Mark the current item with `aria-current="page"`.

### 5.14 Paperless quiz
- Quiz card: white, 2px border, radius 16, `shadow-elevated`. Header has the DepEd badge and timer pill. Progress is shown as segmented dots (done Primary Green, current Dark Green, pending Border) and text "Question 3 of 5".
- Options: 56 minimum height, radius 12, 2px border. Selected is Light Green fill with Primary Green border and a filled check icon. Group role is radio.
- Timer pill: Normal over 5 minutes is Light Green, Warning under 5 minutes is amber, Critical under 1 minute is red. Announce at 5, 1 and 0 minutes only.
- Time is synchronized from the Hub clock, never the device clock. Autosave each answer locally on selection. Warn before leaving. Set `FLAG_SECURE` on Android.
- Results: score ring, encouraging copy regardless of score, per-item review with Correct or Review badge, link each missed item to its lesson page. Never compare a pupil with classmates and never use a full-screen red state.

### 5.15 Socratic AI tutor
- Panel: 2px `#D6CCFC` border, radius 16, `shadow-elevated`. Header is solid Primary Purple with sparkle icon and language tag (FILIPINO or ENGLISH).
- Pupil message: right aligned, white background, `#E4EAF0` border, Primary Text, radius 16 with 4px bottom-right corner.
- Tutor message: left aligned, Light Purple `#EEEAFE`, `#D6CCFC` border, text `#2D237A`, 4px bottom-left corner.
- Grounding tag required on every tutor message: pill, white fill, `#D6CCFC` border, 11px ExtraBold uppercase purple, book-open icon, "BASED ON LESSON 1 (PAGE 2)".
- Typing indicator is three pulsing dots with a text label for assistive technology.
- FAB: 60px circle, Primary Purple, white sparkle, `fixed bottom-6 right-6` on web, 56 minimum on Android. Hidden or disabled during assessments.
- Announce new tutor messages with `aria-live="polite"`.

### 5.16 Camera overlay and Hub sync panel
- Camera: dark navy viewfinder, dashed Primary Green 2px frame with "FRAME NOTEBOOK PAGE HERE", white shutter with Light Green ring, labelled controls. Compress to JPEG under 800KB before upload. Show retake as the main secondary action.
- Hub sync panel: Hub name and IP, beacon, three counters (Queued, Synced today, Modules), progress while syncing, "Sync now" button (kept available even with auto-sync on), last successful sync time. Never discard queued items when the Hub is unreachable.

---

## 6. Accessibility Requirements

- Minimum touch targets as in section 3.2.
- Contrast: Text Primary on Canvas or Surface for all essential copy. Text Secondary for supporting copy of 12px or larger. Text Muted only for placeholders and disabled content.
- Every control exposes name, role and state: `role="switch|checkbox|radio|tab|progressbar|timer|status|alert"`, `aria-checked`, `aria-selected`, `aria-pressed`, `aria-current`.
- Visible focus ring on web: 3px Primary Purple outline with 2px offset (keyboard only). Android uses the system focus indication.
- Do not rely on color, shadow or position alone to convey state.
- Support Escape (web) and the back gesture (Android) to close overlays, and return focus to the trigger.

---

## 7. Content and Tone

- Warm, short, encouraging. Address pupils directly. Never blame, never shame, never compare with classmates.
- Bilingual: English and Filipino. Section titles may use Filipino ("Mga Pagsusulit", "Pagsusulit #2"). All user-facing strings live in resource files, never hard-coded in components.
- Dates use the form "Fri, Oct 9". Times use 12 hour with AM or PM. Numbers for scores show "9 / 10" with spaces around the slash.
- Do not use emojis in the product UI.
- Offline wording: "Saved on this device", "Queued for sync", "Connected to Teacher Hub".

---

## 8. Definition of Done (Checklist for Every UI Change)

- [ ] Uses only tokens from section 1, with no gradients and no new hex values
- [ ] Purple appears only on AI surfaces
- [ ] All icons are Phosphor and sized per section 4
- [ ] Touch targets meet section 3.2
- [ ] Radii, shadows and spacing come from section 3
- [ ] Status is shown with icon plus text, not color alone
- [ ] Offline and loading states exist (badge or banner, skeleton, empty state)
- [ ] Assessment lock rules respected (no AI during an active quiz)
- [ ] Accessible names, roles and states set; focus order checked
- [ ] Strings localized (English and Filipino) with no hard-coded text
- [ ] Matches the corresponding entry in `docs/design-system-showcase.html`. If you add or change a component, update the showcase and this file in the same change

---

## 9. Google Classroom Reference (Layout Mental Model)

Developers may design screen layouts freely, but every screen must map to a Google Classroom structure so pupils and DepEd teachers recognise it. Use the L.A.R.A tokens and components from sections 1 to 5 for all visuals.

| Google Classroom screen | L.A.R.A screen | Platform notes | What L.A.R.A adds |
| :--- | :--- | :--- | :--- |
| Class cards on Home | Classroom card grid (5.12) | Mobile: one column of cards. Desktop: card grid | Hub status beacon, cache progress bar, offline badge |
| Stream tab | Stream (announcements, comments) | Bottom nav item 1 | Sync badge on every post, "Classroom Wi-Fi Hub" source label |
| Classwork tab | Classwork (handouts, videos, assignments, quizzes) | Bottom nav item 2 | Save-for-Home download state, offline-capable primary action |
| People tab | Roster (teacher only) | Mobile: cards. Desktop: table | PENDING / ACTIVE / REJECTED approval pills with Accept / Decline |
| Assignment detail and "Turn in" | Assignment detail with camera capture | Camera overlay (5.16) | Photo queued as `QUEUED_FOR_SYNC` when offline |
| Quiz (Google Forms) | Paperless quiz (5.14) | Full screen, no AI | Shared countdown, auto-submit, auto-grade receipt |
| Grades tab | Grades and DepEd class record | Teacher: table, export to USB | DepEd categories: Written Work, Performance Task, Quarterly Assessment |
| (none) | Socratic AI tutor (5.15) | Bottom nav item 4 on mobile, drawer on desktop | Purple surfaces only, grounding tag, locked during quizzes |

Rules of use:
1. Do not copy Google visuals, colors or logos. Take only the information structure.
2. Every screen needs an offline state, an empty state and a loading skeleton (5.10).
3. Teacher features exist on both Desktop and Mobile. Desktop is the full authoring surface; Mobile covers the on-the-go set (approve, post, start quiz, monitor, grade a photo) and may add authoring when the team has capacity.

