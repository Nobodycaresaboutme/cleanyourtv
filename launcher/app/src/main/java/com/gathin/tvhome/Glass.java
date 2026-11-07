package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Liquid-glass helpers shared by the new screens: glass shapes, the focus spring, the
 * "reduce effects" switch and the background mode (aurora / wallpaper / solid).
 * Everything is plain framework drawing, so it works on any API 26+ TV.
 */
final class Glass {
    static final String PREFS = "tvhome_fx";
    static final String KEY_REDUCE = "reduce_effects";
    static final String KEY_BG = "bg_mode";
    static final int BG_AURORA = 0;
    static final int BG_WALLPAPER = 1;
    static final int BG_SOLID = 2;

    private static Drawable savedRootBackground;
    private static boolean rootSaved = false;

    private Glass() {
    }

    // ---- settings --------------------------------------------------------------------------------

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Reduce effects: no drift, no blur, no springs. Starts from config.xml reduced_motion. */
    static boolean reduced(Context c) {
        return prefs(c).getBoolean(KEY_REDUCE, c.getResources().getBoolean(R.bool.reduced_motion));
    }

    static void setReduced(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_REDUCE, on).apply();
    }

    static int bgMode(Context c) {
        return prefs(c).getInt(KEY_BG, BG_AURORA);
    }

    static int nextBgMode(Context c) {
        int next = (bgMode(c) + 1) % 3;
        prefs(c).edit().putInt(KEY_BG, next).apply();
        return next;
    }

    static String bgName(int mode) {
        if (mode == BG_WALLPAPER) return "Wallpaper";
        if (mode == BG_SOLID) return "Solid";
        return "Aurora";
    }

    // ---- sizes -----------------------------------------------------------------------------------

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ---- shapes ----------------------------------------------------------------------------------

    /** Frosted panel: white fill that is a little brighter at the top, light 1dp rim, big rounded corners. */
    static Drawable glass(Context c, float radiusDp) {
        return shape(c, 0x40FFFFFF, 0x1AFFFFFF, 0x66FFFFFF, 1, radiusDp);
    }

    /** Same slab, brighter with a thicker rim: the focused look. */
    static Drawable glassFocused(Context c, float radiusDp) {
        return shape(c, 0x70FFFFFF, 0x38FFFFFF, 0xE6FFFFFF, 2, radiusDp);
    }

    /** Glass that switches to the focused look by itself. */
    static Drawable glassFocusable(Context c, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, glassFocused(c, radiusDp));
        s.addState(new int[] {}, glass(c, radiusDp));
        return s;
    }

    private static Drawable shape(Context c, int top, int bottom, int stroke, int strokeDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    /** Makes a view a D-pad target: glass background, focus glow ring and a spring to about 1.08. */
    static void focusable(View v, float radiusDp) {
        v.setBackground(glassFocusable(v.getContext(), radiusDp));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, focused) -> springScale(view, focused ? 1.08f : 1f));
    }

    static void springScale(View v, float target) {
        if (reduced(v.getContext())) {
            v.setScaleX(target);
            v.setScaleY(target);
            return;
        }
        spring(v, DynamicAnimation.SCALE_X, target);
        spring(v, DynamicAnimation.SCALE_Y, target);
    }

    private static void spring(View v, DynamicAnimation.ViewProperty property, float target) {
        SpringAnimation a = new SpringAnimation(v, property, target);
        a.getSpring().setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY);
        a.start();
    }

    // ---- launching -------------------------------------------------------------------------------

    /** Tries each intent in turn; shows "not available" if none opens. Never throws. */
    static boolean launch(Activity a, Intent... candidates) {
        for (Intent i : candidates) {
            if (i == null) continue;
            try {
                a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                // try the next one
            }
        }
        Toast.makeText(a, R.string.not_available, Toast.LENGTH_SHORT).show();
        return false;
    }

    // ---- home background mode --------------------------------------------------------------------

    /** Applies the aurora / wallpaper / solid choice to Home. Aurora (the default) leaves Home as it was. */
    static void applyBackground(Activity a) {
        View root = a.findViewById(R.id.root);
        View aurora = a.findViewById(R.id.aurora);
        View sky = a.findViewById(R.id.sky);
        if (root == null) return;
        int mode = bgMode(a);
        Window w = a.getWindow();
        if (mode == BG_AURORA) {
            if (!rootSaved) return;
            root.setBackground(savedRootBackground);
            if (aurora != null) aurora.setVisibility(View.VISIBLE);
            if (sky != null) sky.setVisibility(View.VISIBLE);
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            rootSaved = false;
            savedRootBackground = null;
            return;
        }
        if (!rootSaved) {
            savedRootBackground = root.getBackground();
            rootSaved = true;
        }
        if (aurora != null) aurora.setVisibility(View.GONE);
        if (sky != null) sky.setVisibility(View.GONE);
        if (mode == BG_WALLPAPER) {
            root.setBackgroundColor(0x00000000);
            w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            root.setBackgroundColor(0xFF05080F);
        }
    }
}
