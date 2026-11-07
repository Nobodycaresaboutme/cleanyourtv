package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The Now Playing card above the dock: art, title, artist, progress, round buttons, "Open in" pill. */
public class NowPlaying {
    public Runnable onDown;
    public Runnable onShowChange;

    private final Context ctx;
    private final View panel;
    private final ImageView art, prev, play, next;
    private final TextView label, title, sub, elapsed, remain, open;
    private final SeekBar bar;
    private final float density;
    private final MediaSessionManager msm;
    private final ComponentName listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, String> appNames = new HashMap<String, String>();

    private MediaController ctl;
    private boolean running = false;
    private boolean dockShown = false;
    private boolean isPlaying = false;
    private boolean iconSet = false;
    private boolean artSet = false;
    private long lastUserSeek = 0;
    private long lastUpdate = 0;
    private float bottomY = 0f;
    private String currentKey = "";
    private String currentPkg = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            update();
            if (dockShown) reposition();
            handler.postDelayed(this, dockShown ? 1000 : 3000);
        }
    };

    public NowPlaying(Context ctx, View panel) {
        this.ctx = ctx;
        this.panel = panel;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.msm = ctx.getSystemService(MediaSessionManager.class);
        this.listener = new ComponentName(ctx, GuardRevival.class);
        art = panel.findViewById(R.id.npArt);
        label = panel.findViewById(R.id.npLabel);
        title = panel.findViewById(R.id.npTitle);
        sub = panel.findViewById(R.id.npSub);
        bar = panel.findViewById(R.id.npBar);
        elapsed = panel.findViewById(R.id.npElapsed);
        remain = panel.findViewById(R.id.npRemain);
        prev = panel.findViewById(R.id.npPrev);
        play = panel.findViewById(R.id.npPlay);
        next = panel.findViewById(R.id.npNext);
        open = panel.findViewById(R.id.npOpen);

        panel.setAlpha(0f);
        final float radius = 12 * density;
        art.setClipToOutline(true);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });

        bar.setKeyProgressIncrement(10);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || ctl == null) return;
                lastUserSeek = SystemClock.elapsedRealtime();
                ctl.getTransportControls().seekTo(p * 1000L);
                setText(elapsed, fmt(p * 1000L));
                setText(remain, "-" + fmt((sb.getMax() - p) * 1000L));
            }
            @Override
            public void onStartTrackingTouch(SeekBar sb) { }
            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });

        prev.setOnClickListener(v -> { if (ctl != null) ctl.getTransportControls().skipToPrevious(); });
        next.setOnClickListener(v -> { if (ctl != null) ctl.getTransportControls().skipToNext(); });
        play.setOnClickListener(v -> {
            if (ctl == null) return;
            if (isPlaying) ctl.getTransportControls().pause();
            else ctl.getTransportControls().play();
        });
        open.setOnClickListener(v -> openApp());

        View.OnFocusChangeListener grow = (v, has) ->
                v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(120).start();
        prev.setOnFocusChangeListener(grow);
        play.setOnFocusChangeListener(grow);
        next.setOnFocusChangeListener(grow);
        open.setOnFocusChangeListener(grow);

        // Buttons row: Down goes back to the dock, Up goes to the bar (or the top pills when there is no bar).
        View.OnKeyListener rowKeys = (v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && onDown != null) onDown.run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (bar.getVisibility() == View.VISIBLE) bar.requestFocus();
                    else topPill();
                }
                return true;
            }
            return false;
        };
        prev.setOnKeyListener(rowKeys);
        play.setOnKeyListener(rowKeys);
        next.setOnKeyListener(rowKeys);
        open.setOnKeyListener(rowKeys);

        // Bar: Left/Right seek 10 s, Up goes to the Wi-Fi pill, Down goes to the buttons.
        bar.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) topPill();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) play.requestFocus();
                return true;
            }
            return false;
        });
    }

    public void start() {
        if (running) return;
        running = true;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    public boolean isShowing() {
        return panel.getVisibility() == View.VISIBLE;
    }

    /** Puts the card's bottom edge at y (the top of the dock) and fades it in. */
    public void place(float y) {
        bottomY = y;
        dockShown = true;
        reposition();
        if (isShowing() && panel.getAlpha() < 1f) panel.animate().alpha(1f).setDuration(200).start();
        if (SystemClock.elapsedRealtime() - lastUpdate > 1500) update();
    }

    public void dockHide() {
        if (panel.findFocus() != null) return;
        dockShown = false;
        if (panel.getAlpha() > 0f) panel.animate().alpha(0f).setDuration(160).start();
    }

    /** Up from the dock: focus the buttons row (the part of the card nearest the dock). */
    public boolean focusControls() {
        if (!isShowing()) return false;
        panel.setAlpha(1f);
        dockShown = true;
        return play.requestFocus();
    }

    private void topPill() {
        View pill = panel.getRootView().findViewById(R.id.btnWifi);
        if (pill != null && pill.getVisibility() == View.VISIBLE) pill.requestFocus();
    }

    private void openApp() {
        if (currentPkg.isEmpty()) return;
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(currentPkg);
            if (i == null) i = pm.getLaunchIntentForPackage(currentPkg);
            if (i != null) ctx.startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    /** Bottom edge fixed at bottomY; if the card is taller than the space under the date row, it shrinks to fit. */
    private void reposition() {
        int h = panel.getHeight();
        if (h <= 0) return;
        View root = panel.getRootView();
        View lb = root.findViewById(R.id.leftBlock);
        float minTop = 0f;
        if (lb != null) {
            int[] a = new int[2];
            int[] b = new int[2];
            lb.getLocationOnScreen(a);
            root.getLocationOnScreen(b);
            minTop = a[1] - b[1] + lb.getHeight() + 4 * density;
        }
        float avail = bottomY - minTop;
        float s = (avail > 0 && avail < h) ? Math.max(0.7f, avail / h) : 1f;
        panel.setPivotX(0f);
        panel.setPivotY(h);
        if (panel.getScaleX() != s) {
            panel.setScaleX(s);
            panel.setScaleY(s);
        }
        float y = bottomY - h;
        if (panel.getY() != y) panel.setY(y);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void setText(TextView v, String s) {
        if (!v.getText().toString().equals(s)) v.setText(s);
    }

    private void show() {
        if (panel.getVisibility() != View.VISIBLE) {
            panel.setVisibility(View.VISIBLE);
            panel.post(this::reposition);
            if (onShowChange != null) onShowChange.run();
        }
        if (dockShown && panel.getAlpha() < 1f) panel.setAlpha(1f);
    }

    private void hide() {
        if (panel.getVisibility() != View.GONE) {
            panel.setVisibility(View.GONE);
            if (onShowChange != null) onShowChange.run();
        }
    }

    private MediaController pick() {
        List<MediaController> list = msm.getActiveSessions(listener);
        MediaController paused = null;
        for (MediaController c : list) {
            PlaybackState ps = c.getPlaybackState();
            if (ps == null) continue;
            int st = ps.getState();
            if (st == PlaybackState.STATE_PLAYING) return c;
            if (paused == null && st == PlaybackState.STATE_PAUSED) paused = c;
        }
        return paused;
    }

    private void setArt(MediaMetadata m) {
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b != null) {
            art.setImageBitmap(b);
            artSet = true;
        } else {
            art.setImageDrawable(null);
            artSet = false;
        }
    }

    private String appName(String pkg) {
        String name = appNames.get(pkg);
        if (name == null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ex) {
                name = pkg;
            }
            appNames.put(pkg, name);
        }
        return name;
    }

    private void update() {
        lastUpdate = SystemClock.elapsedRealtime();
        try {
            MediaController found = pick();
            if (found == null) {
                ctl = null;
                hide();
                return;
            }
            MediaMetadata m = found.getMetadata();
            PlaybackState s = found.getPlaybackState();
            if (m == null || s == null) {
                ctl = null;
                hide();
                return;
            }
            ctl = found;

            boolean playing = s.getState() == PlaybackState.STATE_PLAYING;
            if (!iconSet || playing != isPlaying) {
                isPlaying = playing;
                iconSet = true;
                play.setImageResource(playing ? R.drawable.np_ic_pause : R.drawable.np_ic_play);
            }

            String t = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
            String a = nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
            String al = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM));
            String pkg = found.getPackageName();
            String key = pkg + "|" + t + "|" + a;
            if (!key.equals(currentKey)) {
                currentKey = key;
                currentPkg = pkg;
                String name = appName(pkg);
                setText(label, name + " \u00B7 NOW PLAYING");
                setText(title, t);
                setText(sub, al.isEmpty() ? a : a + " \u00B7 " + al);
                setText(open, "Open in " + name);
                setArt(m);
            } else if (!artSet) {
                setArt(m);
            }

            long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long pos = s.getPosition();
            if (playing) {
                pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
            if (duration > 0 && pos > duration) pos = duration;
            if (pos < 0) pos = 0;
            if (duration > 0) {
                int max = (int) (duration / 1000);
                if (bar.getMax() != max) bar.setMax(max);
                if (bar.getVisibility() != View.VISIBLE) bar.setVisibility(View.VISIBLE);
                if (SystemClock.elapsedRealtime() - lastUserSeek > 1500) {
                    int p = (int) (pos / 1000);
                    if (bar.getProgress() != p) bar.setProgress(p);
                    setText(elapsed, fmt(pos));
                    setText(remain, "-" + fmt(duration - pos));
                }
            } else {
                if (bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
                setText(elapsed, "");
                setText(remain, "");
            }
            show();
        } catch (RuntimeException e) {
            hide();
        }
    }
}
