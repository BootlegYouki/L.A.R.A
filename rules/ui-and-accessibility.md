# UI/UX & Accessibility Standards (Grades 1 to 12)

This rule document governs all visual styling, touch targets, and accessibility requirements for Filipino public school learners (Grades 1 to 12) and teachers.

All AI agents and contributors must follow these rules.

---

## 1. Design Authority & L.A.R.A Custom Brand System

* **Brand Authority (Canonical):** All three applications (Mobile, Desktop, and Server) must strictly follow the **L.A.R.A Custom Brand Design System** documented in [`design-system/design-system.md`](../design-system/design-system.md) and previewed in [`design-system/showcase.html`](../design-system/showcase.html).
* **Google Classroom is the layout.** L.A.R.A copies Google Classroom's screens and flows (Home, Stream, Classwork with topics, People, assignment page, grading, To-do) in the L.A.R.A brand, and adds quizzes and the AI tutor. When a screen is not in the design system yet, follow Classroom's structure with the documented components and say so in the PR.
* **Same features on phone and desktop** for teachers and learners (`AGENTS.md` section 1.4); only the layout changes.
* **Tokens only.** Colors, type sizes, spacing and radii come from `design-system/tokens.json` and its platform mirrors (`design-system/mobile/`, `design-system/desktop/`). Token values are not repeated in this file so they cannot drift; a hex value typed into a screen is a violation.
* **Purple is reserved for the Socratic AI tutor.** Green is the classroom brand.
* **Zero gradients.** Every surface, card, button and banner is a flat, solid fill.
* **Typography:** **Nunito only** across all apps, bundled locally.
* **Icon Standard:** Use official **Phosphor Icons** (`ph-*`) bundled locally without external CDN dependencies.

---

## 2. Touch Targets (Precision Rule)

Young children (especially in Grades 1 to 3) have developing fine motor control. Touch targets must prevent miss-taps:

* **Minimum Clickable Height:** **52dp** on all buttons, list items, radio cards, and tabs.
* **Preferred Primary Actions:** **56dp** for major call-to-actions (FAB, "Submit Work", "Start Quiz", "Kunan ng Litrato").
* **Spacing:** Minimum 8dp between adjacent interactive buttons.

---

## 3. High-Contrast & Readability

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
  * `desktop/src/i18n/en.json` and `desktop/src/i18n/fil.json` with the same keys, and a runtime toggle in Settings.
* **CI checks key parity** on both platforms (`scripts/verify_invariants.py`).
* **Words:** use the classroom words table in the design system (section 7.2). Say "learner", and "Hub" or "classroom network", never "internet".

---

## 5. CameraX Homework Guidelines

* In-app viewfinder must display an obvious rectangular document guide to help children frame their notebook page.
* Automatic image pipeline must downscale to max 1080p and compress to JPEG < 800KB.

---

## 6. Gradebook Export Standard

* The Hub must export `.xlsx` and `.csv` files with Learner Name, LRN, one column per assignment and quiz (points earned, with the maximum in the header) and a total. No DepEd categories, quarters or weights: each teacher grades their own way.
* The file is written to a USB drive plugged into the Hub PC, detected automatically. Skip archived (deleted) assignments.

---

## 7. Every Screen

* Has an offline state, an empty state and a loading skeleton.
* Never relies on color alone: pair a status color with an icon and text.
* Never shows a blocking "no connection" dialog.
