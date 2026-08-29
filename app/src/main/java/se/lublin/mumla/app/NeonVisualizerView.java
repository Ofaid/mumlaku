package se.lublin.mumla.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import se.lublin.mumla.R; // Pastikan import R ini sesuai dengan package aplikasi Anda

public class NeonVisualizerView extends View {
    private byte[] mWaveform;
    private Paint mPaint = new Paint();
    private float mSmoothedAmplitude = 0f; // Menggunakan float untuk pergerakan mulus
    private static final int MAX_LEVEL = 1000;
    
    // Faktor kehalusan (Semakin kecil nilainya, semakin lambat/lembut jatuhnya batang)
    private static final float SMOOTHING_FACTOR = 0.2f; 

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
        mPaint.setStrokeWidth(8f); // Dipertebal sedikit agar efek neon lebih terlihat
        mPaint.setAntiAlias(true);
        mPaint.setStrokeCap(Paint.Cap.ROUND); // Ujung batang membulat agar lebih estetik
    }

    private int calculateAmplitude(byte[] buffer, int length) {
        if (buffer == null || length < 2) return 0;

        // Selaraskan panjang agar selalu genap (kelipatan 2 byte untuk PCM 16-bit)
        int safeLength = length - (length % 2);
        long ampSum = 0;
        int sampleCount = safeLength / 2;

        // Warp byte array ke ByteBuffer dengan urutan Little Endian (Standar audio Android)
        ByteBuffer bufferWrapper = ByteBuffer.wrap(buffer, 0, safeLength);
        bufferWrapper.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < sampleCount; i++) {
            short sample = bufferWrapper.getShort();
            ampSum += Math.abs(sample);
        }

        if (sampleCount > 0) {
            long avg = ampSum / sampleCount;
            // Petakan nilai rata-rata short (0 - 32767) ke skala MAX_LEVEL (0 - 1000)
            int calculated = (int) ((avg * MAX_LEVEL) / 32767);
            return Math.min(calculated, MAX_LEVEL);
        }
        return 0;
    }

    public void updateVisualizer(byte[] waveform) {
        this.mWaveform = waveform;
        int targetAmplitude = 0;

        if (waveform != null && waveform.length > 0) {
            int takeLength = Math.min(waveform.length, 512);
            targetAmplitude = calculateAmplitude(waveform, takeLength);
        }

        // Terapkan rumus Linear Interpolation (Lerp) untuk efek smoothing
        mSmoothedAmplitude += (targetAmplitude - mSmoothedAmplitude) * SMOOTHING_FACTOR;

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

        // Gambar batang berdasarkan amplitudo yang sudah dihaluskan
        float panjang = (mSmoothedAmplitude * maxPanjang) / MAX_LEVEL;
        canvas.drawLine(tengahX - panjang, tengahY, tengahX + panjang, tengahY, mPaint);
    }
}
