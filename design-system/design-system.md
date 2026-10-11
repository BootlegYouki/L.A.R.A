# L.A.R.A. Design System and Component Specification

> Canonical design rules for every L.A.R.A. surface: the Android learner app (Kotlin, Jetpack Compose), the Teacher Desktop Hub (React + Tailwind), and the Server Web Portal (React + Tailwind).
> Layout: Google Classroom's screens, copied closely (section 9). Look and voice: a friendly L.A.R.A. brand for Filipino learners (Grades 1 to 12) (sections 1 to 5 and 7).
> Interactive reference with live previews and copy-ready code for both platforms: `design-system/showcase.html`.

> **How to read this document: a guide, not a pixel spec.** The tokens, the invariants in section 0 and the accessibility rules are fixed. The screens in section 9, the sizes in them and the prototype (`prototype/index.html`) show the intended direction, a strong clue, not a drawing to copy blindly. Real development will turn up things this design could not know: real data, a slow phone, a long class name, a Hub limit, a layout that does not fit. When that happens, build what works and keep the spirit (Classroom's places, L.A.R.A.'s look and voice). Never bend an invariant (offline first, privacy, quiz lockout, contrast, target sizes, tokens only). Say what you changed and why in the PR, so the Lead can update this document. A silent deviation is the only wrong choice. The prototype's code is a demo: do not copy it into an app.

---

## 0. Read This First (Rules for Agents)

These rules apply to every UI change. If a request conflicts with them, follow the rules and mention the conflict in your summary.

### Invariants (never violate)

1. **Solid fills only.** No gradients, radial glows, blurred color blobs, or shimmer animations. Loading states use an opacity pulse on solid blocks.
2. **Tokens only.** Use the named tokens in section 1. Do not invent hex values, shades, or opacity-tinted variants of brand colors. The only permitted transparency is the white 20% overlay on dark green headers and the navy scrim on modals and sheets.
3. **Purple means AI.** `#5145E5` and its variants appear only on Socratic AI surfaces (tutor chat, AI tab, AI buttons, grounding tags). Never use purple for decoration, links, or generic emphasis.
4. **Phosphor Icons only.** Regular weight by default, Fill only for the active navigation item, Bold only inside checkboxes. Do not mix icon families.
5. **Target sizes follow the device.** On phones every interactive control is at least 52dp, and quiz options at least 56dp. On desktop (mouse and keyboard) buttons and icon buttons are 40px, and tabs and drawer items 48px, the same as Google Classroom, with at least 8px between neighbours. Quiz options stay 56 on every device.
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
| Info | `#3B82F6` | `#E0F2FE` | `#0369A1` | `#BAE6FD` | neutral information |

Additional fixed values used by AI surfaces: border `#D6CCFC`, bubble text `#2D237A`, chat background `#FAF9FE`.

Contrast notes: Text Muted is never used for essential content. White on Primary Green is permitted for bold text of 14px or larger.

---

## 2. Typography (Nunito)

| Style | Size / line height | Weight | Compose | Tailwind | Use |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Display | 40 / 48 | Black 900 | `displayLarge` | `text-[40px] leading-[48px] font-black` | Welcome and onboarding only |
| Heading 1 | 28 / 36 | ExtraBold 800 | `headlineLarge` | `text-[28px] leading-9 font-extrabold` | Subject and class headers |
| Heading 2 | 22 / 30 | Bold 700 | `headlineMedium` | `text-[22px] leading-[30px] font-bold` | Section titles ("Mga Pagsusulit", "Stream") |
| Title | 18 / 26 | ExtraBold 800 | `titleMedium` | `text-lg leading-[26px] font-extrabold` | Quiz questions, card titles |
| Body | 16 / 24 | Medium 500 | `bodyLarge` | `text-base leading-6 font-medium` | Announcements, instructions, chat |
| Label | 14 / 20 | ExtraBold 800 | `labelLarge` | `text-sm leading-5 font-extrabold` | Buttons, form labels, nav items |
| Caption | 12 / 16 | SemiBold 600 | `bodySmall` | `text-xs leading-4 font-semibold` | Timestamps, badges, metadata |

The type scale matches `design-system/showcase.html` and `design-system/mobile/Type.kt`. In Compose, styles the scale does not name (for example `titleLarge`) are still Nunito, so no Material component falls back to the system font.

Rules:
- Minimum body size on learner screens is 16.
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
- Icon-only buttons are 52 by 52 on phones and 40 by 40 on desktop, and need an accessible name.
- Tailwind core, phone: `min-h-[52px] px-[22px] rounded-xl font-extrabold shadow-card active:scale-[0.98]`. Desktop: `min-h-control px-6 rounded-full font-extrabold` (`control` is 40px in `desktop/tailwind.theme.ts`).

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
- Variants: Synced (green), Queued (amber), AI (purple), Missing (red), Draft (neutral).
- Always include an icon and a label of 22 characters or fewer, displayed in uppercase. Store the label in sentence case in the string file and uppercase it in the UI (`text-transform: uppercase`, `Text(label.uppercase())`), so a screen reader reads a word and not letters.
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
- Empty state: 80px Light Green circle with a 40px icon, Title-style heading, one explanatory sentence, one optional secondary action. Do not blame the user.

### 5.11 Avatars, lists, stat cards, tables
- Avatar: initials, sizes 32, 40, 56, tone derived from the name so it stays stable. Purple tone is reserved for the AI tutor. No photos of learners.
- List item: minimum 64 high, 44px leading icon box with radius 12, two text lines truncated with ellipsis, one trailing element.
- Stat card: label (12 uppercase), value (30 Black), delta line with an arrow icon and text.
- Gradebook: table on desktop with `scope="col"` headers and a scroll wrapper. Show points earned out of the maximum. Missing items must not rely on red alone. On Android use a card list.

### 5.12 Classroom UI
Screen layouts are in section 9. These are the parts they are built from.
- **Class card:** white, 1 px Border, radius 16, `shadow-card`, 20 padding. A 56 px Dark Green tile with the subject icon, the class name (24 Black), the section, a status pill (Amber or Light Green, icon plus words), then a footer with the teacher or the learner count and "Open". The whole card is the link; hover turns the border Primary Green. Never shows the class code. Layout in 9.3.
- **Class banner:** Dark Green, 240 high, radius 12, class name (36 Black), section (22 Bold, Light Green), a 120 px subject icon in a white 18% circle, a 40 px info button. No picture, no gradient.
- **Class tabs:** 48 high, 14 ExtraBold, selected is Dark Green text with a 4 px Primary Green bar rounded on top, 1 px Border under the bar. Learners: Stream, Classwork, People. Teachers add Grades.
- **Side card (Upcoming, Class code):** white, 1 px Border, radius 12, 16 padding, title 16 Black. The class code is shown large (22 Black, 1 px letter spacing) as `K7M-4QX` and only to teachers.
- **New announcement (teacher):** a tonal button (Light Green, Dark Green text, 40 high pill) beside a text Repost button. It opens the announcement dialog (9.4). Learners do not post.
- **Stream post:** Surface Subtle fill, radius 12, no border. Avatar, name, date, kebab; 14 Bold body; one level of comments; a text Add comment button that opens a 40 high pill reply box. Comments show Saved or Queued for sync. New classwork appears as a 72 high one-line row with a 40 px type circle.
- **Topic header and work row:** topic header is 64 high, Heading 2 (22 Black), item count, caret, 2 px Primary Green rule. A work row is at least 72 high with a 44 px type tile (radius 12): Light Green for homework, Light Blue for handouts and videos, Light Amber for quizzes. Title 16 ExtraBold, a 13 Bold meta line, then due date, status badge and Save for home (learner) or kebab (teacher). An opened row sits on Canvas.
- **Person row (teacher):** at least 68 high, 40 px initials avatar, name (16 ExtraBold), status badge, Accept and Decline (small) for `PENDING_APPROVAL`.
- **Assignment card:** icon box, title, meta line (due date, points, duration), status badge, offline-capable primary action. Overdue meta line is red for teachers and neutral for learners, and states the number of days. Do not show Start after the due date unless late work is allowed.
- **Your work card:** Surface Subtle, 1 px Border, radius 24, 20 padding. Header with title (22 Black) and a status badge. Photo slot (dashed 2 px Primary Green, radius 12, 150 high) or the filled photo, then the actions for the current state (9.7), then a helper line in Secondary text. Private comments card below it.
- **Attendance row:** avatar, name, three 44px P/A/L buttons acting as a radio group, Present is green, Absent red, Late amber. Do not pre-mark learners unless the teacher chooses to.

### 5.13 Navigation
- **Desktop top bar:** 64 high, Canvas, no border. Menu (52), L.A.R.A. badge, class crumb (name over section), Hub beacon, Join class or Create class (52), account avatar (52).
- **Desktop drawer:** 260 wide, items 52 high with a full pill shape. Selected is Light Green with Dark Green text and a Fill icon. Red count dots for pending items. Classes show a 32 px initial circle, name and section. Teachers get a **Class tools** group (Attendance, USB export) under their classes. Folds behind the menu button below 860 px.
- **Mobile top app bar:** Dark Green, 64 high, white title (Black) over the section, 52px icon buttons.
- **Mobile bottom nav:** 68 high, four destinations (Stream, Classwork, Quizzes, AI Tutor), Fill icon and Light Green indicator on the active item, AI Tutor uses purple. Disable the AI tab during an assessment and say why.
- Mark the current item with `aria-current="page"`.

### 5.14 Paperless quiz
- Quiz card: white, 2px border, radius 16, `shadow-elevated`. Header has the timer pill. Progress is shown as segmented dots (done Primary Green, current Dark Green, pending Border) and text "Question 3 of 5".
- Options: 56 minimum height, radius 12, 2px border. Selected is Light Green fill with Primary Green border and a filled check icon. Group role is radio.
- Timer pill: Normal over 5 minutes is Light Green, Warning at 5 minutes or less is amber, Critical at 2 minutes or less is red (`rules/quiz-and-anti-cheat.md` 2.3). Announce at 5, 2 and 0 minutes only. The desktop quiz screens are in 9.14.
- Time is synchronized from the Hub clock, never the device clock. Autosave each answer locally on selection. Warn before leaving. Set `FLAG_SECURE` on Android.
- Results: score ring, encouraging copy regardless of score, per-item review with Correct or Review badge, link each missed item to its lesson page. Never compare a learner with classmates and never use a full-screen red state.

### 5.15 Socratic AI tutor
- Panel: 2px `#D6CCFC` border, radius 16, `shadow-elevated`. Header is solid Primary Purple with sparkle icon and language tag (FILIPINO or ENGLISH).
- Learner message: right aligned, white background, `#E4EAF0` border, Primary Text, radius 16 with 4px bottom-right corner.
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
- Visible focus ring on web: 3px Dark Green outline with 2px offset (keyboard only), white on Dark Green surfaces. Purple is never used for focus, because purple means AI. Android uses the system focus indication.
- Do not rely on color, shadow or position alone to convey state.
- Support Escape (web) and the back gesture (Android) to close overlays, and return focus to the trigger.

---

## 7. Content and Tone

### 7.1 Voice
- Warm, short, encouraging, like a kind teacher at the door. Address learners directly, by first name when we know it.
- **Say what happens next.** "Take a photo first, then Turn in is ready." beats "Disabled".
- **Praise first.** Feedback and results start with what went well. Never compare a learner with classmates and never shame.
- **Learners never see "Late", "Missing" or "Failed".** They see "Not turned in yet" and "Turned in after the due date" only in a calm preview line. Teachers see exact statuses.
- **Empty is an invitation, not an error.** Say what will appear and that it is saved on this device. "Nothing due yet. Take a break!"
- **Errors say what to do.** "That code does not match a class. Check it with your teacher." No blame, no apologies, no codes.
- **Offline is calm.** "Saved on this device." "It will turn in by itself when you are connected to the Hub." Never say "internet".
- Greet by time of day on Home ("Magandang umaga, Ana!").
- Bilingual: English and Filipino, Taglish allowed in the tutor. Section titles may use Filipino ("Mga Pagsusulit", "Pagsusulit #2"). All user-facing strings live in resource files, never hard-coded in components.
- Dates use the form "Fri, Oct 9". Times use 12 hour with AM or PM. Numbers for scores show "9 / 10" with spaces around the slash.
- Do not use emojis in the product UI.

### 7.2 Classroom words in L.A.R.A.
Keep Classroom's English words where people already know them. Change only the ones that can sting a child. The Filipino column is a suggestion for the Filipino string files and must be confirmed with a Filipino-speaking teacher before release.

| Classroom says | L.A.R.A. says (English) | Filipino (suggested) |
| :--- | :--- | :--- |
| Stream | Stream | Mga anunsyo |
| Classwork | Classwork | Mga gawain |
| Quizzes | Quizzes | Mga pagsusulit |
| People | People (teacher only) | Mga kasapi |
| Grades | Grades | Mga marka |
| To-do | To-do | Mga gagawin |
| Due soon | Due soon | Malapit nang ipasa |
| Upcoming | Upcoming | Paparating |
| Join class | Join class | Sumali sa klase |
| Class code | Class code | Code ng klase |
| Your work | Your work | Iyong gawa |
| Private comments | Private comments | Pribadong komento |
| Assigned | To do (learner), Assigned (teacher) | Gagawin |
| Turn in | Turn in | Ipasa |
| Turned in | Turned in | Naipasa na |
| Missing | Not turned in yet (learner), Missing (teacher) | Hindi pa naipasa |
| Turned in late | Turned in after the due date (preview line only) | Naipasa pagkatapos ng due date |
| Graded | Graded | May marka na |
| Unsubmit | Take it back to edit | Bawiin para baguhin |
| (none) | Take a photo | Kunan ng litrato |
| (none) | Saved on this device | Naka-save sa device na ito |
| (none) | Queued for sync | Naghihintay ma-sync |
| (none) | Waiting for approval | Naghihintay ng pag-apruba |

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
- [ ] A screen keeps the structure and positions in section 9 and matches its entry in the Classroom Screens group of `design-system/showcase.html`
- [ ] Words follow section 7: warm, no shaming statuses for learners, a kind empty state, a next step in every helper line
- [ ] Matches the corresponding entry in `design-system/showcase.html`. If you add or change a component, update the showcase and this file in the same change

---

## 9. Google Classroom Layout (copy the structure, keep our voice)

Teachers and learners already know Google Classroom. Every L.A.R.A. screen copies the **structure** of the matching Classroom screen, so people find each thing where they expect it, and then says it in L.A.R.A.'s friendly voice (section 7) using L.A.R.A.'s look (sections 1 to 5). Layout is not free design: use the anatomies below. Live, interactive versions are in the **Classroom Screens** group of `design-system/showcase.html`, and as a full-window app in `prototype/index.html`, generated from the showcase by `python3 scripts/build_prototype.py` (a test fails when it is stale). To open it, serve the repo root with any static server, for example `python3 -m http.server`, and visit `/prototype/index.html`. **Home, Stream and the quiz on desktop are the remade screens** (showcase entry "Remake: Home, Stream and Quiz (desktop)"); the other showcase screens are the first pass and are replaced one by one.

Measurements marked "Classroom" were read from the computed styles of the current Classroom web app on 2026-10-11, in a 1440 px wide window, for both a learner and a teacher account: Home, Stream, Classwork with its Create menu, the assignment form, the material form, student work with private comments, People, Grades, class settings, the learner's assignment page and To-do. The teacher screens other than Stream are measured but not yet remade (9.15 lists what was found). **Phone layouts were not measured:** a browser shows only Classroom's web layout, so phone screens are derived from these at a narrow width and are supplied separately. Do not copy its colors, fonts, logo or pictures.

**Stage 2 (2026-10-11): do not look like a Classroom clone.** Keep every element where Classroom puts it, but give it L.A.R.A.'s own look: the class card, tonal buttons, the solid Dark Green banner, the Hub pill, status pills, no greeting. Where this section says "L.A.R.A." next to a Classroom measurement, the L.A.R.A. value wins.

### 9.1 Rules of use

1. **Copy structure, not visuals.** No Google colors, fonts, logos or wordmark. No theme pictures or photos in banners: use Dark Green with a subject icon.
2. **Same places.** Top bar, class drawer, class tabs, banner, Upcoming card, feed, topics, rows and the Your work card sit where Classroom puts them. Do not move them to be clever.
3. **Friendlier by default.** Rounder corners, 52 px targets on phones (desktop copies Classroom's 40), warm words, an encouraging line on every empty state, no shaming statuses.
4. **Add, never replace.** L.A.R.A. additions (Hub beacon, sync and Saved badges, Quizzes, Ask L.A.R.A., camera, class code approval) go in the places named below. They never push a Classroom element out.
5. **Roles.** In the remade shell learners see Stream, Classwork and People; teachers also see Grades and the Class settings gear. Learners never see other learners (no roster, no names in comments beyond a first name, never an LRN), so a learner's People tab can list teachers only (open question, 9.16). The first pass's Quizzes tab and Ask L.A.R.A. tab are not in the remade shell: where quizzes and the AI tutor are reached is decided with the Classwork slice and the AI entry decision (9.16).
6. **Every screen** has an offline state, an empty state and a loading skeleton (5.10). Nothing blocks when the Hub is unreachable.
7. **Teacher features live on Desktop and Mobile, the same set on both** (`AGENTS.md` section 1.4).
8. **Sizes on desktop follow Classroom** (3.2): 40 px buttons and icon buttons, 48 px tabs and drawer items. Phones keep 52.

### 9.2 App shell (desktop)

```
+----------------------------------------------------------------------+
| [menu] [L.A.R.A.] > Science 4              [Hub pill] [+] [avatar]   |  top bar, 64
|                    Grade 4 - Rizal                                   |
+--------------+-------------------------------------------------------+
| Home         | Stream  Classwork  People  [Grades]              [gear]|  class tabs, 48
| Enrolled  ^  |-------------------------------------------------------|
|  To-do       |                                                       |
|  Science 4   |   content sheet: white, top-left radius 24            |
|  Math 4      |   content is centred, at most 1240 wide               |
| Archived     |                                                       |
| Settings     |                                                       |
+--------------+-------------------------------------------------------+
   drawer, 260                      [Grades] and [gear] are teacher only
```

| Part | Classroom (measured at 1440) | L.A.R.A. |
| :--- | :--- | :--- |
| Top bar | 64 high, same tint as the page. Menu, logo, class name over section, then apps and account | 64 high, Canvas, no border. Menu (40), L.A.R.A. badge, class name over section (after a caret, class pages only), Hub pill, Join class or Create class (+), account avatar |
| Hub pill | (none) | 36 high pill: "Connected to Hub" (Light Green, broadcast icon) or "Not connected to Hub" (Amber, wifi-slash icon). Always icon and words |
| Drawer, open | 300 wide, items 48 high, full pill, selected pill filled | 260 wide, items 48 high, full pill, selected is Light Green with Dark Green text. Rows: Home, a section header (Enrolled for learners, Teaching for teachers, graduation-cap icon, collapse caret), To-do (learner) or To review (teacher), one row per class (initial in a 32 px circle, name, section), a rule, Archived classes, Settings |
| Drawer, rail | The menu button folds the drawer into a 76 wide icon rail: Home, the section icon, To-do or To review, Archived, Settings. The class list is hidden | Same. The menu button toggles it (`aria-expanded`). The sheet grows to fill the space |
| Content sheet | White panel beside the drawer, under the top bar | White, top-left radius 24, 1 px Border on the top and left edges. Content is centred, at most 1240 wide, with 24 padding |
| Class tabs | 48 high, 14 Medium, selected is blue with a 2 px bar | 48 high, 14 ExtraBold, selected is Dark Green with a 4 px Primary Green bar rounded on top. Learners: Stream, Classwork, People. Teachers add Grades and, at the right end, a Class settings gear |
| Not in the shell | Calendar and Drive links, Google apps | Left out on purpose: Calendar and Drive are out of scope (`AGENTS.md` 1.4). There is no "Class tools" group in the drawer: where Export to USB lives is an open decision |

The drawer is hidden below 760 px of width until the phone layouts are supplied (9.12).

### 9.3 Home

```
 Due soon (2)  [View To-do] [collapse]        learner. Teacher: Recently due
 +--------------------------------------------------------------+
 | [tile] Pagsusulit #2: Parts of a Leaf  Science 4   [Fri, Oct 9]|
 | [tile] Fractions drill                 Math 4      [Mon, Oct 12]|
 +--------------------------------------------------------------+
 Classes                                        [+ Join class]
 +---------------+ +---------------+ +---------------+ +--------+
 | [tile]     :  | | [tile]     :  | |               | |        |
 | Science 4     | | Math 4        | |  cards stretch to fill     |
 | Grade 4 Rizal | |               | |  the row, 280 wide at least|
 | (status pill) | |               | |                            |
 | MR Ms. Reyes Open > ...                                        |
 +---------------+ +---------------+ +---------------+ +--------+
```

- **Classroom:** a collapsed "Recently due" or "Due soon" pill above a "Classes" section. Class cards are 257 by 296, radius 12, 1 px border: a 100 px coloured header, a 72 px teacher photo overlapping its bottom edge, an empty body, a 57 px footer with three 40 px icon buttons.
- **L.A.R.A. keeps the order and drops the look.** Cards are not a copy of Classroom's.
  - Section headings are 22 Black. The heading row is 56 high with a text link ("View To-do", "View work in progress") and a 40 px collapse button on the right.
  - **Due soon (learner):** rows with a 44 px type tile, title, class name and a due-date badge (Amber when soon, neutral later). **Recently due (teacher):** a row such as "Handout #2: Parts of a Leaf, 5 turned in, 27 assigned" with a Review button.
  - **Class card:** white, 1 px Border, radius 16, `shadow-card`, 20 padding, 232 high at least. From the top: a 56 px Dark Green tile (radius 12) with the white subject icon and a 40 px kebab on the right; the class name (24 Black); the section (14 Bold, Secondary); one status pill; a rule; a footer with the teacher's initials avatar and name (learner) or a users icon and "27 learners" (teacher) on the left, and "Open" with an arrow in Dark Green on the right.
  - **Status pill:** 8 by 12 padding, radius 12, 13 ExtraBold, icon plus words. Amber for something due or to review ("Pagsusulit #2 is due Fri, Oct 9", "5 turned in: Handout #2"), Light Green for "All caught up" or "Nothing to review".
  - **Behaviour:** the whole card opens the class (a stretched button; the kebab stays above it). On hover or focus the border turns Primary Green and the shadow becomes `shadow-card-hover`.
  - **Grid:** `repeat(auto-fill, minmax(280px, 1fr))`, 20 gap, so cards stretch to fill the 1240 wide content.
  - The class code is never on a card.
  - **No greeting.** The first pass had one; Classroom has none and the line was dropped.
- **Join class** (learner) or **Create class** (teacher) is a text button with a plus icon at the right of the Classes heading.
- **States:** *Empty* ("Add a class to get started", one line of help, and a tonal Join class or Create class button, with no class rows in the drawer). *Loading* is flat Surface Subtle blocks the size of the rows and cards. *Offline* adds an Amber banner on top, "Hub not reachable. You are seeing what is saved on this device.", and the Hub pill changes.

### 9.4 Class: Stream

```
 +--------------------------------------------------------------+
 | banner: Dark Green, 240 high, name 36, section 22   [Customize]*|
 |                                  (subject icon)           (i) |
 +----------------+---------------------------------------------+
 | Class code *   | [New announcement] * [Repost] *              |   * teacher only
 | Upcoming       | row: quiz / material / assignment posted     |
 | View all       | post: avatar, name, date, text               |
 |                |       comments (one level) [Add comment]     |
 +----------------+---------------------------------------------+
   220 wide          24 gap                feed fills the rest
```

- **Classroom:** banner 932 by 240 (radius 12, title 36, a round info button at the bottom right, a Customize button for teachers). Below it a 220 wide column (class code card, Upcoming card, radius 12, 1 px border) beside a 712 wide feed. A tonal "New announcement" button (40 high pill) and a text "Reuse post" button sit above the feed. New classwork shows as 72 high one-line rows; announcements are cards with a divider and an "Add comment" button.
- **L.A.R.A.:**
  - **Banner:** Dark Green, 240 high, radius 12, class name 36 Black, section 22 Bold in Light Green, a 120 px subject icon in a white 18% circle at the right, a 40 px info button at the bottom right. No picture. Teachers get a white pill "Customize" at the top right.
  - **Left column, 220 wide:** teacher sees the **Class code** card first (title 16 Black with a kebab, the code `K7M-4QX` at 22 Black with 1 px letter spacing, a hint that learners must be approved). **Upcoming** card for everyone: the next due item for learners, "No work due soon" for a teacher with nothing, and a View all link. Both cards are white, 1 px Border, radius 12, 16 padding.
  - **Teacher bar:** a tonal **New announcement** button (Light Green fill, Dark Green text, 40 high pill, pencil icon) and a text **Repost** button. Learners have no bar: they comment, they do not post.
  - **New announcement dialog:** 640 wide, radius 24, `shadow-modal`, 24 padding, over a dimmed sheet. Title "Announcement" (24 Black), two neutral chips (the class and "All learners"), a text area "Announce something to your class", attach and link icon buttons, Cancel and Post. Post is disabled while the Hub is unreachable, with the hint "Waiting for the Hub. Posting needs the classroom network."
  - **Classwork rows:** 72 high, Surface Subtle, radius 12, no border. A 40 px circle (Amber for a quiz, Light Blue for a material, Light Green for an assignment), "Ms. Reyes posted a new quiz: Pagsusulit #2" (14 ExtraBold), the date under it, a 40 px kebab. The row opens the work (next slice).
  - **Announcement card:** Surface Subtle, radius 12, no border. Header: 40 px initials avatar, name, date, kebab. Body 14 Bold. A rule, then comments (one level, 12 gap, a 32 px avatar, first name only), a text **Add comment** button that opens a pill reply box (40 high) with a Send icon button.
  - **Comment badges:** a comment sent while the Hub is unreachable shows "Queued for sync" (Amber), otherwise "Saved" (Light Green).
  - **Empty** ("This is where you can talk to your class", plus a line for teachers about announcements and one for learners about what appears here) also shows for any class that has no posts. *Loading* is Surface Subtle blocks. *Offline* adds the same Amber banner as Home.
  - Below 760 px of width the left column stacks above the feed.

### 9.5 Class: Classwork

- **Classroom:** a 932 wide column. Right-aligned actions: "View your work" (outlined pill, 40 high) and "Collapse all". Each topic has a 72 high header (22 px) with a 1 px rule and a collapse caret. Rows are 61 high: a 36 px outlined circle icon, title (16), due or posted text (16) on the right, a kebab. An opened row shows "Posted ...", the learner's status, a short instruction preview and a "View instructions" link.
- **L.A.R.A.:**
  - Teachers get a primary **Create** button at the top left (menu: Assignment, Quiz, Material, Video, Topic). Learners get View your work. Both get Collapse all.
  - Topic header: 64 high, Heading 2 (22 Black), item count, collapse caret, 2 px Primary Green rule.
  - Row: at least 72 high. A 44 px type tile with radius 12 (Light Green with a clipboard icon for homework, Light Blue with a book or play icon for handouts and videos, Light Amber with a timer for quizzes). Title (16 ExtraBold), "Posted ..." (13), then the due date, a status badge and a `Save for home` button (learner) or a kebab (teacher).
  - The opened row sits on Canvas, shows when it was posted, a kind status line ("You turned this in on time. Well done!"), a two line preview and a View instructions link.
  - Videos and handouts show **Saved** or **Save for home** so a learner can tell what works without Wi-Fi.
  - Below 520 px the trailing items wrap under the title.

Status words (learners see the left column, teachers the right):

| Learner sees | Teacher sees | Badge |
| :--- | :--- | :--- |
| To do | Assigned | Amber, `clock` |
| Queued for sync | Queued, arrived 9:05 AM | Amber, `cloud-slash` |
| Turned in | Turned in | Green, `check-circle` |
| Turned in after the due date (only in the preview line, never a badge) | Turned in late | Neutral, `clock` |
| Not turned in yet | Missing | Neutral for learners, Red for teachers |
| Graded, 45 / 50 | Graded | Blue, `clipboard-text` |

### 9.6 Class: People (teacher)

- **Classroom:** "Teachers" and "Classmates" sections, each a large heading with a rule, a count ("136 students") and rows of a 32 px avatar and a name.
- **L.A.R.A.:** teacher only. A learner never sees this screen.
  - An info banner on top when learners are waiting: "2 learners want to join: Check that you know them, then accept."
  - Sections "Teachers" and "Learners" (24 Black heading, count, 2 px Primary Green rule). Rows are at least 68 high with a 40 px initials avatar.
  - A learner in `PENDING_APPROVAL` shows a Pending badge, **Accept** (primary, small) and **Decline** (secondary, small). Active shows an Active badge. Rejected shows a Rejected badge and keeps the record. The 40 px small buttons are allowed on this teacher table.
  - On phones each person is a card, not a row.

### 9.7 Assignment detail and Your work

```
 +--------------------------------------------+  +- Your work ------ [To do] -+
 | (tile) Homework: Draw and label a leaf   : |  | [ photo slot             ] |
 | Ms. Reyes, posted Oct 5                    |  | [ Kunan ng litrato     ]   |  56
 | 50 points | Due Mon, Oct 12, 5:00 PM       |  | [ Choose a photo       ]   |
 |--------------------------------------------|  | [ Turn in (disabled)   ]   |
 | instructions                               |  | helper text                |
 | [attachment card]                          |  +----------------------------+
 | Class comments        [Add comment]        |  +- Private comments ---------+
 +--------------------------------------------+  | [Write to Ms. Reyes] [send]|
```

- **Classroom:** a main column (round icon, 32 px title, byline, "50 points | Due ...", a rule, instructions, attachments, Class comments with an Add comment button) and a 300 wide right column with a tinted "Your work" card (status at the right, attachment, a wide Turn in or Unsubmit pill) and a Private comments card.
- **L.A.R.A.:** same two columns, with a 304 wide right column. Below 700 px Your work drops under the instructions. The Your work card is Surface Subtle with radius 24 and changes with the learner's state:

| State | Status badge | Card shows | Actions |
| :--- | :--- | :--- | :--- |
| Assigned | To do | Dashed photo slot "Take a photo of your notebook page" | **Kunan ng litrato** (primary, 56), Choose a photo, Turn in (disabled until there is a photo) |
| Queued | Queued for sync | The photo, "Saved on this device" | Take a new photo, Turn in. Note: "You are not connected to the Hub yet. Your work will turn in by itself when you are." |
| Turned in | Turned in | The photo, "Your teacher has it" | Take it back to edit (until graded) |
| Graded | Graded | Score ("45 / 50") and the teacher's message | See your work. No take back |

- Private comments say in words who can see them: "Only you and your teacher can see these."
- The photo is saved on the device first (JPEG under 800 KB), so Turn in never needs the Hub.

### 9.8 Review learner work (teacher)

- **Classroom:** a review page with the list of learners on one side, the submitted work in the middle, and the grade and comments on the other side.
- **L.A.R.A.:** the same three columns (240, flexible, 280) under a header with a back button, the assignment title, due date and points, and an Instructions or Learner work switch.
  - Left: learners grouped "Turned in (n)" and "Not turned in yet (n)", each with initials, a time line and a status icon. Queued work shows "Queued, arrived 9:05 AM".
  - Middle: the homework photo with zoom, rotate and mark controls. Never crop or compress it.
  - Right: Score field with the maximum ("/ 50"), a short message field with one-tap encouraging phrases ("Great effort!", "Please retake the photo"), and a primary **Return to Ana** button. Returning is never automatic.
  - Photos and scores never leave the Hub.

### 9.9 To-do (learner)

- **Classroom:** tabs Assigned, Missing, Done. An "All classes" select. Groups "No due date", "This week", "Next week", "Later" with a count and a caret. Items: a type icon, title, class, "Posted ...".
- **L.A.R.A.:** same structure with friendly tab names: **To do**, **Not turned in yet**, **Done**. Group headings are 22 Black with a count and a caret. Open a group by default only if it has items. Sort by due date. Items use the type tiles from 9.5. When empty: "All caught up. Take a break!" Teachers get **To review** (work waiting for a score) instead.

### 9.10 Join a class and waiting for approval

- **Classroom:** a dialog with one "Class code" field and a hint to ask the teacher.
- **L.A.R.A.:** a dialog with the title "Join class", one line of help ("Ask your teacher for the 6-letter class code, then type it here."), six code boxes in two groups of three (`K7M-4QX`, no 0, O, 1 or I, letters keyboard, auto-capitalize, paste fills all six), "You are joining as Ana Santos.", Cancel and Join.
- After Join the learner lands in `PENDING_APPROVAL`: a status card with an amber hourglass, "Waiting for Ms. Reyes", a kind explanation, a Waiting for approval badge and Back to Home. It never blocks. A wrong code gets one line: "That code does not match a class. Check it with your teacher."

### 9.11 Grades (teacher)

Classroom's Grades tab is a table of learners by assignments. L.A.R.A. uses the same table in the same place: the Grades tab, points per assignment and quiz, a total, and the USB export. No categories or weights.

### 9.12 Phones

Classroom's phone layout is a top bar, one column of cards, and the class tabs under the bar. L.A.R.A. keeps the top bar and the single column, and moves the class tabs to a bottom navigation within thumb reach, where Quizzes and AI Tutor join them.

- Home: top app bar with menu and Join, greeting, a collapsed Due soon panel (52 high header) and one column of class cards.
- Class: Dark Green app bar with the class name over the section (back button and kebab, 52 px). Below it, the content. Bottom navigation: Stream, Classwork, Quizzes, AI Tutor (5.13). People and Grades are on the teacher's phone only.
- Rows and cards use the same spacing as Desktop but fill the width, with 12 px outer padding.
- Tables become cards (Platform mapping rules, section 0).

### 9.13 Screen map

| Classroom screen | L.A.R.A. screen | Showcase entry | What L.A.R.A. adds |
| :--- | :--- | :--- | :--- |
| Home, class cards | Home with Due soon or Recently due and class cards (9.3, 5.12) | Remake: Home and Stream (desktop) | Hub pill, status pills, offline banner |
| Stream tab | Stream (9.4, 5.12) | Remake: Home and Stream (desktop) | Hub pill, Queued for sync comments, empty and offline states |
| Classwork tab | Classwork | Classwork | Save for home, offline-capable actions, Quizzes |
| People tab | People (teacher only) | People and join requests | Pending approval with Accept and Decline |
| Assignment and Turn in | Assignment detail with camera | Assignment detail and Your work | Photo queued as `QUEUED_FOR_SYNC`, kind helper text |
| Student work | Review learner work | Review learner work | Encouraging message phrases, queued arrivals |
| To-do | To-do | To-do | Friendly tab names |
| Join class | Join a class and waiting | Join a class | Class code format, approval wait |
| Quiz (Google Forms) | Quiz (9.14, 5.14) | Remake: Home, Stream and Quiz (desktop) | Shared countdown, auto-submit, no AI, queued submission, teacher monitor |
| Grades tab | Grades and gradebook | Gradebook Table | Points, USB export |
| (none) | Socratic AI tutor (5.15) | Socratic AI Tutor | Purple surfaces only, grounding tag, locked during quizzes |

### 9.14 Quiz (desktop)

Classroom has no timed quiz (it hands off to Google Forms), so this screen is L.A.R.A.'s own. It sits in the remade shell and reuses the paperless-quiz rules (5.14 and `rules/quiz-and-anti-cheat.md`). It opens from the "posted a new quiz" row on the Stream. Building a quiz comes with the Classwork screens.

**Learner**

```
 <- Back to Stream
 +-----------------------------------------------+      intro: card, 760 wide, 2 px border, radius 16
 | Pagsusulit #2: Parts of a Leaf                |
 | [5 questions] [10 minutes] [Science 4]       |
 | - one timer for the whole quiz ...            |
 | - if the Wi-Fi drops, keep going ...          |
 | - Ask L.A.R.A. is off until you submit        |
 |                              [Start quiz]     |
 +-----------------------------------------------+
 running: [title]                  [timer 04:50 left]     drawer hidden, no tabs
          [=====][-----][-----][-----][-----]   Question 1 of 5
          What gives a leaf its green color?
          ( ) Stomata   (x) Chlorophyll   ...                 options 56 high
          [Previous]                              [Next | Submit]
 done:    score ring, "Your answers are in", Saved or Queued for sync badge, [Back to Stream]
```

- **Intro:** title (24 Black), three neutral badges (questions, minutes, class), three lines with icons (one timer for the whole quiz and no pausing, answers saved on the device if the Wi-Fi drops, Ask L.A.R.A. is off until you submit), and a Primary **Start quiz** button. The 40 px desktop button rule applies; options are the only 56 px items.
- **Running:** the drawer and tabs are hidden so nothing pulls the learner away. Header: title and the **timer pill** (36 high, 14 Black, tabular numbers): Light Green over 5 minutes, Amber at 5 minutes or less, Red at 2 minutes or less (`rules/quiz-and-anti-cheat.md` 2.3), always icon and words. Below: segmented progress (done Primary Green, current Dark Green, pending Border), "Question 3 of 5", the question (22 Black), then options as radio buttons (56 high, 2 px Border, radius 12, selected is Light Green with a Primary Green border and a filled check). Previous (disabled on question 1), Next, and Submit on the last question with "3 of 5 answered". Two quiet notes: answers are saved on this device, and Ask L.A.R.A. is off during a quiz.
- **The AI is not on this screen at all** (not hidden, not disabled): `rules/quiz-and-anti-cheat.md` section 1.
- **Offline:** the Amber banner reads "Hub not reachable. Keep going: the timer keeps running and your answers are saved on this device." Finishing works the same.
- **Time up:** the quiz submits itself and the done title reads "Time is up. Your answers were sent in".
- **Done:** a score ring and an encouraging line whatever the score, a **Saved** badge (Light Green) or **Queued for sync** badge (Amber, offline) and a line that the teacher sees it once the Hub is reachable. The prototype shows a sample "4 of 5". It does not hold the answer key: the real score comes from the Hub, and while the teacher holds scores the receipt carries no score. Per-item review (Correct or Review badges with lesson links, 5.14) is not remade yet.

**Teacher (monitor)**

- Card 960 wide with the title, a status badge (Not started, Live, Closed), the shared **clock** and one action: **Start quiz** (Primary) before it starts, **Close quiz** (Secondary) while live. Closing submits and grades everyone still answering.
- Three stat tiles (In progress, Submitted, Not started), then one row per learner: 32 px initials avatar, first name and initial, a progress bar with "3 of 5", and a status badge (Answering Amber, Submitted Light Green, Not started neutral).
- The clock is the class clock: it starts when the teacher presses Start quiz, learners join the same countdown, and it never comes from a device clock (`rules/quiz-and-anti-cheat.md` 2.2). In the prototype one clock is shared in the page.
- Building a quiz, question images, shuffle and holding scores are not in this slice.

**Prototype controls.** The floating bar has a **Quiz clock starts at** switch (10:00, 4:50, 1:40) so the Amber and Red timer states can be seen without waiting, and a "Restart the demo" link. Neither belongs in the real app.

### 9.15 Measurements for the next slices

Read on 2026-10-11 from a teacher and a learner account at 1440 px, for the screens not remade yet. Sizes are Classroom's; remake them in L.A.R.A.'s look (stage 2 above).

| Screen | What Classroom does |
| :--- | :--- |
| Classwork (teacher) | A Create button (40 high pill) with a menu: Assignment, Quiz assignment, Question, Material, Reuse post, Topic. A "Topic filter" select, "Collapse all", then topic sections (22 heading, 2 px rule) of 59 high rows: a 36 px type circle, title, "Posted 2:04 PM", kebab. A new topic shows "Students will see this topic once work is added to it". Learners get a "View your work" button, and a row opens to the instructions, a "View instructions" link and a 296 by 82 attachment card |
| Create dialogs | Full-window dialog with a close button, type icon and title, a Save-status text and a split Assign button. Left card (960 wide): Title (required), Instructions (optional) with Bold, Italic, Underline, bulleted list, Remove formatting, then "Attach": Drive, YouTube, Create, Upload, Link (48 px circles with captions). Right column (316 wide): For (class), Assign to (All students), Points (100), Due (No due date), Topic (No topic, Create topic, or a topic), Rubric. Material has no points or due date. The Add link dialog has one required Link field |
| Announcement dialog | 932 wide, radius 8: "Post in <class>", "All students", the text area "Announce something to your class", the same format and attach icons, Cancel and Post |
| Student work (teacher) | Two tabs, Instructions and Student work. A toolbar: split Return button (disabled until someone is selected), email icon, a points field. Left list 448 wide: All students checkbox, "Sort by status", then groups (Turned in, Assigned) of 64 high rows with avatar, name, the last private comment as a preview, and "__/100". Right panel: counts (1 Turned in, 0 Assigned), an "Accepting submissions" switch, a student filter, then the chosen learner's work with "No grade" at the top right and a **Private comments** block (count, comments with a name and time, an "Add private comment..." pill with a Send icon) |
| Learner assignment page | Title row with a 40 px icon circle, a 32 title, teacher and time, "100 points", instructions, attachments, "Class comments". Right column 300 wide: **Your work** card (status text, "Add or create", "Mark as done" or "Turn in") and a **Private comments** card ("Add comment to <teacher>", "Private comments are only visible to you and your teachers"). Marking done with no attachment asks "You didn't attach work for ..., so your teacher will just see it's done." After turning in the status is green "Turned in" and the button becomes "Unsubmit" |
| To-do (learner) | Three tabs (Assigned, Missing, Done), a class filter select, an empty state "Nothing on your to-do list right now / Check back later for new assignments" |
| People | Teachers and Students sections (28 heading, 2 px rule) each with an Invite icon button. Students: a select-all checkbox, an Actions button, a sort button, 61 high rows with a checkbox, 32 px avatar, name and a kebab. Empty: "Add students to this class" with Invite students |
| Grades (teacher) | A table: a "Sort by last name" select over the learner column, one column per assignment (due date line, title link, "out of 100", a header menu), a "Class average" row and one row per learner with "__/100" cells. Empty: "This is where you'll view and manage grades". A first-visit dialog explains draft grades for missing work |
| Class settings | Full-window page of cards: Class Details (name, description, section, levels, subject, room), General (Invite codes with an invite link and class code, Stream: "Students can post and comment" or stricter, Classwork on the Stream, Show deleted items), Grading (draft grade for missing work, overall grade calculation, grade categories). **L.A.R.A. departs:** no Meet link, no categories, and learners cannot post to the Stream |

### 9.16 Open decisions

These need the Lead's answer before the matching screen is built. Nothing above settles them.

1. **White on Primary Green is 3.56:1** against the 4.5:1 rule. The Primary buttons in the prototype (Post, Start quiz, Next, Submit) show the current token. Options: darken the button green (touches the code mirrors and the token test) or use Dark Green for filled buttons.
2. **Where the AI tutor is reached** on phone and desktop. The remade shell has no Ask L.A.R.A. entry.
3. **Where Export to USB lives** (the Hub window per PRD FR-5.2, or the Grades tab per the first pass).
4. **Learner People tab:** Classroom shows classmates; L.A.R.A. never shows learners to learners. Teachers only, or no tab?
5. **Quizzes tab:** the first pass had one; the remake reaches quizzes from the Stream and Classwork (Create, Quiz assignment) like Classroom. Confirm when Classwork is remade.
6. **Teacher posting while the Hub is unreachable:** the prototype disables Post with a hint. Comments queue, announcements do not.
7. **Class settings default** for who may post: the prototype assumes learners comment only.
8. **Filipino strings** for the new words (Recently due, Teaching, Enrolled, Queued for sync, Start quiz) need a Filipino-speaking teacher's check.
