# L.A.R.A. Design System

> **Official Canonical Design System for the L.A.R.A. Offline LAN Classroom Ecosystem**  
> *Google Classroom mental model paired with custom Filipino elementary visual brand tokens.*

---

## 1. Principles & Invariants

1. **100% Solid Flat Colors (Zero Gradients):** No linear gradients, radial glows, or blurred backdrops.
2. **Phosphor Icons Exclusively:** All UI components use Phosphor Icons (`ph-*`) bundled locally. No emojis or random SVG sets.
3. **Typography (Nunito Only):** Friendly, rounded typography with high legibility for young elementary pupils (Grades 1 to 6).
4. **Touch Target Standard:** Strict minimum touch target of **52dp / 52px** (preferred 56dp) on all buttons, input fields, and quiz cards.
5. **Non-Muddy Layered Shadows:** Two-layer depth system tinted with Navy (`#17213D`) to avoid muddy gray halos.

---

## 2. Directory Structure

```
design-system/
├── index.html                 # Interactive 3-column shadcn-style component documentation SPA
├── tokens.json                # Cross-platform JSON token dictionary
├── tokens.css                 # Master CSS variables
├── assets/                    # Bundled offline assets (never load from a CDN)
│   ├── fonts/                 # Nunito woff2 + fonts.css (web)
│   └── phosphor/              # Phosphor Regular/Fill/Bold woff2 + phosphor.css (web)
├── mobile/                    # Jetpack Compose assets for mobile/
│   ├── Color.kt               # Jetpack Compose color constants
│   ├── Type.kt                # Jetpack Compose Nunito typography
│   ├── Theme.kt               # MaterialTheme light wrapper
│   └── res/font/              # Nunito .ttf files for Android res/font
├── desktop/                   # Tailwind configuration for desktop/
│   └── tailwind.theme.ts      # Tailwind extension tokens
└── README.md
```

---

## 3. How to Consume

### For Android Mobile Developers (`mobile/`)
Copy or reference the files in `design-system/mobile/` directly into:
`mobile/app/src/main/java/org/lara/app/ui/theme/`, and copy `design-system/mobile/res/font/*.ttf` to `mobile/app/src/main/res/font/`. Add the Phosphor Compose dependency `com.adamglin:phosphor-icon`.

### For Desktop & Server Web Developers (`desktop/` & `server/`)
Import `design-system/desktop/tailwind.theme.ts` into your `desktop/tailwind.config.ts`, or include `design-system/tokens.css` in your global CSS. Copy `design-system/assets/` to `desktop/src/assets/design-system/` and import `fonts/fonts.css` and `phosphor/phosphor.css` from `index.css`. The Hub captive portal (`server/backend/static/portal/`) reuses the same two CSS files.

---

## 4. Interactive Component Documentation

Open `design-system/index.html` in any web browser to view the live component library with interactive previews, live toast notifications, and copyable code snippets:

```bash
xdg-open design-system/index.html
```
