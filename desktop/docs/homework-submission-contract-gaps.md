# Homework submission gaps for cross-team review

Requested behavior: let a learner attach up to three files, remove each selected file independently, and Turn in the complete set; allow Mark as done without a file. This is a pending contract requirement, not an implemented or approved API.

Verified against origin/staging on 22 November 2026:
- POST /api/assignments/{id}/submit requires one binary `file` and one `submission_id`.
- The Hub stores a single file_path for each unique (assignment_id, student_id).
- Submitting a different ungraded file replaces the previous file. Repeating the current route three times does not retain three attachments.
- No fileless-completion route or submission state exists.

Required coordination before Desktop can enable these controls:
1. Contracts define a submission containing zero-to-three attachments, explicit fileless completion, file/total size limits, and a teacher-readable attachment list.
2. Server stores the set atomically, preserves idempotent retries, enforces class/learner ownership and grading/deadline rules, and returns all attachment identities through sync and teacher review.
3. Desktop follows that merged contract: multi-file cards with per-file X, complete-set durable staging, Turn in, empty Mark as done, retry and offline recovery, plus teacher previews of every attachment.
4. Mobile uses the same submission semantics. No team-specific fake files, archive conversion or local-only Submitted status.

Acceptance checks: retain three distinct files on the Hub; show all three to the teacher; remove one draft file without affecting the others; retry without duplicates or data loss; restart offline with the staged set intact; record fileless completion on the Hub and teacher view.

Current Desktop intentionally remains single-file to prevent silently replacing earlier attachments. No Server, Mobile or contract files were edited for this request.
