# LSPosed companion scaffold

This folder is **not included in the current APK or CI build**. It is a source-only starting point, not an installable Xposed module.

Target package: `com.sec.imsservice`. No methods are hooked, no data is collected, and no return values are changed.

To make it installable: add a separate Android Gradle module with compileOnly Xposed API, declare the module entrypoint in `assets/xposed_init` and module metadata, then verify exact firmware method signatures on the test phone. A secure, bounded, opt-in IPC transport must be designed before recording live events. Do not hook `system_server` by default or record subscriber identifiers or call content.
