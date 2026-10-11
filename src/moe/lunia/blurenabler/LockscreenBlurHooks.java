// SPDX-License-Identifier: Apache-2.0

package moe.lunia.blurenabler;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Re-implements the com.android.systemui.enable_lockscreen_blur code paths that R8 stripped from
 * stock SystemUI, where the flag is a read-only false. Mirrors upstream
 * DeviceEntryIconViewBinder/DeviceEntryBackgroundViewModel (fingerprint icon background) and
 * ShortcutElementProvider.drawBlurBehind/shortcutBackground (lockscreen shortcuts).
 */
final class LockscreenBlurHooks {
    private static final String KEYGUARD_UI = "com.android.systemui.keyguard.ui";

    // ShortcutElementProvider.DIM_ALPHA
    private static final float DIM_ALPHA = 0.3f;

    private static final View.OnLayoutChangeListener CORNER_RADIUS_LISTENER =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int width = right - left;
                int height = bottom - top;
                if (height <= 0 || width <= 0) {
                    return;
                }
                // Matches upstream and stock, which compare the width with oldLeft - oldRight.
                if (height == oldBottom - oldTop && width == oldLeft - oldRight) {
                    return;
                }
                Drawable background = v.getBackground();
                if (isBlurDrawable(background)) {
                    XposedHelpers.callMethod(
                            background, "setCornerRadius", Math.min(height, width) / 2f);
                }
            };

    private static final ThreadLocal<Object[]> sComposingShortcut = new ThreadLocal<>();
    private static final Map<View, Object> sDeviceEntryBlurInteractors = new WeakHashMap<>();
    private static final Map<View, Integer> sDeviceEntryColors = new WeakHashMap<>();
    private static final Map<View, Float> sDeviceEntryBgAlpha = new WeakHashMap<>();
    private static final Map<View, Float> sDeviceEntryParentAlpha = new WeakHashMap<>();
    private static final Map<Object, Map<String, Drawable>> sShortcutBlurDrawables =
            new WeakHashMap<>();
    private static final Map<Drawable, Boolean> sShortcutDimmed = new WeakHashMap<>();
    // AnimateAsState shared by a shortcut's content and graphicsLayer lambdas -> its view model
    // MutableState.
    private static final Map<Object, Object> sShortcutViewModels = new WeakHashMap<>();
    private static Object sTransitionAlphaState;
    static float sLockscreenTransitionAlpha = 1f;

    private static ClassLoader sClassLoader;
    private static Class<?> sBlurDrawableClass;
    private static Object sUnit;
    private static Method sGetSize;
    private static Method sGetDrawContext;

    private LockscreenBlurHooks() {}

    static void install(ClassLoader classLoader) {
        if (sClassLoader != null) {
            return;
        }
        try {
            sBlurDrawableClass =
                    XposedHelpers.findClass(
                            "com.android.internal.graphics.drawable.BackgroundBlurDrawable",
                            classLoader);
            sUnit =
                    XposedHelpers.getStaticObjectField(
                            XposedHelpers.findClass("kotlin.Unit", classLoader), "INSTANCE");
        } catch (Throwable t) {
            BlurEnabler.log("lockscreen blur unavailable", t);
            return;
        }
        sClassLoader = classLoader;
        try {
            hookDeviceEntryIcon(classLoader);
        } catch (Throwable t) {
            BlurEnabler.log("device entry icon blur unavailable", t);
        }
        try {
            hookShortcuts(classLoader);
        } catch (Throwable t) {
            BlurEnabler.log("shortcut blur unavailable", t);
        }
        XposedBridge.log(BlurEnabler.TAG + "lockscreen blur hooks installed");
    }

    private static void hookDeviceEntryIcon(ClassLoader cl) {
        // The repeatOnLifecycle(CREATED) block that sets up bgView. Runs before the stock
        // alpha/color collectors are launched, as in upstream.
        XposedHelpers.findAndHookMethod(
                KEYGUARD_UI + ".binder.DeviceEntryIconViewBinder$bind$5$1",
                cl,
                "invokeSuspend",
                Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object block = param.thisObject;
                        setUpDeviceEntryBlur(
                                (ImageView) XposedHelpers.getObjectField(block, "$bgView"),
                                XposedHelpers.getObjectField(
                                        block, "$windowRootViewBlurInteractor"),
                                XposedHelpers.getObjectField(block, "L$0"));
                    }
                });

        // R8-merged FlowCollector: $r8$classId 1 collects bgViewModel.alpha, anything above 1
        // collects bgViewModel.color.
        Class<?> collector =
                XposedHelpers.findClass(
                        KEYGUARD_UI + ".binder.DeviceEntryIconViewBinder$bind$2$1$1$1", cl);
        XposedHelpers.findAndHookMethod(
                collector,
                "emit",
                Object.class,
                XposedHelpers.findClass("kotlin.coroutines.Continuation", cl),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (XposedHelpers.getIntField(param.thisObject, "$r8$classId") < 2) {
                            return;
                        }
                        ImageView bgView =
                                (ImageView)
                                        XposedHelpers.getObjectField(param.thisObject, "$bgView");
                        int color = ((Number) param.args[0]).intValue();
                        sDeviceEntryColors.put(bgView, color);
                        param.args[0] = deviceEntryBackgroundColor(bgView, color);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (XposedHelpers.getIntField(param.thisObject, "$r8$classId") != 1) {
                            return;
                        }
                        View bgView =
                                (View) XposedHelpers.getObjectField(param.thisObject, "$bgView");
                        sDeviceEntryBgAlpha.put(bgView, ((Number) param.args[0]).floatValue());
                        updateDeviceEntryBlurAlpha(bgView);
                    }
                });

        // R8-merged FlowCollector: $r8$classId 1 collects DeviceEntryIconViewModel
        // .deviceEntryViewAlpha into DeviceEntryIconView's alpha.
        XposedHelpers.findAndHookMethod(
                KEYGUARD_UI + ".binder.DeviceEntryIconViewBinder$bind$2$1$2$1",
                cl,
                "emit",
                Object.class,
                XposedHelpers.findClass("kotlin.coroutines.Continuation", cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (XposedHelpers.getIntField(param.thisObject, "$r8$classId") != 1) {
                            return;
                        }
                        View bgView =
                                (View)
                                        XposedHelpers.getObjectField(
                                                XposedHelpers.getObjectField(
                                                        param.thisObject, "$view"),
                                                "bgView");
                        sDeviceEntryParentAlpha.put(bgView, ((Number) param.args[0]).floatValue());
                        updateDeviceEntryBlurAlpha(bgView);
                    }
                });
    }

    /**
     * Upstream only applies bgViewModel.alpha. The blur region doesn't inherit the parent
     * DeviceEntryIconView's alpha, nor the scene container's graphicsLayer alpha that fades the
     * lockscreen out on unlock (neither device entry alpha flow emits then), so apply both plus the
     * lockscreen shortcuts' transitionAlpha.
     */
    private static void updateDeviceEntryBlurAlpha(View bgView) {
        Drawable background = bgView.getBackground();
        if (!sDeviceEntryBlurInteractors.containsKey(bgView) || !isBlurDrawable(background)) {
            return;
        }
        float alpha =
                sDeviceEntryBgAlpha.getOrDefault(bgView, 1f)
                        * sDeviceEntryParentAlpha.getOrDefault(bgView, 1f)
                        * sLockscreenTransitionAlpha;
        background.setAlpha((int) (255 * alpha));
        refreshBlurPath(background);
    }

    private static void setUpDeviceEntryBlur(ImageView bgView, Object interactor, Object scope) {
        Object viewRootImpl = XposedHelpers.callMethod(bgView, "getViewRootImpl");
        if (viewRootImpl == null) {
            return;
        }
        Context context = bgView.getContext();
        Drawable background =
                (Drawable) XposedHelpers.callMethod(viewRootImpl, "createBackgroundBlurDrawable");
        // ??? Stock dropped fingerprint_icon_blur_radius together with the stripped code, so
        // reuse the lockscreen shortcut radius.
        XposedHelpers.callMethod(
                background, "setBlurRadius", dimenPx(context, "keyguard_shortcuts_blur_radius"));
        background.setVisible(false, false);
        bgView.setBackground(background);
        sDeviceEntryBlurInteractors.put(bgView, interactor);

        bgView.addOnLayoutChangeListener(CORNER_RADIUS_LISTENER);
        bgView.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {}

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        v.removeOnLayoutChangeListener(CORNER_RADIUS_LISTENER);
                        v.removeOnAttachStateChangeListener(this);
                    }
                });

        collect(
                scope,
                XposedHelpers.getObjectField(interactor, "isBlurCurrentlySupported"),
                isSupported -> {
                    Drawable bg = bgView.getBackground();
                    if (bg != null) {
                        bg.setVisible((Boolean) isSupported, false);
                        refreshBlurPath(bg);
                    }
                    // Upstream combines the color flow with isBlurCurrentlySupported; the stock
                    // color flow doesn't, so re-tint here when blur support changes.
                    Integer color = sDeviceEntryColors.get(bgView);
                    if (color != null) {
                        bgView.setImageTintList(
                                ColorStateList.valueOf(deviceEntryBackgroundColor(bgView, color)));
                    }
                });
    }

    /**
     * BackgroundBlurDrawable only rebuilds its clip path in setBounds() and on corner radius
     * changes, and leaves it empty while alpha is 0 or the drawable is invisible. bgView is laid
     * out while the alpha flow is still 0 after waking up, so without this the drawable never draws
     * and never dispatches its blur region.
     */
    static void refreshBlurPath(Drawable background) {
        background.setBounds(background.getBounds());
    }

    /** DeviceEntryBackgroundViewModel.color with enableLockscreenBlur(). */
    private static int deviceEntryBackgroundColor(View bgView, int stockColor) {
        // 0 means useBackgroundProtection is off.
        if (stockColor == 0) {
            return 0;
        }
        Object interactor = sDeviceEntryBlurInteractors.get(bgView);
        if (interactor != null && isBlurSupported(interactor)) {
            return surfaceEffect1(bgView.getContext());
        }
        return stockColor;
    }

    private static void hookShortcuts(ClassLoader cl) throws NoSuchMethodException {
        Class<?> drawScope =
                XposedHelpers.findClass("androidx.compose.ui.graphics.drawscope.DrawScope", cl);
        sGetSize = drawScope.getMethod("getSize-NH-jbRc");
        sGetDrawContext = drawScope.getMethod("getDrawContext");

        // KeyguardShortcut's Expandable content lambda, which builds the shortcut Icon modifier
        // chain and inlines drawBlurBehind and shortcutBackground.
        Method shortcutContent =
                XposedHelpers.findMethodExact(
                        KEYGUARD_UI
                                + ".composable.elements.ShortcutElementProvider$$ExternalSyntheticLambda3",
                        cl,
                        "invoke",
                        Object.class,
                        Object.class,
                        Object.class);
        // The prebuilt oat code inlines Modifier.background() here, bypassing the hook below.
        deoptimize(shortcutContent);
        XposedBridge.hookMethod(
                shortcutContent,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        sShortcutViewModels.put(
                                XposedHelpers.getObjectField(param.thisObject, "f$4"),
                                XposedHelpers.getObjectField(param.thisObject, "f$3"));
                        sComposingShortcut.set(new Object[] {param.thisObject, param.args[1]});
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sComposingShortcut.remove();
                    }
                });

        // KeyguardShortcut's graphicsLayer, which stock reduced to scale and translationX. Upstream
        // also sets alpha = if (isDimmed) DIM_ALPHA else alpha, with alpha collected from
        // transitionAlpha, which fades the shortcuts in on AOD -> lockscreen and out on unlock.
        // Both reads go through snapshot state, so the layer block re-runs when either changes.
        sTransitionAlphaState =
                XposedHelpers.newInstance(
                        XposedHelpers.findClass(
                                "androidx.compose.runtime.ParcelableSnapshotMutableFloatState", cl),
                        1f);
        XposedHelpers.findAndHookMethod(
                KEYGUARD_UI
                        + ".composable.elements.ShortcutElementProvider$$ExternalSyntheticLambda6",
                cl,
                "invoke",
                Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object viewModelState =
                                sShortcutViewModels.get(
                                        XposedHelpers.getObjectField(param.thisObject, "f$1"));
                        boolean isDimmed =
                                viewModelState != null
                                        && XposedHelpers.getBooleanField(
                                                XposedHelpers.callMethod(
                                                        viewModelState, "getValue"),
                                                "isDimmed");
                        float alpha =
                                isDimmed
                                        ? DIM_ALPHA
                                        : (float)
                                                XposedHelpers.callMethod(
                                                        sTransitionAlphaState, "getFloatValue");
                        XposedHelpers.callMethod(param.args[0], "setAlpha", alpha);
                    }
                });

        // shortcutBackground's Modifier.background(color, CircleShape), only reached while the
        // shortcut isn't selected. Upstream draws the blur behind selected shortcuts too.
        XposedHelpers.findAndHookMethod(
                "androidx.compose.foundation.BackgroundKt",
                cl,
                "background-bw27NRU",
                XposedHelpers.findClass("androidx.compose.ui.Modifier", cl),
                long.class,
                XposedHelpers.findClass("androidx.compose.ui.graphics.Shape", cl),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object[] composing = sComposingShortcut.get();
                        if (composing == null) {
                            return;
                        }
                        sComposingShortcut.remove();
                        applyShortcutBlur(param, composing[0], composing[1]);
                    }
                });
    }

    private static void applyShortcutBlur(
            XC_MethodHook.MethodHookParam param, Object lambda, Object composer) {
        Object provider = XposedHelpers.getObjectField(lambda, "f$1");
        Object viewModel =
                XposedHelpers.callMethod(XposedHelpers.getObjectField(lambda, "f$3"), "getValue");
        Context context = (Context) XposedHelpers.getObjectField(provider, "context");
        String slotId = (String) XposedHelpers.getObjectField(viewModel, "slotId");
        boolean isBlurSupported =
                isBlurSupported(
                        XposedHelpers.getObjectField(provider, "windowRootViewBlurInteractor"));

        View view =
                (View)
                        XposedHelpers.callMethod(
                                composer,
                                "consume",
                                XposedHelpers.getStaticObjectField(
                                        XposedHelpers.findClass(
                                                "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt",
                                                sClassLoader),
                                        "LocalView"));
        Object viewRootImpl = XposedHelpers.callMethod(view, "getViewRootImpl");
        if (viewRootImpl == null) {
            return;
        }
        Map<String, Drawable> drawables = sShortcutBlurDrawables.get(viewRootImpl);
        if (drawables == null) {
            drawables = new HashMap<>();
            sShortcutBlurDrawables.put(viewRootImpl, drawables);
            collectShortcutTransitionAlpha(
                    view, XposedHelpers.getObjectField(provider, "viewModel"), drawables);
        }
        Drawable drawable =
                drawables.computeIfAbsent(
                        slotId,
                        k -> {
                            Drawable d =
                                    (Drawable)
                                            XposedHelpers.callMethod(
                                                    viewRootImpl, "createBackgroundBlurDrawable");
                            XposedHelpers.callMethod(d, "setBlurRadius", 0);
                            return d;
                        });

        if (!isBlurSupported) {
            // Stop BackgroundBlurDrawable from dispatching blur regions to SF.
            XposedHelpers.callMethod(drawable, "setBlurRadius", 0);
            return;
        }

        int blurRadius = dimenPx(context, "keyguard_shortcuts_blur_radius");
        float cornerRadius =
                context.getResources()
                        .getDimension(dimenId(context, "keyguard_affordance_fixed_height"));
        sShortcutDimmed.put(drawable, XposedHelpers.getBooleanField(viewModel, "isDimmed"));
        Object onDraw =
                kotlinFunction(
                        "kotlin.jvm.functions.Function1",
                        args -> {
                            XposedHelpers.callMethod(drawable, "setBlurRadius", blurRadius);
                            XposedHelpers.callMethod(drawable, "setCornerRadius", cornerRadius);
                            drawable.setAlpha(shortcutBackgroundAlpha(drawable));
                            drawBlurBehind(args[0], drawable);
                            return sUnit;
                        });
        param.args[0] =
                XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass(
                                "androidx.compose.ui.draw.DrawModifierKt", sClassLoader),
                        "drawBehind",
                        param.args[0],
                        onDraw);

        if (!XposedHelpers.getBooleanField(viewModel, "isActivated")) {
            param.args[1] = composeColor(surfaceEffect1(context));
        }
    }

    /**
     * Stock fades the shortcuts with a graphicsLayer on the scene element, which doesn't redraw
     * them, and nothing feeds KeyguardQuickAffordancesCombinedViewModel.currentAlphaForBlur
     * anymore. Push transitionAlpha, upstream's alphaFlow, to the blur drawables directly so their
     * blur regions fade out with the lockscreen. The device entry icon blur reuses it.
     */
    private static void collectShortcutTransitionAlpha(
            View view, Object combinedViewModel, Map<String, Drawable> drawables) {
        Object lifecycleOwner =
                XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass(
                                "androidx.lifecycle.ViewTreeLifecycleOwner", sClassLoader),
                        "get",
                        view);
        if (lifecycleOwner == null) {
            return;
        }
        Object scope =
                XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass(
                                "androidx.lifecycle.LifecycleOwnerKt", sClassLoader),
                        "getLifecycleScope",
                        lifecycleOwner);
        collect(
                scope,
                XposedHelpers.getObjectField(combinedViewModel, "transitionAlpha"),
                alpha -> {
                    sLockscreenTransitionAlpha = ((Number) alpha).floatValue();
                    XposedHelpers.callMethod(
                            sTransitionAlphaState, "setFloatValue", sLockscreenTransitionAlpha);
                    for (Drawable drawable : drawables.values()) {
                        drawable.setAlpha(shortcutBackgroundAlpha(drawable));
                        refreshBlurPath(drawable);
                    }
                    for (View bgView : sDeviceEntryBlurInteractors.keySet()) {
                        updateDeviceEntryBlurAlpha(bgView);
                    }
                    NotificationBlurHooks.onLockscreenTransitionAlphaChanged();
                });
    }

    /** ShortcutElementProvider.drawBlurBehind's backgroundAlpha. */
    private static int shortcutBackgroundAlpha(Drawable drawable) {
        boolean isDimmed = Boolean.TRUE.equals(sShortcutDimmed.get(drawable));
        return (int) ((isDimmed ? DIM_ALPHA : sLockscreenTransitionAlpha) * 255);
    }

    private static void drawBlurBehind(Object drawScope, Drawable drawable) {
        try {
            long size = (long) sGetSize.invoke(drawScope);
            Object canvas =
                    XposedHelpers.callMethod(sGetDrawContext.invoke(drawScope), "getCanvas");
            Canvas nativeCanvas = (Canvas) XposedHelpers.getObjectField(canvas, "internalCanvas");
            drawable.setBounds(
                    0,
                    0,
                    (int) Float.intBitsToFloat((int) (size >> 32)),
                    (int) Float.intBitsToFloat((int) size));
            drawable.draw(nativeCanvas);
        } catch (ReflectiveOperationException e) {
            BlurEnabler.log("drawBlurBehind failed", e);
        }
    }

    /** Collects a StateFlow in the given CoroutineScope, like launch { flow.collect { ... } }. */
    private static void collect(Object scope, Object flow, Consumer<Object> onValue) {
        Object collector =
                kotlinFunction(
                        "kotlinx.coroutines.flow.FlowCollector",
                        args -> {
                            onValue.accept(args[0]);
                            return sUnit;
                        });
        Object block =
                kotlinFunction(
                        "kotlin.jvm.functions.Function2",
                        args -> XposedHelpers.callMethod(flow, "collect", collector, args[1]));
        Class<?> tracing =
                XposedHelpers.findClass(
                        "com.android.app.tracing.coroutines.CoroutineTracingKt", sClassLoader);
        Method launch =
                XposedHelpers.findMethodExact(
                        tracing,
                        "launchTraced$default",
                        XposedHelpers.findClass("kotlinx.coroutines.CoroutineScope", sClassLoader),
                        XposedHelpers.findClass("kotlin.coroutines.CoroutineContext", sClassLoader),
                        XposedHelpers.findClass("kotlinx.coroutines.CoroutineStart", sClassLoader),
                        XposedHelpers.findClass("kotlin.jvm.functions.Function2", sClassLoader),
                        int.class);
        try {
            // Mask 6 leaves context and start at their defaults, as the stock call sites do.
            launch.invoke(null, scope, null, null, block, 6);
        } catch (ReflectiveOperationException e) {
            BlurEnabler.log("launch failed", e);
        }
    }

    private interface Body {
        Object invoke(Object[] args) throws Throwable;
    }

    /** Implements a single-method Kotlin interface from SystemUI's class loader. */
    private static Object kotlinFunction(String interfaceName, Body body) {
        Class<?> type = XposedHelpers.findClass(interfaceName, sClassLoader);
        return Proxy.newProxyInstance(
                sClassLoader,
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "BlurEnabler." + type.getSimpleName();
                        default:
                            try {
                                return body.invoke(args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                    }
                });
    }

    /** LSPosed/Vector extension, absent from the Xposed API 82 jar. */
    static void deoptimize(Method method) {
        try {
            XposedBridge.class
                    .getMethod("deoptimizeMethod", java.lang.reflect.Member.class)
                    .invoke(null, method);
        } catch (ReflectiveOperationException e) {
            BlurEnabler.log("deoptimizeMethod unavailable", e);
        }
    }

    private static boolean isBlurSupported(Object windowRootViewBlurInteractor) {
        Object flow =
                XposedHelpers.getObjectField(
                        windowRootViewBlurInteractor, "isBlurCurrentlySupported");
        return (Boolean) XposedHelpers.callMethod(flow, "getValue");
    }

    static boolean isBlurDrawable(Drawable drawable) {
        return sBlurDrawableClass.isInstance(drawable);
    }

    /** SurfaceEffectColors.surfaceEffect1 */
    private static int surfaceEffect1(Context context) {
        int id =
                Resources.getSystem()
                        .getIdentifier("customColorSurfaceEffect1", "color", "android");
        return context.getResources().getColor(id, context.getTheme());
    }

    /** androidx.compose.ui.graphics.Color(argb: Int) */
    private static long composeColor(int argb) {
        return ((long) argb) << 32;
    }

    static int dimenId(Context context, String name) {
        return context.getResources().getIdentifier(name, "dimen", context.getPackageName());
    }

    static int dimenPx(Context context, String name) {
        return context.getResources().getDimensionPixelSize(dimenId(context, name));
    }
}
