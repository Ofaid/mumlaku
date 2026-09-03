package com.mumla.ofa.model;

public class UserVoiceState {
    private final String userId;
    private final String username;
    private boolean isCurrentlySpeaking;
    private long voiceActivityStartTime;

    // Constructor 2 Parameter (Sesuai kebutuhan MumlaService)
    public UserVoiceState(String userId, String username) {
        this.userId = userId;
        this.username = username;
        this.isCurrentlySpeaking = false;
        this.voiceActivityStartTime = 0L;
    }

    // Getter
    public boolean isCurrentlySpeaking() {
        return isCurrentlySpeaking;
    }

    // Method Tracking Audio
    public void onAudioPacketReceived(long timestampMs) {
        if (voiceActivityStartTime == 0L) {
            voiceActivityStartTime = timestampMs;
        }

        long duration = timestampMs - voiceActivityStartTime;

        // Hysteresis: Baru dianggap speaking kalau > 200ms
        if (!isCurrentlySpeaking && duration >= 200L) {
            isCurrentlySpeaking = true;
        } else if (isCurrentlySpeaking) {
            voiceActivityStartTime = timestampMs; 
        }
    }

    // Method Idle Check (1 Parameter Sesuai Kebutuhan)
    public void onIdle(long currentTimeMs) {
        if (isCurrentlySpeaking) {
            // Idle timeout 300ms
            if (currentTimeMs - voiceActivityStartTime > 300L) {
                isCurrentlySpeaking = false;
                voiceActivityStartTime = 0L;
            }
        }
    }

    // Getter tambahan untuk debug jika perlu
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
}
