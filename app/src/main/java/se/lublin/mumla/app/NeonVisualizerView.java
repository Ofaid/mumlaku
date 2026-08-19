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

    // === PENGATURAN — GAMPANG DIUBAH KAPAN SAJA! ===
    private static final int JUMLAH_BATANG = 3;      // 3 batang saja
    private static final float TEBAL_BATANG = 8f;    // Tebal & jelas
    private static final float KEPEKAAN = 0.8f;      // Tinggi gerakan

    public NeonVisualizerView(Context context) { super(context); init(); }
    public NeonVisualizerView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public NeonVisualizerView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        mPaint.setColor(Color.RED);           // 🔴 Merah
        mPaint.setStrokeWidth(TEBAL_BATANG); // Tebal
        mPaint.setAntiAlias(true);
    }

    public void updateVisualizer(byte[] waveform) {
        this.mWaveform = waveform;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mWaveform == null || mWaveform.length < JUMLAH_BATANG) return;

        int width = getWidth();
        int height = getHeight();
        int centerY = height / 2;
        float jarakAntar = (float) width / (JUMLAH_BATANG + 1);

        for (int i = 0; i < JUMLAH_BATANG; i++) {
            float amplitude = (mWaveform[i] + 128) / 256f * height * KEPEKAAN;
            float x = jarakAntar * (i + 1);
            canvas.drawLine(x, centerY - amplitude/2, x, centerY + amplitude/2, mPaint);
        }
    }
}
