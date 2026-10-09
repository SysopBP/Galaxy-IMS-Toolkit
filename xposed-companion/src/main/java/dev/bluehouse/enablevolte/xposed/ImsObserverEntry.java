package dev.bluehouse.enablevolte.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Companion module entrypoint (source scaffold, not yet part of the APK build).
 * Only Samsung IMS service is in scope. No hooks or state changes are installed.
 * Requires a separate Android library/module with Xposed API compileOnly dependency.
 */
public final class ImsObserverEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.sec.imsservice".equals(param.packageName)) {
            return;
        }
        // TODO: firmware-specific class/method signature discovery.
        // TODO: bounded redacted event transport with explicit user consent.
        // No method hooks until verified on SM-S948U1 / Android 17.
    }
}
