# Galaxy IMS Toolkit 0.2 audit changes

- Root-only app_process carrier worker; authorized Root/System Binder routing remains.
- Shell authorization distinguished from unsupported carrier write capability.
- Serialized and batched writes, broker acknowledgement, readback before IMS reset.
- Errors leave controls usable; changed keys reload after success or failure.
- Effective carrier snapshots are checksummed, firmware/subscription-bound, saved
  before writes, exportable to Download, and restorable. They are not modem backups
  and do not preserve the original persistent override provenance.
- Root commands have bounded output and timeouts.
- XML attribute staging supports duplicate occurrence keys and proper escaping.
- Generated ZIP shell scripts use actual newlines; CSC ZIPs remain reference templates.
- Optional read-only LSPosed observer built into the same APK. Firmware compatibility
  is established by a real handshake, not an installed-manager check.
- SIM profile pages, Download/share diagnostics, global theme palettes and photo
  brightness slider; bounded background decode off the UI thread.
- SDK-failing duplicate workflow now reuses the maintained APK build workflow.
- XML regression checks run before APK compilation.

Device validation is required for Samsung permission enforcement, root-worker
hidden APIs, broker no-restart instrumentation, IMS registration reset, snapshot
restore, LSPosed callback compatibility and module installation.
