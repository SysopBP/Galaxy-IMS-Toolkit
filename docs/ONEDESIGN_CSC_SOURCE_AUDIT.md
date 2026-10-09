# Full-scope OneDesign and OneUI_CSC_Features examination

Examined 2026-10-09.

## Sources and limitations
- https://github.com/Xposed-Modules-Repo/qyz.onedesign — repository exposes **README only**. It documents functionality and dependencies but does not publish its hook implementation in this repository. Do not claim source-level compatibility with One UI 9.
- https://github.com/Mzdyl/OneUI_CSC_Features — inspected tree, README, csc.json, carrier.json, camera-feature.json, native csc_tool.c, customize.sh, service.sh and mount_guard.sh. It has a concrete implementation, but its China-oriented defaults and boot scripts are **not** safe to import unchanged on SM-S948U1.
- Device inventory: Samsung SM-S948U1, Android 17 / SDK 37; IMS preferences under /data/user_de/0/com.sec.imsservice/shared_prefs and /data/user/0/com.sec.imsservice/shared_prefs; CSC files under /optics/configs/carriers/<CODE>/conf and IMS update JSON under /prism/etc/carriers/<CODE>.

## OneUI_CSC_Features: concrete findings
1. Feature definitions are declarative JSON records with `key`, `value`, `command` (MODIFY/DELETE), `desc` and `enabled`. All inspected reference examples are disabled by default. Import this *schema* into the Toolkit, not unverified values.
2. `csc.json` includes settings, voice call, calendar, contacts, SystemUI, RIL, messaging, camera, clock and IMS-facing keys. The Toolkit UI includes a small, explicitly unverified reference subset.
3. `carrier.json` includes VoLTE capability, mobile network menu, network mode, RIL and network indicator branding keys. A configured TRUE value does not prove a modem or carrier actually supports VoLTE.
4. `camera-feature.json` has many camera capability keys. Treat as a separate optional future domain, not an IMS toggle.
5. Native `csc_tool.c` includes custom processing and JSON/XML format detection; review input bounds, XML parser behavior and licensing before any reuse. No native code copied.
6. Installation uses `customize.sh` and a persistent configuration directory `/data/adb/csc_config`. Service scripts actively rebind mounts, restart camera services and write settings; **do not copy these behaviors** into the Toolkit.
7. README documents replacements to optics customer.xml/omc.info, CSC XML, carrier JSON, and system apps/permissions. Replacing whole carrier files across regions risks incompatibility.

## OneDesign: documented architecture, not available implementation
- Advertises LSPosed/Xposed, YukiHookAPI, DexKit, Compose, Room, Hilt and libsu.
- Feature families: localization, phone/dialer, camera, framework, SystemUI, package management, Knox/security, and region-specific UI.
- Source-level method signatures, safety guarantees, and compatibility with Android 17/One UI 9 cannot be established from README. No OneDesign code copied.
- Do not implement Knox bypass or security disabling as part of IMS Toolkit.

## Integration matrix
| Area | Adopt now | Requires verification | Excluded |
|---|---|---|---|
| CSC feature catalog | Declarative key/description reference, searchable comparison | Exact feature presence in loaded CSC | Automatic enablement |
| Carrier feature JSON | Read-only JSON parser, source path, hashes | Active carrier selection and value semantics | Hard-coded CHC values |
| KernelSU module | Offline draft and validation | Correct target path, module overlay semantics, boot safety | Rebinding loops or camera restarts |
| LSPosed | Separate source scaffold, scope restricted to IMS | Framework activation, class signatures, secure IPC, event heartbeat | system_server hooking by default |
| IMS live status | UI states explicitly marked unavailable | Registration events, SIM mapping, VoWiFi/RCS | Claiming a working hook without evidence |

## Implementation next steps
1. Make catalog data-driven and compare every catalog key against actual imported CSC/JSON; group supported, absent and unknown keys.
2. Add verified active CSC detection and per-carrier grouping; never equate presence on disk with active carrier.
3. Build and sign a separate installable LSPosed companion APK; validate narrow read-only hooks on the user's firmware and communicate over signature-protected IPC.
4. Add safe systemless overlay generator with exact file paths, checksum, baseline backup and opt-in install. Test disable/rollback before enabling any writes.
5. CI compile checks, on-device IMS registration tests, reboot safety, and KernelSU module validation.

This is a source review and incremental UI incorporation, **not** a claim that the full external feature set has been ported.
