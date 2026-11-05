package com.tgwgroup.MiRearScreenSwitcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
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
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path wave = new Path();
    private final Path speaker = new Path();
    private final RectF capsule = new RectF();
    private final RectF pillTrack = new RectF();
    private final RectF ring = new RectF();
    private final RectF iconArc = new RectF();

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
        iconPaint.setColor(Color.WHITE);
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

    // Shared speaker glyph: rounded body plus three wave arcs that light up with the level.
    // cx/cy is the glyph centre, u its design unit (glyph is ~8.2u wide, ~5.4u tall). Muted shows a red slash.
    private void drawSpeakerIcon(Canvas c, float cx, float cy, float u, int pct) {
        float x0 = cx - 4.1f * u;
        speaker.reset();
        speaker.moveTo(x0, cy - 1.1f * u);
        speaker.lineTo(x0 + 1.3f * u, cy - 1.1f * u);
        speaker.lineTo(x0 + 3.2f * u, cy - 2.7f * u);
        speaker.lineTo(x0 + 3.2f * u, cy + 2.7f * u);
        speaker.lineTo(x0 + 1.3f * u, cy + 1.1f * u);
        speaker.lineTo(x0, cy + 1.1f * u);
        speaker.close();
        iconPaint.setPathEffect(new CornerPathEffect(0.4f * u));
        c.drawPath(speaker, iconPaint);

        arcPaint.setShader(null);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        if (pct == 0) {
            arcPaint.setStrokeWidth(0.7f * u);
            arcPaint.setColor(0xFFFF453A);
            c.drawLine(x0 - 0.2f * u, cy + 3.2f * u, x0 + 4.0f * u, cy - 3.2f * u, arcPaint);
            return;
        }
        arcPaint.setStrokeWidth(0.55f * u);
        float ax = x0 + 3.4f * u;
        float[] radii = {2.0f * u, 3.4f * u, 4.8f * u};
        int[] thresholds = {0, 33, 66};
        for (int i = 0; i < 3; i++) {
            arcPaint.setColor(pct > thresholds[i] ? Color.WHITE : 0x33FFFFFF);
            iconArc.set(ax - radii[i], cy - radii[i], ax + radii[i], cy + radii[i]);
            c.drawArc(iconArc, -42, 84, false, arcPaint);
        }
    }

    // ---- Style 1: big speaker glyph, number, caption ----
    private void drawSpeaker(Canvas c, int w, int h) {
        float cx = leftInset + (w - leftInset) / 2f;
        drawSpeakerIcon(c, cx, h * 0.27f, h * 0.034f, percent);

        numPaint.setTextSize(h * 0.26f);
        numPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(String.valueOf(percent), cx, h * 0.70f, numPaint);
        numPaint.setTextAlign(Paint.Align.RIGHT); // restore the shared paint for the other styles

        pctPaint.setTextSize(h * 0.05f);
        pctPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText("MEDIA VOLUME", cx, h * 0.88f, pctPaint);
        pctPaint.setTextAlign(Paint.Align.LEFT);
    }

    // ---- Style 2: bottom pill: small glyph, gradient bar, number outside the bar ----
    private void drawPill(Canvas c, int w, int h) {
        float avail = w - leftInset;
        float pillW = Math.min(avail * 0.82f, h * 1.6f);
        float pillH = pillW * 0.196f;
        float pad = pillH * 0.31f;
        float x0 = leftInset + (avail - pillW) / 2f;
        float y = h - pillH - h * 0.077f;
        float cy = y + pillH / 2f;

        // entrance: slides up while the overlay fades in
        c.save();
        c.translate(0, (1f - getAlpha()) * h * 0.12f);

        capsule.set(x0, y, x0 + pillW, y + pillH);
        innerPaint.setColor(0xEB282A32);
        c.drawRoundRect(capsule, pillH / 2f, pillH / 2f, innerPaint);
        innerPaint.setColor(0x12FFFFFF); // restore for the capsule style
        outlinePaint.setStrokeWidth(Math.max(2f, h * 0.004f));
        outlinePaint.setColor(0x2EFFFFFF);
        c.drawRoundRect(capsule, pillH / 2f, pillH / 2f, outlinePaint);
        outlinePaint.setColor(0x8CFFFFFF);

        float u = pillH * 0.095f;
        drawSpeakerIcon(c, x0 + pad + 4.1f * u, cy, u, percent);

        // Number gets its own right-aligned slot so it never overlaps the bar or shifts it.
        textPaint.setTextSize(pillH * 0.46f);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        float numRight = x0 + pillW - pad * 1.2f;
        float slotW = textPaint.measureText("100");
        c.drawText(String.valueOf(percent), numRight, cy + textPaint.getTextSize() * 0.36f, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);

        float trackLeft = x0 + pad + 8.2f * u + pad * 0.9f;
        float trackRight = numRight - slotW - pad * 0.9f;
        float trackR = pillH * 0.12f;
        pillTrack.set(trackLeft, cy - trackR, trackRight, cy + trackR);
        trackPaint.setColor(0x2EFFFFFF);
        c.drawRoundRect(pillTrack, trackR, trackR, trackPaint);

        float fillRight = pillTrack.left + pillTrack.width() * shownFraction;
        if (fillRight > pillTrack.left + 1f) {
            // Same green as the liquid capsule, light to dark along the filled part.
            fillPaint.setShader(new LinearGradient(pillTrack.left, 0, fillRight, 0,
                    new int[]{0xFF5FE083, GREEN, 0xFF1F9B46}, new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            c.save();
            clip.reset();
            clip.addRoundRect(pillTrack, trackR, trackR, Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(pillTrack.left, pillTrack.top, fillRight, pillTrack.bottom, fillPaint);
            c.restore();
        }
        c.restore();
    }

    // ---- Style 3: ring gauge on a dark disc, sweep gradient from 12 o'clock ----
    private void drawRing(Canvas c, int w, int h) {
        float avail = w - leftInset;
        float cx = leftInset + avail / 2f;
        float cy = h / 2f;
        float d = Math.min(avail * 0.62f, h * 0.62f);
        float stroke = d * 0.09f;
        ring.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setShader(null);
        arcPaint.setColor(0x40FFFFFF);
        c.drawArc(ring, 0, 360, false, arcPaint);
        if (shownFraction > 0.01f) {
            c.save();
            c.rotate(-90, cx, cy); // sweep gradient starts at 3 o'clock; rotate so it starts at the top
            arcPaint.setShader(new SweepGradient(cx, cy,
                    new int[]{0xFF5AC8FA, GREEN}, null));
            arcPaint.setColor(Color.WHITE); // fully opaque; colour comes from the shader
            c.drawArc(ring, 0, 360 * shownFraction, false, arcPaint);
            arcPaint.setShader(null);
            c.restore();
        }

        drawSpeakerIcon(c, cx, cy - d * 0.17f, d * 0.032f, percent);
        numPaint.setTextSize(d * 0.30f);
        numPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(String.valueOf(percent), cx, cy + d * 0.24f, numPaint);
        numPaint.setTextAlign(Paint.Align.RIGHT);
    }
}
