#!/system/bin/sh
# Galaxy IMS Toolkit: intentionally inert unless user explicitly enables validated overlay.
MODDIR=${0%/*}
[ -f "$MODDIR/enable_overlay" ] || exit 0
[ -f "$MODDIR/verified-targets.list" ] || exit 0
# No mount retries, watchdog, camera restart, settings writes or system_server restart.
# KernelSU module systemless mounting should be configured via supported module layout.
exit 0
