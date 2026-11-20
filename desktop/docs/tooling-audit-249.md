# Desktop tooling audit — issue #249

## Scope

Issue [#249](https://github.com/BootlegYouki/L.A.R.A/issues/249) flags development-only test/lint dependencies. Starting point: staging `54cfbdc` (2026-11-20). This fix updates `package.json` and `package-lock.json`; no application, database, contract, fixture or test configuration changes are needed.

- Vitest 3.2.7 → 4.1.12, removing the vulnerable `tinypool` dependency and updating `@vitest/mocker`.
- TypeScript ESLint 8.72.0 → 8.73.0, including its patched `minimatch` dependency.

The npm registry metadata for Vitest 4.1.12 accepts Node 22 and Vite 6; TypeScript ESLint 8.73.0 accepts ESLint 9 and TypeScript 5.9. The existing Vite 6/React plugin configuration, Node test environment, test globs and scripts remain compatible without overrides or relaxed peer checks.

## Before/after audit

Both are full `npm audit --json` checks in `desktop/`, including development dependencies, against the live registry on 2026-11-20.

| Severity | Before | After fresh `npm ci --no-audit` |
|---|---:|---:|
| Critical | 2 | 0 |
| High | 0 | 0 |
| Moderate | 8 | 0 |
| Low / informational | 0 | 0 |
| Total | 10 | 0 |

Before: critical `tinypool` / `vitest`; moderate TypeScript ESLint / `minimatch` and Vitest mocker findings. After: `vulnerabilities: {}`. Relevant advisories: [tinypool worker options](https://github.com/advisories/GHSA-5gmw-xhrv-c9v3), [tinypool run options](https://github.com/advisories/GHSA-85c8-ppgw-ccpr), [Vitest mock redirect](https://github.com/advisories/GHSA-82fw-gwwq-j7x9), [manual mock registration](https://github.com/advisories/GHSA-qxg6-89mg-rqv8), [minimatch amplification](https://github.com/advisories/GHSA-g3hv-75wg-m249).

## Verification

Windows, Node 22.19.0, npm 10.9.3; isolated worktree `node_modules`, process TEMP/TMP and npm cache on G:.

| Check | Result |
|---|---|
| `npm ci --no-audit` | Passed, 247 packages installed from the updated lockfile |
| `npm audit --json` | Passed, zero vulnerabilities |
| `npm test` | Passed, 21 files / 99 tests, Vitest 4.1.12 |
| `npm run lint` | Passed, zero warnings |
| `npm run build` | Passed, TypeScript and Vite 6.4.5 production build |
| `python scripts/verify_invariants.py` | Passed with `PYTHONIOENCODING=utf-8` |
| `python -m unittest discover tests` | Passed, 72 tests with the project Python runtime and dependency path |
| Production dependency comparison | Declarations and all resolved production versions unchanged |
| `git diff --check` | Passed |

Shared Python checks used the bundled primary-runtime Python, `PYTHONPATH=G:\Codex\LARA\work\python-packages`, `PYTHONUTF8=1` and `PYTHONIOENCODING=utf-8` so subprocess output and scanner checkmarks decode correctly on Windows.

npm 10.9.3's resolver repeatedly crashed internally (`Cannot read properties of null (reading 'edgesOut')`) when installing Vitest 4, including after a clean baseline `npm ci`. The targeted update used task-local `npx --yes npm@11.6.2 install --save-dev vitest@^4.1.12`; no system npm update or peer-check bypass. A subsequent fresh `npm ci` and all desktop checks used the installed npm 10.9.3 successfully. npm also warned that the existing ESLint 9.39.5 is unsupported; upgrading unrelated ESLint majors is outside this security fix. Node emitted its existing experimental SQLite warning.

## Limits

No native Tauri/Rust, release installer, Linux/macOS, physical LAN or real-Hub run was performed for this development-tool-only change. No installed app or user data was touched. Audit results reflect the registry on the recorded date and may change with new advisories.
