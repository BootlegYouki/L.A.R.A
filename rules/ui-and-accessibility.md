# UI/UX & Elementary Accessibility Standards

This rule document governs all visual styling, touch targets, and accessibility requirements for Filipino elementary school pupils (Grades 1 to 6) and public school teachers (DepEd).

All AI agents and contributors must follow these rules.

---

## 1. Design Authority & Design System Hierarchy

* **Design Team Authority (Primary):** The visual designs, wireframes, and prototypes provided by the project's **Design Team** are the authoritative specification that must be implemented. Teammates must adhere to the design team's screen layouts and user journeys.
* **Design System as Inspiration & Baseline:** The local design system reference at `design-system/index.html` serves as an interactive foundation, architectural reference, and inspiration for tokens, touch targets, and component states.

* **Material Design 3 Best Practice:** While following the Design Team's creative direction, developers should implement screens using Google's **Material Design 3 (Material You)** component library (`androidx.compose.material3` on Android, Tailwind M3 tokens on Desktop) whenever possible to guarantee native accessibility, elevation, and tactile child-friendly feedback.
* **Icons:** Use official Google Material Symbols exclusively. Bundled locally without external CDN links.

---

## 2. Touch Targets (Elementary Precision Rule)

Young children (especially in Grades 1 to 3) have developing fine motor control. Touch targets must prevent miss-taps:

* **Minimum Clickable Height:** **48dp** on all buttons, list items, radio cards, and tabs.
* **Preferred Primary Actions:** **56dp** for major call-to-actions (FAB, "Submit Work", "Start Quiz", "Kunan ng Litrato").
* **Spacing:** Minimum 8dp between adjacent interactive buttons.

---

## 3. High-Contrast & Elementary Readability

* **Contrast Ratio:** Text-to-background contrast must maintain at least **4.5:1** across all surface container roles to ensure readability under bright tropical classroom lighting.
* **Typography Scale:** Avoid dense, tiny fonts. Use minimum 14sp for body text and 18sp for titles.
* **Subject Color Coding:**
  * **Science (Agham):** Green accents (`#146C2E` / `#C4EED0`).
  * **Mathematics (Matematika):** Blue accents (`#0B57D0` / `#D3E3FD`).
  * **English:** Amber accents (`#705D00` / `#FFE16D`).
  * **Filipino & Araling Panlipunan:** Rose accents (`#984061` / `#FFD9E2`).

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
