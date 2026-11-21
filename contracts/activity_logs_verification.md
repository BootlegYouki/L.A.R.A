# Activity contract verification (#367)

2026-11-21, isolated branch `contract/hub-activity-logs`, updated from staging `ad18913` (includes #364).

## Scope

Canonical API/DTO and private master-schema definitions are promoted in this branch. `activity_logs.md` specifies recording, redaction, retention, queue failure and stable paging. The existing simulator receives only a bounded in-memory GET reference because current staging still requires simulator parity. This does not add a simulator dependency to the Rust Hub. The schema-count test includes the two Hub-only internal tables; their exclusion from client storage and sync is tested.

No Rust feature, applied migration or product UI is changed. No production database is opened or migrated. Full durable capture and the three-page console remain #368 after the contract is merged and the post-defense hold permits work.

## Checks

```text
python -m unittest discover tests
Ran 83 tests in 11.751s
OK

python -m unittest discover -s contracts/proposals/367 -p test_contract.py
Ran 10 tests in 0.217s
OK
```

The 10 new shared tests check strict private payload/schema fields, valid SQL, authenticated ADMIN/locality restrictions, bearer-header-only access, no-store, empty pages, old/new paging, a 250-entry catch-up with arrivals during paging, combined filters, invalid parameters, history reset, seven-day expiry, the 20,000-row cap and separate recording/read failures. Query strings are removed from the reference diagnostic request output.

Python 3.12/PyYAML came from uv. The standalone snapshot-schema checks also use development-only jsonschema from uv; runtime and CI dependencies are unchanged.

## Not verified and review gates

The reference resets in memory and is not evidence for production restart durability, crash recovery, queue throughput, disk-full handling or UI behavior. No Rust/UI build, router isolation, native console run or physical-device validation was performed for this contract-only step.

The current Rust parity and all-route registration tests compare the running implementation with the full canonical contract. This branch intentionally adds definitions ahead of the runtime, so that coverage must be coordinated with the dependent #368 implementation; a passing shared Python suite is not a claim that the Rust API now serves the new route or migrates these tables. Keep this PR draft and do not weaken runtime tests to hide that gap. Consuming-team written OK, canonical-contract merge and resolution of the explicit post-defense hold remain prerequisites to server feature work. Any separate shared design/PRD changes retain their documented ownership process.
