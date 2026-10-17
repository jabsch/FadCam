package com.fadcam.streaming.model;

/**
 * Represents an authentication session token for Remote access.
 * Contains token string, creation time, and expiry information.
 */
public class SessionToken {
    private final String token;
    private final long createdAtMs;
    private final long expiresAtMs;
    private final String deviceInfo; // Optional: IP, user agent, etc.
    
    public SessionToken(String token, long createdAtMs, long expiresAtMs, String deviceInfo) {
        this.token = token;
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
        this.deviceInfo = deviceInfo;
    }
    
    public SessionToken(String token, long createdAtMs, long expiresAtMs) {
        this(token, createdAtMs, expiresAtMs, "unknown");
    }
    
    public String getToken() {
        return token;
    }
    
    public long getCreatedAtMs() {
        return createdAtMs;
    }
    
    public long getExpiresAtMs() {
        return expiresAtMs;
    }
    
    public String getDeviceInfo() {
        return deviceInfo;
    }
    
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAtMs;
    }
    
    public boolean isValid() {
        return !isExpired() && token != null && !token.isEmpty();
    }
    
    /**
     * Convert to a JSON object for storage. org.json escapes deviceInfo (it holds the
     * browser's User-Agent, which can contain quotes); the old format string did not.
     */
    public org.json.JSONObject toJson() throws org.json.JSONException {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("token", token);
        o.put("createdAt", createdAtMs);
        o.put("expiresAt", expiresAtMs);
        o.put("deviceInfo", deviceInfo != null ? deviceInfo : "");
        return o;
    }

    /**
     * Parse from a stored JSON object, or null if it is incomplete.
     */
    public static SessionToken fromJson(org.json.JSONObject o) {
        String token = o.optString("token", null);
        if (token == null || token.isEmpty()) return null;
        return new SessionToken(token, o.optLong("createdAt"), o.optLong("expiresAt"),
                o.optString("deviceInfo", ""));
    }
}
