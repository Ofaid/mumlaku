package se.lublin.mumla.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class NeonVisualizerView extends View {
    private byte[] mWaveform;
    private Paint mPaint = new Paint();
    private int mAmplitude = 0;
    private static final int MAX_LEVEL = 1000;

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
        mPaint.setColor(Color.CYAN);
        mPaint.setStrokeWidth(3f);
        mPaint.setAntiAlias(true);
    }

    private int calculateAmplitude(byte[] buffer, int length) {
        if (buffer == null || length < 2) return 0;

        long ampSum = 0;
        int sampleCount = 0;

        for (int i = 0; i < length - 1; i += 2) {
            short sample;
            if (buffer[i] < 0) {
                sample = (short)(((buffer[i + 1] + 1) << 8) + (buffer[i] & 0xFF));
            } else {
                sample = (short)(((buffer[i + 1] & 0xFF) << 8) + (buffer[i] & 0xFF));
            }
            ampSum += Math.abs(sample);
            sampleCount++;
        }

        if (sampleCount > 0) {
            long avg = (ampSum * 10) / (sampleCount * 5);
            return (int)Math.min(avg, MAX_LEVEL);
        }
        return 0;
    }

    public void updateVisualizer(byte[] waveform) {
        this.mWaveform = waveform;
        if (waveform != null && waveform.length > 0) {
            int takeLength = Math.min(waveform.length, 512);
            mAmplitude = calculateAmplitude(waveform, takeLength);
        } else {
            mAmplitude = 0;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int lebar = getWidth();
        int tinggi = getHeight();
        float tengahX = lebar / 2f;
        float tengahY = tinggi / 2f;
        float maxPanjang = lebar / 2f - 20f;

        float panjang = (mAmplitude * maxPanjang) / MAX_LEVEL;
        canvas.drawLine(tengahX - panjang, tengahY, tengahX + panjang, tengahY, mPaint);
    }
}

