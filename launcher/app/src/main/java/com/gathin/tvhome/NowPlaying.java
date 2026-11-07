package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NowPlaying {
    public Runnable onDown;

    private final Context ctx;
    private final View panel;
    private final ImageView art;
    private final TextView app, title, artist, lyric, elapsed, total;
    private final SeekBar bar;
    private final ImageView prev, play, next;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private MediaController ctl;
    private boolean running = false;
    private boolean dockShown = false;
    private boolean isPlaying = false;
    private long lastUserSeek = 0;
    private String currentKey = "";
    private volatile TreeMap<Long, String> lyrics = null;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            update();
            handler.postDelayed(this, 1000);
        }
    };

    public NowPlaying(Context ctx, View panel) {
        this.ctx = ctx;
        this.panel = panel;
        this.density = ctx.getResources().getDisplayMetrics().density;
        art = panel.findViewById(R.id.npArt);
        app = panel.findViewById(R.id.npApp);
        title = panel.findViewById(R.id.npTitle);
        artist = panel.findViewById(R.id.npArtist);
        lyric = panel.findViewById(R.id.npLyric);
        elapsed = panel.findViewById(R.id.npElapsed);
        total = panel.findViewById(R.id.npTotal);
        bar = panel.findViewById(R.id.npBar);
        prev = panel.findViewById(R.id.npPrev);
        play = panel.findViewById(R.id.npPlay);
        next = panel.findViewById(R.id.npNext);

        panel.setAlpha(0f);
        bar.setKeyProgressIncrement(10);
        bar.setNextFocusUpId(R.id.npBar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || ctl == null) return;
                lastUserSeek = SystemClock.elapsedRealtime();
                ctl.getTransportControls().seekTo(p * 1000L);
                elapsed.setText(fmt(p * 1000L));
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
        View.OnKeyListener down = (v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && onDown != null) onDown.run();
                return true;
            }
            return false;
        };
        prev.setOnKeyListener(down);
        play.setOnKeyListener(down);
        next.setOnKeyListener(down);
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

    /** Puts the card just above the given y and fades it in. */
    public void place(float bottomY) {
        float h = panel.getHeight() > 0 ? panel.getHeight() : 170 * density;
        panel.setY(bottomY - h);
        dockShown = true;
        panel.animate().alpha(1f).setDuration(200).start();
    }

    public void dockHide() {
        if (panel.findFocus() != null) return;
        dockShown = false;
        panel.animate().alpha(0f).setDuration(160).start();
    }

    public boolean focusControls() {
        if (panel.getVisibility() != View.VISIBLE) return false;
        panel.setAlpha(1f);
        dockShown = true;
        return play.requestFocus();
    }

    private String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private void update() {
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(ctx, GuardRevival.class));
            MediaController found = null;
            for (MediaController c : list) {
                PlaybackState ps = c.getPlaybackState();
                if (ps == null) continue;
                if (ps.getState() == PlaybackState.STATE_PLAYING) {
                    found = c;
                    break;
                }
                if (found == null && ps.getState() == PlaybackState.STATE_PAUSED) found = c;
            }
            if (found == null) {
                ctl = null;
                panel.setVisibility(View.GONE);
                return;
            }
            MediaMetadata m = found.getMetadata();
            PlaybackState s = found.getPlaybackState();
            if (m == null || s == null) {
                ctl = null;
                panel.setVisibility(View.GONE);
                return;
            }
            ctl = found;
            isPlaying = s.getState() == PlaybackState.STATE_PLAYING;
            play.setImageResource(isPlaying ? R.drawable.np_ic_pause : R.drawable.np_ic_play);

            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (t == null) t = "";
            if (a == null) a = "";
            title.setText(t);
            artist.setText(a);

            Bitmap bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bmp != null) {
                art.setImageBitmap(bmp);
                art.setVisibility(View.VISIBLE);
            } else {
                art.setVisibility(View.GONE);
            }
            try {
                PackageManager pm = ctx.getPackageManager();
                app.setText(pm.getApplicationLabel(pm.getApplicationInfo(found.getPackageName(), 0)));
            } catch (Exception ex) {
                app.setText(found.getPackageName());
            }

            long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long pos = s.getPosition();
            if (isPlaying) {
                long passed = SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
                pos = pos + (long) (passed * s.getPlaybackSpeed());
            }
            if (duration > 0 && pos > duration) pos = duration;
            if (duration > 0) {
                bar.setMax((int) (duration / 1000));
                total.setText(fmt(duration));
                if (SystemClock.elapsedRealtime() - lastUserSeek > 1500) {
                    bar.setProgress((int) (pos / 1000));
                    elapsed.setText(fmt(pos));
                }
                bar.setVisibility(View.VISIBLE);
            } else {
                bar.setVisibility(View.GONE);
                elapsed.setText("");
                total.setText("");
            }

            String key = t + "|" + a;
            if (!key.equals(currentKey)) {
                currentKey = key;
                lyrics = null;
                lyric.setText(" ");
                if (!t.isEmpty()) fetchLyrics(key, t, a, duration / 1000);
            }
            TreeMap<Long, String> ly = lyrics;
            if (ly != null) {
                Map.Entry<Long, String> en = ly.floorEntry(pos + 300);
                String line = en == null ? "" : en.getValue();
                lyric.setText(line.isEmpty() ? " " : line);
            }

            panel.setVisibility(View.VISIBLE);
            if (dockShown) panel.setAlpha(1f);
        } catch (RuntimeException e) {
            panel.setVisibility(View.GONE);
        }
    }

    private void fetchLyrics(final String key, final String t, final String a, final long durSec) {
        pool.execute(() -> {
            TreeMap<Long, String> map = null;
            try {
                String q = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(t, "UTF-8")
                        + "&artist_name=" + URLEncoder.encode(a, "UTF-8");
                HttpURLConnection c = (HttpURLConnection) new URL(q).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "CleanYourTV-NowPlaying/1.0");
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                JSONArray arr = new JSONArray(sb.toString());
                String best = null;
                double bestDiff = 1e9;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String syn = o.optString("syncedLyrics", "");
                    if (syn.isEmpty() || syn.equals("null")) continue;
                    double diff = Math.abs(o.optDouble("duration", 0) - durSec);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = syn;
                    }
                }
                if (best != null) map = parse(best);
            } catch (Exception ex) {
            }
            final TreeMap<Long, String> result = map;
            handler.post(() -> {
                if (key.equals(currentKey)) lyrics = result;
            });
        });
    }

    private TreeMap<Long, String> parse(String lrc) {
        TreeMap<Long, String> m = new TreeMap<>();
        Pattern p = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
        for (String line : lrc.split("\n")) {
            Matcher mt = p.matcher(line);
            List<Long> ts = new ArrayList<>();
            int end = 0;
            while (mt.find()) {
                long ms = Long.parseLong(mt.group(1)) * 60000 + Long.parseLong(mt.group(2)) * 1000;
                if (mt.group(3) != null) ms += Long.parseLong((mt.group(3) + "00").substring(0, 3));
                ts.add(ms);
                end = mt.end();
            }
            String text = line.substring(end).trim();
            for (long ms : ts) m.put(ms, text);
        }
        return m.isEmpty() ? null : m;
    }
}
