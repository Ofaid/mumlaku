/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://gnu.org>.
 */

package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;
import se.lublin.mumla.util.HtmlUtils;

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Modified to release microphone dynamic focus for PTT only.
 */
public class MumlaService extends HumlaService implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        MumlaConnectionNotification.OnActionListener,
        MumlaReconnectNotification.OnActionListener, IMumlaService {
    private static final String TAG = MumlaService.class.getName();

    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int TTS_THRESHOLD = 250;
    public static final int RECONNECT_DELAY = 10000;

    private Settings mSettings;
    private MumlaConnectionNotification mNotification;
    private MumlaMessageNotification mMessageNotification;
    private MumlaReconnectNotification mReconnectNotification;
    private MumlaOverlay mChannelOverlay;
    private PowerManager.WakeLock mProximityLock;
    private boolean mPTTSoundEnabled;
    private boolean mShortTtsMessagesEnabled;
    private boolean mErrorShown;
    private List<IChatMessage> mMessageLog;
    private boolean mSuppressNotifications;

    private AudioManager mAudioManager;

    private TextToSpeech mTTS;
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if(status == TextToSpeech.ERROR)
                logWarning(getString(R.string.tts_failed));
        }
    };

    private MumlaHotCorner mHotCorner;
    private MumlaHotCorner.MumlaHotCornerListener mHotCornerListener = new MumlaHotCorner.MumlaHotCornerListener() {
        @Override
        public void onHotCornerDown() {
            onTalkKeyDown();
        }

        @Override
        public void onHotCornerUp() {
            onTalkKeyUp();
        }
    };

    private BroadcastReceiver mTalkReceiver;

    private AudioManager.OnAudioFocusChangeListener mAudioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            // Mengatasi jika ada interupsi audio dari sistem
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                onTalkKeyUp(); 
            }
        }
    };

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnecting() {
            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }

            final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
            mNotification = MumlaConnectionNotification.create(MumlaService.this,
                    getString(R.string.mumlaConnecting) + tor,
                    MumlaService.this);
            mNotification.show();

            mErrorShown = false;
        }

        @Override
        public void onConnected() {
            if (mNotification != null) {
                final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
                mNotification.setCustomContentText(getString(R.string.connected) + tor);
                mNotification.setActionsShown(true);
                mNotification.show();
            }

            // PERBAIKAN UTAMA: Saat terhubung, paksa transmisi ke PASSIVE
            // Ini memotong bug HumlaService bawaan yang langsung menyalakan mic
            setTalkingState(TalkState.PASSIVE);
            Log.d(TAG, "Terhubung ke server. Jalur Mikrofon STANDBY (Aman dari pembajakan jalur).");
        }

        @Override
        public void onDisconnected(HumlaException e) {
            if (mNotification != null) {
                mNotification.hide();
                mNotification = null;
            }
            if (e != null && !mSuppressNotifications) {
                mReconnectNotification =
                        MumlaReconnectNotification.show(MumlaService.this,
                                e.getMessage() + (mSettings.isTorEnabled() ? " (Tor)" : ""),
                                isReconnecting(), MumlaService.this);
            }
            // Bebaskan fokus jika terputus tiba-tiba
            if (mAudioManager != null) {
                mAudioManager.abandonAudioFocus(mAudioFocusChangeListener);
            }
        }

        @Override
        public void onUserConnected(IUser user) {
            if (user.getTextureHash() != null && user.getTexture() == null) {
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (user == null) return;

            int selfSession;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }

            if (user.getSession() == selfSession) {
                mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened());
                if(mNotification != null) {
                    String contentText;
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        contentText = getString(R.string.status_notify_muted_and_deafened);
                    else if (user.isSelfMuted())
                        contentText = getString(R.string.status_notify_muted);
                    else
                        contentText = getString(R.string.connected);
                    mNotification.setCustomContentText(contentText);
                    mNotification.show();
                }
            }

            if (user.getTextureHash() != null && user.getTexture() == null) {
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onMessageLogged(IMessage message) {
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();
            // Bagian kode di bawah ini dipotong demi efisiensi tampilan instruksi full-code
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mSettings = Settings.getInstance(this);
        // ... Inisialisasi bawaan Mumla lainnya ...
    }

    /**
     * MODIFIKASI KUNCI: Dipicu saat tombol PTT Ditekan (On)
     */
    public void onTalkKeyDown() {
        if (mAudioManager != null) {
            // Minta fokus audio komunikasi suara secara instan transient
            mAudioManager.requestAudioFocus(mAudioFocusChangeListener, 
                    AudioManager.STREAM_VOICE_CALL, 
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }

        // Jalankan perintah pengiriman suara ke server via super class
        setTalkingState(TalkState.TALKING);
        Log.d(TAG, "PTT Aktif: Mengambil alih hardware mikrofon.");
    }

    /**
     * MODIFIKASI KUNCI: Dipicu saat tombol PTT Dilepas (Off)
     */
    public void onTalkKeyUp() {
        // Hentikan status bicara di server
        setTalkingState(TalkState.PASSIVE);

        if (mAudioManager != null) {
            // KUNCI UTAMA: Bebaskan mic dari sistem Android saat itu juga!
            mAudioManager.abandonAudioFocus(mAudioFocusChangeListener);
            Log.d(TAG, "PTT Dilepas: Jalur mikrofon dikembalikan ke Android OS.");
        }
    }

    @Override
    public void onDestroy() {
        if (mAudioManager != null) {
            mAudioManager.abandonAudioFocus(mAudioFocusChangeListener);
        }
        super.onDestroy();
    }

    // --- Sisa Interface Stubs bawaan MumlaService ---
    @Override
    public IBinder onBind(Intent intent) { return new MumlaBinder(); }
    public class MumlaBinder extends Binder { public MumlaService getService() { return MumlaService.this; } }
            // --- KELANJUTAN KODE DARI BAGIAN YANG TERPOTONG ---
            String ttsMessage;
            if (mShortTtsMessagesEnabled) {
                for (Element anchor : parsedMessage.getElementsByTag("A")) {
                    // Ambil hanya bagian domain dari tautan/link
                    String href = anchor.attr("href");
                    if (href != null && href.equals(anchor.text())) {
                        try {
                            Uri uri = Uri.parse(href);
                            anchor.text(uri.getHost());
                        } catch (Exception e) {
                            // Abaikan jika format link tidak valid
                        }
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }

            // Jalankan Fitur Text-To-Speech jika diaktifkan di pengaturan Mumla
            if (mTTS != null && mSettings.isTextToSpeechEnabled() && ttsMessage.length() < TTS_THRESHOLD) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mTTS.speak(ttsMessage, TextToSpeech.QUEUE_FLUSH, null, Integer.toString(message.getMessageId()));
                } else {
                    mTTS.speak(ttsMessage, TextToSpeech.QUEUE_FLUSH, null);
                }
            }

            // Simpan riwayat chat ke dalam log aplikasi
            mMessageLog.add(new ChatMessage(message, strippedMessage));
            if (mMessageNotification != null && !mSuppressNotifications) {
                mMessageNotification.showNotification(message, strippedMessage);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mSettings = Settings.getInstance(this);
        mMessageLog = Collections.synchronizedList(new ArrayList<IChatMessage>());
        
        // Daftarkan observer ke HumlaService untuk memantau status koneksi server
        registerObserver(mObserver);

        // Inisialisasi Text To Speech jika diaktifkan user
        if (mSettings.isTextToSpeechEnabled()) {
            mTTS = new TextToSpeech(this, mTTSInitListener);
        }

        // Setup filter broadcast untuk interupsi tombol bicara pihak ketiga / wired headset
        IntentFilter filter = new IntentFilter(TalkBroadcastReceiver.ACTION_TALK);
        mTalkReceiver = new TalkBroadcastReceiver(this);
        registerReceiver(mTalkReceiver, filter);

        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);
    }

    /**
     * MODIFIKASI KUNCI: Dipicu saat tombol PTT Ditekan (On)
     */
    public void onTalkKeyDown() {
        if (mAudioManager != null) {
            // Minta fokus audio komunikasi suara secara instan transient
            mAudioManager.requestAudioFocus(mAudioFocusChangeListener, 
                    AudioManager.STREAM_VOICE_CALL, 
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }

        // Jalankan perintah pengiriman suara ke server via super class
        setTalkingState(TalkState.TALKING);
        Log.d(TAG, "PTT Aktif: Mengambil alih hardware mikrofon.");
    }

    /**
     * MODIFIKASI KUNCI: Dipicu saat tombol PTT Dilepas (Off)
     */
    public void onTalkKeyUp() {
        // Hentikan status bicara di server
        setTalkingState(TalkState.PASSIVE);

        if (mAudioManager != null) {
            // KUNCI UTAMA: Bebaskan mic dari sistem Android saat itu juga!
            mAudioManager.abandonAudioFocus(mAudioFocusChangeListener);
            Log.d(TAG, "PTT Dilepas: Jalur mikrofon dikembalikan ke Android OS.");
        }
    }

    @Override
    public void onDestroy() {
        // Bersihkan seluruh listener dan kembalikan focus audio saat aplikasi mati
        if (mAudioManager != null) {
            mAudioManager.abandonAudioFocus(mAudioFocusChangeListener);
        }
        if (mTalkReceiver != null) {
            unregisterReceiver(mTalkReceiver);
        }
        if (mTTS != null) {
            mTTS.shutdown();
        }
        
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
        
        unregisterObserver(mObserver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder();
    }

    public class MumlaBinder extends Binder {
        public MumlaService getService() {
            return MumlaService.this;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Settings.KEY_TTS_ENABLED.equals(key)) {
            if (sharedPreferences.getBoolean(key, false) && mTTS == null) {
                mTTS = new TextToSpeech(this, mTTSInitListener);
            } else if (mTTS != null) {
                mTTS.shutdown();
                mTTS = null;
            }
        }
    }

    @Override
    public void onNotificationAction(int actionId) {
        // Aksi kustom ketika user menekan tombol di bar notifikasi sistem Android
        if (actionId == MumlaConnectionNotification.ACTION_DISCONNECT) {
            disconnect();
        }
    }

    @Override
    public void onReconnectAction(int actionId) {
        if (actionId == MumlaReconnectNotification.ACTION_CANCEL) {
            cancelReconnect();
        }
    }

    @Override
    public List<IChatMessage> getMessageLog() {
        return mMessageLog;
    }

    @Override
    public void clearMessageLog() {
        mMessageLog.clear();
    }
}
