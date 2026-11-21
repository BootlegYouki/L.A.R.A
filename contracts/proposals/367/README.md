# Activity-history draft artifacts (#367)

These are the original review artifacts retained for provenance. The definitions have now been promoted on this PR branch:

- `contracts/openapi.yaml`: route, parameters and Activity DTOs.
- `contracts/schema/server_master.sql`: private activity tables.
- `contracts/activity_logs.md`: event catalog, safety limits, retention and paging semantics.
- `tests/test_activity_logs.py`: canonical SQL and reference-API checks.

Those canonical files take precedence. This PR is still unmerged; no deployed Rust recorder/API or GUI activity page is claimed. Server implementation is #368, after contract merge and the explicit post-defense hold.

The snapshot SQL/schema and focused fixtures remain useful for reviewing the original limits. Run `uv run --python 3.12 --with pyyaml --with jsonschema python -m unittest discover -s contracts/proposals/367 -p test_contract.py`. The fixture test also checks that the promoted DTO shapes match the safe snapshot. jsonschema is a development-only uv dependency; no runtime or current CI dependency is added.

Any team member can author this additive contract without prior Tech Lead approval under governance commit `7bed6b3`. Written OK from the consuming Server team and a co-lead tests/invariants check and merge are still required. Separate design-proposal and Lead-owned PRD/design-system decisions are not implicitly accepted by contract authorship permission.