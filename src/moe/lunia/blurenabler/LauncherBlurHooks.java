// SPDX-License-Identifier: Apache-2.0

package moe.lunia.blurenabler;

import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Re-implements com.android.launcher3.blurred_home_animation, which R8 stripped from stock Pixel
 * Launcher where the flag is a read-only false: ScalingWorkspaceRevealAnim.addBlurLayer() is empty,
 * while applyBlur() and removeBlurLayer() still animate and remove the layer.
 */
final class LauncherBlurHooks {
    private static final String TAG = "ScalingWorkspaceRevealAnim";

    private static boolean sInstalled;

    private LauncherBlurHooks() {}

    static void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        try {
            // The constructor calls addBlurLayer() when playBlur is set, but the prebuilt oat code
            // has dropped the call to the now empty method, so hook the constructor instead.
            XposedBridge.hookAllConstructors(
                    XposedHelpers.findClass(
                            "com.android.quickstep.util.ScalingWorkspaceRevealAnim", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object[] args = param.args;
                            if (args.length > 0
                                    && args[args.length - 1] instanceof Boolean playBlur
                                    && playBlur) {
                                addBlurLayer(param.thisObject);
                            }
                        }
                    });
            sInstalled = true;
            XposedBridge.log(BlurEnabler.TAG + "launcher blur hooks installed");
        } catch (Throwable t) {
            BlurEnabler.log("launcher blur hooks unavailable", t);
        }
    }

    /** ScalingWorkspaceRevealAnim.addBlurLayer */
    private static void addBlurLayer(Object anim) {
        Object launcher = XposedHelpers.getObjectField(anim, "launcher");
        View dragLayer = (View) XposedHelpers.getObjectField(launcher, "mDragLayer");
        Object viewRootImpl = XposedHelpers.callMethod(dragLayer, "getViewRootImpl");
        if (viewRootImpl == null) {
            return;
        }
        SurfaceControl parent =
                (SurfaceControl) XposedHelpers.callMethod(viewRootImpl, "getSurfaceControl");
        if (!parent.isValid()) {
            Log.e(TAG, "Parent surface is not ready at the moment. Can't apply blur.");
            return;
        }
        SurfaceControl.Builder builder =
                new SurfaceControl.Builder()
                        .setName("Home to launcher blur layer")
                        .setParent(parent)
                        .setOpaque(false)
                        .setHidden(false);
        try {
            XposedHelpers.callMethod(builder, "setCallsite", TAG);
        } catch (NoSuchMethodError ignored) {
            // Debug metadata only.
        }
        SurfaceControl blurLayer = builder.build();

        Object setupTransaction =
                XposedHelpers.newInstance(
                        XposedHelpers.findClass(
                                "com.android.quickstep.util.SurfaceTransaction",
                                anim.getClass().getClassLoader()));
        Object properties = XposedHelpers.callMethod(setupTransaction, "forSurface", blurLayer);
        XposedHelpers.callMethod(XposedHelpers.callMethod(properties, "setAlpha", 0f), "setShow");
        XposedHelpers.callMethod(
                XposedHelpers.getObjectField(anim, "surfaceTransactionApplier"),
                "scheduleApply",
                setupTransaction);

        XposedHelpers.setObjectField(anim, "blurLayer", blurLayer);
    }
}
