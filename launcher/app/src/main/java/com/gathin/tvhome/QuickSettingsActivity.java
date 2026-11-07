package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The control panel the remote's Settings button opens (HomeKeyService / MainActivity start this class
 * when settings_key_panel is true). Liquid-glass tiles over Home or whatever app is playing:
 * Store and Wi-Fi pills, a background toggle and the gear on top; media, Wi-Fi, Audio, Clock, Wallpaper
 * and Effects tiles; search / accessibility / input / settings circles; a live RAM and storage footer.
 * More tiles come from assets/control_panel.json, or from control_panel.json in the app's files folder
 * (that one wins). Translucent and in a task of its own, so Back returns to what was on screen.
 */
public final class QuickSettingsActivity extends Activity {
    private static final long REFRESH_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView wifiPill, wifiValue, audioValue, mediaTitle, mediaSub, wallpaperValue, effectsValue, footer;
    private boolean reduced;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        reduced = Glass.reduced(this);
        if (Build.VERSION.SDK_INT >= 31 && !reduced) {
            try {
                getWindow().setBackgroundBlurRadius(Glass.dp(this, 14));   // small, cheap, ignored where unsupported
            } catch (RuntimeException e) {
                // no cross-window blur on this TV: the dim scrim is enough
            }
        }
        setContentView(buildUi());
    }

    // ---- UI --------------------------------------------------------------------------------------

    private int dp(float v) {
        return Glass.dp(this, v);
    }

    private TextView label(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String text) {
        TextView t = label(14, 0xFFFFFFFF, false);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(9), dp(20), dp(9));
        Glass.focusable(t, 40);
        return t;
    }

    private ImageView circle(Drawable icon, int sizeDp, Runnable click) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(icon);
        b.setPadding(dp(sizeDp / 4f), dp(sizeDp / 4f), dp(sizeDp / 4f), dp(sizeDp / 4f));
        Glass.focusable(b, 60);
        b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMargins(dp(8), dp(4), dp(8), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    /** A glass tile with a small title and a bigger value; returns the tile, the value view is child 1. */
    private LinearLayout tile(String title, String value, Runnable click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(18), dp(14), dp(18), dp(14));
        t.addView(label(12, 0xAAFFFFFF, false));
        ((TextView) t.getChildAt(0)).setText(title);
        TextView v = label(17, 0xFFFFFFFF, true);
        v.setText(value);
        t.addView(v);
        Glass.focusable(t, 26);
        t.setOnClickListener(x -> click.run());
        return t;
    }

    private static TextView valueOf(LinearLayout tile) {
        return (TextView) tile.getChildAt(1);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        r.setLayoutParams(lp);
        return r;
    }

    private void addTile(LinearLayout row, View tile, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(dp(6), dp(4), dp(6), dp(4));
        row.addView(tile, lp);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99050A14);
        root.setOnClickListener(v -> finish());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(26), dp(22), dp(26), dp(22));
        panel.setBackground(Glass.glass(this, 32));
        panel.setClickable(true);
        int width = Math.min(dp(860), getResources().getDisplayMetrics().widthPixels - dp(48));
        root.addView(panel, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Top row: Store, Wi-Fi name, then the background toggle and the gear on the right.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView store = pill("Store");
        store.setOnClickListener(v -> openStore());
        wifiPill = pill(wifiName());
        wifiPill.setOnClickListener(v -> openWifi());
        View spacer = new View(this);
        ImageView toggle = circle(new Glyph(Glyph.TOGGLE), 44, this::cycleBackground);
        ImageView gear = circle(getDrawable(R.drawable.ic_settings), 44, this::openSettings);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.setMarginEnd(dp(10));
        top.addView(store, pl);
        top.addView(wifiPill);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(toggle);
        top.addView(gear);
        panel.addView(top);

        // Row 1: media mini-card (double width), Wi-Fi, Audio.
        LinearLayout media = tile("Now playing", "Nothing playing", this::openNowPlaying);
        mediaTitle = valueOf(media);
        mediaSub = label(13, 0xBBFFFFFF, false);
        media.addView(mediaSub);
        LinearLayout wifi = tile("Wi-Fi", wifiName(), this::openWifi);
        wifiValue = valueOf(wifi);
        LinearLayout audio = tile("Audio", audioName(), this::openAudio);
        audioValue = valueOf(audio);
        LinearLayout r1 = row();
        addTile(r1, media, 2f);
        addTile(r1, wifi, 1f);
        addTile(r1, audio, 1f);
        panel.addView(r1);

        // Row 2: Clock, Wallpaper, Effects, then any tiles from control_panel.json, four per row.
        LinearLayout clock = tile("Clock", "Screensaver", this::openClock);
        LinearLayout wallpaper = tile("Wallpaper", Glass.bgName(Glass.bgMode(this)), this::openWallpaper);
        wallpaperValue = valueOf(wallpaper);
        LinearLayout effects = tile("Effects", reduced ? "Reduced" : "Full", this::toggleEffects);
        effectsValue = valueOf(effects);
        List<View> more = new ArrayList<View>();
        more.add(clock);
        more.add(wallpaper);
        more.add(effects);
        more.addAll(extraTiles());
        LinearLayout r = null;
        for (int i = 0; i < more.size(); i++) {
            if (i % 4 == 0) {
                r = row();
                panel.addView(r);
            }
            addTile(r, more.get(i), 1f);
        }

        // Bottom: four glass circles.
        LinearLayout circles = new LinearLayout(this);
        circles.setOrientation(LinearLayout.HORIZONTAL);
        circles.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(14);
        circles.addView(circle(new Glyph(Glyph.SEARCH), 56, this::openSearch));
        circles.addView(circle(new Glyph(Glyph.ACCESS), 56, this::openAccessibility));
        circles.addView(circle(getDrawable(R.drawable.ic_input), 56, this::openInputs));
        circles.addView(circle(getDrawable(R.drawable.ic_settings), 56, this::openSettings));
        panel.addView(circles, cl);

        // Footer: live memory and storage.
        footer = label(13, 0xCCFFFFFF, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(22), dp(8), dp(22), dp(8));
        footer.setBackground(Glass.glass(this, 40));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.gravity = Gravity.CENTER_HORIZONTAL;
        fl.topMargin = dp(12);
        panel.addView(footer, fl);

        media.requestFocus();
        return root;
    }

    // ---- live values -----------------------------------------------------------------------------

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String w = wifiName();
            wifiPill.setText(w);
            wifiValue.setText(w);
            audioValue.setText(audioName());
            footer.setText(memoryLine());
            updateMedia();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    private String wifiName() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info != null) {
                String s = info.getSSID();
                if (s != null && !s.equals(WifiManager.UNKNOWN_SSID) && !s.equals("<unknown ssid>")) {
                    return s.replace("\"", "");
                }
            }
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "Offline";
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return "Ethernet";
        } catch (RuntimeException e) {
            // fall through
        }
        return "Wi-Fi";
    }

    private String audioName() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            int[] order = {AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER};
            for (int type : order) {
                for (AudioDeviceInfo d : outs) {
                    if (d.getType() != type) continue;
                    CharSequence n = d.getProductName();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return TextUtils.isEmpty(n) ? "Bluetooth" : n.toString();
                    if (type == AudioDeviceInfo.TYPE_HDMI_ARC) return "HDMI ARC";
                    if (type == AudioDeviceInfo.TYPE_HDMI) return "HDMI";
                    if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return "TV speakers";
                    return TextUtils.isEmpty(n) ? "Wired" : n.toString();
                }
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return "TV speakers";
    }

    private String memoryLine() {
        double gb = 1024d * 1024d * 1024d;
        double ram = 0;
        double disk = 0;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ram = mi.availMem / gb;
            disk = new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes() / gb;
        } catch (RuntimeException e) {
            // leave zeros
        }
        return String.format(Locale.US, "%.1f GB RAM free  -  %.1f GB storage free", ram, disk);
    }

    private void updateMedia() {
        try {
            MediaSessionManager msm = getSystemService(MediaSessionManager.class);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(this, GuardRevival.class));
            MediaController best = null;
            for (MediaController c : list) {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null && c.getMetadata() != null) best = c;
            }
            MediaMetadata m = best == null ? null : best.getMetadata();
            if (m == null) {
                mediaTitle.setText("Nothing playing");
                mediaSub.setText("");
                return;
            }
            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            mediaTitle.setText(TextUtils.isEmpty(t) ? "Playing" : t);
            mediaSub.setText(a == null ? "" : a);
        } catch (RuntimeException e) {
            mediaTitle.setText("Nothing playing");
            mediaSub.setText("");
        }
    }

    // ---- actions: every one has a fallback and never throws ----------------------------------------

    private Intent settingsIntent(String action) {
        return SystemSettings.intent(this, action);
    }

    private void openSettings() {
        Glass.launch(this, settingsIntent(Settings.ACTION_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
        finish();
    }

    private void openWifi() {
        Glass.launch(this, settingsIntent(Settings.ACTION_WIFI_SETTINGS), new Intent(Settings.ACTION_WIFI_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
        finish();
    }

    private void openAudio() {
        Glass.launch(this, settingsIntent(Settings.ACTION_SOUND_SETTINGS), new Intent(Settings.ACTION_SOUND_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
        finish();
    }

    private void openAccessibility() {
        Glass.launch(this, settingsIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS), new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
        finish();
    }

    private void openSearch() {
        Glass.launch(this, new Intent("android.search.action.GLOBAL_SEARCH"), new Intent(Intent.ACTION_ASSIST), new Intent(Intent.ACTION_VOICE_COMMAND));
        finish();
    }

    private void openInputs() {
        Glass.launch(this, new Intent("com.android.tv.action.VIEW_INPUTS"), new Intent("android.media.tv.action.SETUP_INPUTS"));
        finish();
    }

    private void openStore() {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage("com.android.vending");
        if (i == null) i = pm.getLaunchIntentForPackage("com.android.vending");
        Glass.launch(this, i, new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=tv")));
        finish();
    }

    private void openClock() {
        Glass.launch(this, new Intent(this, ClockActivity.class), new Intent(Settings.ACTION_DREAM_SETTINGS), new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        finish();
    }

    private void openNowPlaying() {
        Glass.launch(this, new Intent(this, NowPlayingActivity.class));
        finish();
    }

    /** Wallpaper tile: the system picker where the TV has one, else Home's own launcher settings. */
    private void openWallpaper() {
        Glass.launch(this, new Intent(Intent.ACTION_SET_WALLPAPER),
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        finish();
    }

    /** Aurora, wallpaper, solid. Home applies the choice the next time it is shown. */
    private void cycleBackground() {
        int mode = Glass.nextBgMode(this);
        wallpaperValue.setText(Glass.bgName(mode));
        Toast.makeText(this, "Background: " + Glass.bgName(mode), Toast.LENGTH_SHORT).show();
    }

    private void toggleEffects() {
        reduced = !reduced;
        Glass.setReduced(this, reduced);
        effectsValue.setText(reduced ? "Reduced" : "Full");
        Toast.makeText(this, reduced ? "Reduced effects on" : "Full effects on", Toast.LENGTH_SHORT).show();
    }

    // ---- plugin tiles: control_panel.json ------------------------------------------------------------

    private List<View> extraTiles() {
        List<View> out = new ArrayList<View>();
        try {
            JSONArray arr = new JSONObject(readConfig()).optJSONArray("tiles");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                final Intent intent = tileIntent(o);
                if (intent == null) continue;           // missing package: the tile is hidden
                LinearLayout t = tile(o.optString("title", "Tile"), o.optString("subtitle", "Open"), () -> {
                    Glass.launch(this, intent);
                    finish();
                });
                out.add(t);
            }
        } catch (Exception e) {
            // a broken file just means no extra tiles
        }
        return out;
    }

    private Intent tileIntent(JSONObject o) {
        String pkg = o.optString("package", "");
        if (!pkg.isEmpty()) {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            return i != null ? i : pm.getLaunchIntentForPackage(pkg);
        }
        String action = o.optString("action", "");
        if (action.isEmpty()) return null;
        if (action.startsWith("android.settings.")) return settingsIntent(action);
        Intent i = new Intent(action);
        String uri = o.optString("uri", "");
        if (!uri.isEmpty()) i.setData(Uri.parse(uri));
        return i;
    }

    private String readConfig() throws Exception {
        File external = new File(getExternalFilesDir(null), "control_panel.json");
        InputStream in = external.exists() ? new java.io.FileInputStream(external) : getAssets().open("control_panel.json");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        in.close();
        return buf.toString("UTF-8");
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    // ---- three small icons drawn in code (no extra image files) --------------------------------------

    private static final class Glyph extends Drawable {
        static final int SEARCH = 0;
        static final int ACCESS = 1;
        static final int TOGGLE = 2;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(int kind) {
            this.kind = kind;
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas c) {
            float w = getBounds().width();
            float h = getBounds().height();
            float u = Math.min(w, h);
            float cx = getBounds().exactCenterX();
            float cy = getBounds().exactCenterY();
            p.setStrokeWidth(u * 0.08f);
            p.setStyle(Paint.Style.STROKE);
            if (kind == SEARCH) {
                c.drawCircle(cx - u * 0.06f, cy - u * 0.06f, u * 0.26f, p);
                c.drawLine(cx + u * 0.14f, cy + u * 0.14f, cx + u * 0.36f, cy + u * 0.36f, p);
            } else if (kind == ACCESS) {
                c.drawCircle(cx, cy, u * 0.42f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy - u * 0.2f, u * 0.06f, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - u * 0.22f, cy - u * 0.06f, cx + u * 0.22f, cy - u * 0.06f, p);
                c.drawLine(cx, cy - u * 0.06f, cx, cy + u * 0.1f, p);
                c.drawLine(cx, cy + u * 0.1f, cx - u * 0.14f, cy + u * 0.3f, p);
                c.drawLine(cx, cy + u * 0.1f, cx + u * 0.14f, cy + u * 0.3f, p);
            } else {
                // two sliders: the "toggle" glyph
                c.drawLine(cx - u * 0.36f, cy - u * 0.16f, cx + u * 0.36f, cy - u * 0.16f, p);
                c.drawLine(cx - u * 0.36f, cy + u * 0.16f, cx + u * 0.36f, cy + u * 0.16f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + u * 0.14f, cy - u * 0.16f, u * 0.09f, p);
                c.drawCircle(cx - u * 0.14f, cy + u * 0.16f, u * 0.09f, p);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
