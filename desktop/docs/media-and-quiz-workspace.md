# Inline media and wide quiz workspace

Desktop-only follow-up, 22 November 2026. Preserve L.A.R.A tokens and current authoring behavior.

- Raster attachments (PNG/JPEG/WebP/GIF) render within announcements and assignment attachment areas. Click an image for a larger accessible dialog. MP4/WebM use native video controls, preload none and no autoplay. Documents, unsupported types and unavailable/offline previews retain the attachment card.
- When MIME metadata is absent from sync, Desktop probes at most 32 bytes through the existing file route and recognizes passive raster/video signatures. It does not infer the type from a title or extension. A native Desktop loopback adapter forwards authenticated existing Hub download/stream routes, including video Range headers. The adapter exposes an opaque local URL, never the Hub token; it validates current account selection and passive media types, rejects redirects, bounds responses at 250 MiB and releases listeners when the component unmounts (two-hour expiry as a backstop). No Server/Mobile/contracts changes.
- The selected quiz design is a wide workspace: title/time sidebar, question cards with two-column option inputs, scrollable question area and fixed action footer. At narrow widths it becomes one column. Quiz payloads, answer selection and validation remain unchanged; answers are not newly persisted.

Verification (Windows real-Hub QA): build and lint passed; 175 frontend tests passed; 50 native tests passed (3 existing opt-in skips). Both existing owl-image posts rendered and enlarged. The existing QA MP4 returned HTTP 206 with Content-Range and decoded in WebView2. Quiz footer and dialog bounds passed at 1200, 820 and 720 pixels with four questions; visual review confirmed the side settings panel and compact answer grid. Frontend preview tests cover unsupported/offline fallback, listener release, video lazy loading/no autoplay and playback-error fallback. Native tests exclude active media types such as SVG/HTML.

Limits: previews require a reachable Hub; this does not add offline image caching or new video codecs. Existing attachment navigation remains the fallback. Desktop local changes are not an installer publication.

## Follow-up: recent image cache and learner assignment layout

Images use the same account/Hub/material/revision preview lease across tab/class navigation. Idle leases expire after five minutes, with at most eight idle entries; logout or switching account/Hub clears them. Each native lease buffers at most 8 MiB for a passive image, coalesces concurrent requests and serves repeat reads locally after the normal account/token checks. Videos remain streamed. Large/unavailable images retain the file-card fallback. Cache data is memory only and does not survive exiting the app. Recently viewed images can be read while the Hub is unreachable if the cached account token remains valid.

The learner assignment page uses instructions plus attachment previews in a main column and a compact Your work/private-comments column. At narrow widths it stacks. Duplicate Your work headings and the empty-submission filler were removed; attachment/upload/comment behavior is unchanged. Classwork image previews are smaller thumbnails with enlargement available.

Cache/layout verification: 179 frontend tests and 51 native tests pass (3 existing opt-in native skips); build and lint pass. Native QA retained the exact image URL across tab and classroom navigation, served the cached PNG twice with the QA Hub stopped and restored immediately afterwards, and contained the learner layout at 1200/820/720 pixels. Teacher work review retains its existing roster flow.

## Selected homework file and learner posting

A selected (unsubmitted) homework attachment now has an accessible 40px X control. It clears both the React draft and native input value, restores focus to the picker, clears stale errors and allows selecting the same file again. It is disabled while staging; it does not remove a queued or submitted homework record.

Active learners have the text announcement composer per contract #388. It sends no teacher-only allow_comments field, hides comment moderation and the unsupported learner file picker, rejects pending/removed/rejected/nonmember/archived classes, and leaves teacher Classwork controls restricted. The running real QA Hub and current staging Server still reject learner announcement POSTs. Desktop preserves the draft with a specific Hub-update message on that rejection. Learner posting is therefore prepared on Desktop, not verified as successful end to end; learner attachment management and own-post editing/deletion are not implemented in this follow-up. No Server/Mobile/contracts files changed.

## Reference-style Your work card

Selected files appear as a compact filename/type/thumbnail card with the X beside it, above Add attachment and Turn in. Selected PNG/JPEG thumbnail reads are capped at 8 MiB and cancelled when selection changes. Larger images retain a file icon. The card's status is beside its heading. Styling retains the green theme and existing submission flow.

Mark as done appears disabled in the empty state with an explicit explanation: the current contract requires `file` and `submission_id` on `/api/assignments/{id}/submit`. There is no fileless completion route or supported completion flag to sync to the teacher. This needs a contract/Server implementation before Desktop can enable it. No dummy attachment or local-only submitted state was introduced.
