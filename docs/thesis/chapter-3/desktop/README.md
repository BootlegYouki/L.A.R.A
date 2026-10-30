# Desktop Team — Chapter III Documentation

**Issue:** [#174](https://github.com/BootlegYouki/L.A.R.A/issues/174)  
**Branch:** `docs/desktop-ch3-174`  
**Team:** Desktop (Aki-QA and kerrV)  
**Status:** Draft — Awaiting PNG exports and Lead approval

## Contents

This directory contains the desktop team's Chapter III (Design and Methodology) manuscript sections:

### Sections

1. **3.1.1 Research Design** (`3.1.1-research-design.md`)
   - Descriptive-developmental research approach
   - Agile/Scrum development model (6 sprints)
   - Development phases from contracts through field evaluation
   - **Diagram:** Development model (Sprint 0–6)

2. **3.2.2 Proposed Project Development Cost** (`3.2.2-development-cost.md`)
   - Hardware costs (Hub PC, router, USB, optional UPS)
   - Software costs (all open-source, PHP 0)
   - Development and other costs
   - Paperless cost savings analysis

3. **3.2.4 Implementation Plan** (`3.2.4-implementation-plan.md`)
   - 9-step pilot school deployment process
   - Implementation timeline table
   - Post-pilot support responsibilities

4. **3.2.1.5 Desktop Sitemap** (`3.2.1.5-sitemap-desktop.md`)
   - Learner navigation structure
   - Teacher navigation structure (+ management features)
   - Implementation status (Sprint 0–6)
   - **Diagram:** Desktop application sitemap

5. **3.2.3 Development and Testing (Desktop)** (`3.2.3-development-and-testing-desktop.md`)
   - Technology stack with verified versions
   - Development methodology (contract-first, mock Hub, sprints)
   - Testing strategy and verification approach

### Diagrams

Located in `diagrams/`:

- `development-model.drawio` — Editable draw.io source
- `development-model.png` — ⚠️ **NEEDS EXPORT** (see instructions below)
- `sitemap-desktop.drawio` — Editable draw.io source
- `sitemap-desktop.png` — ⚠️ **NEEDS EXPORT** (see instructions below)
- `README-EXPORT-INSTRUCTIONS.md` — Step-by-step PNG export guide

## Outstanding Actions

### 1. Export PNG Diagrams (Required Before PR Merge)

Open draw.io desktop application and export both diagrams:

```
File → Export as → PNG...
- Zoom: 200%
- Border Width: 0
- Background: White (uncheck "Transparent")
- Selection Only: Unchecked
```

See `diagrams/README-EXPORT-INSTRUCTIONS.md` for detailed steps.

### 2. Outstanding Questions (14 Total)

**Research Design (2):**
- Research type terminology confirmation
- Mobile team coordination status

**Development Cost (7):**
- Hardware specifications and prices
- Labor costs inclusion
- Survey/transportation costs

**Implementation Plan (5):**
- Responsible persons for each step
- Duration estimates
- Deployment dates and pilot school name

All questions marked with `[ASK LEAD: ...]` in the respective files.

## Evidence Sources

All content is evidence-based from:
- `docs/PRD.md` (Sections 1, 3, 4, 5, 7, 9, 10)
- `desktop/docs/TECH_SPEC.md`
- `desktop/package.json`
- `server/docs/hub_hardware.md`
- `design-system/prototype.html`
- GitHub Milestones (Sprint 0, 5, 6)
- `AGENTS.md`
- Issue #173 (Mobile coordination)

## Validation Status

✅ All 5 Markdown files created  
✅ Both .drawio files created  
⚠️ PNG exports pending (manual action required)  
✅ No modifications outside `docs/thesis/chapter-3/desktop/`  
✅ Terminology consistent (Learner, Teacher, Admin, Hub)  
✅ All unsupported facts marked with `[ASK LEAD: ...]`  
✅ Image links use correct relative paths  

## File Structure

```
docs/thesis/chapter-3/desktop/
├── README.md (this file)
├── 3.1.1-research-design.md
├── 3.2.1.5-sitemap-desktop.md
├── 3.2.2-development-cost.md
├── 3.2.3-development-and-testing-desktop.md
├── 3.2.4-implementation-plan.md
└── diagrams/
    ├── README-EXPORT-INSTRUCTIONS.md
    ├── development-model.drawio
    ├── development-model.png (⚠️ needs export)
    ├── sitemap-desktop.drawio
    └── sitemap-desktop.png (⚠️ needs export)
```

## Next Steps

1. **Export PNGs** from .drawio files (you must do this manually)
2. **Review** all 5 Markdown sections for accuracy
3. **Coordinate** with Mobile team on research design (Wed/Thu)
4. **Obtain** Lead answers to 14 outstanding questions
5. **Commit and push** after PNGs are ready and you approve
6. **Create PR** to `staging` with labels `type:doc` and `scope:desktop`

## Notes

- Desktop is in Sprint 0 (specification phase) — sitemap documents planned navigation
- Mobile team coordination required for sections 3.1.2 and 3.1.3
- Mobile use case diagram review pending (before Thursday night)
- All content follows academic formatting: third person, formal English, evidence-based
