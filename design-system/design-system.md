# L.A.R.A. Design System and Component Specification

> Canonical design rules for every L.A.R.A. surface: the Android learner app (Kotlin, Jetpack Compose), the Teacher Desktop Hub (React + Tailwind), and the Server Web Portal (React + Tailwind).
> Layout: Google Classroom's screens, copied closely (section 9). Look and voice: a friendly L.A.R.A. brand for Filipino learners (Grades 1 to 12) (sections 1 to 5 and 7).
> Interactive reference with live previews and copy-ready code for both platforms: `design-system/showcase.html`.

---

## 0. Read This First (Rules for Agents)

These rules apply to every UI change. If a request conflicts with them, follow the rules and mention the conflict in your summary.

### Invariants (never violate)

1. **Solid fills only.** No gradients, radial glows, blurred color blobs, or shimmer animations. Loading states use an opacity pulse on solid blocks.
2. **Tokens only.** Use the named tokens in section 1. Do not invent hex values, shades, or opacity-tinted variants of brand colors. The only permitted transparency is the white 20% overlay on dark green headers and the navy scrim on modals and sheets.
3. **Purple means AI.** `#5145E5` and its variants appear only on Socratic AI surfaces (tutor chat, AI tab, AI buttons, grounding tags). Never use purple for decoration, links, or generic emphasis.
4. **Phosphor Icons only.** Regular weight by default, Fill only for the active navigation item, Bold only inside checkboxes. Do not mix icon families.
5. **52 minimum touch target.** Every interactive control on learner-facing screens is at least 52dp (Compose) or 52px (web). Quiz options are at least 56. The 40px compact size is allowed on the Teacher Desktop Hub only.
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
- **Classroom card:** white, 1 px Border, radius 16, `shadow-card`. Header is Dark Green (`#176B36`) and at least 104 high with the class name (Title 22 Black), section and teacher in white, a 44 px subject icon in a white 20% circle at the top right, and a 60 px teacher initials avatar with a 3 px white ring overlapping the bottom edge. Body shows the next one or two things due, or one friendly line. Footer has a Saved badge and 52 px icon buttons. Teachers also see the class code in a Light Green tag with Dark Green text (the old white 20% tag measures 4.0:1 and fails contrast).
- **Class banner:** Dark Green, at least 168 high, radius 16, class name (34 Black), section (18 Bold, Light Green), a 72 px subject icon in a white 20% circle, a 52 px info button. No picture, no gradient.
- **Class tabs:** 56 high, 15 ExtraBold, selected is Dark Green text with a 4 px Primary Green bar rounded on top, 1 px Border under the bar. Ask L.A.R.A. is the only purple tab.
- **Side card (Upcoming, Class code):** white, 1 px Border, radius 16, 16 padding, title 16 Black. Class code is shown large (26 Black) as `K7M-4QX` and only to teachers.
- **Composer (teacher):** white, 1 px Border, radius 16, at least 68 high, avatar, "Post an announcement to your class", Add button.
- **Stream post:** Canvas fill, 1 px Border, radius 16. Header: avatar, name, date, source label ("Classroom Wi-Fi Hub") on its own line, Synced or Queued for sync badge, kebab. Attachment card (white, radius 12), one level of comments, a 52 high pill reply box. A teacher can turn comments off per post and the post shows a "Comments off" badge.
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
- Timer pill: Normal over 5 minutes is Light Green, Warning under 5 minutes is amber, Critical under 1 minute is red. Announce at 5, 1 and 0 minutes only.
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

Teachers and learners already know Google Classroom. Every L.A.R.A. screen copies the **structure** of the matching Classroom screen, so people find each thing where they expect it, and then says it in L.A.R.A.'s friendly voice (section 7) using L.A.R.A.'s look (sections 1 to 5). Layout is not free design: use the anatomies below. Live, interactive versions of every screen are in the **Classroom Screens** group of `design-system/showcase.html`.

Measurements marked "Classroom" were taken from the learner side of the current Classroom web app (Home, Stream, Classwork, People, assignment detail, To-do, and its phone layout) on a 1810 px wide window, and are given so the proportions can be matched. The teacher side (composer, Create menu, class code card, reviewing work) follows Classroom's documented structure and was not measured. Do not copy its colors, fonts, logo or pictures.

### 9.1 Rules of use

1. **Copy structure, not visuals.** No Google colors, fonts, logos or wordmark. No theme pictures or photos in banners: use Dark Green with a subject icon.
2. **Same places.** Top bar, class drawer, class tabs, banner, Upcoming card, feed, topics, rows and the Your work card sit where Classroom puts them. Do not move them to be clever.
3. **Friendlier by default.** Rounder corners, 52 px targets (Classroom uses 40), warm words, an encouraging line on every empty state, no shaming statuses.
4. **Add, never replace.** L.A.R.A. additions (Hub beacon, sync and Saved badges, Quizzes, Ask L.A.R.A., camera, class code approval) go in the places named below. They never push a Classroom element out.
5. **Roles.** Learners see Stream, Classwork, Quizzes and Ask L.A.R.A. Teachers see Stream, Classwork, Quizzes, People and Grades. Learners never see other learners (no roster, no names in comments beyond a first name, never an LRN).
6. **Every screen** has an offline state, an empty state and a loading skeleton (5.10). Nothing blocks when the Hub is unreachable.
7. **Teacher features live on Desktop and Mobile.** Desktop is the full authoring surface. Mobile covers the on-the-go set (approve, post, start a quiz, monitor, grade a photo) and may add authoring when the team has capacity.

### 9.2 App shell (Desktop and Hub portal)

```
+----------------------------------------------------------------------+
| [menu] [L.A.R.A.] > Science 4                    [Hub] [+] [avatar]  |  top bar, 64
|                    Grade 4 - Rizal                                   |
+--------------+-------------------------------------------------------+
| Home         | Stream  Classwork  Quizzes  [People  Grades]  [Ask]   |  class tabs, 56
| To-do        |-------------------------------------------------------|
| Enrolled  ^  |                                                       |
|  Science 4   |   content sheet: white, top-left radius 24            |
|  Math 4      |                                                       |
|  ...         |                                                       |
| Archived     |                                                       |
| Settings     |                                                       |
+--------------+-------------------------------------------------------+
   drawer, 260
```

| Part | Classroom | L.A.R.A. |
| :--- | :--- | :--- |
| Top bar | 64 high, same tint as the page, no border. Menu, logo, class name over section, then add, apps, account | 64 high, Canvas, no border. Menu (52), L.A.R.A. badge, class name over section, Hub beacon, Join class or Create class (52), account avatar (52) |
| Drawer | 300 wide, items 48 high, full pill shape, selected pill filled | 260 wide, items 52 high, full pill, selected is Light Green with Dark Green text and a Fill icon. Rows: Home, To-do (learner) or To review with a red count (teacher), Enrolled or Teaching with a collapse caret, one row per class (initial in a 32 px circle, name, section), Class tools (teacher only: Attendance, USB export), Archived classes, Settings |
| Content sheet | White panel beside the drawer, starts under the top bar | White, top-left radius 24, 1 px Border on the top and left edges |
| Class tabs | 48 high, 14 Medium, selected is blue with a 2 px bar | 56 high, 15 ExtraBold, selected is Dark Green with a 4 px Primary Green bar rounded on top. Learners: Stream, Classwork, Quizzes. Teachers add People and Grades |
| Ask L.A.R.A. | (none) | Purple item at the right end of the tab bar, learners only. It opens the AI drawer (5.15). The 60 px AI button (5.15) stays on screens without a tab bar (Home, To-do). Both are disabled with an explanation during a quiz |

Below 860 px the drawer folds behind the menu button, as in Classroom, and the sheet becomes full width.

### 9.3 Home

```
 Magandang umaga, Ana!                       greeting (L.A.R.A. addition)
 You have 2 things to turn in this week.

 +- Due soon (2) ------------------------ [collapse] +   panel, radius 24
 | [tile] Pagsusulit #2: Parts of a Leaf   Fri, Oct 9 |
 | [tile] Fractions drill                  Mon, Oct 12 |
 +-----------------------------------------------------+
 +- Classes ------------------------------ + Join class +
 | [card] [card] [card] [card]                          |   grid, 16 gap
 +-----------------------------------------------------+
```

- **Classroom:** a collapsed pill reading "Due soon" (416 by 64, radius 28) above a "Classes" panel (radius 28, 24 padding). Class cards are 296 by 296, radius 12, 1 px border: a 100 px header, a 72 px teacher photo overlapping the header's bottom edge, an empty body, and a 57 px footer with three 40 px icon buttons.
- **L.A.R.A.:** keep the order and proportions. Panels have radius 24. Cards are at least 250 wide with radius 16, `shadow-card`, and `shadow-card-hover` on hover.
  - Header: Dark Green, at least 104 high. Class name (Title 22 Black), section, teacher name. A 44 px subject icon in a white 20% circle at the top right. A 60 px teacher avatar with initials and a 3 px white ring overlapping the bottom edge.
  - Body: the next one or two things due (icon, title, due date). If there is nothing, one friendly line ("Nothing due yet. Take a break!"). One card may show a cache bar ("Saving lessons for home study: 68%").
  - Footer: Saved badge, then 52 px icon buttons for Your work and More options.
  - Teachers also see the class code (`K7M-4QX`) in a Light Green tag with Dark Green text on the header. Learners never see it.
- **Learner Home** shows Due soon. **Teacher Home** replaces it with "Waiting for you" (work to review, learners asking to join) and shows Create class instead of Join class.
- The greeting follows the time of day ("Magandang umaga", "Magandang hapon") and uses the first name.

### 9.4 Class: Stream

```
 +-------------------------------------------------------------+
 | banner: Dark Green, class name 34, section 18, icon, [info]  |
 +----------------+--------------------------------------------+
 | Class code *   | [avatar] Post an announcement ...   [Add] *|   * teacher only
 | Upcoming       | post: avatar, name, date, source, Synced   |
 | - Handout #2   |       text, attachment card                |
 |   View all     |       comments (one level), reply box      |
 +----------------+--------------------------------------------+
```

- **Classroom:** banner 932 by 240 (radius 12, title 36, a round info button at the bottom right). Below it an Upcoming card 196 wide (radius 12, 1 px border, "View all" link) beside a 712 wide feed, 24 apart. Posts have a tinted fill, radius 12, a name and date, a kebab menu, attachment cards (296 by 82) and a comments block ending in a 40 high pill reply box.
- **L.A.R.A.:**
  - Banner: Dark Green, at least 168 high, radius 16, a 72 px subject icon at the top right, a 52 px info button at the bottom right. No picture.
  - Left column 216 wide: teacher sees the Class code card (large `K7M-4QX`, a hint that learners must be approved) above Upcoming.
  - Teacher composer: avatar, "Post an announcement to your class", an Add button. It sits above the first post.
  - Post card: Canvas fill, 1 px Border, radius 16. Header: avatar, name, date, the source label "Classroom Wi-Fi Hub" on its own line, a Synced or Queued for sync badge, kebab. A teacher can turn comments off per post: show a "Comments off" badge and hide the reply box.
  - Comments: one level. The reply box is a 52 high pill with a Send icon button.
  - Below 700 px the left column stacks above the feed.
  - Empty: "No announcements yet. When your teacher posts, it shows up here and is saved on this device."

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
| Home, class cards | Home with Due soon and class cards (5.12) | Home and app shell | Greeting, Hub beacon, Saved badge, cache bar |
| Stream tab | Stream (5.12) | Class Stream | Sync badges, source label, comments off |
| Classwork tab | Classwork | Classwork | Save for home, offline-capable actions, Quizzes |
| People tab | People (teacher only) | People and join requests | Pending approval with Accept and Decline |
| Assignment and Turn in | Assignment detail with camera | Assignment detail and Your work | Photo queued as `QUEUED_FOR_SYNC`, kind helper text |
| Student work | Review learner work | Review learner work | Encouraging message phrases, queued arrivals |
| To-do | To-do | To-do | Friendly tab names |
| Join class | Join a class and waiting | Join a class | Class code format, approval wait |
| Quiz (Google Forms) | Paperless quiz (5.14) | Paperless Quiz | Shared countdown, auto-submit, no AI |
| Grades tab | Grades and gradebook | Gradebook Table | Points, USB export |
| (none) | Socratic AI tutor (5.15) | Socratic AI Tutor | Purple surfaces only, grounding tag, locked during quizzes |
