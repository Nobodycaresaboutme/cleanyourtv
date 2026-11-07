package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Slow drifting aurora glow behind the home screen: a dark gradient with four soft coloured blobs. */
public class AuroraView extends View {
    private static final int FRAME_MS = 66;
    private static final int[] COLORS = {0xFF00E6A8, 0xFF2DA8FF, 0xFF8A5CFF, 0xFFFF5CC8};
    private static final float[] CX = {0.18f, 0.45f, 0.75f, 0.90f};
    private static final float[] CY = {0.30f, 0.12f, 0.28f, 0.55f};
    private static final float[] RAD = {0.34f, 0.32f, 0.36f, 0.28f};
    private static final float[] SPEED = {0.11f, 0.08f, 0.13f, 0.09f};
    private static final float[] PHASE = {0f, 1.7f, 3.1f, 4.6f};

    private final Paint base = new Paint();
    private final Paint[] blob = new Paint[4];
    private final float[] rpx = new float[4];
    private boolean running = false;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    public AuroraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        base.setShader(new LinearGradient(0, 0, 0, h,
                new int[] {0xFF06122B, 0xFF0A2D3F, 0xFF050A14}, null, Shader.TileMode.CLAMP));
        for (int i = 0; i < 4; i++) {
            rpx[i] = RAD[i] * w;
            blob[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            blob[i].setShader(new RadialGradient(CX[i] * w, CY[i] * h, rpx[i],
                    (COLORS[i] & 0x00FFFFFF) | 0x99000000, COLORS[i] & 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || blob[0] == null) return;
        canvas.drawRect(0, 0, w, h, base);
        float t = SystemClock.uptimeMillis() / 1000f;
        for (int i = 0; i < 4; i++) {
            float dx = (float) Math.sin(t * SPEED[i] + PHASE[i]) * 0.10f * w;
            float dy = (float) Math.cos(t * SPEED[i] * 0.8f + PHASE[i]) * 0.07f * h;
            float cx = CX[i] * w;
            float cy = CY[i] * h;
            canvas.save();
            canvas.translate(dx, dy);
            canvas.drawRect(cx - rpx[i], cy - rpx[i], cx + rpx[i], cy + rpx[i], blob[i]);
            canvas.restore();
        }
    }

    private void go() {
        if (running) return;
        running = true;
        post(frame);
    }

    private void halt() {
        running = false;
        removeCallbacks(frame);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        go();
    }

    @Override
    protected void onDetachedFromWindow() {
        halt();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) go();
        else halt();
    }
}
