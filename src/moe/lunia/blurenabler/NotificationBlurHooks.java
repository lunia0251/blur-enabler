// SPDX-License-Identifier: Apache-2.0

package moe.lunia.blurenabler;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Re-implements com.android.systemui.lockscreen_blur_for_notifications, which R8 stripped from
 * stock SystemUI where the flag is a read-only false. Stock keeps notification_row_transparency, so
 * rows are already translucent in the shade; this makes them translucent on the lockscreen too and
 * restores NotificationBackgroundView's BackgroundBlurDrawable, mirroring upstream
 * ActivatableNotificationView and NotificationBackgroundView.
 */
final class NotificationBlurHooks {
    private static final String ROW = "com.android.systemui.statusbar.notification.row";
    private static final String STACK = "com.android.systemui.statusbar.notification.stack";

    private static final Map<View, Drawable> sBlurDrawables = new WeakHashMap<>();
    private static Class<?> sActivatableClass;
    private static Class<?> sStackClass;
    private static boolean sInstalled;
    private static final Map<View, View.OnAttachStateChangeListener> sAttachListeners =
            new WeakHashMap<>();

    private NotificationBlurHooks() {}

    static void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        try {
            hookRowBackground(cl);
            hookBackgroundView(cl);
            hookAlphaPropagation(cl);
            sInstalled = true;
            XposedBridge.log(BlurEnabler.TAG + "notification blur hooks installed");
        } catch (Throwable t) {
            BlurEnabler.log("notification blur unavailable", t);
        }
    }

    private static void hookRowBackground(ClassLoader cl) throws NoSuchMethodException {
        Class<?> activatable = XposedHelpers.findClass(ROW + ".ActivatableNotificationView", cl);
        sActivatableClass = activatable;
        Class<?> row = XposedHelpers.findClass(ROW + ".ExpandableNotificationRow", cl);

        // Stock: mIsBlurSupported && !mOnKeyguard. Upstream: mIsBlurSupported &&
        // notificationRowTransparency() && (!mOnKeyguard || lockscreenBlurForNotifications()).
        XposedBridge.hookMethod(
                activatable.getDeclaredMethod("usesTransparentBackground"),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(
                                XposedHelpers.getBooleanField(
                                        param.thisObject, "mIsBlurSupported"));
                    }
                });
        // The prebuilt oat code may inline usesTransparentBackground() into these.
        deoptimize(row, "usesTransparentBackground");
        deoptimize(activatable, "calculateBgColor", boolean.class, boolean.class);
        deoptimize(row, "setBackgroundTintColor", int.class);
        deoptimize(row, "setOnKeyguard", boolean.class);
        deoptimizeAll(
                XposedHelpers.findClass(
                        ROW + ".ExpandableNotificationRowController$$ExternalSyntheticLambda2", cl),
                "accept");
        deoptimizeAll(
                XposedHelpers.findClass(
                        "com.android.systemui.statusbar.notification.shelf.ui.viewbinder"
                                + ".NotificationShelfViewBinder$bind$2$2$2$1",
                        cl),
                "emit");

        // Upstream calls mBackgroundNormal.setBlurBackgroundEnabled(usesBlurredBackground()) from
        // initBackground(), setIsBlurSupported() and setOnKeyguard(). Stock reduced those to
        // updateBackgroundTint(); usesTransparentBackground(); so re-sync after every tint update.
        XposedHelpers.findAndHookMethod(
                activatable,
                "updateBackgroundTint",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object view = param.thisObject;
                        boolean usesBlurredBackground =
                                (boolean)
                                        XposedHelpers.callMethod(view, "usesTransparentBackground");
                        setBlurBackgroundEnabled(
                                (View) XposedHelpers.getObjectField(view, "mBackgroundNormal"),
                                usesBlurredBackground);
                    }
                });
    }

    private static void hookBackgroundView(ClassLoader cl) throws NoSuchMethodException {
        Class<?> background = XposedHelpers.findClass(ROW + ".NotificationBackgroundView", cl);
        deoptimize(background, "setCustomBackground$1");
        deoptimize(background, "setRadius", float.class, float.class);
        deoptimize(
                XposedHelpers.findClass(ROW + ".ActivatableNotificationView", cl),
                "setBackgroundTintColor",
                int.class);

        XposedHelpers.findAndHookMethod(
                background,
                "onDraw",
                Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;
                        Drawable blur = sBlurDrawables.get(view);
                        if (blur != null) {
                            drawBackground(view, (Canvas) param.args[0], blur);
                            param.setResult(null);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                background,
                "setTint",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Drawable blur = sBlurDrawables.get((View) param.thisObject);
                        if (blur != null) {
                            XposedHelpers.callMethod(blur, "setColor", param.args[0]);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                background,
                "updateBackgroundRadii",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        updateBlurRadii((View) param.thisObject);
                    }
                });

        XposedHelpers.findAndHookMethod(
                background,
                "verifyDrawable",
                Drawable.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Drawable blur = sBlurDrawables.get((View) param.thisObject);
                        if (blur != null && param.args[0] == blur) {
                            param.setResult(true);
                        }
                    }
                });
    }

    /**
     * Stock still overrides setAlpha() in these three, but the AlphaPropagationHelper call that
     * passed the alpha down to the rows' blur drawables was stripped. Without it, rows that are
     * hidden through alpha (e.g. collapsed into the lockscreen shelf) keep their blur regions.
     */
    private static void hookAlphaPropagation(ClassLoader cl) {
        sStackClass = XposedHelpers.findClass(STACK + ".NotificationStackScrollLayout", cl);
        XC_MethodHook propagate =
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        onAlphaChanged((View) param.thisObject);
                    }
                };
        XposedHelpers.findAndHookMethod(
                ROW + ".ExpandableView", cl, "setAlpha", float.class, propagate);
        XposedHelpers.findAndHookMethod(
                STACK + ".NotificationChildrenContainer", cl, "setAlpha", float.class, propagate);
        XposedHelpers.findAndHookMethod(sStackClass, "setAlpha", float.class, propagate);
    }

    private static void onAlphaChanged(View changed) {
        for (Map.Entry<View, Drawable> entry : sBlurDrawables.entrySet()) {
            for (Object v = entry.getKey(); v instanceof View; v = ((View) v).getParent()) {
                if (v == changed) {
                    updateBlurAlpha(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
    }

    private static void updateBlurAlpha(View view, Drawable blur) {
        blur.setAlpha(blurAlpha(view));
        LockscreenBlurHooks.refreshBlurPath(blur);
    }

    /** NotificationBackgroundView.setBlurBackgroundEnabled */
    private static void setBlurBackgroundEnabled(View view, boolean enabled) {
        if (enabled && !sBlurDrawables.containsKey(view)) {
            View.OnAttachStateChangeListener old = sAttachListeners.remove(view);
            if (old != null) {
                view.removeOnAttachStateChangeListener(old);
            }
            View.OnAttachStateChangeListener listener =
                    new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View v) {
                            if (!sBlurDrawables.containsKey(view)) {
                                createBlurDrawable(view);
                            }
                            view.removeOnAttachStateChangeListener(this);
                            sAttachListeners.remove(view);
                        }

                        @Override
                        public void onViewDetachedFromWindow(View v) {}
                    };
            sAttachListeners.put(view, listener);
            view.addOnAttachStateChangeListener(listener);
            if (view.isAttachedToWindow()) {
                listener.onViewAttachedToWindow(view);
            }
        } else if (!enabled) {
            View.OnAttachStateChangeListener listener = sAttachListeners.remove(view);
            if (listener != null) {
                view.removeOnAttachStateChangeListener(listener);
            }
            Drawable blur = sBlurDrawables.remove(view);
            if (blur != null) {
                blur.setCallback(null);
                view.invalidate();
            }
        }
    }

    private static void createBlurDrawable(View view) {
        Object viewRootImpl = XposedHelpers.callMethod(view, "getViewRootImpl");
        if (viewRootImpl == null) {
            return;
        }
        Drawable blur =
                (Drawable) XposedHelpers.callMethod(viewRootImpl, "createBackgroundBlurDrawable");
        XposedHelpers.callMethod(
                blur,
                "setBlurRadius",
                LockscreenBlurHooks.dimenPx(
                        view.getContext(), "notification_background_blur_radius"));
        XposedHelpers.callMethod(blur, "setXfermode", (Object) null);
        blur.setCallback(view);
        // Upstream sets mNormalColor and relies on the setTint() that follows in
        // updateBackgroundTint(); this runs after it, so take the current tint.
        int tint = XposedHelpers.getIntField(view, "mTintColor");
        XposedHelpers.callMethod(
                blur,
                "setColor",
                tint != 0 ? tint : XposedHelpers.getIntField(view, "mNormalColor"));
        sBlurDrawables.put(view, blur);
        updateBlurRadii(view);
        view.invalidate();
    }

    private static void updateBlurRadii(View view) {
        updateBlurRadii(view, false, false);
    }

    /** Clipped edges are cut flat, like the clipped stock background. */
    private static void updateBlurRadii(View view, boolean topClipped, boolean bottomClipped) {
        Drawable blur = sBlurDrawables.get(view);
        if (blur == null || XposedHelpers.getBooleanField(view, "mDontModifyCorners")) {
            return;
        }
        float[] r = ((float[]) XposedHelpers.getObjectField(view, "mCornerRadii")).clone();
        if (topClipped) {
            r[0] = r[1] = r[2] = r[3] = 0f;
        }
        if (bottomClipped) {
            r[4] = r[5] = r[6] = r[7] = 0f;
        }
        XposedHelpers.callMethod(
                blur, "setCornerRadius", r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]);
    }

    /** Stock NotificationBackgroundView.onDraw, drawing the blur drawable as upstream does. */
    private static void drawBackground(View view, Canvas canvas, Drawable blur) {
        int clipTopAmount = XposedHelpers.getIntField(view, "mClipTopAmount");
        int clipBottomAmount = XposedHelpers.getIntField(view, "mClipBottomAmount");
        float clipTop = Math.max(clipTopAmount, XposedHelpers.getIntField(view, "mTopOverlap"));
        int clipBottom =
                Math.max(clipBottomAmount, XposedHelpers.getIntField(view, "mBottomOverlap"));
        boolean expandAnimationRunning =
                XposedHelpers.getBooleanField(view, "mExpandAnimationRunning");
        int actualHeight = (int) XposedHelpers.callMethod(view, "getActualHeight$1");
        if (clipBottom + clipTop >= actualHeight && !expandAnimationRunning) {
            return;
        }
        canvas.save();
        if (!expandAnimationRunning) {
            canvas.clipRect(0f, clipTop, view.getWidth(), actualHeight - clipBottom);
        }
        int bottom = actualHeight;
        if (XposedHelpers.getBooleanField(view, "mBottomIsRounded")
                && XposedHelpers.getBooleanField(view, "mBottomAmountClips")
                && !expandAnimationRunning) {
            bottom -= clipBottom;
        }
        boolean alignedToRight = (boolean) XposedHelpers.callMethod(view, "isAlignedToRight");
        int width = view.getWidth();
        int actualWidth = XposedHelpers.getIntField(view, "mExpandAnimationWidth");
        if (!expandAnimationRunning || actualWidth <= -1) {
            actualWidth = XposedHelpers.getIntField(view, "mActualWidth");
            if (actualWidth <= -1) {
                actualWidth = width;
            }
        }
        int left = alignedToRight ? width - actualWidth : 0;
        int right = alignedToRight ? width : actualWidth;
        if (expandAnimationRunning) {
            left = (int) ((width - actualWidth) / 2f);
            right = left + actualWidth;
        }
        // The blur region ignores canvas and view clipping, so clip the bounds to what stock
        // shows: the clipRect above and the row's clip bounds, which hide rows collapsed into the
        // shelf. Without this they keep blurring at full size under the lockscreen shelf.
        Rect bounds = new Rect(left, 0, right, bottom);
        boolean visible =
                expandAnimationRunning
                        || bounds.intersect(0, (int) clipTop, width, actualHeight - clipBottom);
        Rect rowClip = view.getParent() instanceof View row ? row.getClipBounds() : null;
        if (visible && rowClip != null) {
            rowClip.offset(-view.getLeft(), -view.getTop());
            visible = bounds.intersect(rowClip);
        }
        if (visible) {
            updateBlurRadii(view, bounds.top > 0, bounds.bottom < bottom);
            blur.setAlpha(blurAlpha(view));
            blur.setBounds(bounds);
            blur.draw(canvas);
        }
        canvas.restore();
    }

    /**
     * mDrawableAlpha times the alpha of every view up to NotificationStackScrollLayout, standing in
     * for the stripped AlphaPropagationHelper. On the lockscreen the stack also fades out with the
     * scene container's graphicsLayer, so follow the lockscreen transitionAlpha there too.
     */
    private static int blurAlpha(View view) {
        float alpha = XposedHelpers.getIntField(view, "mDrawableAlpha");
        for (Object v = view; v instanceof View; v = ((View) v).getParent()) {
            alpha *= ((View) v).getAlpha();
            if (sStackClass.isInstance(v)) {
                break;
            }
        }
        Object row = view.getParent();
        if (sActivatableClass.isInstance(row)
                && XposedHelpers.getBooleanField(row, "mOnKeyguard")) {
            alpha *= LockscreenBlurHooks.sLockscreenTransitionAlpha;
        }
        return (int) alpha;
    }

    static void onLockscreenTransitionAlphaChanged() {
        for (Map.Entry<View, Drawable> entry : sBlurDrawables.entrySet()) {
            updateBlurAlpha(entry.getKey(), entry.getValue());
        }
    }

    private static void deoptimize(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        LockscreenBlurHooks.deoptimize(type.getDeclaredMethod(name, parameterTypes));
    }

    private static void deoptimizeAll(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                LockscreenBlurHooks.deoptimize(method);
            }
        }
    }
}
