package se.lublin.mumla.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class NeonVisualizerView extends View {
    private byte[] mWaveform;
    private final Paint mPaint = new Paint();

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
        mPaint.setStrokeWidth(2f);
        mPaint.setAntiAlias(true);
    }

    // ✅ Fungsi resmi untuk menerima data gelombang suara
    public void updateVisualizer(byte[] waveform) {
        this.mWaveform = waveform;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mWaveform == null || mWaveform.length == 0) return;

        int width = getWidth();
        int height = getHeight();
        int centerY = height / 2;
        float barWidth = (float) width / mWaveform.length;

        for (int i = 0; i < mWaveform.length; i++) {
            // Ubah nilai byte (-128 s/d 127) jadi tinggi garis
            float amplitude = (mWaveform[i] + 128) / 256f * height;
            float x = i * barWidth;
            canvas.drawLine(x, centerY - amplitude / 2f, x, centerY + amplitude / 2f, mPaint);
        }
    }
}
