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
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final ImageView art, prev, play, next;
    private final TextView app, title, artist, lyric, lyricNext, elapsed, total;
    private final SeekBar bar;
    private final float density;
    private final MediaSessionManager msm;
    private final ComponentName listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "np-lyrics");
        th.setDaemon(true);
        return th;
    });
    private final Map<String, String> appNames = new HashMap<String, String>();
    private final Map<String, TreeMap<Long, String>> cache =
            new LinkedHashMap<String, TreeMap<Long, String>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TreeMap<Long, String>> e) {
                    return size() > 20;
                }
            };

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
    private volatile TreeMap<Long, String> lyricsMap = null;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            update();
            reposition();
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
        app = panel.findViewById(R.id.npApp);
        title = panel.findViewById(R.id.npTitle);
        artist = panel.findViewById(R.id.npArtist);
        lyric = panel.findViewById(R.id.npLyric);
        lyricNext = panel.findViewById(R.id.npLyricNext);
        elapsed = panel.findViewById(R.id.npElapsed);
        total = panel.findViewById(R.id.npTotal);
        bar = panel.findViewById(R.id.npBar);
        prev = panel.findViewById(R.id.npPrev);
        play = panel.findViewById(R.id.npPlay);
        next = panel.findViewById(R.id.npNext);

        panel.setAlpha(0f);
        bar.setKeyProgressIncrement(10);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || ctl == null) return;
                lastUserSeek = SystemClock.elapsedRealtime();
                ctl.getTransportControls().seekTo(p * 1000L);
                setText(elapsed, fmt(p * 1000L));
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

        // Buttons: Up goes to a top pill (prev to Inputs, play to Wi-Fi, next to Settings), Down goes to the bar.
        prev.setOnKeyListener(buttonKeys(R.id.btnInputs));
        play.setOnKeyListener(buttonKeys(R.id.btnWifi));
        next.setOnKeyListener(buttonKeys(R.id.btnSettings));
        // Bar: Left/Right seek, Up goes to play/pause, Down goes back to the dock.
        bar.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && onDown != null) onDown.run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) play.requestFocus();
                return true;
            }
            return false;
        });
    }

    private View.OnKeyListener buttonKeys(final int pillId) {
        return (v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) bar.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    View pill = panel.getRootView().findViewById(pillId);
                    if (pill != null && pill.getVisibility() == View.VISIBLE) pill.requestFocus();
                }
                return true;
            }
            return false;
        };
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
    public void place(float y) {
        bottomY = y;
        dockShown = true;
        reposition();
        if (panel.getAlpha() < 1f) panel.animate().alpha(1f).setDuration(200).start();
        if (SystemClock.elapsedRealtime() - lastUpdate > 1500) update();
    }

    public void dockHide() {
        if (panel.findFocus() != null) return;
        dockShown = false;
        if (panel.getAlpha() > 0f) panel.animate().alpha(0f).setDuration(160).start();
    }

    /** Up from the dock: focus the bar (the lowest part of the card). */
    public boolean focusControls() {
        if (panel.getVisibility() != View.VISIBLE) return false;
        panel.setAlpha(1f);
        dockShown = true;
        return bar.requestFocus();
    }

    private void reposition() {
        int h = panel.getHeight();
        if (h <= 0) h = (int) (150 * density);
        float y = Math.max(0f, bottomY - h);
        if (panel.getY() != y) panel.setY(y);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void setText(TextView v, String s) {
        if (!v.getText().toString().equals(s)) v.setText(s);
    }

    private void hide() {
        if (panel.getVisibility() != View.GONE) panel.setVisibility(View.GONE);
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
            art.setVisibility(View.VISIBLE);
            artSet = true;
        } else {
            art.setVisibility(View.INVISIBLE);
            artSet = false;
        }
    }

    private void setApp(String pkg) {
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
        setText(app, name);
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
            String pkg = found.getPackageName();
            long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            String key = pkg + "|" + t + "|" + a;
            if (!key.equals(currentKey)) {
                currentKey = key;
                setText(title, t);
                setText(artist, a);
                setApp(pkg);
                setArt(m);
                lyricsMap = null;
                setText(lyric, " ");
                setText(lyricNext, " ");
                if (!t.isEmpty()) loadLyrics(key, t, a, duration / 1000);
            } else if (!artSet) {
                setArt(m);
            }

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
                }
                setText(total, fmt(duration));
            } else {
                if (bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
                setText(elapsed, "");
                setText(total, "");
            }

            TreeMap<Long, String> ly = lyricsMap;
            if (ly != null) {
                Map.Entry<Long, String> en = ly.floorEntry(pos + 300);
                Map.Entry<Long, String> nx = ly.higherEntry(pos + 300);
                String line = (en == null || en.getValue().isEmpty()) ? " " : en.getValue();
                String nline = (nx == null || nx.getValue().isEmpty()) ? " " : nx.getValue();
                if (!line.equals(lyric.getText().toString())) {
                    setText(lyric, line);
                    setText(lyricNext, nline);
                    lyric.setTranslationY(14 * density);
                    lyric.setAlpha(0f);
                    lyric.animate().translationY(0f).alpha(1f).setDuration(280).start();
                    lyricNext.setTranslationY(10 * density);
                    lyricNext.setAlpha(0f);
                    lyricNext.animate().translationY(0f).alpha(0.55f).setDuration(280).start();
                }
            }

            if (panel.getVisibility() != View.VISIBLE) {
                panel.setVisibility(View.VISIBLE);
                panel.post(this::reposition);
            }
            if (dockShown && panel.getAlpha() < 1f) panel.setAlpha(1f);
        } catch (RuntimeException e) {
            hide();
        }
    }

    private void loadLyrics(final String key, final String t, final String a, final long durSec) {
        if (cache.containsKey(key)) {
            TreeMap<Long, String> c = cache.get(key);
            lyricsMap = (c == null || c.isEmpty()) ? null : c;
            return;
        }
        pool.execute(() -> {
            TreeMap<Long, String> map = null;
            HttpURLConnection c = null;
            try {
                String q = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(t, "UTF-8")
                        + "&artist_name=" + URLEncoder.encode(a, "UTF-8");
                c = (HttpURLConnection) new URL(q).openConnection();
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
                // no lyrics, no network: the lyric line just stays blank
            } finally {
                if (c != null) c.disconnect();
            }
            final TreeMap<Long, String> result = map;
            handler.post(() -> {
                cache.put(key, result == null ? new TreeMap<Long, String>() : result);
                if (key.equals(currentKey)) lyricsMap = result;
            });
        });
    }

    private TreeMap<Long, String> parse(String lrc) {
        TreeMap<Long, String> m = new TreeMap<Long, String>();
        Pattern p = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
        for (String line : lrc.split("\n")) {
            Matcher mt = p.matcher(line);
            List<Long> ts = new ArrayList<Long>();
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
