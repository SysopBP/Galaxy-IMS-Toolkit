# Galaxy IMS Toolkit — LSPosed integration

## Integration status

The main app currently builds as `:app`. The `xposed-companion` directory is not registered in `settings.gradle`; **do not describe it as an active LSPosed module or a working hook** until its Android module, entry points and runtime handshake have been verified.

## Implementation requirements

1. Preserve the existing `com.sysopbp.galaxyims` application, UI, XML Lab, backup/restore and Root/Shizuku/TokenX backend routing. Do not replace the app with a separate frontend.
2. Audit `xposed-companion` source, Gradle configuration, manifest and LSPosed module metadata. Register the companion as a Gradle subproject only once its build files and dependencies are validated.
3. Implement a read-only IMS observer initially. Target Samsung IMS packages only after identifying the installed package and compatible method signatures on the test device. Avoid hooks that modify provisioning or telephony state.
4. Provide explicit statuses in the app: LSPosed unavailable, module not enabled, scope missing, hook loaded, observer active, observer error. Do not infer successful hooks from the mere presence of LSPosed.
5. Exchange status/events using a permission-protected IPC mechanism; never expose IMS events or phone identifiers to arbitrary apps. Redact identifiers from diagnostics.
6. Handle Android 17 / One UI 9 signature and class changes gracefully, with exception isolation, rate limiting, and a kill switch. Never crash SystemUI, Samsung IMS, or the host process if hooks fail.
7. Add unit/build checks and release both the main APK and companion artifact (if separate). Confirm app identity and UI continuity before publishing.

## Validation checklist

- Main APK builds and launches with existing glass UI and all navigation intact.
- XML Lab, comparisons, defaults restoration and existing backends continue working.
- Companion builds independently and its LSPosed metadata is recognized.
- Disabled/absent LSPosed displays an accurate inactive state without crashes.
- Enabled scoped hooks report a verified runtime handshake and real IMS registration events.
- Both SIM slots, VoLTE, VoWiFi and RCS are reported only when supported by actual data sources.
- Crash logs and diagnostics can be exported without leaking subscriber identifiers.

**Note:** This file records implementation and acceptance criteria, not completion of runtime hooking.