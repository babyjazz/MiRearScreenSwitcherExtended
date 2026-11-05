package com.tgwgroup.MiRearScreenSwitcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Transient volume indicator drawn over the rear pager: a glass capsule filled with the same green
 * liquid as the charging page, plus the percentage. Call {@link #show(int, int)} on every volume
 * change; it fades itself out {@link #HIDE_MS} after the last call.
 */
public class VolumeOverlayView extends View {
    private static final long HIDE_MS = 1500;
    private static final long FADE_MS = 220;

    private final Paint dimPaint = new Paint();
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path wave = new Path();
    private final RectF capsule = new RectF();

    private final int leftInset; // camera cutout: content is centered in what is right of it
    private float shownFraction = 0f; // animated fill, 0..1
    private int percent = 0;
    private float phase = 0f;
    private ValueAnimator levelAnimator;
    private ValueAnimator waveAnimator;

    private final Runnable hide = () -> animate().alpha(0f).setDuration(FADE_MS)
            .withEndAction(() -> {
                setVisibility(GONE);
                stopWave();
            }).start();

    public VolumeOverlayView(Context context, int leftInset) {
        super(context);
        this.leftInset = leftInset;
        setVisibility(GONE);
        setAlpha(0f);
        setClickable(false); // gestures are handled at the Activity level
        dimPaint.setColor(0x73000000);
        innerPaint.setColor(0x12FFFFFF);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(0x8CFFFFFF);
        wavePaint.setColor(0x47FFFFFF);
        numPaint.setColor(Color.WHITE);
        numPaint.setTypeface(Typeface.DEFAULT_BOLD);
        numPaint.setTextAlign(Paint.Align.RIGHT);
        pctPaint.setColor(0xFFAABBCC);
        pctPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** Safe to call on every volume event; restarts the hide timer. */
    public void show(int level, int max) {
        percent = max > 0 ? Math.round(level * 100f / max) : 0;
        setVisibility(VISIBLE);
        animate().cancel();
        animate().alpha(1f).setDuration(FADE_MS).start();
        animateLevelTo(percent / 100f);
        startWave();
        removeCallbacks(hide);
        postDelayed(hide, HIDE_MS);
    }

    private void animateLevelTo(float target) {
        if (levelAnimator != null) levelAnimator.cancel();
        levelAnimator = ValueAnimator.ofFloat(shownFraction, target);
        levelAnimator.setDuration(350);
        levelAnimator.setInterpolator(new DecelerateInterpolator(2f));
        levelAnimator.addUpdateListener(a -> {
            shownFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        levelAnimator.start();
    }

    private void startWave() {
        if (waveAnimator != null && waveAnimator.isRunning()) return;
        waveAnimator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        waveAnimator.setDuration(2000);
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        waveAnimator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        waveAnimator.start();
    }

    private void stopWave() {
        if (waveAnimator != null) waveAnimator.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(hide);
        if (levelAnimator != null) levelAnimator.cancel();
        stopWave();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, dimPaint);

        // Proportions follow the rear screen height so it matches the design at any density.
        float capH = h * 0.70f, capW = capH * 0.357f, gap = h * 0.117f;
        numPaint.setTextSize(h * 0.25f);
        pctPaint.setTextSize(h * 0.09f);
        String num = String.valueOf(percent);
        // Reserve the widest possible number so the capsule never shifts when the digit count changes.
        float slotW = numPaint.measureText("000"), pctW = pctPaint.measureText("%");
        float rowW = capW + gap + slotW + pctW;
        float x0 = leftInset + (w - leftInset - rowW) / 2f;
        capsule.set(x0, (h - capH) / 2f, x0 + capW, (h + capH) / 2f);
        float r = capW / 2f;

        c.drawRoundRect(capsule, r, r, innerPaint);

        float top = capsule.bottom - capsule.height() * shownFraction;
        c.save();
        clip.reset();
        clip.addRoundRect(capsule, r, r, Path.Direction.CW);
        c.clipPath(clip);
        liquidPaint.setShader(new LinearGradient(0, top, 0, capsule.bottom,
                new int[]{0xFF5FE083, 0xFF34C759, 0xFF1F9B46}, new float[]{0f, 0.4f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(capsule.left, top, capsule.right, capsule.bottom, liquidPaint);
        // Wave crest on top of the fill; skipped when the capsule is empty or full.
        if (shownFraction > 0.02f && shownFraction < 0.98f) {
            float amp = h * 0.012f;
            wave.reset();
            wave.moveTo(capsule.left, top);
            for (float x = 0; x <= capW; x += 4) {
                wave.lineTo(capsule.left + x,
                        top + (float) Math.sin(x / capW * 2 * Math.PI + phase) * amp);
            }
            wave.lineTo(capsule.right, top + amp * 3);
            wave.lineTo(capsule.left, top + amp * 3);
            wave.close();
            c.drawPath(wave, wavePaint);
        }
        c.restore();

        outlinePaint.setStrokeWidth(Math.max(2f, h * 0.005f));
        c.drawRoundRect(capsule, r, r, outlinePaint);

        float baseline = h / 2f + numPaint.getTextSize() * 0.36f;
        float tx = capsule.right + gap;
        // Number is right-aligned in its fixed slot so the "%" stays put and short numbers grow toward the capsule.
        c.drawText(num, tx + slotW, baseline, numPaint);
        c.drawText("%", tx + slotW, baseline, pctPaint);
    }
}
