# Homework capture — MOBILE 3.2 (#9)

## Prerequisites checked

Started from staging `4a01df0`, which includes merged MOBILE 3.1.1 (#250) and all earlier mobile steps.

| Requirement | Staging result |
|---|---|
| MOBILE 2.5 offline sync | Present. Account-isolated Room, atomic pulls, reconnect scheduling and queued-work preservation |
| Assignment and submission storage | Present in Room and the shared client schema, including `QUEUED_FOR_SYNC` and rejection reasons |
| Upload contract | `POST /api/assignments/{id}/submit`, multipart `submission_id` and `file`, returns `Submission` |
| Mock upload | Present in `scripts/mock_hub.py`, including replacement of an existing learner submission |
| Previous mobile UI | #250 merged into staging before branching |
| Camera dependencies and permission | Required by this step, absent on the starting branch |
| Real Hub uploads | Pending SERVER 3.3 (#53). Main upload integration must be repeated when it lands |

## Boundaries

The PRD and contract take precedence over the design-system assignment table's take-back action. Supported submission actions follow the contract's file-replacement route. Graded resubmission semantics remain an open PRD question, so graded work stays read-only in the capture screen and Hub grades are preserved.

Camera files stay app-private. Compression runs off the main thread, corrects orientation, fits within a 1920-by-1080 envelope in either orientation, starts at JPEG quality 75 and reduces quality until strictly below 800 KB. Capture originals are removed after a compressed copy is safely written. Gallery originals are never modified.

Queued files must remain durable through process death, sign-out, retries and sync resets. A submission keeps the same client ID across retries. Receipts must match the account and assignment and may acknowledge only the exact file version sent, so a replacement queued during an upload cannot be marked synced by the older response.

## Implementation

- `HomeworkScreen` opens from the learner's Classwork assignment row. Instructions and submission status read from Room. The draft photo remains a separate private file until Turn in writes the durable submission row.
- `CameraCaptureScreen` binds CameraX preview and file capture to the screen lifecycle, releases its own use cases on exit, requests camera permission, and supplies the framing overlay. Gallery selection imports a private copy with a 64 MB source-file safety limit.
- `HomeworkPhoto` performs sampled decoding, EXIF orientation correction, resizing and bounded JPEG compression off the UI thread. Replacement draft files are removed only after the new copy is saved.
- `HomeworkRepository` commits the file reference and Hub binding together, retains queued files on failures and rejects inactive memberships locally. A per-account mutex serializes photo replacement and upload. Successful replacement removes the superseded local file after the transaction commits.
- `HomeworkApi` streams the multipart body with the existing bearer session, bounded responses and redirects disabled. The Hub may return a canonical submission ID created by another device. The receipt adopts that ID atomically while retaining the local file. `CacheDao` preserves a conflicting queued local photo during the preceding pull.
- `HomeworkUploadWorker` reconciles sync before uploading. The existing delta worker also flushes photos after successful sync, covering periodic, connectivity and foreground Hub-reachability triggers. The normal queued-work sign-out warning includes homework already.
- A successful Hub handshake schedules the existing reconnect worker immediately, bypassing an earlier offline retry chain. Announcement events retain the regular queue. Phone evidence covers foreground recovery with Wi-Fi unchanged.
- Replacement drafts explicitly say that Turn in is required for the new version. Bottom navigation reserves space for two enlarged label lines at 200% text.

Implementation and evidence live in `mobile/`. Room version 2 stores accepted JPEG references. Capture and document review use CameraX, EXIF and OpenCV.

## OpenCV document review

The requested enhancement uses the official OpenCV 4.13.0 Android AAR, with its native libraries bundled and its Apache license in `assets/licenses/`. Initialization is lazy and image work runs off the UI thread with two native processing threads.

Captured and imported photos first become an orientation-corrected, bounded, lossless PNG. Transparent images receive a white paper background. Detection runs on a grayscale copy with its longest edge at most 640 pixels. Locally contrast-enhanced edges and a shadow-tolerant bright-paper mask supply page candidates. Separate ordinary, shadow-tolerant and brighter paper masks retain candidates that a single threshold would merge with the desk or remove under shadow. Convex boundaries bridge small edge interruptions, and their enclosed area determines candidate size. An irregular contour can propose a fitted rectangle, which must still remain within the image and pass all four edge checks. Each candidate must pass a coverage check against the lower-quartile background brightness before the largest valid four-corner region covering at least a quarter of the image is selected. Each of its four sides must also show paper brighter than its immediate surroundings at least eight of fifteen sampled positions. This rejects large candidates that merge furniture with the paper. A rejected larger object cannot hide a valid smaller page.

The camera shows a static framing guide. Page detection runs only after capture or gallery import, followed by perspective correction and enhancement. This avoids live-frame allocation and disagreement between a preview outline and the captured image.
Capture and gallery import automatically open an adjusted, enhanced preview. Confident page boundaries receive perspective correction. Unclear edges receive an enhanced full-photo preview. Manual adjustment is optional through Adjust corners, and the preview explains which result is shown.

`DocumentReviewScreen` provides corner dragging, a corner selector and labeled horizontal/vertical sliders. Handles have 52dp touch regions. Dragging outside the handles permits scrolling. Crossed, collapsed, out-of-range and tiny page boundaries disable Preview scan. The learner can always choose the full photo or cancel the review.

Enhance clarity defaults on. It estimates paper illumination at low resolution, blends a mild shadow correction into neutral regions, and applies CLAHE to luminance while retaining chroma. Colored regions retain their original illumination before the contrast step. The preview supports returning to corner adjustment and switching enhancement off. Use this photo creates the final JPEG under 800 KB and replaces the local draft. Turn in queues the accepted image. Cancelling review preserves earlier drafts and submissions. Draft cleanup checks Room references before deleting a JPEG, protecting a queued file after process restoration.

Native libraries use compressed APK packaging. The universal debug APK is approximately 80 MB with the bundled OpenCV engine and the existing supported architectures.

This extends the original framing-guide scope at the user's request. Quality, native-library size, memory and timing evidence are recorded alongside the normal capture/upload checks. Budget-device certification remains a separate verification requirement.

## Verification still required

Automated checkpoint passed 164 Debug and 164 Release tests, lint, 67 shared tests and invariants. It includes mock upload and retry checks. Results are in [evidence/9](evidence/9/README.md).

Phone evidence now covers real camera capture, the camera-permission fallback, gallery import, offline cold launch, sign-out retention, automatic foreground reconnect, replacement upload and 200% text. The real captured JPEG was 1080×1440 and 112,387 bytes. The gallery replacement reached the mock byte-for-byte with one submission retained. A later foreground reconnect uploaded in 9.03 seconds with Wi-Fi unchanged.

The target 3–4 GB budget-phone compression measurement, peak-memory profiling, physical isolated-LAN testing and real-Hub submission integration remain pending. The Xiaomi reports 7,495,244 kB of physical memory; its synthetic 12MP timing results are in the evidence and apply to that device.
