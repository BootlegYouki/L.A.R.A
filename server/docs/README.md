# Server & Local Hub Subsystem Documentation

This directory contains architectural notes, network service flows, and concurrency guides maintained by the **Server Development Team**.

Whenever implementing or refactoring Local Hub features, document them here so the Lead Developer and future teammates have immediate technical context:

* Discovery & Beacon Protocol (`discovery_mdns_udp.md`)
* Central SQLite Migrations & Delta-Sync Ledger (`database_sync.md`)
* Video Streaming & Token-Bucket Rate Limiter (`streaming_rate_limit.md`)
* `llama-server` Process Manager & FIFO Queue (`slm_inference_queue.md`)
* DepEd Class Record Excel & USB Flash Export (`deped_exporter.md`)
