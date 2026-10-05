# UI/UX & Elementary Accessibility Standards

This rule document governs all visual styling, touch targets, and accessibility requirements for Filipino elementary school pupils (Grades 1 to 6) and public school teachers (DepEd).

All AI agents and contributors must follow these rules.

---

## 1. Design Authority & L.A.R.A Custom Brand System

* **Brand Authority (Canonical):** All three applications (Mobile, Desktop, and Server) must strictly follow the **L.A.R.A Custom Brand Design System** documented in [`docs/design-system.md`](../docs/design-system.md) and previewed in [`docs/design-system-showcase.html`](../docs/design-system-showcase.html).
* **Google Classroom Mental Model:** Use Google Classroom structures (Class Cards, Stream announcements with teacher avatars, Classwork materials with icons) as the UX mental model, paired with L.A.R.A brand tokens.
* **Core Brand Tokens (Zero Gradients):**
  * **Primary Green (`#2E9B4B`):** General classroom learning, active tabs, buttons.
  * **Dark Green (`#176B36`):** Hover states and solid classroom card headers.
  * **Light Green (`#E4F6E8`):** Selected states and badge backgrounds.
  * **Primary Purple (`#5145E5`):** Strictly reserved for the Socratic AI Tutor.
  * **Canvas Background (`#F7FBFA`):** Mint-tinted soft background.
  * **Surface White (`#FFFFFF`):** High-contrast cards and dialogs.
  * **Primary Text (`#17213D`):** Deep navy high-contrast text.
  * **Gradient Invariant:** Zero gradients. All surfaces, cards, buttons, and banners must use 100% flat, solid color fills.
* **Typography:** **Nunito Only** across all apps (rounded, friendly, readable for primary grade children).
* **Icon Standard:** Use official **Phosphor Icons** (`ph-*`) bundled locally without external CDN dependencies.

---

## 2. Touch Targets (Elementary Precision Rule)

Young children (especially in Grades 1 to 3) have developing fine motor control. Touch targets must prevent miss-taps:

* **Minimum Clickable Height:** **52dp** on all buttons, list items, radio cards, and tabs.
* **Preferred Primary Actions:** **56dp** for major call-to-actions (FAB, "Submit Work", "Start Quiz", "Kunan ng Litrato").
* **Spacing:** Minimum 8dp between adjacent interactive buttons.

---

## 3. High-Contrast & Elementary Readability

* **Contrast Ratio:** Text-to-background contrast must maintain at least **4.5:1** across all surface container roles to ensure readability under bright tropical classroom lighting.
* **Typography Scale:** Avoid dense, tiny fonts. Use minimum 14sp for body text and 18sp for titles.
* **Subject colors:** the design system does not define per-subject colors, and tokens-only is an invariant. Do not use per-subject hex accents unless the Lead adds them to `design-system/tokens.json` first.

---

## 4. Bilingual Localization Rule

* **Zero Hardcoded Strings:** Never hardcode user-facing strings inside Compose UI components or React pages.
* **Mobile Localization:**
  * English strings in `mobile/app/src/main/res/values/strings.xml`.
  * Filipino strings in `mobile/app/src/main/res/values-tl/strings.xml`.
* **Desktop Localization:**
  * Externalized JSON/TS dictionary with instant runtime toggle between English and Filipino.

---

## 5. CameraX Homework Guidelines

* In-app viewfinder must display an obvious rectangular document guide to help children frame their notebook page.
* Automatic image pipeline must downscale to max 1080p and compress to JPEG < 800KB.

---

## 6. DepEd Gradebook Export Standard

* Hub desktop application must export `.xlsx` and `.csv` files strictly conforming to DepEd Class Record columns: Learner Name, LRN, Written Works, Performance Tasks, and Quarterly Assessment.
* Automatically detect mounted USB flash drives for one-click direct transfer.
