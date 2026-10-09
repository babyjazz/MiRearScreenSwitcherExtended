/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪 (Luodao T0 Yu Xie Shu Shi Cheng Shan)
 *
 * Chief Tester: 汐木泽 (Xi Mu Ze)
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.BlurMaskFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import androidx.core.graphics.PathParser;

/**
 * Lightning-shaped liquid-fill view.
 * Fills green liquid from bottom up, with gravity sensing.
 */
public class LightningShapeView extends View implements SensorEventListener {
    private Paint liquidPaint;      // liquid brush
    private Paint liquidShinePaint; // liquid sheen brush
    private Paint bubblePaint;      // bubble brush
    private Paint outlinePaint;     // outline brush
    private Paint glassHighlightPaint;  // glass highlight brush
    private Paint glassReflectionPaint; // glass reflection brush
    private Paint innerGlowPaint;   // inner glow brush
    private Paint glassDepthPaint;  // glass depth brush
    private Path lightningPath;     // lightning shape path
    private Path highlightPath;     // highlight path (top-left)
    private Path wavePath;          // liquid-surface wave path
    private float fillLevel = 0f;   // fill ratio 0.0 - 1.0
    private float waveOffset = 0f;  // wave animation offset
    private float tiltX = 0f;       // X-axis tilt (gravity sensing)
    private float tiltY = 0f;       // Y-axis tilt (gravity sensing)
    private float[] bubblePositions = new float[6]; // bubble Y positions (gravity-affected)
    private SensorManager sensorManager;
    private Sensor accelerometer;
    
    // V3.5: fullscreen liquid mode (no lightning outline)
    private boolean fullScreenMode = false;
    
    // V3.5: reuse objects to avoid GC (perf)
    private Path fullScreenLiquidPath = new Path();
    private Path fullScreenWavePath = new Path();  // reused wave path
    private Paint fullScreenShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint fullScreenBottomShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // reused bottom-shadow brush
    private Paint fullScreenWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // reused wave brush
    private Paint fullScreenEdgeShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // reused edge-shine brush
    private int lastShadowHeight = -1;  // cached last height; avoids recreating the shader
    private int lastBottomShadowHeight = -1;  // cached bottom shadow height
    private int lastEdgeShineWidth = -1;  // cached edge shine width
    private Paint bubbleHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // reused bubble highlight brush
    
    // V3.5: wave calculation optimization (precomputed; avoids per-frame sin)
    private float[] wavePoints = new float[200];  // precomputed wave points
    private int lastWaveWidth = -1;  // cached wave width
    private float lastWaveOffset = -1f;  // cached wave offset
    
    // V3.14: restored wave calculation frequency for smoothness
    private static final float WAVE_UPDATE_THRESHOLD = 0.01f;  // wave update threshold (maxed out)
    private float lastProcessedWaveOffset = -1f;  // last processed wave offset
    
    public LightningShapeView(Context context) {
        super(context);
        init();
    }
    
    public LightningShapeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        // Initialize the gravity sensor
        try {
            sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        } catch (Exception e) {
            Log.w("LightningShapeView", "Gravity sensor init failed", e);
        }
        
        // Enable a hardware-accelerated layer type
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        
        // Liquid brush (green gradient)
        liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        liquidPaint.setStyle(Paint.Style.FILL);
        liquidPaint.setDither(true); // dither, smoother gradient
        
        // Liquid sheen brush (reflection off the liquid surface)
        liquidShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        liquidShinePaint.setStyle(Paint.Style.FILL);
        
        // Bubble brush (bubbles inside the liquid)
        bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(0x80FFFFFF);  // more transparent so bubbles stand out
        bubblePaint.setMaskFilter(new BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)); // less blur, crisper bubbles
        
        // V3.5: bubble highlight brush (pre-initialized to avoid per-frame allocs)
        bubbleHighlightPaint.setColor(0xB0FFFFFF);
        
        // Main outline brush (semi-transparent white)
        outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(6f);
        outlinePaint.setColor(0x80FFFFFF);
        
        // Glass highlight brush (bright top-left edge)
        glassHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glassHighlightPaint.setStyle(Paint.Style.STROKE);
        glassHighlightPaint.setStrokeWidth(4f);
        glassHighlightPaint.setColor(0xF0FFFFFF); // very bright
        glassHighlightPaint.setMaskFilter(new BlurMaskFilter(2f, BlurMaskFilter.Blur.OUTER));
        
        // Glass reflection brush (outer halo)
        glassReflectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glassReflectionPaint.setStyle(Paint.Style.STROKE);
        glassReflectionPaint.setStrokeWidth(12f);
        glassReflectionPaint.setColor(0x50FFFFFF);
        glassReflectionPaint.setMaskFilter(new BlurMaskFilter(6f, BlurMaskFilter.Blur.OUTER));
        
        // Glass depth brush (inner shadow for depth)
        glassDepthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glassDepthPaint.setStyle(Paint.Style.STROKE);
        glassDepthPaint.setStrokeWidth(8f);
        glassDepthPaint.setColor(0x40000000);
        glassDepthPaint.setMaskFilter(new BlurMaskFilter(4f, BlurMaskFilter.Blur.INNER));
        
        // Inner glow brush (halo around the liquid)
        innerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerGlowPaint.setStyle(Paint.Style.STROKE);
        innerGlowPaint.setStrokeWidth(2f);
        innerGlowPaint.setColor(0x60FFFFFF);
        
        // Create paths
        lightningPath = new Path();
        highlightPath = new Path();
        wavePath = new Path();
        
        // Initialize bubble positions (simple approach)
        for (int i = 0; i < bubblePositions.length; i++) {
            bubblePositions[i] = (float) Math.random();
        }
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // Parse the SVG path with Android's PathParser
        // Original SVG path data (from lightening.xml)
        String pathData = "M511.616,85.333 c-27.947,0 -54.059,14.08 -69.717,37.547 l-256.811,385.707 " +
                         "a86.187,86.187 0,0,0 22.613,118.571 l6.101,3.84 " +
                         "c12.501,7.04 26.624,10.795 41.003,10.795 h172.544 " +
                         "v211.499 c0,47.147 37.675,85.376 84.139,85.376 " +
                         "c27.861,0 53.888,-13.952 69.547,-37.291 l257.707,-383.829 " +
                         "a86.187,86.187 0,0,0 -22.187,-118.613 l-6.144,-3.883 " +
                         "a83.2,83.2 0,0,0 -41.216,-10.965 h-173.44 " +
                         "v-213.333 C595.755,123.52 558.08,85.333 511.616,85.333 z";
        
        try {
            // Parse the SVG path with AndroidX PathParser
            lightningPath = PathParser.createPathFromPathData(pathData);
            
            // Scale the path to fit the view (original viewBox is 1024x1024)
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setScale(w / 1024f, h / 1024f);
            lightningPath.transform(matrix);
            
        } catch (Exception e) {
            Log.e("LightningShapeView", "Failed to parse SVG path; using a simplified lightning shape", e);
            
            // Fall back: use a simplified lightning shape
            lightningPath.reset();
            float centerX = w / 2f;
            
            lightningPath.moveTo(centerX, h * 0.08f);
            lightningPath.lineTo(centerX - w * 0.18f, h * 0.5f);
            lightningPath.lineTo(centerX + w * 0.05f, h * 0.52f);
            lightningPath.lineTo(centerX - w * 0.08f, h * 0.92f);
            lightningPath.lineTo(centerX + w * 0.12f, h * 0.58f);
            lightningPath.lineTo(centerX + w * 0.18f, h * 0.56f);
            lightningPath.close();
        }
        
        // Use the system battery green (#34C759); drop the gradient, use a solid color
        liquidPaint.setShader(null);  // remove gradient
        liquidPaint.setColor(0xFF34C759);  // system battery green
        
        // Create a top-left highlight path (simulating glass reflection)
        highlightPath.reset();
        highlightPath.moveTo(w * 0.2f, h * 0.1f);
        highlightPath.lineTo(w * 0.35f, h * 0.15f);
        highlightPath.lineTo(w * 0.3f, h * 0.35f);
        highlightPath.lineTo(w * 0.15f, h * 0.3f);
        highlightPath.close();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        long drawStartTime = System.nanoTime();  // perf tracking start
        
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
        // V3.5: fullscreen liquid mode - draw liquid directly, no lightning clip
        if (fullScreenMode) {
            drawFullScreenLiquid(canvas, width, height);
            
            // V3.5: perf tracking (bugfix + frame-interval tracking)
            long drawEndTime = System.nanoTime();
            long drawTimeNanos = drawEndTime - drawStartTime;
            totalDrawTime += drawTimeNanos;
            
            // Compute frame interval
            if (lastFrameTimeNanos > 0) {
                long frameInterval = drawStartTime - lastFrameTimeNanos;
                totalFrameInterval += frameInterval;
            }
            lastFrameTimeNanos = drawStartTime;
            frameCount++;
            
            // Log stats every 60 frames
            if (frameCount % 60 == 0) {
                float avgDrawTimeMs = (totalDrawTime / (float)frameCount) / 1_000_000f;  // nanos→ms
                float currentDrawMs = drawTimeNanos / 1_000_000f;
                float avgFrameIntervalMs = (totalFrameInterval / (float)(frameCount - 1)) / 1_000_000f;  // avg frame interval
                
                long currentTime = System.currentTimeMillis();
                long timeSinceLastLog = currentTime - lastFrameTime;
                float actualFps = (timeSinceLastLog > 0) ? (60000f / timeSinceLastLog) : 0;
                
                // Compute the theoretical max frame interval (accounting for draw time)
                float drawTimeMs = currentDrawMs;
                float maxTheoreticalFps = (drawTimeMs > 0) ? (1000f / drawTimeMs) : 999;
                float vsyncFps = (avgFrameIntervalMs > 0) ? (1000f / avgFrameIntervalMs) : 0;
                
                Log.d("LightningPerf", String.format("📊 Perf: FPS=%.1f, VSync=%.1fHz (interval %.2fms), avg draw=%.2fms", 
                    actualFps, vsyncFps, avgFrameIntervalMs, avgDrawTimeMs));
                
                lastFrameTime = currentTime;
                totalDrawTime = 0;
                totalFrameInterval = 0;
                frameCount = 0;
                lastFrameTimeNanos = 0;
            }
            
            return;
        }
        
        // Original lightning-container mode
        // Apply gravity tilt (exaggerated, simulates real liquid)
        canvas.save();
        canvas.translate(tiltX * 5, tiltY * 3);
        
        // Layer 0: glass depth shadow (inner recess look)
        canvas.save();
        canvas.translate(2, 2);
        canvas.drawPath(lightningPath, glassDepthPaint);
        canvas.restore();
        
        // Layer 1: soft outer reflection (outermost halo)
        canvas.save();
        canvas.translate(4, 4);
        canvas.drawPath(lightningPath, glassReflectionPaint);
        canvas.restore();
        
        // Layer 2: save canvas and clip to the lightning shape
        canvas.save();
        canvas.clipPath(lightningPath);
        
        // Draw the liquid fill (bottom up)
        if (fillLevel > 0) {
            float fillHeight = height * fillLevel;
            
            // 2.1 main liquid (green gradient)
            canvas.drawRect(0, height - fillHeight, width, height, liquidPaint);
            
            // 2.2 dark shadow at the liquid bottom (reused brush; only rebuilding the shader on height change)
            if (lastBottomShadowHeight != height) {
                fullScreenBottomShadowPaint.setShader(new LinearGradient(
                    0, height - 30, 0, height,
                    new int[]{0x00000000, 0x40000000, 0x50000000},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
                ));
                lastBottomShadowHeight = height;
            }
            canvas.drawRect(0, height - 30, width, height, fullScreenBottomShadowPaint);
            
            // 2.3 surface waves (optimized: fewer computations)
            if (fillHeight > 20) {
                float waveY = height - fillHeight;
                fullScreenWavePath.reset();
                
                // V3.17: subtle gravity-tilt strength for the thin lighting, refined look
                float leftTilt = tiltX * 6;  // left tilt amount (subtle)
                float rightTilt = -tiltX * 6; // right tilt amount (subtle)
                
                fullScreenWavePath.moveTo(0, waveY + leftTilt);
                
                // V3.6: unified wave computation (no duplicates)
                updateWavePoints(width, waveOffset);
                
                // Use precomputed wave points
                int pointCount = Math.min(width / 8, wavePoints.length);  // fewer drawn points
                for (int i = 0; i < pointCount; i++) {
                    float x = (float) i / (pointCount - 1) * width;
                    float wave = wavePoints[i];
                    float tilt = leftTilt + (rightTilt - leftTilt) * (x / (float)width);
                    fullScreenWavePath.lineTo(x, waveY + wave + tilt);
                }
                fullScreenWavePath.lineTo(width, height);
                fullScreenWavePath.lineTo(0, height);
                fullScreenWavePath.close();
                
                // Draw the wavy liquid (reused brush, alpha only)
                fullScreenWavePaint.set(liquidPaint);
                fullScreenWavePaint.setAlpha(220);
                canvas.drawPath(fullScreenWavePath, fullScreenWavePaint);
            }
            
            // 2.4 surface sheen removed (user asked to drop the white on top of the liquid)
            // No white highlight; keep the pure liquid color
            
            // V3.15: fixed bubble flicker; draw bubbles every frame
            if (fillHeight > 10) {  // lower threshold so bubbles show at low liquid levels
                drawBubbles(canvas, width, height, fillHeight);
            }
            
            // 2.6 bright left edge of the liquid (reused brush; shader rebuilt only on width change)
            if (lastEdgeShineWidth != width) {
                fullScreenEdgeShinePaint.setStyle(Paint.Style.FILL);
                fullScreenEdgeShinePaint.setShader(new LinearGradient(
                    width * 0.08f, 0, width * 0.22f, 0,
                    new int[]{0x00FFFFFF, 0x30FFFFFF, 0x20FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.3f, 0.7f, 1f},
                    Shader.TileMode.CLAMP
                ));
                lastEdgeShineWidth = width;
            }
            canvas.drawRect(width * 0.08f, height - fillHeight, 
                           width * 0.22f, height, fullScreenEdgeShinePaint);
            
            // 2.8 internal light-scatter effect removed
            // Keep the pure liquid color; no white scatter
            
            // 2.9 liquid/glass boundary reflection removed
            // Keep the pure liquid color
        }
        
        // Restore the canvas (clear clipping)
        canvas.restore();
        
        // Layer 3: main outline
       canvas.drawPath(lightningPath, outlinePaint);
        
        // Layer 4: strong top-left highlight (simulating light-source reflection)
        //canvas.save();
        //canvas.clipPath(lightningPath);
        //canvas.translate(-width * 0.05f, -height * 0.05f);
        //canvas.drawPath(lightningPath, glassHighlightPaint);
        //canvas.restore();
        
        // Layer 5: soft bottom-right shadow (3D depth)
        //canvas.save();
        //canvas.translate(width * 0.02f, height * 0.02f);
        //Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        //shadowPaint.setStyle(Paint.Style.STROKE);
        //shadowPaint.setStrokeWidth(3f);
        //shadowPaint.setColor(0x30000000); // 19% transparent black
        //canvas.drawPath(lightningPath, shadowPaint);
        //canvas.restore();
        
        // Layer 6: inner highlight (light band along the top-left edge)
        canvas.save();
        canvas.clipPath(lightningPath);
        // Draw the small top-left highlight reflection (reused brush; no per-frame allocs)
        if (lastEdgeShineWidth != width) {
            fullScreenEdgeShinePaint.setStyle(Paint.Style.FILL);
            fullScreenEdgeShinePaint.setShader(new android.graphics.RadialGradient(
                width * 0.25f, height * 0.2f, width * 0.3f,
                new int[]{0x50FFFFFF, 0x20FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
            ));
            lastEdgeShineWidth = width;
        }
        canvas.drawPath(highlightPath, fullScreenEdgeShinePaint);
        canvas.restore();
        
        // Restore the gravity-tilt transform
        canvas.restore();
    }
    
    /**
     * V3.6: unified wave computation (no duplicate calculation).
     */
    private void updateWavePoints(int width, float waveOffset) {
        if (lastWaveWidth != width || Math.abs(lastProcessedWaveOffset - waveOffset) > WAVE_UPDATE_THRESHOLD) {
            // V3.14: restored point count for smoothness
            int pointCount = Math.min(width / 6, wavePoints.length);
            for (int i = 0; i < pointCount; i++) {
                float x = (float) i / (pointCount - 1) * width;
                wavePoints[i] = (float) Math.sin((x / (float)width * 4 * Math.PI) + waveOffset) * 8f;
            }
            lastWaveWidth = width;
            lastProcessedWaveOffset = waveOffset;
        }
    }
    
    /**
     * V3.7: draw bubbles in the liquid (restored gravity effect, keeping perf optimizations).
     */
    private void drawBubbles(Canvas canvas, int width, int height, float fillHeight) {
        float baseY = height - fillHeight;
        
        // V3.17: subtle bubble gravity response, refined look
        float gravityOffsetX = -tiltX * 5; // tilt left, bubbles drift right (subtle)
        float gravityOffsetY = tiltY * 2;   // fore-aft tilt effect (subtle)
        
        // V3.15: more bubbles, fixed flicker
        // Draw bubbles (simple approach)
        // Bubble 1 (large)
        float bubble1X = width * 0.2f + gravityOffsetX;
        float bubble1Y = baseY + fillHeight * bubblePositions[0] + gravityOffsetY;
        canvas.drawCircle(bubble1X, bubble1Y, 6f, bubblePaint);
        
        // Bubble 2 (medium)
        float bubble2X = width * 0.4f + gravityOffsetX * 0.8f;
        float bubble2Y = baseY + fillHeight * bubblePositions[1] + gravityOffsetY;
        canvas.drawCircle(bubble2X, bubble2Y, 4f, bubblePaint);
        
        // Bubble 3 (small)
        float bubble3X = width * 0.6f + gravityOffsetX * 0.6f;
        float bubble3Y = baseY + fillHeight * bubblePositions[2] + gravityOffsetY;
        canvas.drawCircle(bubble3X, bubble3Y, 3f, bubblePaint);
        
        // Bubble 4 (small)
        float bubble4X = width * 0.8f + gravityOffsetX * 0.9f;
        float bubble4Y = baseY + fillHeight * bubblePositions[3] + gravityOffsetY;
        canvas.drawCircle(bubble4X, bubble4Y, 3.5f, bubblePaint);
        
        // Bubble 5 (medium)
        float bubble5X = width * 0.3f + gravityOffsetX * 0.7f;
        float bubble5Y = baseY + fillHeight * bubblePositions[4] + gravityOffsetY;
        canvas.drawCircle(bubble5X, bubble5Y, 4.5f, bubblePaint);
        
        // Bubble 6 (small)
        float bubble6X = width * 0.7f + gravityOffsetX * 0.5f;
        float bubble6Y = baseY + fillHeight * bubblePositions[5] + gravityOffsetY;
        canvas.drawCircle(bubble6X, bubble6Y, 2.5f, bubblePaint);
        
        // Simple bubble rise logic
        for (int i = 0; i < bubblePositions.length; i++) {
            bubblePositions[i] -= 0.002f; // fixed rise speed
            if (bubblePositions[i] < 0) {
                bubblePositions[i] = 1.0f; // restart from the bottom
            }
        }
    }
    
    private long waveAnimationStartTime = 0;
    private android.view.Choreographer.FrameCallback frameCallback;
    
    // V3.5: performance tracking
    private long lastFrameTime = 0;
    private long frameCount = 0;
    private long totalDrawTime = 0;
    private long lastFrameTimeNanos = 0;  // previous frame time (nanos)
    private long totalFrameInterval = 0;  // total frame interval
    
    /**
     * Start the wave animation (optimized for 120fps).
     */
    private void startWaveAnimation() {
        // Avoid double-start
        if (frameCallback != null) {
            return;
        }
        
        // Record the start time (actual frame time)
        waveAnimationStartTime = 0;
        
        // Create the FrameCallback
        frameCallback = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (fillLevel > 0) {
                    // Initialize the start time
                    if (waveAnimationStartTime == 0) {
                        waveAnimationStartTime = frameTimeNanos;
                    }
                    
                    // V3.14: restored wave speed for smoothness
                    long elapsedNanos = frameTimeNanos - waveAnimationStartTime;
                    waveOffset = (float)((elapsedNanos / 1_000_000_000.0) * Math.PI * 1.5); // 0.67s per cycle
                    
                    // Request a redraw (postInvalidateOnAnimation keeps in sync with vsync)
                    postInvalidateOnAnimation();
                    
                    // V3.6: fix - only schedule the next frame when needed, avoid infinite recursion
                    if (fillLevel > 0) {
                        android.view.Choreographer.getInstance().postFrameCallback(this);
                    }
                }
            }
        };
        
        // Start the frame callback
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback);
        Log.d("LightningShapeView", "✓ Wave animation started (Choreographer.FrameCallback, follows refresh rate)");
    }
    
    /**
     * Set the fill level.
     * @param level 0.0 - 1.0
     */
    public void setFillLevel(float level) {
        this.fillLevel = Math.max(0f, Math.min(1f, level));
        
        // If filling starts, start the wave animation (once)
        if (level > 0.01f && frameCallback == null) {
            startWaveAnimation();
        }
        
        // If fill is 0, stop the animation
        if (level <= 0 && frameCallback != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallback = null;
        }
        
        // Force a redraw
        invalidate();
        Log.d("LightningShapeView", "🔋 Fill level updated: " + (level * 100) + "%");
    }
    
    /**
     * V3.5: set fullscreen liquid mode.
     */
    public void setFullScreenMode(boolean enabled) {
        this.fullScreenMode = enabled;
        
        // In fullscreen mode, start the wave animation immediately
        if (enabled && fillLevel > 0) {
            startWaveAnimation();
        }
        
        invalidate();
    }
    
    /**
     * V3.7: draw fullscreen liquid (restored wave effect, keeping perf).
     */
    private void drawFullScreenLiquid(Canvas canvas, int width, int height) {
        if (fillLevel <= 0) return;
        
        float fillHeight = height * fillLevel;
        
        // V3.17: subtle gravity-tilt strength, refined look
        float leftTilt = tiltX * 8;  // left tilt amount (subtle)
        float rightTilt = -tiltX * 8; // right tilt amount (subtle)
        
        // 1. reuse the Path object; no per-frame allocs
        fullScreenLiquidPath.reset();
        
        // Surface wave + gravity tilt
        float waveY = height - fillHeight;
        fullScreenLiquidPath.moveTo(0, waveY + leftTilt);
        
        // V3.7: unified wave computation (no duplicates)
        updateWavePoints(width, waveOffset);
        
        // V3.14: restored point count for smoothness
        int pointCount = Math.min(width / 6, wavePoints.length);  // dense wave points restored
        for (int i = 0; i < pointCount; i++) {
            float x = (float) i / (pointCount - 1) * width;
            float wave = wavePoints[i];
            float tilt = leftTilt + (rightTilt - leftTilt) * (x / (float)width);
            fullScreenLiquidPath.lineTo(x, waveY + wave + tilt);
        }
        
        // Connect to bottom-right, then bottom-left, forming a closed path
        fullScreenLiquidPath.lineTo(width, height);
        fullScreenLiquidPath.lineTo(0, height);
        fullScreenLiquidPath.close();
        
        // 2. draw the whole liquid
        canvas.drawPath(fullScreenLiquidPath, liquidPaint);
        
        // 3. draw the bottom shadow (only recreate the shader on height change)
        if (lastShadowHeight != height) {
            fullScreenShadowPaint.setShader(new LinearGradient(
                0, height - 40, 0, height,
                new int[]{0x00000000, 0x20000000, 0x40000000},
                new float[]{0f, 0.7f, 1f},
                Shader.TileMode.CLAMP
            ));
            lastShadowHeight = height;
        }
        canvas.drawPath(fullScreenLiquidPath, fullScreenShadowPaint);
        
        // V3.15: fixed bubble flicker; draw every frame
        if (fillHeight > 10) {  // lower threshold so bubbles show at any liquid height
            drawBubbles(canvas, width, height, fillHeight);
        }
    }
    
    /**
     * Get the current fill level.
     */
    public float getFillLevel() {
        return fillLevel;
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // V3.5: register the gravity sensor (UI delay lowers callback rate)
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            Log.d("LightningShapeView", "✅ Gravity sensor registered (UI delay)");
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Unregister the gravity sensor
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            Log.d("LightningShapeView", "❌ Gravity sensor unregistered");
        }
        
        // V3.5: stop the Choreographer callbacks
        if (frameCallback != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallback = null;
        }
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // Get gravity accelerometer readings (X and Y)
            float x = event.values[0]; // left/right tilt (-10 to 10)
            float y = event.values[1]; // fore/aft tilt (-10 to 10)
            
            // V3.17: subtle gravity response, more refined
            float smoothFactor = 0.05f; // subtle sensitivity
            tiltX = tiltX * (1 - smoothFactor) + x * smoothFactor;
            tiltY = tiltY * (1 - smoothFactor) + y * smoothFactor;
            
            // Clamp the tilt range (small range)
            tiltX = Math.max(-2f, Math.min(2f, tiltX));
            tiltY = Math.max(-2f, Math.min(2f, tiltY));
            
            // V3.5: no invalidate() here; the Choreographer drives redraws to avoid over-drawing
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No precision-change handling
    }
}

