package dev.bluehouse.enablevolte.xposed;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.Set;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Fail-closed observer: exact registration signatures only; no mutation hooks. */
public final class ImsObserverEntry implements IXposedHookLoadPackage {
    private static final Uri PROVIDER = Uri.parse("content://com.sysopbp.galaxyims.observer");
    private Context context;
    private String state = "Waiting for IMS classes";
    private long lastEvent;
    private int attempts;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean enabled() {
        try {
            Bundle config = context.getContentResolver().call(PROVIDER, "config", null, null);
            return config != null && config.getBoolean("enabled");
        } catch (Throwable ignored) { return false; }
    }
    private void report(String method, Bundle data) {
        try { context.getContentResolver().call(PROVIDER, method, null, data); }
        catch (Throwable ignored) { /* A failed IPC must never affect Samsung IMS. */ }
    }
    private void heartbeat() {
        if (enabled()) {
            Bundle data = new Bundle(); data.putString("state", state);
            report("heartbeat", data);
        }
        handler.postDelayed(this::heartbeat, 30000);
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (!"com.sec.imsservice".equals(param.packageName) ||
            !"com.sec.imsservice".equals(param.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (context != null) return;
                context = (Context) hook.args[0];
                handler.post(() -> discover(param.classLoader));
                handler.post(ImsObserverEntry.this::heartbeat);
            }
        });
    }

    private void discover(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClassIfExists(
                "com.sec.internal.ims.core.RegistrationManagerBase", loader);
            int hooks = 0;
            if (type != null) for (Method method : type.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!"notifyImsRegistration".equals(method.getName()) || args.length < 2 ||
                    !"com.sec.ims.ImsRegistration".equals(args[0].getName()) || args[1] != boolean.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        try {
                            if (!enabled()) return;
                            Object registration = hook.args[0];
                            boolean registered = (Boolean) hook.args[1];
                            int slot = (Integer) XposedHelpers.callMethod(registration, "getPhoneId");
                            Object raw = XposedHelpers.callMethod(registration, "getServices");
                            if (!(raw instanceof Set) || slot < 0 || slot > 1) return;
                            Set<?> services = (Set<?>) raw;
                            Bundle data = new Bundle();
                            data.putInt("slot", slot);
                            data.putBoolean("mmtel", registered && services.contains("mmtel"));
                            boolean rcs = services.contains("im") || services.contains("ft") ||
                                services.contains("ft_http") || services.contains("slm");
                            data.putBoolean("rcs", registered && rcs);
                            try {
                                int rat = (Integer) XposedHelpers.callMethod(registration, "getRegiRat");
                                data.putBoolean("volte", registered && services.contains("mmtel") && (rat == 13 || rat == 20));
                                data.putBoolean("vowifi", registered && services.contains("mmtel") && rat == 18);
                            } catch (Throwable ignored) { /* RAT unknown: do not infer Wi-Fi calling. */ }
                            report("registration", data);
                        } catch (Throwable ignored) { state = "Observer error"; }
                    }
                });
                hooks++;
            }
            if (hooks > 0) state = "Observer active";
            else if (++attempts < 5) {
                state = "Waiting for IMS classes";
                handler.postDelayed(() -> discover(loader), 5000);
            } else state = "Unsupported firmware signatures";
        } catch (Throwable ignored) { state = "Observer error"; }
    }
}
