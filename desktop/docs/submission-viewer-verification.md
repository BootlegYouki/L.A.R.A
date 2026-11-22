# Submitted files and live comment threads — 22 Nov 2026

Desktop-only fixes for #407. Base: staging b860308.

- Submitted images use the existing Radix preview pattern: Close preview, Escape and focus restoration.
- Open file and Download submitted file invoke a native, account-authorized export. They never navigate the main WebView. Files are copied without overwriting to the OS Downloads folder. Open file launches the OS default viewer; PDFs are not embedded in the WebView.
- Ordered submission attachment metadata is validated, persisted by additive migration 006 and retained by grading reads. Each attachment uses its contract index and a separate account-scoped cache filename. A successful format replacement removes superseded extensions so exports cannot select an older photo.
- While a comment thread is open, visible and connected, a five-second timer requests the existing coalesced sync and rereads SQLite. Existing sync backoff remains authoritative. Closing the thread clears the timer; drafts stay in component state. No new Hub routes or events.

Verification:
- Packaged custom-protocol app against the real QA Hub: submitted PNG opens; Close and Escape return to assignment; image and PDF downloads match original bytes; app URL unchanged; PDF Open launches Edge with the exported PDF.
- Real-Hub private and class comments arrive without Refresh while unsent drafts remain unchanged.
- Hub-stopped final packaged app: cached PDF downloads and comment history remain available; teacher replies are disabled. Cold-start remembered authentication was not established in the tool runtime (secure credential persistence unavailable); this is not an authentication change.
- Checks passed: 202 frontend tests; 54 native tests (3 opt-in tests skipped); 92 shared tests; lint, production build and offline invariant guard.
- Automated regression coverage: native preview MIME allowlist and authorization, attachment metadata bounds/sanitization, additive database parity, attachment parsing, safe PDF actions, comment polling and draft retention/offline cleanup.

Limits: Windows verified. Linux/macOS system viewers not exercised. The existing compiled QA Hub supports legacy single submissions; multiple indexed attachments are covered by parsing/native validation but not a live batch integration run. Automatic approval review rejected building the newer Server subsystem under Desktop-only scope. No installer replacement or staging merge is part of this change.
