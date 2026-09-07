package dev.slushii.thorbottom;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * The no-Shizuku fallback: our own black window over the bottom screen.
 *
 * <p>This is not a workaround so much as the same trick the vendor uses. AYN's own
 * "top display only" is literally {@code windowManager.addView(...)} of an opaque window on
 * display 4 plus a touch-disable property - it never powers the panel down. So covering the
 * screen ourselves produces the same result the button produces, and needs nothing but the
 * ordinary "Display over other apps" permission.
 *
 * <p>Two differences from the Shizuku path, both unavoidable:
 * <ul>
 *   <li>The vendor's menu keeps reading "dual screen", because the vendor's state genuinely
 *       is dual - we are only covering it.</li>
 *   <li>Our window is a TYPE_APPLICATION_OVERLAY (2038), which sorts above the vendor's own
 *       windows (2021), so it would hide the dual-screen menu. The service takes the cover
 *       down as soon as that menu appears - the same detection the deep-off path uses.</li>
 * </ul>
 *
 * <p>Touch is swallowed rather than passed through, which matches what the vendor's
 * {@code sys.temp.disabled.touch} achieves.
 */
public final class ScreenCover {

    private static final String TAG = "ThorBottomScreen";

    private static View view;
    private static WindowManager windowManager;

    private ScreenCover() {}

    public static boolean canDraw(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    public static boolean isShowing() { return view != null; }

    /** The bottom screen, or null if this device does not have one. */
    private static Display bottomDisplay(Context ctx) {
        DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) return null;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return null;
    }

    public static synchronized boolean show(Context ctx) {
        if (view != null) return true;
        if (!canDraw(ctx)) return false;
        try {
            Display bottom = bottomDisplay(ctx);
            if (bottom == null) return false;

            Context displayContext = ctx.createDisplayContext(bottom);
            WindowManager wm = displayContext.getSystemService(WindowManager.class);
            if (wm == null) return false;

            View v = new View(displayContext);
            v.setBackgroundColor(Color.BLACK);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Not focusable so we never steal input focus from the game on the main
                    // screen, but deliberately NOT "not touchable": swallowing taps on the
                    // bottom screen is the point.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.CENTER;
            lp.setTitle("ThorBottomScreenCover");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.setFitInsetsTypes(0);
            }

            wm.addView(v, lp);
            view = v;
            windowManager = wm;
            Log.i(TAG, "cover shown on display " + bottom.getDisplayId());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cover show failed", t);
            view = null;
            windowManager = null;
            return false;
        }
    }

    public static synchronized boolean hide() {
        if (view == null) return true;
        try {
            windowManager.removeView(view);
        } catch (Throwable t) {
            Log.w(TAG, "cover hide failed", t);
        } finally {
            view = null;
            windowManager = null;
        }
        Log.i(TAG, "cover hidden");
        return true;
    }
}
