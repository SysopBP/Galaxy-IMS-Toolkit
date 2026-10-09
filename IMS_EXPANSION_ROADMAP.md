# Galaxy IMS Toolkit expansion roadmap

Single existing app, default `main` branch, normal Debug APK workflow; preserve black/glass UI and compact floating navigation.

## Phase 1 — Smart IMS XML Lab
- Discover IMS XML files dynamically; verify actual paths, permissions, and report errors instead of claiming files absent.
- Parse recognizable settings into typed switches, dropdowns, and text fields based on XML schema; keep unknown values in advanced XML editor.
- Per-SIM search/filter, original vs modified comparison, validation, reset original draft, and export.
- Treat original device XML as read-only until safe apply is independently validated.

## Phase 2 — Backend manager
- Verify Root UID 0, TokenX UID 1000, Shizuku, and shell independently in app (Termux success does not prove app authorization).
- Route operations by capability, report actual execution UID, permissions, and failures; no silent fallback for writes.
- Avoid restarting or killing system_server.

## Phase 3 — Live IMS dashboard
- Dual-SIM registration, VoLTE, VoWiFi, RCS, video calling, registration transport, timestamped diagnostics.
- Prefer Samsung `dumpsys secims` RegistrationManager and subscription information; distinguish configured flags from confirmed active service.
- Sanitize subscriber identifiers and network secrets in exports.

## Phase 4 — Backup and recovery
- Timestamped local snapshots, hashes, side-by-side diffs, validation, exports, and explicit restoration confirmation.
- No direct live writes until backup, compatibility, permission checks, verification, and rollback are proven.

## Phase 5 — CSC module builder
- Build KernelSU-compatible CSC overlays rather than overwrite partitions; carrier profile explorer and differences.
- Validate compatibility and provide removal/rollback instructions; avoid unsupported carrier provisioning claims.

## Current implementation notes
- XML Lab supports manual import, read-only auto-discovery attempts, comparison, draft editing/validation/export, and draft reset.
- Root backend identity check is being introduced; TokenX routing is not yet wired into XML Lab.
- Existing IMS diagnostics use Samsung registration signals; test on physical SIM and eSIM.
- Never claim a changed XML value guarantees carrier/network IMS enablement. Protect calling and emergency services.
