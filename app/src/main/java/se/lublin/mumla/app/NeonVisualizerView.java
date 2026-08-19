package se.lublin.mumla.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import se.lublin.mumla.R;

public class NeonVisualizerView extends View {
    private byte[] mWaveform;
    private final Paint mPaint = new Paint();
    
    // === PENGATURAN — BISA DIUBAH SESUAI SELERA ===
    private static final int NUM_LEDS = 13;
    private static final float LED_HEIGHT_DP = 6f;
    private static final float LED_SPACING_DP = 2f;
    private static final float SMOOTH = 0.15f;
    private static final float SENSITIVITY = 1.0f;
    private static final int SILENCE_THRESHOLD = 12;
    // ==============================================

    private float mLedHeight;
    private float mLedSpacing;
    private float[] mLastLevels;

    public NeonVisualizerView(Context context) {
        super(context);
        init();
    }

    public NeonVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NeonVisualizerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.FILL);
        
        float density = getResources().getDisplayMetrics().density;
        mLedHeight = LED_HEIGHT_DP * density;
        mLedSpacing = LED_SPACING_DP * density;
        mLastLevels = new float[NUM_LEDS];
    }

    public void updateVisualizer(byte[] waveform) {
        this.mWaveform = waveform;
        invalidate();
    }

    private float[] getLedLevels(byte[] waveform) {
        float[] levels = new float[NUM_LEDS];
        if (waveform == null || waveform.length == 0) return levels;

        int step = waveform.length / NUM_LEDS;
        final double LOG_2 = Math.log(2.0);

        for (int i = 0; i < NUM_LEDS; i++) {
            int start = i * step;
            int end = Math.min(start + step, waveform.length);
            
            int peak = 0;
            for (int j = start; j < end; j++) {
                int val = waveform[j] + 128;
                peak = Math.max(peak, val);
            }

            if (peak < SILENCE_THRESHOLD) {
                levels[i] = 0f;
                continue;
            }

            double val = (peak - SILENCE_THRESHOLD) / (255.0 - SILENCE_THRESHOLD) 
                        * SENSITIVITY * Short.MAX_VALUE;
            double logVal = Math.log(val) / LOG_2;
            levels[i] = (float) Math.max(0.0, Math.min(1.0, logVal / 15.0));
        }
        return levels;
    }

    // 🟢 WARNA: PASTI HIJAU — TIDAK AKAN MERAH LAGI
    private int getColor(float strength) {
        strength = Math.max(0f, Math.min(1f, strength));
        int hijau = (int)(100 + strength * 155);
        return Color.rgb(0, hijau, 0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // Kalau tidak ada data / diam → padam halus
        if (mWaveform == null || mWaveform.length == 0) {
            for (int i = 0; i < mLastLevels.length; i++) {
                mLastLevels[i] += (0f - mLastLevels[i]) * SMOOTH;
            }
            drawLeds(canvas);
            return;
        }

        float[] targetLevels = getLedLevels(mWaveform);
        
        // Haluskan gerakan semua batang
        for (int i = 0; i < NUM_LEDS; i++) {
            mLastLevels[i] += (targetLevels[i] - mLastLevels[i]) * SMOOTH;
        }

        drawLeds(canvas);
    }

    private void drawLeds(Canvas canvas) {
        int width = getWidth();
        float centerY = getHeight() / 2f;
        float ledWidth = (width - (NUM_LEDS - 1) * mLedSpacing) / NUM_LEDS;
        float totalWidth = NUM_LEDS * ledWidth + (NUM_LEDS - 1) * mLedSpacing;
        float startX = (width - totalWidth) / 2f;

        for (int i = 0; i < NUM_LEDS; i++) {
            float level = mLastLevels[i];
            float x = startX + i * (ledWidth + mLedSpacing);

            int jarakTengah = Math.abs(i - NUM_LEDS / 2);
            float ambang = 0.05f + jarakTengah * 0.03f;
            float panjang = level > ambang ? (level - ambang) / (1f - ambang) * ledWidth : 0f;

            mPaint.setColor(getColor(level));

            if (panjang > 1f) {
                if (i < NUM_LEDS / 2) {
                    canvas.drawRect(x + ledWidth - panjang, centerY - mLedHeight / 2f,
                                    x + ledWidth, centerY + mLedHeight / 2f, mPaint);
                } else {
                    canvas.drawRect(x, centerY - mLedHeight / 2f,
                                    x + panjang, centerY + mLedHeight / 2f, mPaint);
                }
            }
        }
    }
}
