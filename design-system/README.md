# L.A.R.A. Design System

> **Canonical design system for the L.A.R.A. offline classroom apps.** Google Classroom's mental model with a Filipino elementary brand: solid flat colors, Nunito, Phosphor icons, navy-tinted shadows.

Owned by the Lead Developer. Developers **use** these files; they do not edit them in a feature PR.

## The three sources and how they relate

| Source | What it is | Role |
| :--- | :--- | :--- |
| [`showcase.html`](./showcase.html) | Interactive sample (v2.0): every foundation and component, with copy-ready **React + Tailwind** and **Kotlin Compose** code. Open it in a browser; it works offline. | **The design.** When this changes, the files below follow it. |
| [`design-system.md`](./design-system.md) | Written rules: tokens, components, accessibility, Google Classroom layout reference, definition of done | The rules agents and reviewers cite |
| This folder | Ready-to-copy token and theme files | The code form of the sample |

The written rules and the interactive sample both live in this folder, next to the code that mirrors them.

## What is in this folder

```
design-system/
├── tokens.css              # Plain CSS custom properties (Hub captive portal, static pages)
├── tokens.json             # Token dictionary with the Compose and Tailwind name of each token
├── desktop/
│   ├── tailwind.theme.ts   # Tailwind config extension: laraTheme + laraType (Tailwind 3 style or `@config`)
│   └── theme.css           # Same tokens as a Tailwind 4 @theme block
├── mobile/                 # Jetpack Compose theme, package org.lara.app.ui.theme
│   ├── Color.kt            # object LaraColors (Primary, Ai, Success, WarningLight, ...)
│   ├── Type.kt             # Nunito family and LaraTypography
│   ├── Shape.kt            # LaraSpacing, LaraShapes, MinTouchTarget, Modifier.laraShadow()
│   ├── Theme.kt            # LaraTheme { } (light only)
│   └── res/font/           # Nunito .ttf files for Android res/font
├── assets/                 # Bundled offline assets, never a CDN
│   ├── fonts/              # Nunito woff2 + fonts.css
│   └── phosphor/           # Phosphor Regular, Fill, Bold woff2 + phosphor.css
├── design-system.md        # Written rules (tokens, components, accessibility, Google Classroom reference)
└── showcase.html           # Interactive sample, the visual source of truth (open in a browser)
```

## Principles
1. **Solid fills only, zero gradients.**
2. **Tokens only.** Use the named tokens. Never write a hex value or `Color(0xFF...)` in a screen.
3. **Purple means AI.** Purple appears only on Socratic tutor surfaces.
4. **Phosphor icons only.** Regular by default, Fill for the active navigation item, Bold inside checkboxes.
5. **Nunito everywhere,** bundled locally.
6. **52 minimum touch target,** 56 for primary actions and quiz options.
7. **Never rely on color alone:** pair every status color with an icon and a text label.
8. **Offline first:** every screen has an offline state; say "Hub" or "classroom network", never "internet".

## How to use it

### Android (`mobile/`)
1. Copy `mobile/Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt` to `mobile/app/src/main/java/org/lara/app/ui/theme/`.
2. Copy `mobile/res/font/*.ttf` to `mobile/app/src/main/res/font/`.
3. Add the Phosphor Compose dependency `com.adamglin:phosphor-icon`.
4. Wrap the app in `LaraTheme { ... }`; use `LaraColors.*`, `LaraShapes`, `LaraSpacing` and `Modifier.laraShadow(...)`. Colors and shadows on pupil screens must come from these, never from literals.

### Desktop (`desktop/`)
1. Copy `assets/` to `desktop/src/assets/design-system/`.
2. In `index.css` import `fonts/fonts.css` and `phosphor/phosphor.css` from that copy.
3. **Tailwind 4:** `@import "../../design-system/desktop/theme.css";` after `@import "tailwindcss";`.
   **Tailwind 3 style config:** `theme: { extend: laraTheme }` from `tailwind.theme.ts`.
4. Use utilities such as `bg-lara-primary`, `text-lara-ink-2`, `shadow-card`, `rounded-xl`, and the class strings in `laraType`. Icons: `@phosphor-icons/react`.

### Hub captive portal (`server/`)
Link `tokens.css` plus `assets/fonts/fonts.css` and `assets/phosphor/phosphor.css`. Use the CSS variables (`var(--color-primary-green)`), no inline hex values.

## Keeping it in sync (Lead process)
1. Change the design in `design-system/showcase.html` (its token variables and its React and Kotlin code blocks).
2. Update the matching files in this folder and the tables in `design-system/design-system.md`.
3. Run `python3 -m unittest discover tests`. `tests/test_design_tokens.py` compares every color, radius and shadow in the showcase with `tokens.css`, `tokens.json`, `Color.kt`, `tailwind.theme.ts` and `theme.css`, and fails on drift.
4. Merge as a Lead-reviewed design-system PR; teams then update their code.

## Fonts and icons are bundled
Nunito (SIL OFL) and Phosphor (MIT) ship in `assets/` with their licenses. No page or app may load them from a CDN: the guardrail scanner rejects Google Fonts, unpkg, cdnjs and jsDelivr.
