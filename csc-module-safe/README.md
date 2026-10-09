# Safe CSC script replacements (inactive templates)

These scripts are adaptations of the **behavioral design**, not copies of upstream code, from [Mzdyl/OneUI_CSC_Features](https://github.com/Mzdyl/OneUI_CSC_Features). They intentionally **do not** install, mount, or modify CSC files.

Compared with upstream:
- No 180-round mount guardian; no bind-mount retry or lazy unmount.
- No hardcoded CSC fallback destinations.
- No camera service restart.
- No `settings put` side effects.
- Fail closed when an enable marker and verified target manifest are absent.
- Not wired into the current module ZIP generator or APK build.

To implement actual CSC changes, first verify exact paths and active CSC on the device, generate checksummed overlays, validate KernelSU's supported systemless mount layout, and test rollback in recovery. Do not install these as a functional CSC module.
