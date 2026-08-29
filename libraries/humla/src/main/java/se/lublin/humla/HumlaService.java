/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties;

import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import se.lublin.humla.audio.AudioOutput;
import se.lublin.humla.audio.BluetoothScoReceiver;
import se.lublin.humla.audio.inputmode.ActivityInputMode;
import se.lublin.humla.audio.inputmode.ContinuousInputMode;
import se.lublin.humla.audio.inputmode.IInputMode;
import se.lublin.humla.audio.inputmode.ToggleInputMode;
import se.lublin.humla.audio.javacpp.CELT7;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.exception.NotConnectedException;
import se.lublin.humla.exception.NotSynchronizedException;
import se.lublin.humla.model.Channel;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.Server;
import se.lublin.humla.model.ServerSettings;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.model.User;
import se.lublin.humla.model.WhisperTarget;
import se.lublin.humla.model.WhisperTargetList;
import se.lublin.humla.net.HumlaConnection;
import se.lublin.humla.net.HumlaTCPMessageType;
import se.lublin.humla.net.HumlaUDPMessageType;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.protocol.AudioHandler;
import se.lublin.humla.protocol.ModelHandler;
import se.lublin.humla.util.HumlaCallbacks;
import se.lublin.humla.util.HumlaDisconnectedException;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaLogger;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.humla.util.VoiceTargetMode;

public class HumlaService extends Service implements IHumlaService, IHumlaSession,
        HumlaConnection.HumlaConnectionListener, HumlaLogger, BluetoothScoReceiver.Listener {
    private static final String TAG = HumlaService.class.getName();

    static {
        Security.insertProviderAt(new org.spongycastle.jce.provider.BouncyCastleProvider(), 1);
    }

    public static final String ACTION_CONNECT = "se.lublin.humla.CONNECT";
    public static final String EXTRAS_SERVER = "server";
    public static final String EXTRAS_AUTO_RECONNECT = "auto_reconnect";
    public static final String EXTRAS_AUTO_RECONNECT_DELAY = "auto_reconnect_delay";
    public static final String EXTRAS_CERTIFICATE = "certificate";
    public static final String EXTRAS_CERTIFICATE_PASSWORD = "certificate_password";
    public static final String EXTRAS_DETECTION_THRESHOLD = "detection_threshold";
    public static final String EXTRAS_AMPLITUDE_BOOST = "amplitude_boost";
    public static final String EXTRAS_TRANSMIT_MODE = "transmit_mode";
    public static final String EXTRAS_INPUT_RATE = "input_frequency";
    public static final String EXTRAS_INPUT_QUALITY = "input_quality";
    public static final String EXTRAS_USE_OPUS = "use_opus";
    public static final String EXTRAS_FORCE_TCP = "force_tcp";
    public static final String EXTRAS_USE_TOR = "use_tor";
    public static final String EXTRAS_CLIENT_NAME = "client_name";
    public static final String EXTRAS_ACCESS_TOKENS = "access_tokens";
    public static final String EXTRAS_TRUST_STORE = "trust_store";
    public static final String EXTRAS_TRUST_STORE_PASSWORD = "trust_store_password";
    public static final String EXTRAS_TRUST_STORE_FORMAT = "trust_store_format";
    public static final String EXTRAS_HALF_DUPLEX = "half_duplex";
    public static final String EXTRAS_LOCAL_MUTE_HISTORY = "local_mute_history";
    public static final String EXTRAS_LOCAL_IGNORE_HISTORY = "local_ignore_history";
    public static final String EXTRAS_ENABLE_PREPROCESSOR = "enable_preprocessor";
    public static final String EXTRAS_ECHO_CANCELLATION_METHOD = "echo_cancellation_method";
    public static final String EXTRAS_AUDIO_SOURCE = "audio_source";
    public static final String EXTRAS_AUDIO_STREAM = "audio_stream";
    public static final String EXTRAS_FRAMES_PER_PACKET = "frames_per_packet";

    private Server mServer;
    private boolean mAutoReconnect;
    private int mAutoReconnectDelay;
    private byte[] mCertificate;
    private String mCertificatePassword;
    private boolean mUseOpus;
    private boolean mForceTcp;
    private boolean mUseTor;
    private String mClientName;
    private List<String> mAccessTokens;
    private String mTrustStore;
    private String mTrustStorePassword;
    private String mTrustStoreFormat;
    private List<Integer> mLocalMuteHistory;
    private List<Integer> mLocalIgnoreHistory;
    private AudioHandler.Builder mAudioBuilder;
    private int mTransmitMode;

    private byte mVoiceTargetId;
    private WhisperTargetList mWhisperTargetList;

    private PowerManager.WakeLock mWakeLock;
    private Handler mHandler;
    private HumlaCallbacks mCallbacks;

    private HumlaConnection mConnection;
    private ConnectionState mConnectionState;
    private ModelHandler mModelHandler;
    private AudioHandler mAudioHandler;
    private BluetoothScoReceiver mBluetoothReceiver;

    private ActivityInputMode mActivityInputMode;
    private ToggleInputMode mToggleInputMode;
    private ContinuousInputMode mContinuousInputMode;

    private boolean mReconnecting;

    // ✅ BUFER — UTUH TIDAK DIUBAH
    private short[] mLatestRecordingBuffer;

    private final BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mReconnecting) {
                try { unregisterReceiver(this); }
                catch (IllegalArgumentException e) {
                    Log.e(TAG, "Error unregistering connectivity receiver: " + e.getMessage());
                }
                return;
            }
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                Log.v(TAG, "Connectivity restored, attempting reconnect.");
                connect();
            }
        }
    };

    private final AudioHandler.AudioEncodeListener mAudioInputListener =
            new AudioHandler.AudioEncodeListener() {
                @Override
                public void onAudioEncoded(byte[] data, int length) {
                    // ✅ BUFER — UTUH TIDAK DIUBAH
                    if (length > 0) {
                        mLatestRecordingBuffer = new short[length / 2];
                        for (int i = 0; i < mLatestRecordingBuffer.length; i++) {
                            int lo = data[i * 2] & 0xFF;
                            int hi = data[i * 2 + 1] << 8;
                            mLatestRecordingBuffer[i] = (short) (hi | lo);
                        }
                    }
                    if (mConnection != null && mConnection.isSynchronized()) {
                        mConnection.sendUDPMessage(data, length, false);
                    }
                }
                @Override
                public void onTalkingStateChanged(final boolean talking) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!isSynchronized()) return;
                                if (mModelHandler == null || mConnection == null) return;
                                final User currentUser = mModelHandler.getUser(mConnection.getSession());
                                if (currentUser == null) return;
                                currentUser.setTalkState(talking ? TalkState.TALKING : TalkState.PASSIVE);
                                mCallbacks.onUserTalkStateUpdated(currentUser);
                            } catch (NotSynchronizedException e) { e.printStackTrace(); }
                        }
                    });
                }
            };

    private AudioOutput.AudioOutputListener mAudioOutputListener = new AudioOutput.AudioOutputListener() {
        @Override
        public void onUserTalkStateUpdated(final User user) {
            mCallbacks.onUserTalkStateUpdated(user);
        }
        @Override
        public User getUser(int session) {
            return mModelHandler != null ? mModelHandler.getUser(session) : null;
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                try { configureExtras(extras); }
                catch (AudioException e) {
                    throw new RuntimeException("Attempted to initialize audio erroneously.", e);
                }
            }
            if (ACTION_CONNECT.equals(intent.getAction())) {
                if (extras == null || !extras.containsKey(EXTRAS_SERVER))
                    throw new RuntimeException(ACTION_CONNECT + " requires EXTRAS_SERVER.");
                connect();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:HumlaService");
        mHandler = new Handler(getMainLooper());
        mCallbacks = new HumlaCallbacks();
        mAudioBuilder = new AudioHandler.Builder()
                .setContext(this)
                .setLogger(this)
                .setEncodeListener(mAudioInputListener)
                .setTalkingListener(mAudioOutputListener);
        mConnectionState = ConnectionState.DISCONNECTED;
        mBluetoothReceiver = new BluetoothScoReceiver(this, this);
        registerReceiver(mBluetoothReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        mToggleInputMode = new ToggleInputMode();
        mActivityInputMode = new ActivityInputMode(0);
        mContinuousInputMode = new ContinuousInputMode();
        mWhisperTargetList = new WhisperTargetList();
        AndroidUsingLinkProperties.setup(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(mBluetoothReceiver); }
        catch (IllegalArgumentException e) {
            Log.e(TAG, "Error unregistering bluetooth receiver: " + e.getMessage());
        }
    }

    public IBinder onBind(Intent intent) { return new HumlaBinder(this); }

    protected void connect() {
        try {
            setReconnecting(false);
            mConnectionState = ConnectionState.DISCONNECTED;
            mVoiceTargetId = 0;
            mWhisperTargetList.clear();
            mLatestRecordingBuffer = null;
            mConnection = new HumlaConnection(this);
            mConnection.setForceTCP(mForceTcp);
            mConnection.setUseTor(mUseTor);
            mConnection.setKeys(mCertificate, mCertificatePassword);
            mConnection.setTrustStore(mTrustStore, mTrustStorePassword, mTrustStoreFormat);
            mModelHandler = new ModelHandler(this, mCallbacks, this,
                    mLocalMuteHistory, mLocalIgnoreHistory);
            mConnection.addTCPMessageHandlers(mModelHandler);
            mConnectionState = ConnectionState.CONNECTING;
            mCallbacks.onConnecting();
            mConnection.connect(mServer.getSrvHost(), mServer.getSrvPort());
        } catch (HumlaException e) {
            e.printStackTrace();
            mCallbacks.onDisconnected(e);
        }
    }

    public void disconnect()                          { if (mConnection != null) mConnection.disconnect(); }
    public boolean isConnectionEstablished()           { return mConnection != null && mConnection.isConnected(); }
    public boolean isSynchronized()                   { return mConnection != null && mConnection.isSynchronized(); }

    @Override
    public void onConnectionEstablished() {
        Mumble.Version.Builder version = Mumble.Version.newBuilder();
        version.setRelease(mClientName);
        version.setVersion(Constants.PROTOCOL_VERSION);
        version.setOs("Android");
        version.setOsVersion(Build.VERSION.RELEASE);
        Mumble.Authenticate.Builder auth = Mumble.Authenticate.newBuilder();
        auth.setUsername(mServer.getUsername());
        auth.setPassword(mServer.getPassword());
        auth.addCeltVersions(CELT7.getBitstreamVersion());
        auth.setOpus(mUseOpus);
        auth.addAllTokens(mAccessTokens);
        mConnection.sendTCPMessage(version.build(), HumlaTCPMessageType.Version);
        mConnection.sendTCPMessage(auth.build(), HumlaTCPMessageType.Authenticate);
    }

    @Override
    public void onConnectionSynchronized() {
        if (!mConnection.isConnected()) return;
        if (mModelHandler == null) { Log.e(TAG, "model null"); return; }
        mConnectionState = ConnectionState.CONNECTED;
        Log.v(TAG, "Connected");
        mWakeLock.acquire();
        try {
            mAudioHandler = mAudioBuilder.initialize(
                    mModelHandler.getUser(mConnection.getSession()),
                    mConnection.getMaxBandwidth(), mConnection.getCodec(), mVoiceTargetId);
            mConnection.addTCPMessageHandlers(mAudioHandler);
            mConnection.addUDPMessageHandlers(mAudioHandler);
        } catch (AudioException | NotSynchronizedException e) {
            Log.e(TAG, "Audio init failed", e);
            if (e instanceof AudioException) onConnectionWarning(e.getMessage());
            else throw new RuntimeException(e);
        }
        mCallbacks.onConnected();
    }

    @Override public void onConnectionHandshakeFailed(X509Certificate[] chain) { mCallbacks.onTLSHandshakeFailed(chain); }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        if (e != null) {
            Log.e(TAG, "Disconnected: " + e.getMessage() + " / " + e.getReason());
            mConnectionState = ConnectionState.CONNECTION_LOST;
            setReconnecting(mAutoReconnect &&
                    e.getReason() == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR);
        } else {
            Log.v(TAG, "Disconnected normally");
            mConnectionState = ConnectionState.DISCONNECTED;
        }
        if (mWakeLock.isHeld()) mWakeLock.release();
        if (mAudioHandler != null) mAudioHandler.shutdown();
        mModelHandler = null; mAudioHandler = null; mLatestRecordingBuffer = null;
        mVoiceTargetId = 0; mWhisperTargetList.clear();
        mBluetoothReceiver.stopBluetoothSco();
        mCallbacks.onDisconnected(e);
    }

    @Override public void onConnectionWarning(String warning) { logWarning(warning); }
    @Override public void logInfo(String msg)    { if (mConnection != null && mConnection.isSynchronized()) mCallbacks.onLogInfo(msg); }
    @Override public void logWarning(String msg) { mCallbacks.onLogWarning(msg); }
    @Override public void logError(String msg)   { mCallbacks.onLogError(msg); }

    public void setReconnecting(boolean reconnecting) {
        if (mReconnecting == reconnecting) return;
        mReconnecting = reconnecting;
        if (reconnecting) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                mHandler.postDelayed(() -> { if (mReconnecting) connect(); }, mAutoReconnectDelay);
            } else {
                try { registerReceiver(mConnectivityReceiver,
                        new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)); }
                catch (IllegalArgumentException e) { Log.e(TAG, "Register fail", e); }
            }
        } else {
            try { unregisterReceiver(mConnectivityReceiver); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void createAudioHandler() throws AudioException {
        if (BuildConfig.DEBUG && mConnectionState != ConnectionState.CONNECTED)
            throw new AssertionError("Not connected");
        if (mAudioHandler != null) {
            mConnection.removeTCPMessageHandler(mAudioHandler);
            mConnection.removeUDPMessageHandler(mAudioHandler);
            mAudioHandler.shutdown();
        }
        try {
            mAudioHandler = mAudioBuilder.initialize(
                    mModelHandler.getUser(mConnection.getSession()),
                    mConnection.getMaxBandwidth(), mConnection.getCodec(), mVoiceTargetId);
            mConnection.addTCPMessageHandlers(mAudioHandler);
            mConnection.addUDPMessageHandlers(mAudioHandler);
        } catch (NotSynchronizedException e) {
            throw new RuntimeException("Not synced creating audio", e);
        }
    }

    public boolean configureExtras(Bundle extras) throws AudioException {
        boolean needReconnect = false;
        if (extras.containsKey(EXTRAS_SERVER))           { mServer = extras.getParcelable(EXTRAS_SERVER); needReconnect = true; }
        if (extras.containsKey(EXTRAS_AUTO_RECONNECT))        mAutoReconnect          = extras.getBoolean(EXTRAS_AUTO_RECONNECT);
        if (extras.containsKey(EXTRAS_AUTO_RECONNECT_DELAY))  mAutoReconnectDelay      = extras.getInt   (EXTRAS_AUTO_RECONNECT_DELAY);
        if (extras.containsKey(EXTRAS_CERTIFICATE))           { mCertificate = extras.getByteArray(EXTRAS_CERTIFICATE); needReconnect = true; }
        if (extras.containsKey(EXTRAS_CERTIFICATE_PASSWORD))   mCertificatePassword    = extras.getString(EXTRAS_CERTIFICATE_PASSWORD);
        if (extras.containsKey(EXTRAS_DETECTION_THRESHOLD))    mActivityInputMode.setThreshold(extras.getFloat(EXTRAS_DETECTION_THRESHOLD));
        if (extras.containsKey(EXTRAS_AMPLITUDE_BOOST))        mAudioBuilder.setAmplitudeBoost(extras.getFloat(EXTRAS_AMPLITUDE_BOOST));
        if (extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
            mTransmitMode = extras.getInt(EXTRAS_TRANSMIT_MODE);
            IInputMode mode;
            switch (mTransmitMode) {
                case Constants.TRANSMIT_PUSH_TO_TALK:   mode = mToggleInputMode;       break;
                case Constants.TRANSMIT_CONTINUOUS:    mode = mContinuousInputMode;   break;
                case Constants.TRANSMIT_VOICE_ACTIVITY: mode = mActivityInputMode;     break;
                default: throw new IllegalArgumentException("Bad transmit mode");
            }
            mAudioBuilder.setInputMode(mode);
        }
        if (extras.containsKey(EXTRAS_INPUT_RATE))          mAudioBuilder.setInputSampleRate(extras.getInt(EXTRAS_INPUT_RATE));
        if (extras.containsKey(EXTRAS_INPUT_QUALITY))       mAudioBuilder.setTargetBitrate(extras.getInt(EXTRAS_INPUT_QUALITY));
        if (extras.containsKey(EXTRAS_USE_OPUS))            { mUseOpus = extras.getBoolean(EXTRAS_USE_OPUS); needReconnect = true; }
        if (extras.containsKey(EXTRAS_FORCE_TCP))           { boolean v = extras.getBoolean(EXTRAS_FORCE_TCP); mForceTcp |= v; needReconnect = true; }
        if (extras.containsKey(EXTRAS_USE_TOR))            { mUseTor = extras.getBoolean(EXTRAS_USE_TOR); mForceTcp |= mUseTor; needReconnect = true; }
        if (extras.containsKey(EXTRAS_CLIENT_NAME))         { mClientName = extras.getString(EXTRAS_CLIENT_NAME); needReconnect = true; }
        if (extras.containsKey(EXTRAS_ACCESS_TOKENS))        { mAccessTokens = extras.getStringArrayList(EXTRAS_ACCESS_TOKENS);
            if (mConnection != null && mConnection.isConnected()) mConnection.sendAccessTokens(mAccessTokens); }
        if (extras.containsKey(EXTRAS_AUDIO_SOURCE))         mAudioBuilder.setAudioSource(extras.getInt(EXTRAS_AUDIO_SOURCE));
        if (extras.containsKey(EXTRAS_AUDIO_STREAM))         mAudioBuilder.setAudioStream(extras.getInt(EXTRAS_AUDIO_STREAM));
        if (extras.containsKey(EXTRAS_FRAMES_PER_PACKET))   mAudioBuilder.setTargetFramesPerPacket(extras.getInt(EXTRAS_FRAMES_PER_PACKET));
        if (extras.containsKey(EXTRAS_TRUST_STORE))         { mTrustStore = extras.getString(EXTRAS_TRUST_STORE); needReconnect = true; }
        if (extras.containsKey(EXTRAS_TRUST_STORE_PASSWORD)){ mTrustStorePassword = extras.getString(EXTRAS_TRUST_STORE_PASSWORD); needReconnect = true; }
        if (extras.containsKey(EXTRAS_TRUST_STORE_FORMAT))   { mTrustStoreFormat = extras.getString(EXTRAS_TRUST_STORE_FORMAT); needReconnect = true; }
        if (extras.containsKey(EXTRAS_HALF_DUPLEX))         mAudioBuilder.setHalfDuplexEnabled(
                extras.getInt(EXTRAS_TRANSMIT_MODE) == Constants.TRANSMIT_PUSH_TO_TALK &&
                        extras.getBoolean(EXTRAS_HALF_DUPLEX));
        if (extras.containsKey(EXTRAS_LOCAL_MUTE_HISTORY))   { mLocalMuteHistory = extras.getIntegerArrayList(EXTRAS_LOCAL_MUTE_HISTORY); needReconnect = true; }
        if (extras.containsKey(EXTRAS_LOCAL_IGNORE_HISTORY)) { mLocalIgnoreHistory = extras.getIntegerArrayList(EXTRAS_LOCAL_IGNORE_HISTORY); needReconnect = true; }
        if (extras.containsKey(EXTRAS_ENABLE_PREPROCESSOR))  mAudioBuilder.setPreprocessorEnabled(extras.getBoolean(EXTRAS_ENABLE_PREPROCESSOR));
        if (extras.containsKey(EXTRAS_ECHO_CANCELLATION_METHOD)) mAudioBuilder.setEchoCancellationMethod(extras.getString(EXTRAS_ECHO_CANCELLATION_METHOD));

        if (mAudioHandler != null && mAudioHandler.isInitialized()) {
            createAudioHandler();
            Log.i(TAG, "Audio reloaded");
        }
        return needReconnect;
    }

    @Override public void onBluetoothScoConnected() { mAudioBuilder.setBluetoothEnabled(true); if(mAudioHandler!=null)try{createAudioHandler();}catch(AudioException e){e.printStackTrace();} }
    @Override public void onBluetoothScoDisconnected() { mAudioBuilder.setBluetoothEnabled(false); if(mAudioHandler!=null)try{createAudioHandler();}catch(AudioException e){e.printStackTrace();} }

    public HumlaConnection getConnection() { return mConnection; }

    private AudioHandler getAudioHandler() throws NotSynchronizedException {
        if (!isSynchronized()) throw new NotSynchronizedException();
        if (mAudioHandler == null && mConnectionState == ConnectionState.CONNECTED)
            throw new RuntimeException("Audio missing");
        return mAudioHandler;
    }
    private ModelHandler getModelHandler() throws NotSynchronizedException {
        if (!isSynchronized()) throw new NotSynchronizedException();
        if (mModelHandler == null && mConnectionState == ConnectionState.CONNECTED)
            throw new RuntimeException("Model missing");
        return mModelHandler;
    }
    private BluetoothScoReceiver getBluetoothReceiver() throws NotSynchronizedException {
        if (!isSynchronized()) throw new NotSynchronizedException();
        return mBluetoothReceiver;
    }

    @Override public ConnectionState getConnectionState()    { return mConnectionState; }
    @Override public HumlaException getConnectionError()    { return mConnection != null ? mConnection.getError() : null; }
    @Override public boolean isReconnecting()                { return mReconnecting; }
    @Override public void cancelReconnect()                  { setReconnecting(false); }
    @Override public Server getTargetServer()                { return mServer; }
    @Override public IHumlaSession HumlaSession() throws HumlaDisconnectedException {
        if (mConnectionState != ConnectionState.CONNECTED) throw new HumlaDisconnectedException();
        return this;
    }

    @Override public long getTCPLatency()   { try { return getConnection().getTCPLatency(); } catch (NotConnectedException e) { throw new IllegalStateException(e); } }
    @Override public long getUDPLatency()   { try { return getConnection().getUDPLatency(); } catch (NotConnectedException e) { throw new IllegalStateException(e); } }
    @Override public int  getMaxBandwidth()   { try { return getConnection().getMaxBandwidth(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public int  getCurrentBandwidth(){ try { return getAudioHandler().getCurrentBandwidth(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public int  getServerVersion() { try { return getConnection().getServerVersion(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public String getServerRelease(){ try { return getConnection().getServerRelease(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public String getServerOSName() { try { return getConnection().getServerOSName(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public String getServerOSVersion(){ try { return getConnection().getServerOSVersion(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public int  getSessionId()   { try { return getConnection().getSession(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public IUser getSessionUser(){ try { return getModelHandler().getUser(getSessionId()); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public IChannel getSessionChannel() {
        IUser u = getSessionUser();
        if (u != null) return u.getChannel();
        throw new IllegalStateException("Session user null");
    }
    @Override public IUser getUser(int s)   { try { return getModelHandler().getUser(s); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public IChannel getChannel(int id){ try { return getModelHandler().getChannel(id); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public IChannel getRootChannel() { return getChannel(0); }
    @Override public int  getPermissions()  { try { return getModelHandler().getPermissions(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public int  getTransmitMode() { return mTransmitMode; }
    @Override public HumlaUDPMessageType getCodec(){ try { return getConnection().getCodec(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public boolean usingBluetoothSco(){ try { return getBluetoothReceiver().isBluetoothScoOn(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public void enableBluetoothSco() { try { getBluetoothReceiver().startBluetoothSco(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }
    @Override public void disableBluetoothSco(){ try { getBluetoothReceiver().stopBluetoothSco(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }

    // ✅ HANYA UBAH private → protected — ISI SAMA PERSIS AGAR ANAK BISA PAKAI
    protected boolean isTalking()               { return mToggleInputMode.isTalkingOn(); }
    protected void setTalkingState(boolean v)   { mToggleInputMode.setTalkingOn(v); }
    // ✅ FUNGSI BUFER — TAMBAH/PASTIKAN ADA, SAMA PERSIS
    protected short[] getRecordingBuffer()      { return mLatestRecordingBuffer != null ? mLatestRecordingBuffer.clone() : null; }

    @Override public void joinChannel(int c)                     { moveUserToChannel(getSessionId(), c); }
    @Override public void moveUserToChannel(int ses, int ch)    { Mumble.UserState.Builder b = Mumble.UserState.newBuilder(); b.setSession(ses); b.setChannelId(ch); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.UserState); }
    @Override public void createChannel(int p, String n, String d, int pos, boolean tmp) { Mumble.ChannelState.Builder b = Mumble.ChannelState.newBuilder(); b.setParent(p); b.setName(n); b.setDescription(d); b.setPosition(pos); b.setTemporary(tmp); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.ChannelState); }
    @Override public void sendAccessTokens(List<String> t)       { getConnection().sendAccessTokens(t); }
    @Override public void requestBanList()                       { throw new UnsupportedOperationException(); }
    @Override public void requestUserList()                      { throw new UnsupportedOperationException(); }
    @Override public void requestPermissions(int c)             { Mumble.PermissionQuery.Builder b = Mumble.PermissionQuery.newBuilder(); b.setChannelId(c); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.PermissionQuery); }
    @Override public void requestComment(int ses)                { Mumble.RequestBlob.Builder b = Mumble.RequestBlob.newBuilder(); b.addSessionComment(ses); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.RequestBlob); }
    @Override public void requestAvatar(int ses)                 { Mumble.RequestBlob.Builder b = Mumble.RequestBlob.newBuilder(); b.addSessionTexture(ses); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.RequestBlob); }
    @Override public void requestChannelDescription(int ch)      { Mumble.RequestBlob.Builder b = Mumble.RequestBlob.newBuilder(); b.addChannelDescription(ch); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.RequestBlob); }
    @Override public void registerUser(int ses)                  { Mumble.UserState.Builder b = Mumble.UserState.newBuilder(); b.setSession(ses); b.setUserId(0); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.UserState); }
    @Override public void kickBanUser(int ses, String r, boolean ban) { Mumble.UserRemove.Builder b = Mumble.UserRemove.newBuilder(); b.setSession(ses); b.setReason(r); b.setBan(ban); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.UserRemove); }

    @Override public Message sendUserTextMessage(int ses, String txt) {
        if (!isSynchronized()) throw new IllegalStateException();
        Mumble.TextMessage.Builder b = Mumble.TextMessage.newBuilder(); b.addSession(ses); b.setMessage(txt); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.TextMessage);
        User me = getModelHandler().getUser(getSessionId()), him = getModelHandler().getUser(ses);
        List<User> ul = new ArrayList<>(); ul.add(him);
        return new Message(getSessionId(), me.getName(), new ArrayList<>(), new ArrayList<>(), ul, txt);
    }
    @Override public Message sendChannelTextMessage(int ch, String txt, boolean tree) {
        if (!isSynchronized()) throw new IllegalStateException();
        Mumble.TextMessage.Builder b = Mumble.TextMessage.newBuilder(); if(tree) b.addTreeId(ch); else b.addChannelId(ch); b.setMessage(txt); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.TextMessage);
        User me = getModelHandler().getUser(getSessionId()); Channel c = getModelHandler().getChannel(ch); List<Channel> cl = new ArrayList<>(); cl.add(c);
        return new Message(getSessionId(), me.getName(), cl, tree ? cl : new ArrayList<>(), new ArrayList<>(), txt);
    }

    @Override public void setUserComment(int ses, String cmt)     { Mumble.UserState.Builder b = Mumble.UserState.newBuilder(); b.setSession(ses); b.setComment(cmt); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.UserState); }
    @Override public void setPrioritySpeaker(int ses, boolean p)   { Mumble.UserState.Builder b = Mumble.UserState.newBuilder(); b.setSession(ses); b.setPrioritySpeaker(p); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.UserState); }
    @Override public void removeChannel(int id)                   { Mumble.ChannelRemove.Builder b = Mumble.ChannelRemove.newBuilder(); b.setChannelId(id); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.ChannelRemove); }
    @Override public void setMuteDeafState(int ses, boolean m, boolean d) { Mumble.UserState.Builder b = Mumble.UserState.newBuilder(); b.setSession(ses); b.setMute(m); b.setDeaf(d); if(!m) b.setSuppress(false); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.UserState); }
    @Override public void setSelfMuteDeafState(boolean m, boolean d) { Mumble.UserState.Builder b = Mumble.UserState.newBuilder(); b.setSelfMute(m); b.setSelfDeaf(d); getConnection().sendTCPMessage(b.build(), HumlaTCPMessageType.UserState); }

    @Override public void registerObserver(IHumlaObserver o)     { mCallbacks.registerObserver(o); }
    @Override public void unregisterObserver(IHumlaObserver o)   { mCallbacks.unregisterObserver(o); }
    @Override public boolean isConnected()                        { return mConnectionState == ConnectionState.CONNECTED; }

    @Override public void linkChannels(IChannel a, IChannel b)    { Mumble.ChannelState.Builder cb = Mumble.ChannelState.newBuilder(); cb.setChannelId(a.getId()); cb.addLinksAdd(b.getId()); getConnection().sendTCPMessage(cb.build(), HumlaTCPMessageType.ChannelState); }
    @Override public void unlinkChannels(IChannel a, IChannel b) { Mumble.ChannelState.Builder cb = Mumble.ChannelState.newBuilder(); cb.setChannelId(a.getId()); cb.addLinksRemove(b.getId()); getConnection().sendTCPMessage(cb.build(), HumlaTCPMessageType.ChannelState); }
    @Override public void unlinkAllChannels(IChannel ch)          { Mumble.ChannelState.Builder cb = Mumble.ChannelState.newBuilder(); cb.setChannelId(ch.getId()); for(IChannel lnk:ch.getLinks()) cb.addLinksRemove(lnk.getId()); getConnection().sendTCPMessage(cb.build(), HumlaTCPMessageType.ChannelState); }

    @Override public byte registerWhisperTarget(WhisperTarget t)   { byte id = mWhisperTargetList.append(t); if(id<0) return‑1; Mumble.VoiceTarget.Builder vb = Mumble.VoiceTarget.newBuilder(); vb.setId(id); vb.addTargets(t.createTarget()); getConnection().sendTCPMessage(vb.build(), HumlaTCPMessageType.VoiceTarget); return id; }
    @Override public void unregisterWhisperTarget(byte id)        { mWhisperTargetList.free(id); }
    @Override public void setVoiceTargetId(byte id)               { if((id & ~0x1F)!=0) throw new IllegalArgumentException(); mVoiceTargetId = id; if(mAudioHandler!=null) mAudioHandler.setVoiceTargetId(id); mCallbacks.onVoiceTargetChanged(VoiceTargetMode.fromId(id)); }
    @Override public byte getVoiceTargetId()                      { return mVoiceTargetId; }
    @Override public VoiceTargetMode getVoiceTargetMode()         { return VoiceTargetMode.fromId(mVoiceTargetId); }
    @Override public WhisperTarget getWhisperTarget()             { return VoiceTargetMode.fromId(mVoiceTargetId)==VoiceTargetMode.WHISPER ? mWhisperTargetList.get(mVoiceTargetId) : null; }
    @Override public ServerSettings getServerSettings()           { try { return getModelHandler().getServerSettings(); } catch (NotSynchronizedException e) { throw new IllegalStateException(e); } }

    public enum ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, CONNECTION_LOST }

    public static class HumlaBinder extends Binder {
        private final IHumlaService mService;
        private HumlaBinder(IHumlaService s) { mService = s; }
        public IHumlaService getService() { return mService; }
    }
}
