# L.A.R.A Material Design 3 UI System

> **Live Deployment:** [https://lara-design-system.vercel.app](https://lara-design-system.vercel.app)


This directory contains the visual and technical design system reference website for the **L.A.R.A** project, tailored for Philippine Public Elementary Schools (DepEd Grades 1 to 6).

> **Design Authority Note:** This deployed design system is an architectural inspiration and component token reference. The actual screens and mockups crafted by the project's **Design Team** are the primary authority that must be implemented. However, developers should leverage Google Material Design 3 (Material You) primitives to realize the Design Team's vision with consistent elevation, tactile feedback, and accessibility.

---

## 1. Viewing the Design System Website

The design system website is completely self-contained in `index.html`. It requires no build step and has zero external dependencies or internet requirements.

### Option A: Open Directly in Your Browser
Simply double-click `index.html` or run:
```bash
xdg-open index.html
# or
brave index.html
```

### Option B: Serve via Local HTTP Server
```bash
# Python
python3 -m http.server 3000

# Node
npx serve .
```
Then visit `http://localhost:3000`.

---

## 2. Interactive Features on the Website

* **Live Bilingual Switcher:** Toggle between **English** and **Filipino** in the top bar to preview how components render localized copy.
* **Material 3 Dynamic Theming:** Toggle between **Light** and **Dark** modes to inspect surface container hierarchy and contrast ratios.
* **Paperless Quiz Countdown Simulation:** Test the visual timer pill color transitions (Green for safe -> Yellow at 5 mins -> Red pulsing at 2 mins).
* **Socratic AI Tutor Sheet Preview:** Review the pedagogical chat layout and grounding badge.

---

## 3. Core Standards for Developers

### Touch Targets
* All buttons, cards, and list items must have a **minimum touch height of 48dp** (preferred **56dp** for primary actions) to support young children's fine motor control on entry-level Android touchscreens.

### Color Contrast
* Text-to-background contrast must maintain at least a **4.5:1 ratio** to ensure readability under standard classroom ambient light.

### Elementary Subject Coding
* **Science (Agham):** Green accents (`#146C2E` / `#C4EED0`).
* **Mathematics (Matematika):** Blue accents (`#0B57D0` / `#D3E3FD`).
* **English:** Amber accents (`#705D00` / `#FFE16D`).
* **Filipino & Araling Panlipunan:** Rose accents (`#984061` / `#FFD9E2`).
