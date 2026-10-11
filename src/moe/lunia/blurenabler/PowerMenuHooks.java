// SPDX-License-Identifier: Apache-2.0

package moe.lunia.blurenabler;

import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Lets the power menu's window blur reach under the navigation bar. Stock blurs it with
 * Window.setBackgroundBlurRadius(), whose BackgroundBlurDrawable only covers the decor view, and
 * SystemUIDialog keeps navigationBars() in the window's fitInsetsTypes, so the bottom inset stays
 * sharp. Deliberate deviation from stock.
 */
final class PowerMenuHooks {
    private static boolean sInstalled;

    private PowerMenuHooks() {}

    static void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.globalactions.GlobalActionsDialogLite",
                    cl,
                    "createDialog",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Window window = ((Dialog) param.getResult()).getWindow();
                            WindowManager.LayoutParams attrs = window.getAttributes();
                            attrs.setFitInsetsTypes(
                                    attrs.getFitInsetsTypes()
                                            & ~WindowInsets.Type.navigationBars());
                            window.setAttributes(attrs);
                            // Keep the menu where stock lays it out by padding the content by
                            // the navigation bar inset the window now extends over.
                            View content = window.getDecorView().findViewById(android.R.id.content);
                            int bottomPadding = content.getPaddingBottom();
                            content.setOnApplyWindowInsetsListener(
                                    (v, insets) -> {
                                        v.setPadding(
                                                v.getPaddingLeft(),
                                                v.getPaddingTop(),
                                                v.getPaddingRight(),
                                                bottomPadding
                                                        + insets.getInsets(
                                                                        WindowInsets.Type
                                                                                .navigationBars())
                                                                .bottom);
                                        return insets;
                                    });
                        }
                    });
            sInstalled = true;
            XposedBridge.log(BlurEnabler.TAG + "power menu hooks installed");
        } catch (Throwable t) {
            BlurEnabler.log("power menu hooks unavailable", t);
        }
    }
}
