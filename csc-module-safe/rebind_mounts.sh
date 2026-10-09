#!/system/bin/sh
# Safe replacement for upstream bind-mount retry helpers.
# No fallback paths or destructive unmount operations.
log_mount() { :; }
rebind_all_targets() { return 0; }
ensure_all_targets() { return 0; }
restart_camera_services() { return 1; }
