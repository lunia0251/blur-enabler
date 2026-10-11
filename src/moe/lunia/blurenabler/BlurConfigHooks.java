// SPDX-License-Identifier: Apache-2.0

package moe.lunia.blurenabler;

import android.content.res.Resources;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Forces config_enableBlurExpansion and config_enableAppLaunchBlur to true. Pixel vendor RROs (e.g.
 * framework-res__panther__auto_generated_rro_vendor) set both to false. SystemUI reads the former
 * once through Resources.getSystem() in shared.system.BlurUtils, Pixel Launcher reads both through
 * Resources.getBoolean(int).
 */
final class BlurConfigHooks {
    private static boolean sInstalled;

    private BlurConfigHooks() {}

    static void install() {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        Resources system = Resources.getSystem();
        int blurExpansion = system.getIdentifier("config_enableBlurExpansion", "bool", "android");
        int appLaunchBlur = system.getIdentifier("config_enableAppLaunchBlur", "bool", "android");
        if (blurExpansion == 0 || appLaunchBlur == 0) {
            XposedBridge.log(BlurEnabler.TAG + "blur config resources not found");
            return;
        }
        XposedHelpers.findAndHookMethod(
                Resources.class,
                "getBoolean",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int id = (int) param.args[0];
                        if (id == blurExpansion || id == appLaunchBlur) {
                            param.setResult(true);
                        }
                    }
                });
        XposedBridge.log(BlurEnabler.TAG + "blur config hooks installed");
    }
}
