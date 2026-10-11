// SPDX-License-Identifier: Apache-2.0

package moe.lunia.blurenabler;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public final class BlurEnabler implements IXposedHookLoadPackage {
    static final String TAG = "BlurEnabler: ";

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(lpparam.processName)) {
            return;
        }
        switch (lpparam.packageName) {
            case SYSTEM_UI -> {
                BlurConfigHooks.install();
                LockscreenBlurHooks.install(lpparam.classLoader);
                NotificationBlurHooks.install(lpparam.classLoader);
                PowerMenuHooks.install(lpparam.classLoader);
            }
            case LAUNCHER -> {
                BlurConfigHooks.install();
                LauncherBlurHooks.install(lpparam.classLoader);
            }
            default -> {}
        }
    }

    static void log(String message, Throwable t) {
        XposedBridge.log(TAG + message + ": " + t);
    }
}
