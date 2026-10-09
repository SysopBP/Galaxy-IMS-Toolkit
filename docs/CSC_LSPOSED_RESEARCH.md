# CSC and LSPosed integration research (SM-S948U1 / Android 17)

## References
- OneDesign Xposed repository: https://github.com/Xposed-Modules-Repo/qyz.onedesign
- OneUI CSC feature module: https://github.com/Mzdyl/OneUI_CSC_Features
- Systemless CSC module reference: https://github.com/alexdonh/magisk-samsung-csc-features

OneDesign's public README describes YukiHookAPI, DexKit and hooks for regional features, framework, SystemUI and Samsung services. The README is **not** evidence that its private implementation can be copied or that a particular hook works on One UI 9. Avoid using its Knox/security-bypass features.

## Device-confirmed source paths
- /data/user_de/0/com.sec.imsservice/shared_prefs: imsconfig_{0,1}, imsprofile_{0,1}, imsswitch_{0,1}, globalsettings_{0,1}, CSC_INFO_PREF, OMCNW_CODE and more.
- /data/user/0/com.sec.imsservice/shared_prefs: additional IMS preferences.
- /optics/configs/carriers/<CSC>/conf/customer.xml
- /optics/configs/carriers/<CSC>/conf/system/cscfeature.xml
- /optics/configs/carriers/<CSC>/conf/system/customer_carrier_feature.json
- /prism/etc/carriers/<CSC>/imsupdate.json
These were reported by the user's read-only inventory; existence does not establish which CSC is active.

## Implementation sequence
1. Read-only LSPosed integration capability screen: distinguish module installed, framework active, scope configured, and hook heartbeat. Never claim UID 1000 from the presence of rish.
2. Separate LSPosed companion module with narrow opt-in scope for com.sec.imsservice, no system_server hooks by default. Avoid collecting phone numbers, subscriber identifiers, call content, authentication tokens, or IMS secrets.
3. Discover actual class/method signatures on the user's firmware before enabling any hook; gracefully handle absent or changed methods. Record only timestamp, SIM slot, registration state and redacted reason codes.
4. IPC to main APK with signature-protected access and bounded event history. No untrusted exported receiver, no logs in public storage by default.
5. CSC library: group by carrier code and type, detect active CSC from verified properties and/or loaded runtime data, display diffs and hashes, and distinguish carrier JSON from XML.
6. Keep overrides disabled until compatibility, recovery and rollback are tested. Never overwrite source CSC/IMS files in place.

## Known limitations
The existing CSC ZIP exporter is an inactive template. The existing app auto-discovery reads copies, not live IMS state. Runtime hooking requires an installed LSPosed-compatible framework, a compiled companion module, scope selection and real-device testing. No working hook is claimed here.
