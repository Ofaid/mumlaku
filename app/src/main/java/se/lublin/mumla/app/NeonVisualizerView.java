package se.lublin.mumla.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.BlurMaskFilter;
import android.util.AttributeSet;
import android.view.View;

public class NeonVisualizerView extends View {
    private static final int BARS_COUNT = 16; // Jumlah batang visualizer
    private final Paint neonPaint = new Paint();
    private float[] barLevels = new float[BARS_COUNT];

    private float barWidth;
    private final float gapRatio = 0.15f; // Jarak antar batang

    public NeonVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaint();
        // Mulai dengan semua batang di bawah
        for (int i = 0; i < BARS_COUNT; i++) {
            barLevels[i] = 0f;
        }
    }

    private void initPaint() {
        neonPaint.setColor(Color.CYAN); // Ubah warna di sini kalau mau
        neonPaint.setStyle(Paint.Style.FILL);
        neonPaint.setAntiAlias(true);
        neonPaint.setMaskFilter(new BlurMaskFilter(12, BlurMaskFilter.Blur.OUTER)); // Efek neon menyala
    }

    // === Fungsi ini dipanggil untuk masukkan data kekuatan suara ===
    public void setAudioLevel(float normalizedLevel) {
        // Geser data lama ke kiri, data baru masuk ke paling kanan
        for (int i = 0; i < BARS_COUNT - 1; i++) {
            barLevels[i] = barLevels[i + 1];
        }
        // Pastikan nilai tidak melampaui batas 0.0 - 1.0
        barLevels[BARS_COUNT - 1] = Math.max(0f, Math.min(1f, normalizedLevel));
        invalidate(); // Minta menggambar ulang
    }

    // === Fungsi ini dipanggil dari ChannelList.java untuk data gelombang asli ===
    public void updateVisualizer(byte[] waveform) {
        if (waveform == null || waveform.length == 0) return;

        // Hitung rata-rata kekuatan suara
        float sum = 0f;
        for (byte b : waveform) {
            sum += Math.abs(b);
        }
        float average = sum / waveform.length;
        float level = average / 128f; // Ubah jadi nilai 0.0 sampai 1.0

        setAudioLevel(level);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float viewWidth = getWidth();
        float viewHeight = getHeight();

        // Hitung lebar batang supaya pas memenuhi layar
        float totalGapWidth = (BARS_COUNT - 1) * gapRatio;
        barWidth = viewWidth / (BARS_COUNT + totalGapWidth);

        // Gambar setiap batang
        for (int i = 0; i < BARS_COUNT; i++) {
            float level = barLevels[i];
            float barHeight = viewHeight * level;

            float left = i * (barWidth + (barWidth * gapRatio));
            float top = viewHeight - barHeight;
            float right = left + barWidth;
            float bottom = viewHeight;

            canvas.drawRect(left, top, right, bottom, neonPaint); // Ini yang menggambar jadi BATANG/balok
        }
    }
}

