package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.content.pm.PackageManager;
import java.util.List;

public class NowPlaying {
    private final Context ctx;
    private final View panel;
    private final TextView title;
    private final TextView artist;
    private final ProgressBar bar;
    private final ImageView art;
    private final TextView app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;

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
        this.title = panel.findViewById(R.id.npTitle);
        this.artist = panel.findViewById(R.id.npArtist);
        this.bar = panel.findViewById(R.id.npBar);
        this.art = panel.findViewById(R.id.npArt);
        this.app = panel.findViewById(R.id.npApp);
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

    private void update() {
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(ctx, GuardRevival.class);
            List<MediaController> list = msm.getActiveSessions(listener);
            MediaController found = null;
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    found = c;
                    break;
                }
                if (found == null && s != null && s.getState() == PlaybackState.STATE_PAUSED) {
                    found = c;
                }
            }
            if (found == null) {
                panel.setVisibility(View.GONE);
                return;
            }
            MediaMetadata m = found.getMetadata();
            PlaybackState s = found.getPlaybackState();
            if (m == null || s == null) {
                panel.setVisibility(View.GONE);
                return;
            }
            title.setText(m.getString(MediaMetadata.METADATA_KEY_TITLE));
            artist.setText(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
            Bitmap bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART); if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART); if (bmp != null) { art.setImageBitmap(bmp); art.setVisibility(View.VISIBLE); } else { art.setVisibility(View.GONE); }
            try { PackageManager pm = ctx.getPackageManager(); app.setText(pm.getApplicationLabel(pm.getApplicationInfo(found.getPackageName(), 0))); } catch (Exception e) { app.setText(found.getPackageName()); }
            long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING) {
                long passed = SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
                pos = pos + (long) (passed * s.getPlaybackSpeed());
            }
            if (duration > 0) {
                bar.setMax((int) (duration / 1000));
                bar.setProgress((int) (pos / 1000));
                bar.setVisibility(View.VISIBLE);
            } else {
                bar.setVisibility(View.GONE);
            }
            panel.setVisibility(View.VISIBLE);
        } catch (RuntimeException e) {
            panel.setVisibility(View.GONE);
        }
    }
}
