# Optional Samsung IMS observer

The main APK includes the xposed-companion Android library and legacy LSPosed metadata.
There is one app identity: com.sysopbp.galaxyims. Enable this APK in LSPosed and scope
only com.sec.imsservice. Enable Optional LSPosed observer in the app, then restart.

The observer discovers notifyImsRegistration methods only when the first parameter
is com.sec.ims.ImsRegistration and the second is boolean. Unsupported signatures
report inactive after bounded discovery retries. This is not a claim that the target
SM-S948U1 firmware has that signature; confirm the real heartbeat on-device.

The hook never changes arguments, return values, provisioning, settings or permissions.
The provider validates the caller UID/package. Events contain only slot, registered
service flags and heartbeat state; no IMSI, ICCID, SIP identity, phone number or addresses.
Disable the app switch to stop event collection without another reboot.

VoLTE is derived from mmtel plus LTE/NR RAT, Wi-Fi calling from mmtel plus IWLAN RAT,
and RCS from messaging/file-transfer service membership. Missing RAT stays unknown.
An event describes the callback observed, not proof of a completed call or message.
No system_server hooks or active provisioning overrides are installed.

## Device verification
- Main APK launches with LSPosed disabled.
- Root-only, Root Binder and System Binder routes report actual failures/readback.
- Enable module scoped to Samsung IMS and verify Observer active heartbeat.
- Change registration for each SIM and verify redacted events.
- Disable observer and confirm event collection stops.
- Unsupported classes/methods must not crash Samsung IMS.

CSC exports remain reference templates: a deployable overlay requires a verified
firmware-specific target path, baseline and mount behavior. No guessed CSC mounts
or direct shared-preference writes have been introduced.
