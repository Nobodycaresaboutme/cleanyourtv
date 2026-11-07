package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full-screen Now Playing: big rounded album art on the left, label / title / artist / album, round
 * glass buttons, a seek bar, and LRCLIB synced lyrics that slide up as the line changes. The
 * background is the blurred album art with a slow drift. Lyrics are cached on disk.
 * Needs notification-listener access (GuardRevival), the same as the home card.
 */
public final class NowPlayingActivity extends Activity {
    private static final long TICK_MS = 500;
    private static final long MISS_TTL_MS = 3L * 24 * 3600 * 1000;
    private static final int MAX_CACHE_FILES = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "np-full-lyrics");
        t.setDaemon(true);
        return t;
    });

    private MediaSessionManager msm;
    private ComponentName listener;
    private MediaController ctl;
    private boolean reduced;

    private ImageView bg, art, btnPrev, btnPlay, btnNext;
    private View wash;
    private TextView label, title, artist, album, elapsed, total;
    private SeekBar bar;
    private LinearLayout lyricBox;
    private final TextView[] lines = new TextView[5];
    private final List<Animator> drift = new ArrayList<Animator>();

    private String currentKey = "";
    private boolean isPlaying = false;
    private boolean iconSet = false;
    private long lastUserSeek = 0;
    private int shownIndex = -2;
    private volatile long[] lyricTimes = null;
    private volatile String[] lyricTexts = null;
    private volatile boolean lyricsDone = false;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        reduced = Glass.reduced(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        msm = getSystemService(MediaSessionManager.class);
        listener = new ComponentName(this, GuardRevival.class);
        setContentView(buildUi());
        btnPlay.requestFocus();
    }

    // ---- UI --------------------------------------------------------------------------------------

    private int dp(float v) {
        return Glass.dp(this, v);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private ImageView roundButton(int icon, int sizeDp, int padDp) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        Glass.focusable(b, 60);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMarginEnd(dp(16));
        b.setLayoutParams(lp);
        return b;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05080F);

        // Background: the art shrunk to a few pixels and stretched back (a cheap blur on any API),
        // a colour wash from its average colour, and a dark scrim so the text stays readable.
        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setScaleX(1.35f);
        bg.setScaleY(1.35f);
        root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wash = new View(this);
        root.addView(wash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(this);
        scrim.setBackgroundColor(0x77000000);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(64), dp(40), dp(64), dp(40));
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Glass.glass(this, 28));
        art.setElevation(dp(12));
        art.setClipToOutline(true);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(28));
            }
        });
        content.addView(art, new LinearLayout.LayoutParams(dp(300), dp(300)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        colLp.setMarginStart(dp(48));
        content.addView(col, colLp);

        label = text(12, 0xAAFFFFFF, true);
        label.setLetterSpacing(0.2f);
        label.setText("NOW PLAYING");
        col.addView(label);
        title = text(30, 0xFFFFFFFF, true);
        title.setText("Nothing playing");
        col.addView(title);
        artist = text(18, 0xCCFFFFFF, false);
        col.addView(artist);
        album = text(14, 0x88FFFFFF, false);
        col.addView(album);

        // Lyrics: five lines, the middle one big and white, the rest dim (and softly blurred on API 31+).
        lyricBox = new LinearLayout(this);
        lyricBox.setOrientation(LinearLayout.VERTICAL);
        lyricBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        boxLp.topMargin = dp(10);
        boxLp.bottomMargin = dp(6);
        col.addView(lyricBox, boxLp);
        float[] sizes = {15, 18, 27, 18, 15};
        float[] alphas = {0.22f, 0.4f, 1f, 0.4f, 0.22f};
        for (int i = 0; i < 5; i++) {
            TextView t = text(sizes[i], 0xFFFFFFFF, i == 2);
            t.setSingleLine(false);
            t.setMaxLines(2);
            t.setAlpha(alphas[i]);
            t.setText(" ");
            if (i != 2 && !reduced && Build.VERSION.SDK_INT >= 31) {
                t.setRenderEffect(RenderEffect.createBlurEffect(i == 1 || i == 3 ? 1.5f : 3f,
                        i == 1 || i == 3 ? 1.5f : 3f, Shader.TileMode.CLAMP));
            }
            lines[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            lyricBox.addView(t, lp);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        btnPrev = roundButton(R.drawable.np_ic_prev, 56, 16);
        btnPlay = roundButton(R.drawable.np_ic_play, 64, 18);
        btnNext = roundButton(R.drawable.np_ic_next, 56, 16);
        buttons.addView(btnPrev);
        buttons.addView(btnPlay);
        buttons.addView(btnNext);
        col.addView(buttons);
        btnPrev.setOnClickListener(v -> { if (ctl != null) ctl.getTransportControls().skipToPrevious(); });
        btnNext.setOnClickListener(v -> { if (ctl != null) ctl.getTransportControls().skipToNext(); });
        btnPlay.setOnClickListener(v -> togglePlay());

        LinearLayout seek = new LinearLayout(this);
        seek.setOrientation(LinearLayout.HORIZONTAL);
        seek.setGravity(Gravity.CENTER_VERTICAL);
        elapsed = text(12, 0xB3FFFFFF, false);
        elapsed.setText("0:00");
        total = text(12, 0xB3FFFFFF, false);
        total.setText("0:00");
        bar = new SeekBar(this);
        bar.setFocusable(true);
        bar.setKeyProgressIncrement(10);
        bar.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || ctl == null) return;
                lastUserSeek = SystemClock.elapsedRealtime();
                ctl.getTransportControls().seekTo(p * 1000L);
                elapsed.setText(fmt(p * 1000L));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        seek.addView(elapsed);
        seek.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seek.addView(total);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seekLp.topMargin = dp(12);
        col.addView(seek, seekLp);
        return root;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(tick);
        startDrift();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        stopDrift();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    /** Slow background drift, two long reversing animators. Off with "reduce effects". */
    private void startDrift() {
        if (reduced || !drift.isEmpty()) return;
        ObjectAnimator x = ObjectAnimator.ofFloat(bg, View.TRANSLATION_X, -dp(70), dp(70));
        ObjectAnimator y = ObjectAnimator.ofFloat(bg, View.TRANSLATION_Y, dp(40), -dp(40));
        x.setDuration(38000);
        y.setDuration(51000);
        for (ObjectAnimator a : new ObjectAnimator[] {x, y}) {
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.setInterpolator(new LinearInterpolator());
            a.start();
            drift.add(a);
        }
    }

    private void stopDrift() {
        for (Animator a : drift) a.cancel();
        drift.clear();
    }

    // ---- keys ------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && ctl != null) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    togglePlay();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    ctl.getTransportControls().skipToNext();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    ctl.getTransportControls().skipToPrevious();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    ctl.getTransportControls().seekTo(Math.max(0, currentPos() + 10000));
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    ctl.getTransportControls().seekTo(Math.max(0, currentPos() - 10000));
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void togglePlay() {
        if (ctl == null) return;
        if (isPlaying) ctl.getTransportControls().pause();
        else ctl.getTransportControls().play();
    }

    // ---- media -----------------------------------------------------------------------------------

    private MediaController pick() {
        List<MediaController> list = msm.getActiveSessions(listener);
        MediaController paused = null;
        MediaController any = null;
        for (MediaController c : list) {
            PlaybackState ps = c.getPlaybackState();
            if (any == null && c.getMetadata() != null) any = c;
            if (ps == null) continue;
            int st = ps.getState();
            if (st == PlaybackState.STATE_PLAYING) return c;
            if (paused == null && st == PlaybackState.STATE_PAUSED) paused = c;
        }
        return paused != null ? paused : any;
    }

    private long currentPos() {
        if (ctl == null) return 0;
        PlaybackState s = ctl.getPlaybackState();
        if (s == null) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, pos);
    }

    private void refresh() {
        MediaController found;
        try {
            found = pick();
        } catch (SecurityException ex) {
            showMessage("Allow notification access for this launcher to see what is playing");
            return;
        } catch (RuntimeException ex) {
            found = null;
        }
        if (found == null) {
            ctl = null;
            showMessage("Nothing playing");
            return;
        }
        MediaMetadata m = found.getMetadata();
        PlaybackState s = found.getPlaybackState();
        if (m == null || s == null) {
            ctl = null;
            showMessage("Nothing playing");
            return;
        }
        ctl = found;

        boolean playing = s.getState() == PlaybackState.STATE_PLAYING;
        if (!iconSet || playing != isPlaying) {
            isPlaying = playing;
            iconSet = true;
            btnPlay.setImageResource(playing ? R.drawable.np_ic_pause : R.drawable.np_ic_play);
        }

        String t = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        String a = nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        String al = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM));
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String key = found.getPackageName() + "|" + t + "|" + a;
        if (!key.equals(currentKey)) {
            currentKey = key;
            title.setText(t.isEmpty() ? "Unknown title" : t);
            artist.setText(a);
            album.setText(al);
            label.setText("NOW PLAYING \u00B7 " + appName(found.getPackageName()).toUpperCase());
            setArt(m);
            lyricTimes = null;
            lyricTexts = null;
            lyricsDone = false;
            shownIndex = -2;
            for (TextView l : lines) l.setText(" ");
            lines[2].setTextSize(27);
            if (!t.isEmpty()) loadLyrics(key, t, a, al, duration / 1000);
        }

        long pos = currentPos();
        if (duration > 0 && pos > duration) pos = duration;
        if (duration > 0) {
            int max = (int) (duration / 1000);
            if (bar.getMax() != max) bar.setMax(max);
            bar.setVisibility(View.VISIBLE);
            if (SystemClock.elapsedRealtime() - lastUserSeek > 1500) {
                int p = (int) (pos / 1000);
                if (bar.getProgress() != p) bar.setProgress(p);
                elapsed.setText(fmt(pos));
            }
            total.setText(fmt(duration));
        } else {
            bar.setVisibility(View.INVISIBLE);
            elapsed.setText("");
            total.setText("");
        }
        updateLyrics(pos);
    }

    private void showMessage(String msg) {
        if (!currentKey.isEmpty() || !msg.equals(title.getText().toString())) {
            currentKey = "";
            title.setText(msg);
            artist.setText("");
            album.setText("");
            label.setText("NOW PLAYING");
            for (TextView l : lines) l.setText(" ");
            lyricTimes = null;
            lyricTexts = null;
        }
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ex) {
            return pkg;
        }
    }

    private void setArt(MediaMetadata m) {
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) {
            art.setImageDrawable(null);
            bg.setImageDrawable(null);
            wash.setBackgroundColor(0x00000000);
            return;
        }
        art.setImageBitmap(b);
        try {
            Bitmap small = Bitmap.createScaledBitmap(b, 48, 48, true);
            bg.setImageBitmap(small);
            if (Build.VERSION.SDK_INT >= 31 && !reduced) {
                bg.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP));
            }
            int avg = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0);
            wash.setBackgroundColor((avg & 0x00FFFFFF) | 0x55000000);
        } catch (RuntimeException ex) {
            bg.setImageDrawable(null);
            wash.setBackgroundColor(0x00000000);
        }
    }

    // ---- lyrics ----------------------------------------------------------------------------------

    private void updateLyrics(long pos) {
        long[] times = lyricTimes;
        String[] texts = lyricTexts;
        if (times == null || texts == null) {
            if (lyricsDone && shownIndex != -3) {
                shownIndex = -3;
                lines[2].setText("No synced lyrics for this track");
                lines[2].setTextSize(16);
            }
            return;
        }
        long at = pos + 300;
        int idx = -1;
        for (int i = 0; i < times.length; i++) {
            if (times[i] <= at) idx = i;
            else break;
        }
        if (idx == shownIndex) return;
        boolean first = shownIndex < -1;
        shownIndex = idx;
        lines[2].setTextSize(27);
        for (int k = 0; k < 5; k++) {
            int i = idx + k - 2;
            String s = (i >= 0 && i < texts.length) ? texts[i] : "";
            lines[k].setText(s.isEmpty() ? " " : s);
        }
        if (!reduced && !first) {
            lyricBox.animate().cancel();
            lyricBox.setTranslationY(dp(44));
            lyricBox.animate().translationY(0f).setDuration(420).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            lines[2].animate().cancel();
            lines[2].setAlpha(0.4f);
            lines[2].animate().alpha(1f).setDuration(360).start();
        }
    }

    private void loadLyrics(final String key, final String t, final String a, final String al, final long durSec) {
        pool.execute(() -> {
            String lrc = readCache(key);
            if (lrc == null) {
                lrc = fetch(t, a, al, durSec);
                writeCache(key, lrc == null ? "-" : lrc);
            } else if (lrc.equals("-")) {
                lrc = null;
            }
            final TreeMap<Long, String> map = lrc == null ? null : parse(lrc);
            handler.post(() -> {
                if (!key.equals(currentKey)) return;
                lyricsDone = true;
                if (map == null) return;
                long[] ts = new long[map.size()];
                String[] ss = new String[map.size()];
                int i = 0;
                for (Map.Entry<Long, String> e : map.entrySet()) {
                    ts[i] = e.getKey();
                    ss[i] = e.getValue();
                    i++;
                }
                lyricTexts = ss;
                lyricTimes = ts;
            });
        });
    }

    /** LRCLIB: exact match first (needs a duration), then a search, picking the closest length. */
    private String fetch(String t, String a, String al, long durSec) {
        try {
            if (durSec > 0 && !a.isEmpty()) {
                String url = "https://lrclib.net/api/get?track_name=" + enc(t) + "&artist_name=" + enc(a)
                        + (al.isEmpty() ? "" : "&album_name=" + enc(al)) + "&duration=" + durSec;
                String body = http(url);
                if (body != null) {
                    String syn = new JSONObject(body).optString("syncedLyrics", "");
                    if (!syn.isEmpty() && !syn.equals("null")) return syn;
                }
            }
            String body = http("https://lrclib.net/api/search?track_name=" + enc(t) + "&artist_name=" + enc(a));
            if (body == null) return null;
            JSONArray arr = new JSONArray(body);
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
            return best;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String enc(String s) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String http(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "CleanYourTV-NowPlaying/2.0");
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception ex) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static TreeMap<Long, String> parse(String lrc) {
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

    // ---- disk cache: cacheDir/lyrics/<sha1 of key>; "-" marks "no lyrics" and expires after 3 days ---

    private File cacheFile(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            File dir = new File(getCacheDir(), "lyrics");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, sb.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private String readCache(String key) {
        File f = cacheFile(key);
        if (f == null || !f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            String s = new String(buf, 0, off, "UTF-8");
            if (s.equals("-") && System.currentTimeMillis() - f.lastModified() > MISS_TTL_MS) return null;
            return s;
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeCache(String key, String content) {
        File f = cacheFile(key);
        if (f == null) return;
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(content.getBytes("UTF-8"));
            out.close();
            File[] all = f.getParentFile().listFiles();
            if (all != null && all.length > MAX_CACHE_FILES) {
                java.util.Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
                for (int i = 0; i < all.length - MAX_CACHE_FILES; i++) all[i].delete();
            }
        } catch (Exception ex) {
            // cache is best effort
        }
    }

    // ---- small helpers ---------------------------------------------------------------------------

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
