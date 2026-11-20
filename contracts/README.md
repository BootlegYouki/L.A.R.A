# Contracts: The Only Shared Surface Between Teams

Everything the three teams must agree on lives here. Nothing else is shared.

| Path | Defines | Used by |
| :--- | :--- | :--- |
| [`openapi.yaml`](./openapi.yaml) | Every REST route, request, response and error | Server implements; Mobile and Desktop call |
| [`events/`](./events/) | Every WebSocket event schema, the handshake and direction table ([README](./events/README.md)) | All three |
| [`schema/server_master.sql`](./schema/server_master.sql) | The Hub's authoritative SQLite schema | Server migrations |
| [`schema/client_offline.sql`](./schema/client_offline.sql) | The offline slice for clients | Room entities, desktop migrations |
| [`ai/socratic_system_prompt.txt`](./ai/socratic_system_prompt.txt) | The one Socratic system prompt, with `{reply_language}` and `{lesson_chunks}` placeholders | Bundled byte for byte by the Mobile and Desktop apps, which build the prompt on-device. The Hub never loads it |
| [`planned/document-pipeline.md`](./planned/document-pipeline.md) | **Planned, not implemented, not covered by tests:** teacher document upload and parse, chunk sync, model-file serving, the attempt state for the tutor lockout | Becomes OpenAPI in a `contract-change` PR when the post-defense issues start. Do not code against it |
| [`naming_rules.md`](./naming_rules.md) | Serialization, privacy and error rules | All three |

These files are checked by [`../tests/`](../tests/) and implemented by the real Hub in `server/backend`.

## Changing a contract (Lead-reviewed `contract-change` PR)

Contract changes are **never** part of a feature PR. They go first, in their own small PR, so all three teams branch from the same truth.

1. **Ask:** open or comment on the issue explaining the need. The Lead decides. If you think the design itself is wrong or could be better, not just a missing field, open a *Design Proposal* issue first (`rules/team-workflow-and-prs.md` section 1.2).
2. **Change together** (one PR, label `contract-change`):
   * `contracts/openapi.yaml`, `contracts/events/*.json` and/or `contracts/schema/*.sql`. Keep both SQL files in parity (see `rules/database-and-sync.md`).
   * `tests/` so the change is covered. `tests/test_contract_coverage.py` fails if a path has no route in the Hub source or an event is undocumented.
3. **Verify:** `python3 -m unittest discover tests` and `python3 scripts/verify_invariants.py`.
4. **Tag all three team leads** in the PR. The Lead merges.
5. **Then** each team updates its code on a normal feature branch from the new `staging`.

## Rules every contract must follow
* `snake_case` JSON keys; epoch **milliseconds** for timestamps; UUID v4 strings for ids; `UPPER_SNAKE` enum values.
* Errors use `{"error": {"code", "message"}}` with stable codes (for example `UNAUTHORIZED`, `FORBIDDEN`, `CLASS_CODE_INVALID`, `TIME_LIMIT_EXCEEDED`, `COMMENTS_DISABLED`, `FILE_TOO_LARGE`, `QUIZ_NOT_DRAFT`, `QUIZ_NOT_STARTED`, `QUIZ_ALREADY_ACTIVE`, `QUIZ_CLOSED`, `ADMIN_EXISTS`).
* Student-facing shapes never include `correct_answer`. Client-facing shapes never include `pin_hash`, another person's LRN or server file paths.
* The sync cursor is an integer sequence, never a timestamp.
