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
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Transient volume indicator drawn over the rear pager. Four visual styles, picked in the
 * Flutter app (volume_overlay_style int pref, read on every show):
 * 0 liquid capsule, 1 speaker waves, 2 bottom pill, 3 ring gauge.
 * Call {@link #show(int, int)} on every volume change; it fades itself out {@link #HIDE_MS}
 * after the last call. The level fills/animates the same in every style.
 */
public class VolumeOverlayView extends View {
    private static final long HIDE_MS = 1500;
    private static final long FADE_MS = 220;

    private static final int GREEN = 0xFF34C759;

    private final Paint dimPaint = new Paint();
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path wave = new Path();
    private final Path speaker = new Path();
    private final RectF capsule = new RectF();
    private final RectF pillTrack = new RectF();
    private final RectF ring = new RectF();

    private final int leftInset; // camera cutout: content is centered in what is right of it
    private int style = 0;
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
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** Safe to call on every volume event; restarts the hide timer. */
    public void show(int level, int max) {
        // Style is a Flutter-side pick; apply it on every press so a change needs no bridge.
        // Flutter's setInt is stored as a long in FlutterSharedPreferences; getInt would throw ClassCastException.
        style = (int) Math.max(0, Math.min(3, getContext().getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                .getLong("flutter.volume_overlay_style", 0L)));
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
        switch (style) {
            case 1: drawSpeaker(c, w, h); break;
            case 2: drawPill(c, w, h); break;
            case 3: drawRing(c, w, h); break;
            default: drawCapsule(c, w, h); break;
        }
    }

    // ---- Style 0: liquid capsule (unchanged design) ----
    private void drawCapsule(Canvas c, int w, int h) {
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
                new int[]{0xFF5FE083, GREEN, 0xFF1F9B46}, new float[]{0f, 0.4f, 1f},
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

    // ---- Style 1: speaker icon, waves light up with the level, mute shows a red slash ----
    private void drawSpeaker(Canvas c, int w, int h) {
        float cx = leftInset + (w - leftInset) / 2f;
        float u = h * 0.02f; // design unit scaled to the screen
        float iconC = h / 2f - h * 0.14f;

        speaker.reset();
        speaker.moveTo(cx - 3.2f * u, iconC - 1.4f * u);
        speaker.lineTo(cx - 1.4f * u, iconC - 1.4f * u);
        speaker.lineTo(cx + 0.4f * u, iconC - 3.2f * u);
        speaker.lineTo(cx + 0.4f * u, iconC + 3.2f * u);
        speaker.lineTo(cx - 1.4f * u, iconC + 1.4f * u);
        speaker.lineTo(cx - 3.2f * u, iconC + 1.4f * u);
        speaker.close();
        c.drawPath(speaker, textPaint);

        int muted = percent == 0 ? 1 : 0;
        arcPaint.setStrokeWidth(0.9f * u);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        // Wave arcs; the ones above the level stay dim.
        drawWaveArc(c, cx, iconC, 1.9f * u, 4.0f * u, muted == 1 || percent > 0);
        drawWaveArc(c, cx, iconC, 3.2f * u, 4.0f * u, muted == 1 || percent > 33);
        drawWaveArc(c, cx, iconC, 4.5f * u, 4.0f * u, muted == 1 || percent > 66);

        if (muted == 1) { // red slash over the speaker
            arcPaint.setStrokeWidth(1.0f * u);
            arcPaint.setColor(0xFFFF453A);
            c.drawLine(cx - 3.6f * u, iconC + 3.6f * u, cx + 4.4f * u, iconC - 3.6f * u, arcPaint);
            arcPaint.setColor(Color.WHITE);
        }

        numPaint.setTextSize(h * 0.29f);
        numPaint.setTextAlign(Paint.Align.CENTER);
        String num = String.valueOf(percent);
        c.drawText(num, cx, h * 0.82f + numPaint.getTextSize() * 0.3f, numPaint);
        pctPaint.setTextSize(h * 0.05f);
        pctPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText("MEDIA VOLUME", cx, h * 0.93f, pctPaint);
        numPaint.setTextAlign(Paint.Align.RIGHT); // restore shared paints' align for the other styles
        pctPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawWaveArc(Canvas c, float cx, float cy, float r, float span, boolean on) {
        arcPaint.setColor(on ? Color.WHITE : 0x2EFFFFFF);
        float s = cy - r, e = cy + r;
        if (r < 2.4f * getHeight() * 0.02f) {
            wave.reset();
            wave.moveTo(cx, s);
            wave.quadTo(cx + span, cy, cx, e);
        } else {
            wave.reset();
            wave.moveTo(cx, s);
            wave.quadTo(cx + span * 1.25f, cy, cx, e);
        }
        c.drawPath(wave, arcPaint);
        arcPaint.setColor(Color.WHITE);
    }

    // ---- Style 2: bottom pill with a track (same motion language as the pager entrance) ----
    private void drawPill(Canvas c, int w, int h) {
        float avail = w - leftInset;
        float pillW = Math.min(avail * 0.82f, h * 1.6f);
        float pillH = pillW * 0.196f;
        float pad = pillH * 0.31f;
        float x0 = leftInset + (avail - pillW) / 2f;
        float y = h - pillH - h * 0.077f;

        // entrance: slide up 12% of height during the show fade
        float slide = (1f - getAlpha()) * h * 0.12f;
        c.save();
        c.translate(0, slide);

        // small speaker glyph on the left
        float u = pillH * 0.36f;
        float cy = y + pillH / 2f;
        float gx = x0 + pad * 1.6f;
        speaker.reset();
        speaker.moveTo(gx - 1.2f * u, cy - 0.8f * u);
        speaker.lineTo(gx - 0.35f * u, cy - 0.8f * u);
        speaker.lineTo(gx + 0.5f * u, cy - 1.55f * u);
        speaker.lineTo(gx + 0.5f * u, cy + 1.55f * u);
        speaker.lineTo(gx - 0.35f * u, cy + 0.8f * u);
        speaker.lineTo(gx - 1.2f * u, cy + 0.8f * u);
        speaker.close();
        if (percent == 0) { // muted: dim glyph
            textPaint.setColor(0x99FFFFFF);
            c.drawPath(speaker, textPaint);
            textPaint.setColor(Color.WHITE);
            arcPaint.setStyle(Paint.Style.STROKE);
            arcPaint.setStrokeCap(Paint.Cap.ROUND);
            arcPaint.setStrokeWidth(u * 0.28f);
            arcPaint.setColor(0xFFFF453A);
            c.drawLine(gx - 1.5f * u, cy + 1.5f * u, gx + 1.8f * u, cy - 1.5f * u, arcPaint);
        } else {
            c.drawPath(speaker, textPaint);
            arcPaint.setStyle(Paint.Style.STROKE);
            arcPaint.setStrokeCap(Paint.Cap.ROUND);
            arcPaint.setStrokeWidth(u * 0.28f);
            arcPaint.setColor(Color.WHITE);
            wave.reset();
            wave.moveTo(gx + 1.2f * u, cy - 0.6f * u);
            wave.quadTo(gx + 1.8f * u, cy, gx + 1.2f * u, cy + 0.6f * u);
            c.drawPath(wave, arcPaint);
        }

        float trackX = gx + 2.6f * u;
        float trackR = pillH * 0.22f;
        pillTrack.set(trackX, cy - trackR, x0 + pillW - pad, cy + trackR);
        trackPaint.setColor(0x2EFFFFFF);
        c.drawRoundRect(pillTrack, trackR, trackR, trackPaint);
        float fillR = pillTrack.right - pillTrack.left - (1f - shownFraction) * pillTrack.width();
        clip.reset();
        clip.addRoundRect(pillTrack, trackR, trackR, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        fillPaint.setColor(GREEN);
        c.drawRect(pillTrack.left, pillTrack.top, pillTrack.left + fillR, pillTrack.bottom, fillPaint);
        c.restore();

        // value, right-aligned in a fixed slot so the pill does not shift
        textPaint.setTextSize(pillH * 0.5f);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        String num = String.valueOf(percent);
        c.drawText(num, x0 + pillW - pad, cy + textPaint.getTextSize() * 0.36f, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);

        c.restore(); // slide
    }

    // ---- Style 3: ring gauge with a gradient sweep ----
    private void drawRing(Canvas c, int w, int h) {
        float avail = w - leftInset;
        float cx = leftInset + avail / 2f;
        float cy = h / 2f;
        float d = Math.min(avail * 0.62f, h * 0.62f);
        ring.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f);

        float stroke = d * 0.09f;
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        // background track
        arcPaint.setColor(0x24FFFFFF);
        c.drawArc(ring, 0, 360, false, arcPaint);
        // foreground sweep
        if (shownFraction > 0.01f) {
            arcPaint.setShader(new SweepGradient(cx, cy,
                    new int[]{0xFF5AC8FA, GREEN}, null));
            c.drawArc(ring, -90, 360 * shownFraction, false, arcPaint);
            arcPaint.setShader(null);
        }

        // speaker glyph above the number
        float u = d * 0.055f;
        float iy = cy - d * 0.13f;
        speaker.reset();
        speaker.moveTo(cx - 1.6f * u, iy - 0.95f * u);
        speaker.lineTo(cx - 0.5f * u, iy - 0.95f * u);
        speaker.lineTo(cx + 0.5f * u, iy - 1.7f * u);
        speaker.lineTo(cx + 0.5f * u, iy + 1.7f * u);
        speaker.lineTo(cx - 0.5f * u, iy + 0.95f * u);
        speaker.lineTo(cx - 1.6f * u, iy + 0.95f * u);
        speaker.close();
        c.drawPath(speaker, textPaint);
        arcPaint.setStrokeWidth(u * 0.45f);
        wave.reset();
        wave.moveTo(cx + 1.2f * u, iy - 0.6f * u);
        wave.quadTo(cx + 1.9f * u, iy, cx + 1.2f * u, iy + 0.6f * u);
        c.drawPath(wave, arcPaint);

        numPaint.setTextSize(d * 0.32f);
        numPaint.setTextAlign(Paint.Align.CENTER);
        String num = String.valueOf(percent);
        c.drawText(num, cx, cy + d * 0.10f + numPaint.getTextSize() * 0.36f, numPaint);
        numPaint.setTextAlign(Paint.Align.RIGHT);
    }
}
