package se.lublin.mumla.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
/* Satu batang mendatar*/
public class NeonVisualizerView extends View {
    private byte[] mWaveform;
    private final Paint mPaint = new Paint();

    // === SEMUA SUDAH SESUAI KEMAUANMU ===
    private static final float TEBAL_GARIS = 12f;       // Tebal & jelas
    private static final float KEPEKAAN = 3.0f;         // Peka — naik nada langsung memanjang
    private static final float BATAS_MAKSIMAL = 0.9f;   // Tidak keluar layar

    public NeonVisualizerView(Context context) { super(context); init(); }
    public NeonVisualizerView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public NeonVisualizerView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        mPaint.setColor(Color.RED);           // 🔴 Merah
        mPaint.setStrokeWidth(TEBAL_GARIS);  // Tebal
        mPaint.setAntiAlias(true);
    }

    public void updateVisualizer(byte[] waveform) {
        this.mWaveform = waveform;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mWaveform == null || mWaveform.length < 3) return;

        int width = getWidth();
        int height = getHeight();
        int centerX = width / 2;       // Di tengah layar
        int centerY = height / 2;

        // === AMBIL NOMOR 3 — YANG SUDAH TERBUKTI HIDUP ===
        int index = 2;
        float amplitude = (mWaveform[index] + 128) / 256f * width * KEPEKAAN;

        // === BATASI BIAR TIDAK KELUAR LAYAR ===
        float maksimal = width * BATAS_MAKSIMAL;
        if (amplitude > maksimal) amplitude = maksimal;

        // === GARIS MENDATAR DARI TENGAH KE KANAN ===
        float mulaiDari = centerX;
        float sampaiKe = centerX + amplitude / 2;

        canvas.drawLine(mulaiDari, centerY, sampaiKe, centerY, mPaint);
    }
}
