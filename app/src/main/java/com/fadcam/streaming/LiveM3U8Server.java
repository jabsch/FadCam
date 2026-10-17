package com.fadcam.streaming;

import com.fadcam.Log;
import com.fadcam.FLog;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.streaming.model.ClientEvent;
import com.fadcam.streaming.model.ClientMetrics;
import com.fadcam.streaming.model.StreamQuality;
import com.fadcam.utils.ServiceStartPolicy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

/**
 * LiveM3U8Server provides HTTP endpoints for HLS streaming of fMP4 segments.
 * 
 * Endpoints:
 * - GET /live.m3u8         - HLS playlist referencing buffered segments
 * - GET /seg-{id}.m4s      - Serves individual fMP4 segment bytes
 * - GET /status            - JSON status (fps, bitrate, resolution, connections)
 * - GET /                  - Simple HTML landing page with instructions
 * 
 * Architecture:
 * - Built on NanoHTTPD lightweight HTTP server
 * - Reads segment data from RemoteStreamManager circular buffer
 * - CORS-enabled for web player compatibility
 * - Connection tracking for active client monitoring
 * 
 * COMPRESSION NOTE: All JSON responses explicitly disable GZIP compression by manually
 * setting the Content-Length header. This is the official NanoHTTPD 2.3.1 approach
 * (see NanoHTTPD GZipIntegrationTest.java line 151). Manual Content-Length prevents
 * auto-compression when clients send Accept-Encoding: gzip headers, avoiding "Unexpected
 * token" JSON parse errors in browsers that receive compressed bytes but expect plain text.
 */
    public class LiveM3U8Server extends NanoHTTPD {
        private static final String TAG = "LiveM3U8Server";
        
        private final RemoteStreamManager streamManager;
        private final android.content.Context context;
        
        public LiveM3U8Server(android.content.Context context, int port) throws IOException {
            super("0.0.0.0", port);  // CRITICAL FIX: Bind to ALL interfaces (0.0.0.0), not just localhost
            this.context = context.getApplicationContext();
            this.streamManager = RemoteStreamManager.getInstance();
            
            FLog.i(TAG, "✅ [HTTP Server] Listening on ALL interfaces (0.0.0.0:" + port + ")");
            FLog.i(TAG, "✅ [HTTP Server] Now ACCESSIBLE from other devices on hotspot!");
        }
    
    /**
     * Helper method to create JSON responses with proper charset encoding.
     * 
     * COMPRESSION CONTROL: Manually sets Content-Length header to prevent automatic
     * GZIP compression in NanoHTTPD 2.3.1. From GZipIntegrationTest.java line 151:
     * "Content should not be gzipped if Content-Length is added manually".
     * 
     * Without this, NanoHTTPD auto-compresses JSON when client sends Accept-Encoding: gzip,
     * which causes "Unexpected token" errors in browsers trying to parse gzipped bytes as JSON.
     * 
     * @param status HTTP response status
     * @param json JSON content as String
     * @return Response with UTF-8 charset, no compression
     */
    private Response jsonResponse(Response.Status status, String json) {
        byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", json);
        // Setting Content-Length manually prevents NanoHTTPD from auto-gzipping (confirmed in official tests)
        response.addHeader("Content-Length", String.valueOf(jsonBytes.length));
        response.addHeader("Cache-Control", "no-cache");
        return response;
    }
    
    /**
     * The current POST request's body, read once at the start of {@link #serve}. NanoHTTPD
     * 2.3.1 never drains an unread body, so a handler that ignored it (toggle, pause, stop
     * alarm...) left "{}" in the keep-alive stream and the next request on it failed with 400.
     */
    private static final ThreadLocal<java.util.Map<String, String>> REQUEST_BODY = new ThreadLocal<>();

    @NonNull
    private static java.util.Map<String, String> postBody() {
        java.util.Map<String, String> body = REQUEST_BODY.get();
        return body != null ? body : java.util.Collections.emptyMap();
    }

    @Override
    public Response serve(IHTTPSession session) {
        java.util.Map<String, String> body = new java.util.HashMap<>();
        if (Method.POST.equals(session.getMethod())) {
            // NanoHTTPD loads the whole body into memory before any auth check: one huge POST
            // from anyone on the network ran the app out of memory and killed the recording
            if (!bodySizeAcceptable(session)) {
                Response tooLarge = newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE,
                        MIME_PLAINTEXT, "Request body too large");
                tooLarge.closeConnection(true);
                return tooLarge;
            }
            try {
                session.parseBody(body);
            } catch (Exception e) {
                FLog.w(TAG, "Failed to read request body: " + e.getMessage());
            }
        }
        REQUEST_BODY.set(body);
        try {
            return serveRequest(session);
        } finally {
            REQUEST_BODY.remove();
        }
    }

    /** Every API body is a small JSON document; anything bigger, or of unknown size, is refused. */
    private static final long MAX_POST_BODY_BYTES = 64 * 1024;

    private static boolean bodySizeAcceptable(IHTTPSession session) {
        String length = session.getHeaders().get("content-length");
        if (length == null) return true; // NanoHTTPD reads no body without a length
        try {
            long n = Long.parseLong(length.trim());
            return n >= 0 && n <= MAX_POST_BODY_BYTES;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Response serveRequest(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        String clientIP = session.getRemoteIpAddress();
        
        // OPTIMIZATION: Commented out HTTP request logging (called 10-30x per second during streaming)
        // Every request was being logged, massive I/O overhead during playback
        
        // Track unique client IPs (only adds if new, Set handles duplicates)
        streamManager.trackClientIP(clientIP);
        
        // Track API calls (not fragment/HLS calls)
        boolean isApiCall = !uri.startsWith("/seg-") && !uri.startsWith("/live.m3u8") && 
                           !uri.startsWith("/stream.m3u8") && !uri.startsWith("/init.mp4") &&
                           !uri.startsWith("/css/") && !uri.startsWith("/js/") && !uri.startsWith("/assets/");
        
        // Log API requests (important ones that indicate client activity)
        // (/status is left out: the dashboard polls it every 2 s.)
        if (isApiCall && (uri.equals("/") || uri.startsWith("/auth") || uri.startsWith("/api/"))) {
            FLog.i(TAG, "📥 [API] " + method + " " + uri + " from " + clientIP);
        }
        
        if (isApiCall) {
            if (Method.GET.equals(method)) {
                streamManager.incrementClientGetRequests(clientIP);
            } else if (Method.POST.equals(method)) {
                streamManager.incrementClientPostRequests(clientIP);
            }
        }
        
        Response response;

        // No CORS headers: the dashboard is served from this same server and the cloud one
        // goes through the relay. "Allow-Origin: *" let any web page open in a browser on the
        // network read /status and the live video while Remote Security was off.
        if (Method.OPTIONS.equals(method)) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
        }
        
        // Remote Security: everything except the page itself, its static files and
        // the sign-in endpoints needs a valid session while a password is set.
        if (!isPublicPath(uri) && !validateAuthToken(session)) {
            response = jsonResponse(Response.Status.UNAUTHORIZED,
                "{\"success\":false,\"message\":\"Unauthorized\",\"authEnabled\":true,\"authenticated\":false}");
        } else if (Method.GET.equals(method)) {
            if ("/live.m3u8".equals(uri) || "/stream.m3u8".equals(uri)) {
                // HLS playlist - industry standard for live streaming
                response = servePlaylist(queryToken(session));
            } else if ("/init.mp4".equals(uri)) {
                response = serveInitSegment(clientIP);
            } else if (uri.startsWith("/seg-") && uri.endsWith(".m4s")) {
                response = serveFragment(uri, clientIP);
            } else if ("/status".equals(uri)) {
                response = serveStatus();
            } else if ("/auth/check".equals(uri)) {
                response = handleAuthCheck(session);
            } else if ("/audio/volume".equals(uri)) {
                response = getVolume();
            } else if ("/api/github/notification".equals(uri)) {
                // GitHub notification proxy - avoids CORS issues
                response = fetchGitHubNotification();
            } else if ("/api/notifications".equals(uri)) {
                response = getNotifications();
            } else if ("/".equals(uri)) {
                response = serveLandingPage();
            } else if (uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/assets/") || uri.startsWith("/fadex/")) {
                // Serve static web assets (CSS, JS, images, fonts, notification configs)
                response = serveStaticFile(uri);
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
            }
        } else if (Method.POST.equals(method)) {
            // Authentication endpoints (no token required)
            if ("/auth/login".equals(uri)) {
                response = handleLogin(session, clientIP);
            } else if ("/auth/logout".equals(uri)) {
                response = handleLogout(session);
            } else if ("/auth/logoutAll".equals(uri)) {
                // Not a public path, so the auth gate already required a valid session
                RemoteAuthManager.getInstance(context).clearAllSessions();
                response = jsonResponse(Response.Status.OK,
                        new com.fadcam.streaming.model.AuthResponse(true, "All sessions logged out").toJson());
            } else if ("/auth/changePassword".equals(uri)) {
                response = handleChangePassword(session);
            } else if ("/torch/toggle".equals(uri)) {
                response = toggleTorch();
            } else if ("/recording/toggle".equals(uri)) {
                response = toggleRecording();
            } else if ("/recording/pause".equals(uri)) {
                response = pauseRecording();
            } else if ("/recording/resume".equals(uri)) {
                response = resumeRecording();
            } else if ("/config/recordingMode".equals(uri)) {
                response = setRecordingMode(session);
            } else if ("/config/streamQuality".equals(uri)) {
                response = setStreamQuality(session);
            } else if ("/config/batteryWarning".equals(uri)) {
                response = setBatteryWarning(session);
            } else if ("/config/videoCodec".equals(uri)) {
                response = setVideoCodec(session);
            } else if ("/audio/volume".equals(uri)) {
                response = setVolume(session);
            } else if ("/alarm/ring".equals(uri)) {
                response = ringAlarm(session);
            } else if ("/alarm/stop".equals(uri)) {
                response = stopAlarm();
            } else if ("/alarm/schedule".equals(uri)) {
                response = scheduleAlarm(session);
            } else if ("/alarm/schedule/cancel".equals(uri)) {
                response = cancelScheduledAlarm();
            } else if ("/camera/switch".equals(uri)) {
                response = switchCamera(session);
            } else if ("/config/zoom".equals(uri)) {
                response = setZoom(session);
            } else if ("/config/exposure".equals(uri)) {
                response = setExposure(session);
            } else if ("/config/mirror".equals(uri)) {
                response = setMirror(session);
            } else if ("/config/aeLock".equals(uri)) {
                response = toggleAeLock(session);
            } else if ("/api/notifications".equals(uri)) {
                response = handleNotifications(session);
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
            }
        } else if (Method.GET.equals(method)) {
            if ("/api/notifications".equals(uri)) {
                response = getNotifications();
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
            }
        } else {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Only GET, POST and OPTIONS requests supported");
        }

        return response;
    }
    
    /**
     * Serve HLS playlist (M3U8) with fragment references.
     */
    @NonNull
    private Response servePlaylist(@Nullable String queryToken) {
        // A player that can't send headers (native HLS) signs in with ?token=; the
        // init and segment links carry it too so their requests are let through.
        String tokenSuffix = queryToken == null ? ""
                : "?token=" + android.net.Uri.encode(queryToken);
        // Check if streaming is enabled at all
        if (!streamManager.isStreamingEnabled()) {
            FLog.w(TAG, "❌ Streaming is disabled - recording not started or streaming mode is DISABLED");
            String message = "❌ Streaming Disabled\n\n" +
                "Please start recording on FadCam with streaming enabled (STREAM_ONLY or STREAM_AND_SAVE mode).\n\n" +
                "Check /status endpoint for current state.";
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                message
            );
        }
        
        // Check if initialization segment is available
        byte[] initSegment = streamManager.getInitializationSegment();
        if (initSegment == null) {
            FLog.w(TAG, "❌ Init segment not available yet - recording just started or stopped");
            String message = "⏳ Stream Initializing\n\n" +
                "Recording is starting up. First fragments are being encoded.\n" +
                "This usually takes 2-3 seconds. Please wait...\n\n" +
                "Check /status endpoint for current state.";
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                message
            );
        }
        
        // Get buffered fragments
        java.util.List<RemoteStreamManager.FragmentData> fragments = streamManager.getBufferedFragments();
        
        if (fragments.isEmpty()) {
            FLog.w(TAG, "⏳ No fragments buffered yet - waiting for encoder output");
            String message = "⏳ Buffering Fragments\n\n" +
                "Init segment ready, waiting for first video fragments from encoder.\n" +
                "This usually takes 1-2 seconds. Please wait...\n\n" +
                "Check /status endpoint for current state.";
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                message
            );
        }
        
        // Track connection
        streamManager.incrementConnections();
        
        try {
            // Generate M3U8 playlist - CORRECT ORDER FOR fMP4
            StringBuilder m3u8 = new StringBuilder();
            m3u8.append("#EXTM3U\n");
            m3u8.append("#EXT-X-VERSION:7\n"); // fMP4 requires version 7
            m3u8.append("#EXT-X-INDEPENDENT-SEGMENTS\n"); // MUST come early
            
            // CRITICAL: Get buffered fragments before generating rest of playlist
            // Apple HLS spec requires minimum 6 segments, we use 8 for more buffer room
            java.util.List<RemoteStreamManager.FragmentData> stable = new java.util.ArrayList<>(fragments);
            int LIVE_WINDOW_SIZE = 8;
            java.util.List<RemoteStreamManager.FragmentData> liveEdge = new java.util.ArrayList<>();
            int startIdx = Math.max(0, stable.size() - LIVE_WINDOW_SIZE);
            for (int i = startIdx; i < stable.size(); i++) {
                liveEdge.add(stable.get(i));
            }

            m3u8.append("#EXT-X-TARGETDURATION:")
                .append(RemoteStreamManager.getTargetDurationSeconds(liveEdge))
                .append("\n");
            
            // Media sequence BEFORE map
            m3u8.append("#EXT-X-MEDIA-SEQUENCE:").append(liveEdge.get(0).sequenceNumber).append("\n");
            
            // INIT SEGMENT - MUST be declared before fragments
            m3u8.append("#EXT-X-MAP:URI=\"/init.mp4").append(tokenSuffix).append("\"\n");

            // Professional live streaming: minimum 2 fragments buffered before serving (lowered to reduce startup delay)
            if (fragments.size() < 2) {
                FLog.d(TAG, "⏳ Buffering... Only " + fragments.size() + "/2 fragments available");
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Buffering stream... Please wait (" + fragments.size() + "/2 fragments ready)"
                );
            }
            
            // Add fragments to playlist
            for (RemoteStreamManager.FragmentData fragment : liveEdge) {
                m3u8.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", fragment.getDurationSeconds())).append(",\n");
                m3u8.append("/seg-").append(fragment.sequenceNumber).append(".m4s")
                    .append(tokenSuffix).append("\n");
            }
            
            // No per-request logging: players fetch the playlist every couple of seconds.
            Response response = newFixedLengthResponse(
                Response.Status.OK,
                // NanoHTTPD writes this as the Content-Type; adding another header sent two
                "application/vnd.apple.mpegurl; charset=utf-8",
                m3u8.toString()
            );
            
            // ULTRA-AGGRESSIVE cache prevention for HLS playlist
            // Prevents browser/HLS.js from serving stale playlists with old fragment numbers
            response.addHeader("Content-Disposition", "inline");
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0, s-maxage=0");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
            response.addHeader("ETag", String.valueOf(System.currentTimeMillis())); // Force unique response each time
            response.addHeader("Last-Modified", new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).format(new java.util.Date()));

            return response;
            
        } finally {
            streamManager.decrementConnections();
        }
    }
    
    /**
     * Serve initialization segment (ftyp + moov).
     */
    @NonNull
    private Response serveInitSegment(String clientIP) {
        byte[] initSegment = streamManager.getInitializationSegment();
        
        if (initSegment == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Initialization segment not available yet"
            );
        }
        
        streamManager.incrementConnections();
        
        try {
            // Check if this is first request from this client
            ClientMetrics metrics = streamManager.getClientMetrics(clientIP);
            boolean isFirstRequest = (metrics == null || metrics.getTotalBytesServed() == 0);
            
            // Track data served to this client
            streamManager.addDataServed(clientIP, initSegment.length);
            
            // Log first request event
            if (isFirstRequest) {
                streamManager.logClientEvent(new ClientEvent(
                    clientIP,
                    ClientEvent.EventType.CONNECTED,
                    "First request - init segment"
                ));
            }
            
            InputStream initStream = new java.io.ByteArrayInputStream(initSegment);
            Response response = newFixedLengthResponse(Response.Status.OK, "video/mp4", initStream, initSegment.length);
            // CRITICAL: Do NOT cache init segment - it changes on each recording start/restart!
            // If cached, browser uses old init with new fragments = decode failure
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
            response.addHeader("Content-Length", String.valueOf(initSegment.length));
            
            return response;
            
        } finally {
            streamManager.decrementConnections();
        }
    }
    
    /**
     * Serve individual fMP4 fragment (moof + mdat).
     * URI format: /seg-{sequenceNumber}.m4s
     */
    @NonNull
    private Response serveFragment(String uri, String clientIP) {
        try {
            // Extract sequence number from URI: "/seg-123.m4s" -> 123
            String seqNumStr = uri.substring(5, uri.length() - 4); // Remove "/seg-" and ".m4s"
            int sequenceNumber = Integer.parseInt(seqNumStr);
            
            int oldest = streamManager.getOldestSequenceNumber();
            int latest = streamManager.getLatestSequenceNumber();

            // Reject clearly stale requests early (e.g., cached player asking for old segments)
            if (sequenceNumber < oldest || sequenceNumber > latest) {
                FLog.w(TAG, "❌ Fragment #" + sequenceNumber + " outside window [" + oldest + ", " + latest + "] - treating as stale request");
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Fragment outside live window");
            }

            RemoteStreamManager.FragmentData fragment = streamManager.getFragment(sequenceNumber);
            
            if (fragment == null) {
                FLog.w(TAG, "Fragment #" + sequenceNumber + " not found in buffer");
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Fragment not found");
            }
            
            streamManager.incrementConnections();
            
            try {
                // Get previous data before adding new
                ClientMetrics prevMetrics = streamManager.getClientMetrics(clientIP);
                long previousData = prevMetrics != null ? prevMetrics.getTotalBytesServed() : 0;
                long previousMB = previousData / (1024 * 1024);
                
                // Track new data served
                streamManager.addDataServed(clientIP, fragment.sizeBytes);
                
                // Get new total
                ClientMetrics newMetrics = streamManager.getClientMetrics(clientIP);
                long newData = newMetrics != null ? newMetrics.getTotalBytesServed() : 0;
                long newMB = newData / (1024 * 1024);
                
                // Log milestone every 10MB served to this client
                if (newMB > 0 && previousMB / 10 < newMB / 10) {
                    streamManager.logClientEvent(new ClientEvent(
                        clientIP,
                        ClientEvent.EventType.DATA_MILESTONE,
                        newMB + " MB served"
                    ));
                }
                
                // Serve fragment bytes
                InputStream fragmentStream = new java.io.ByteArrayInputStream(fragment.data);
                Response response = newFixedLengthResponse(Response.Status.OK, "video/mp4", fragmentStream, fragment.sizeBytes);
                // Never allow fragment caching; stale caches caused old video playback
                response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                response.addHeader("Pragma", "no-cache");
                response.addHeader("Expires", "0");
                response.addHeader("Content-Length", String.valueOf(fragment.sizeBytes));
                
                return response;
                
            } finally {
                streamManager.decrementConnections();
            }
            
        } catch (NumberFormatException e) {
            FLog.e(TAG, "Invalid sequence number in URI: " + uri, e);
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid fragment ID");
        }
    }
    
    /**
     * Handle torch toggle request from web interface.
     * Mirrors TorchToggleActivity logic: checks if recording is active and routes appropriately.
     */
    @NonNull
    private Response toggleTorch() {
        try {
            FLog.i(TAG, "🔦 Torch toggle requested via web interface");

            // Toggle state in RemoteStreamManager
            RemoteStreamManager manager = RemoteStreamManager.getInstance();
            boolean newState = !manager.isTorchOn();
            manager.setTorchState(newState);

            // Always delegate to TorchService.
            // TorchService internally routes to RecordingService (CaptureRequest) if recording
            // is active, and falls back to CameraManager.setTorchMode() otherwise.
            // Sending TOGGLE_RECORDING_TORCH directly to RecordingService fails when there is
            // no active capture session (e.g. stream_only mode without recording).
            android.content.Intent intent = new android.content.Intent(
                    context, com.fadcam.services.TorchService.class);
            intent.setAction(com.fadcam.Constants.INTENT_ACTION_TOGGLE_TORCH);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }

            FLog.i(TAG, "✅ Torch toggle intent sent to TorchService. New state: " + newState);
            
            String responseJson = String.format(java.util.Locale.US, "{\"status\": \"success\", \"torch_state\": %s}", newState);
            return jsonResponse(Response.Status.OK, responseJson);
        } catch (Exception e) {
            FLog.e(TAG, "Error toggling torch", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, errorJson(e.getMessage()));
        }
    }

    /**
     * Handle GET /audio/volume - Get current device volume level
     */
    @NonNull
    private Response getVolume() {
        try {
            FLog.i(TAG, "🔊 Volume status requested via web interface");
            
            android.media.AudioManager audioManager = (android.media.AudioManager) context.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (audioManager == null) {
                FLog.e(TAG, "AudioManager not available");
                return jsonResponse(Response.Status.INTERNAL_ERROR, 
                    "{\"status\": \"error\", \"message\": \"AudioManager not available\"}");
            }
            
            int currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            
            // Update RemoteStreamManager state
            RemoteStreamManager manager = RemoteStreamManager.getInstance();
            manager.setMediaVolume(currentVolume);
            
            String responseJson = String.format(java.util.Locale.US,
                "{\"status\": \"success\", \"volume\": %d, \"max_volume\": %d, \"percentage\": %.1f}",
                currentVolume, maxVolume, (currentVolume * 100.0f / maxVolume)
            );
            
            FLog.i(TAG, "✅ Volume retrieved: " + currentVolume + "/" + maxVolume);
            
            return jsonResponse(Response.Status.OK, responseJson);
        } catch (Exception e) {
            FLog.e(TAG, "Error getting volume", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                errorJson(e.getMessage()));
        }
    }

    /**
     * Handle POST /audio/volume - Set device volume level
     * Expects JSON body: {"volume": 0-15} or {"percentage": 0-100}
     */
    @NonNull
    private Response setVolume(IHTTPSession session) {
        try {
            FLog.i(TAG, "🔊 Volume change requested via web interface");
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            
            String postData = files.get("postData");
            if (postData == null || postData.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Missing request body\"}");
            }
            
            // Parse JSON to extract volume or percentage
            org.json.JSONObject json = new org.json.JSONObject(postData);
            
            android.media.AudioManager audioManager = (android.media.AudioManager) context.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (audioManager == null) {
                FLog.e(TAG, "AudioManager not available");
                return jsonResponse(Response.Status.INTERNAL_ERROR, 
                    "{\"status\": \"error\", \"message\": \"AudioManager not available\"}");
            }
            
            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int targetVolume;
            
            if (json.has("volume")) {
                // Direct volume level (0-maxVolume)
                targetVolume = json.getInt("volume");
                targetVolume = Math.max(0, Math.min(targetVolume, maxVolume));
            } else if (json.has("percentage")) {
                // Percentage (0-100)
                float percentage = (float) json.getDouble("percentage");
                percentage = Math.max(0, Math.min(percentage, 100));
                targetVolume = Math.round(percentage * maxVolume / 100.0f);
            } else {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Missing 'volume' or 'percentage' field\"}");
            }
            
            // Set the volume
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC, 
                targetVolume, 
                0  // No UI flags (silent change)
            );
            
            // Update RemoteStreamManager state
            RemoteStreamManager manager = RemoteStreamManager.getInstance();
            manager.setMediaVolume(targetVolume);
            
            int actualVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            float actualPercentage = actualVolume * 100.0f / maxVolume;
            
            FLog.i(TAG, "✅ Volume set to: " + actualVolume + "/" + maxVolume + " (" + String.format(java.util.Locale.US, "%.1f", actualPercentage) + "%)");
            
            String responseJson = String.format(java.util.Locale.US,
                "{\"status\": \"success\", \"volume\": %d, \"max_volume\": %d, \"percentage\": %.1f}",
                actualVolume, maxVolume, actualPercentage
            );
            
            return jsonResponse(Response.Status.OK, responseJson);
        } catch (org.json.JSONException e) {
            FLog.e(TAG, "Invalid JSON in volume request", e);
            return jsonResponse(Response.Status.BAD_REQUEST, 
                errorJson("Invalid JSON: " + e.getMessage()));
        } catch (Exception e) {
            FLog.e(TAG, "Error setting volume", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                errorJson(e.getMessage()));
        }
    }

    /**
     * Handle POST /recording/toggle - Start/Stop recording based on current state.
     * Mirrors torch pattern: checks if recording is already in progress.
     */
    @NonNull
    private Response toggleRecording() {
        try {
            FLog.i(TAG, "⏹️ Recording toggle requested via web interface");
            
            // A live session (paused included) only counts while a recording service is in the
            // foreground: the raw flag survives a process kill and is also set by dual recordings
            boolean isRecording = com.fadcam.RecordingShortcutDispatcher.hasActiveSession(context);

            android.content.Intent intent;
            if (isRecording) {
                // Stop recording (also when paused: the web card's Stop expects a stop; Resume has
                // /recording/resume). Goes to the dual service when that owns the session.
                intent = com.fadcam.RecordingShortcutDispatcher.stopIntent(context);
                FLog.d(TAG, "Recording in progress - sending STOP_RECORDING intent");
            } else {
                // Start recording
                intent = new android.content.Intent(context, com.fadcam.services.RecordingService.class);
                intent.setAction(com.fadcam.Constants.INTENT_ACTION_START_RECORDING);
                FLog.d(TAG, "Recording not in progress - sending START_RECORDING intent");
            }
            
            // Start/stop action dispatch via unified policy.
            ServiceStartPolicy.startRecordingAction(context, intent);
            
            boolean stopping = isRecording;
            String action = stopping ? "stop" : "start";
            String responseJson = String.format(java.util.Locale.US, "{\"status\": \"success\", \"action\": \"%s\", \"isRecording\": %s, \"isPaused\": false}", 
                action, !stopping);
            
            FLog.i(TAG, "✅ Recording " + action + " intent sent");
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", responseJson);
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            FLog.e(TAG, "Error toggling recording", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", 
                errorJson(e.getMessage()));
        }
    }

    /**
     * POST /recording/pause - Pause the current recording.
     */
    @NonNull
    private Response pauseRecording() {
        try {
            FLog.i(TAG, "⏸️ Recording pause requested via web interface");
            
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);
            boolean isRecording = spManager.isRecordingInProgress();
            
            if (!isRecording) {
                return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                    "{\"status\": \"error\", \"message\": \"Not currently recording\"}");
            }
            
            android.content.Intent intent = new android.content.Intent(context, com.fadcam.services.RecordingService.class);
            intent.setAction(com.fadcam.Constants.INTENT_ACTION_PAUSE_RECORDING);
            ServiceStartPolicy.startRecordingAction(context, intent);
            
            FLog.i(TAG, "✅ Pause recording intent sent");
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                "{\"status\": \"success\", \"action\": \"pause\", \"isRecording\": true, \"isPaused\": true}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            FLog.e(TAG, "Error pausing recording", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8",
                errorJson(e.getMessage()));
        }
    }

    /**
     * POST /recording/resume - Resume the paused recording.
     */
    @NonNull
    private Response resumeRecording() {
        try {
            FLog.i(TAG, "▶️ Recording resume requested via web interface");
            
            boolean isPaused = RemoteStreamManager.getInstance().isPaused();
            
            if (!isPaused) {
                return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                    "{\"status\": \"error\", \"message\": \"Recording is not paused\"}");
            }
            
            android.content.Intent intent = new android.content.Intent(context, com.fadcam.services.RecordingService.class);
            intent.setAction(com.fadcam.Constants.INTENT_ACTION_RESUME_RECORDING);
            ServiceStartPolicy.startRecordingAction(context, intent);
            
            FLog.i(TAG, "✅ Resume recording intent sent");
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                "{\"status\": \"success\", \"action\": \"resume\", \"isRecording\": true, \"isPaused\": false}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            FLog.e(TAG, "Error resuming recording", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8",
                errorJson(e.getMessage()));
        }
    }

    /**
     * Handle POST /config/recordingMode - Set recording mode
     */
    @NonNull
    private Response setRecordingMode(IHTTPSession session) {
        try {
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"No body\"}");
            }
            
            // Parse JSON mode from body
            String mode = null;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                mode = json.getString("mode");
            } catch (Exception e) {
                FLog.e(TAG, "Failed to parse recording mode JSON", e);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Invalid JSON\"}");
            }
            
            // Convert mode string to StreamingMode enum
            RemoteStreamManager.StreamingMode streamingMode;
            if ("stream_only".equals(mode)) {
                streamingMode = RemoteStreamManager.StreamingMode.STREAM_ONLY;
            } else if ("stream_and_save".equals(mode)) {
                streamingMode = RemoteStreamManager.StreamingMode.STREAM_AND_SAVE;
            } else {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"error\": \"Invalid mode. Use 'stream_only' or 'stream_and_save'\"}");
            }
            
            // Set the mode in both RemoteStreamManager and preferences
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);
            spManager.setStreamingMode(streamingMode);
            streamManager.setStreamingMode(streamingMode);
            
            FLog.i(TAG, "✅ Recording mode set to: " + mode);
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", 
                "{\"status\": \"success\", \"message\": \"Recording mode set to " + mode + "\"}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            FLog.e(TAG, "Error setting recording mode", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", errorJson(e.getMessage()));
        }
    }

    /**
     * Handle POST /config/streamQuality - Set stream quality
     */
    @NonNull
    private Response setStreamQuality(IHTTPSession session) {
        try {
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"No body\"}");
            }
            
            // Parse JSON quality from body
            String quality = null;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                quality = json.getString("quality");
            } catch (Exception e) {
                FLog.e(TAG, "Failed to parse stream quality JSON", e);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Invalid JSON\"}");
            }
            
            // Convert string to preset and apply
            try {
                StreamQuality.Preset preset = StreamQuality.Preset.valueOf(quality.trim().toUpperCase(java.util.Locale.ROOT));
                streamManager.setStreamQuality(preset, context);
            } catch (IllegalArgumentException e) {
                FLog.e(TAG, "Invalid stream quality preset: " + quality);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Invalid quality preset\"}");
            }
            
            FLog.i(TAG, "✅ Stream quality set to: " + quality);
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", 
                "{\"status\": \"success\", \"message\": \"Stream quality set to " + quality + "\"}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            FLog.e(TAG, "Error setting stream quality", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", errorJson(e.getMessage()));
        }
    }

    /**
     * Handle POST /config/batteryWarning - Set battery warning threshold
     */
    @NonNull
    private Response setBatteryWarning(IHTTPSession session) {
        try {
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                FLog.w(TAG, "❌ Battery warning: No body received");
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"No body\"}");
            }
            
            FLog.d(TAG, "[Battery] Received body: " + body);
            
            // Parse JSON threshold from body
            int threshold = 20; // Default
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                threshold = json.getInt("threshold");
                FLog.d(TAG, "[Battery] Parsed threshold: " + threshold);
            } catch (Exception e) {
                FLog.e(TAG, "Failed to parse battery warning threshold JSON", e);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Invalid JSON\"}");
            }
            
            // Validate threshold
            if (threshold < 5 || threshold > 100) {
                FLog.w(TAG, "❌ Battery warning: Invalid threshold " + threshold);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Threshold must be between 5 and 100\"}");
            }
            
            // Store battery warning threshold using SharedPreferencesManager
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);
            spManager.setBatteryWarningThreshold(threshold);
            FLog.i(TAG, "✅ Battery warning threshold set to: " + threshold + "%");
            
            // Verify it was stored
            int storedValue = spManager.getBatteryWarningThreshold();
            FLog.d(TAG, "[Battery] Verified stored value: " + storedValue);
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", 
                "{\"status\": \"success\", \"message\": \"Battery warning set to " + threshold + "%\"}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            FLog.e(TAG, "Error setting battery warning", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", errorJson(e.getMessage()));
        }
    }

    
    // START: VideoCodec Config Endpoint
    @NonNull
    private Response setVideoCodec(IHTTPSession session) {
        try {
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"status\": \"error\", \"message\": \"Missing request body\"}");
            }
            
            // Parse JSON: {"codec": "AVC"} or {"codec": "HEVC"}
            String codec = null;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                codec = json.getString("codec");
            } catch (Exception e) {
                FLog.e(TAG, "Failed to parse video codec JSON", e);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"status\": \"error\", \"message\": \"Invalid JSON\"}");
            }
            
            if (codec == null || codec.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"status\": \"error\", \"message\": \"Missing or invalid codec field\"}");
            }
            
            // Validate codec is one of the supported values
            codec = codec.trim().toUpperCase();
            if (!codec.equals("AVC") && !codec.equals("HEVC")) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"status\": \"error\", \"message\": \"Invalid codec. Must be AVC or HEVC\"}");
            }
            
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);
            
            // Store in SharedPreferences
            android.content.SharedPreferences.Editor editor = spManager.sharedPreferences.edit();
            editor.putString(com.fadcam.Constants.PREF_VIDEO_CODEC, codec);
            editor.apply();
            
            FLog.i(TAG, "✅ Video codec set to: " + codec);
            
            // Verify it was stored
            String storedCodec = spManager.getVideoCodec().toString();
            FLog.d(TAG, "[VideoCodec] Verified stored codec: " + storedCodec);
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", 
                "{\"status\": \"success\", \"message\": \"Video codec set to " + codec + "\"}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            FLog.e(TAG, "Error setting video codec", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", 
                errorJson(e.getMessage()));
        }
    }
    // END: VideoCodec Config Endpoint

    /** POST /camera/switch – switch active camera (BACK ↔ FRONT or explicit target). */
    @NonNull
    private Response switchCamera(IHTTPSession session) {
        try {
            com.fadcam.SharedPreferencesManager spManager =
                    com.fadcam.SharedPreferencesManager.getInstance(context);
            com.fadcam.CameraType current = spManager.getCameraSelection();

            // Parse optional body for explicit "to" target
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            String body = files.get("postData");

            com.fadcam.CameraType target;
            if (body != null && !body.isEmpty()) {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(body);
                    String toStr = json.optString("to", "").trim().toUpperCase();
                    com.fadcam.CameraType parsed = null;
                    try { parsed = com.fadcam.CameraType.valueOf(toStr); }
                    catch (IllegalArgumentException ignored) {}
                    target = (parsed != null) ? parsed
                            : (current == com.fadcam.CameraType.BACK
                                    ? com.fadcam.CameraType.FRONT
                                    : com.fadcam.CameraType.BACK);
                } catch (Exception e) {
                    target = (current == com.fadcam.CameraType.BACK)
                            ? com.fadcam.CameraType.FRONT
                            : com.fadcam.CameraType.BACK;
                }
            } else {
                target = (current == com.fadcam.CameraType.BACK)
                        ? com.fadcam.CameraType.FRONT
                        : com.fadcam.CameraType.BACK;
            }

            if (spManager.isRecordingInProgress()) {
                android.content.Intent intent = new android.content.Intent(
                        context, com.fadcam.services.RecordingService.class);
                intent.setAction(com.fadcam.Constants.INTENT_ACTION_SWITCH_CAMERA);
                intent.putExtra(com.fadcam.Constants.INTENT_EXTRA_CAMERA_TYPE_SWITCH, target.toString());
                com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, intent);
                FLog.i(TAG, "✅ Camera switch (live): " + current + " → " + target);
            } else {
                spManager.sharedPreferences.edit()
                        .putString(com.fadcam.Constants.PREF_CAMERA_SELECTION, target.toString())
                        .apply();
                // Notify HomeFragment so it can restart preview and update mirror button
                android.content.Intent broadcastIntent = new android.content.Intent(
                        com.fadcam.Constants.BROADCAST_ON_CAMERA_SWITCH_COMPLETE);
                broadcastIntent.putExtra(com.fadcam.Constants.BROADCAST_EXTRA_CAMERA_TYPE_FROM,
                        current.toString());
                broadcastIntent.putExtra(com.fadcam.Constants.BROADCAST_EXTRA_CAMERA_TYPE_TO,
                        target.toString());
                context.sendBroadcast(broadcastIntent.setPackage(context.getPackageName()));
                FLog.i(TAG, "✅ Camera switch (preference + broadcast): " + current + " → " + target);
            }

            return jsonResponse(Response.Status.OK,
                    "{\"status\": \"success\", \"camera\": \"" + target.toString().toLowerCase() + "\"}");
        } catch (Exception e) {
            FLog.e(TAG, "Error switching camera", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                    errorJson(e.getMessage()));
        }
    }

    /** POST /config/zoom – set zoom ratio and optional pan {ratio, panX, panY}. */
    @NonNull
    private Response setZoom(IHTTPSession session) {
        try {
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, "{\"error\": \"No body\"}");
            }

            org.json.JSONObject json = new org.json.JSONObject(body);
            float ratio = (float) json.optDouble("ratio", 1.0);
            boolean hasPan = json.has("panX") || json.has("panY");
            float requestedPanX = hasPan
                    ? Math.max(-1f, Math.min(1f, (float) json.optDouble("panX", 0.0)))
                    : 0.0f;
            float requestedPanY = hasPan
                    ? Math.max(-1f, Math.min(1f, (float) json.optDouble("panY", 0.0)))
                    : 0.0f;

            com.fadcam.SharedPreferencesManager spManager =
                    com.fadcam.SharedPreferencesManager.getInstance(context);
            com.fadcam.CameraType cam = spManager.getCameraSelection();
            float maxZoomRatio = spManager.getMaxSupportedZoomRatio(cam);
            ratio = Math.max(0.5f, Math.min(maxZoomRatio, ratio));
            if (ratio <= 1.0f) {
                requestedPanX = 0.0f;
                requestedPanY = 0.0f;
                hasPan = true;
            }

            // Persist immediately so /status becomes the single source of truth even before the
            // recording service finishes applying the request.
            spManager.setSpecificZoomRatio(cam, ratio);
            float effectivePanX = spManager.getSpecificPanX(cam);
            float effectivePanY = spManager.getSpecificPanY(cam);
            if (hasPan) {
                spManager.setSpecificPan(cam, requestedPanX, requestedPanY);
                effectivePanX = requestedPanX;
                effectivePanY = requestedPanY;
            }

            RemoteStreamManager.getInstance().invalidateStatusCache();

            if (spManager.isRecordingInProgress()) {
                android.content.Intent intent = new android.content.Intent(
                        context, com.fadcam.services.RecordingService.class);
                intent.setAction(com.fadcam.Constants.INTENT_ACTION_SET_ZOOM_RATIO);
                intent.putExtra(com.fadcam.Constants.EXTRA_ZOOM_RATIO, ratio);
                if (hasPan) {
                    intent.putExtra(com.fadcam.Constants.EXTRA_PAN_X, requestedPanX);
                    intent.putExtra(com.fadcam.Constants.EXTRA_PAN_Y, requestedPanY);
                }
                com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, intent);
                FLog.i(TAG, "✅ Zoom dispatched (live): ratio=" + ratio + " pan=" + effectivePanX + "," + effectivePanY);
            } else {
                FLog.i(TAG, "✅ Zoom saved (preference): ratio=" + ratio);
            }

            android.content.Intent zoomBcast = new android.content.Intent(com.fadcam.Constants.BROADCAST_ON_ZOOM_CHANGED);
            zoomBcast.putExtra(com.fadcam.Constants.EXTRA_BROADCAST_ZOOM_RATIO, ratio);
            zoomBcast.putExtra(com.fadcam.Constants.EXTRA_BROADCAST_PAN_X, effectivePanX);
            zoomBcast.putExtra(com.fadcam.Constants.EXTRA_BROADCAST_PAN_Y, effectivePanY);
            context.sendBroadcast(zoomBcast.setPackage(context.getPackageName()));

            return jsonResponse(
                    Response.Status.OK,
                    String.format(
                            java.util.Locale.US,
                            "{\"status\": \"success\", \"ratio\": %.2f, \"panX\": %.3f, \"panY\": %.3f}",
                            ratio,
                            effectivePanX,
                            effectivePanY));
        } catch (Exception e) {
            FLog.e(TAG, "Error setting zoom", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                    errorJson(e.getMessage()));
        }
    }

    /** POST /config/exposure – set exposure compensation using the active device AE range. */
    @NonNull
    private Response setExposure(IHTTPSession session) {
        try {
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                FLog.w(TAG, "❌ [/config/exposure] Empty or missing body");
                return jsonResponse(Response.Status.BAD_REQUEST, "{\"error\": \"No body\"}");
            }

            org.json.JSONObject json = new org.json.JSONObject(body);
            com.fadcam.SharedPreferencesManager spMgr = com.fadcam.SharedPreferencesManager.getInstance(context);
            int exposureMin = spMgr != null ? spMgr.getExposureCompensationMin() : -12;
            int exposureMax = spMgr != null ? spMgr.getExposureCompensationMax() : 12;
            int ev = Math.max(exposureMin, Math.min(exposureMax, json.optInt("ev", 0)));
            FLog.i(TAG, "→ [/config/exposure] Received ev: " + ev);

            // CRITICAL: Save exposure to SharedPreferences so status endpoint returns correct value
            if (spMgr != null) {
                try {
                    spMgr.setSavedExposureCompensation(ev);
                    // Verify write was successful
                    int verify = spMgr.getSavedExposureCompensation();
                    FLog.i(TAG, "💾 Exposure saved: " + ev + ", verified read-back: " + verify);
                    if (verify != ev) {
                        FLog.w(TAG, "⚠️ Exposure verification FAILED! Wrote: " + ev + ", Read: " + verify);
                    }
                } catch (Exception e) {
                    FLog.e(TAG, "❌ Error saving exposure to SharedPreferences: " + e.getMessage(), e);
                }
            } else {
                FLog.e(TAG, "❌ SharedPreferencesManager is null!");
            }

                android.content.Intent exposureBroadcast = new android.content.Intent(
                    com.fadcam.Constants.BROADCAST_ON_EXPOSURE_CHANGED);
                exposureBroadcast.putExtra(
                    com.fadcam.Constants.EXTRA_BROADCAST_EXPOSURE_COMPENSATION,
                    ev);
                context.sendBroadcast(exposureBroadcast.setPackage(context.getPackageName()));

            android.content.Intent intent = new android.content.Intent(
                    context, com.fadcam.services.RecordingService.class);
            intent.setAction(com.fadcam.Constants.INTENT_ACTION_SET_EXPOSURE_COMPENSATION);
            intent.putExtra(com.fadcam.Constants.EXTRA_EXPOSURE_COMPENSATION, ev);
            com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, intent);
            FLog.i(TAG, "✅ Exposure compensation intent sent: " + ev);
            
            // CRITICAL: Invalidate status cache so next /status request returns new exposure value
            FLog.d(TAG, "🗑️ [setExposure] Invalidating status cache due to exposure change from " + json.optInt("ev", -999) + " to " + ev);
            RemoteStreamManager.getInstance().invalidateStatusCache();

            // Compute display EV value for web confirmation (so web doesn't have to guess)
            float stepValue = (spMgr != null) ? spMgr.getExposureCompensationStep() : 0.33f;
            float displayEv = ev * stepValue;
            
            // Return actual display value so web can confirm immediately without unit confusion
            return jsonResponse(Response.Status.OK, "{\"status\": \"success\", \"ev\": " + ev + ", \"evDisplay\": " + String.format(java.util.Locale.US, "%.2f", displayEv) + "}");
        } catch (Exception e) {
            FLog.e(TAG, "❌ [/config/exposure] Exception: " + e.getMessage(), e);
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                    errorJson(e.getMessage()));
        }
    }

    /** POST /config/mirror – toggle or set front-camera mirror {enabled: bool}. */
    @NonNull
    private Response setMirror(IHTTPSession session) {
        try {
            com.fadcam.SharedPreferencesManager spManager =
                    com.fadcam.SharedPreferencesManager.getInstance(context);

            boolean enabled;
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            String body = files.get("postData");

            if (body != null && !body.isEmpty()) {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(body);
                    if (json.has("enabled")) {
                        enabled = json.getBoolean("enabled");
                    } else {
                        enabled = !spManager.isFrontVideoMirrorEnabled();
                    }
                } catch (Exception e) {
                    enabled = !spManager.isFrontVideoMirrorEnabled();
                }
            } else {
                enabled = !spManager.isFrontVideoMirrorEnabled();
            }

            // CRITICAL: Save mirror state to SharedPreferences so status endpoint returns correct value
            if (spManager != null) {
                spManager.setFrontVideoMirrorEnabled(enabled);
                FLog.i(TAG, "💾 Mirror state saved to SharedPreferences: " + enabled);
            }

            android.content.Intent intent = new android.content.Intent(
                    context, com.fadcam.services.RecordingService.class);
            intent.setAction(com.fadcam.Constants.INTENT_ACTION_SET_FRONT_VIDEO_MIRROR);
            intent.putExtra(com.fadcam.Constants.EXTRA_FRONT_VIDEO_MIRROR_ENABLED, enabled);
            com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, intent);
            FLog.i(TAG, "✅ Front mirror set to: " + enabled);

            return jsonResponse(Response.Status.OK,
                    "{\"status\": \"success\", \"mirrorEnabled\": " + enabled + "}");
        } catch (Exception e) {
            FLog.e(TAG, "Error setting mirror", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                    errorJson(e.getMessage()));
        }
    }

    /** POST /config/aeLock – toggle AE lock on/off. */
    @NonNull
    private Response toggleAeLock(IHTTPSession session) {
        try {
            // CRITICAL: Consume the POST body to keep HTTP stream clean
            // Without this, the next request may fail with 400 Bad Request
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            
            com.fadcam.SharedPreferencesManager spManager =
                    com.fadcam.SharedPreferencesManager.getInstance(context);
            if (spManager == null) {
                FLog.e(TAG, "❌ SharedPreferencesManager is null in toggleAeLock!");
                return jsonResponse(Response.Status.INTERNAL_ERROR,
                        "{\"status\": \"error\", \"message\": \"SharedPreferencesManager unavailable\"}");
            }
            
            boolean newLocked = !spManager.isAeLockedSaved();

            // CRITICAL: Save AE lock state to SharedPreferences so status endpoint returns correct value
            try {
                spManager.setSavedAeLock(newLocked);
                // Verify write was successful
                boolean verify = spManager.isAeLockedSaved();
                FLog.i(TAG, "💾 AE lock saved: " + newLocked + ", verified read-back: " + verify);
                if (verify != newLocked) {
                    FLog.w(TAG, "⚠️ AE lock verification FAILED! Wrote: " + newLocked + ", Read: " + verify);
                }
            } catch (Exception e) {
                FLog.e(TAG, "❌ Error saving AE lock to SharedPreferences: " + e.getMessage(), e);
            }

            android.content.Intent intent = new android.content.Intent(
                    context, com.fadcam.services.RecordingService.class);
            intent.setAction(com.fadcam.Constants.INTENT_ACTION_TOGGLE_AE_LOCK);
            intent.putExtra(com.fadcam.Constants.EXTRA_AE_LOCK, newLocked);
            com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, intent);
            FLog.i(TAG, "✅ AE lock set to: " + newLocked);
            
            // CRITICAL: Invalidate status cache so next /status request returns new AE lock state
            RemoteStreamManager.getInstance().invalidateStatusCache();

            return jsonResponse(Response.Status.OK,
                    "{\"status\": \"success\", \"aeLockEnabled\": " + newLocked + "}");
        } catch (Exception e) {
            FLog.e(TAG, "❌ [/config/aeLock] Exception: " + e.getMessage(), e);
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                    errorJson(e.getMessage()));
        }
    }

    @NonNull
    private Response serveStatus() {
        try {
            String statusJson = streamManager.getStatusJson();
            return jsonResponse(Response.Status.OK, statusJson);
        } catch (Exception e) {
            FLog.e(TAG, "❌ [/status] Error in serveStatus: " + e.getMessage(), e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, "{\"error\": \"Failed to generate status\"}");
        }
    }
    
    // START: Authentication Endpoints
    /** Failed sign-ins per client IP: [failures since last lockout, locked-until elapsedRealtime]. */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> LOGIN_FAILURES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int LOGIN_MAX_FAILURES = 5;
    private static final long LOGIN_LOCKOUT_MS = 30_000L;

    /**
     * Handle POST /auth/login - Authenticate and return session token
     */
    @NonNull
    private Response handleLogin(IHTTPSession session, String clientIP) {
        try {
            RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
            
            // If auth is disabled, return success without token
            if (!authManager.isAuthEnabled()) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.authDisabled();
                return jsonResponse(Response.Status.OK, authResponse.toJson());
            }
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            String body = files.get("postData");
            
            if (body == null || body.isEmpty()) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Missing request body");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            // Extract password from JSON
            String password = null;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                password = json.getString("password");
                // Trim whitespace from password
                if (password != null) {
                    password = password.trim();
                }
            } catch (Exception e) {
                FLog.e(TAG, "Failed to parse login JSON", e);
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Invalid JSON");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            // Brute-force guard: passwords can be 4 characters and /auth/login is public on the LAN
            String loginKey = clientIP != null ? clientIP : "unknown";
            long now = android.os.SystemClock.elapsedRealtime();
            // One lock around check, verify and count: NanoHTTPD runs a thread per
            // connection, so parallel guesses all passed the check before any was counted
            synchronized (LOGIN_FAILURES) {
            long[] attempts = LOGIN_FAILURES.get(loginKey);
            if (attempts != null && attempts[1] > now) {
                long waitSec = (attempts[1] - now + 999) / 1000;
                return jsonResponse(Response.Status.UNAUTHORIZED, com.fadcam.streaming.model.AuthResponse
                        .failure("Too many attempts. Try again in " + waitSec + " s").toJson());
            }

            // Verify password
            if (!authManager.verifyPassword(password)) {
                FLog.w(TAG, "Invalid login attempt from " + clientIP + " (password mismatch)");
                if (attempts == null) {
                    attempts = new long[2];
                    LOGIN_FAILURES.put(loginKey, attempts);
                }
                if (++attempts[0] >= LOGIN_MAX_FAILURES) {
                    attempts[0] = 0;
                    attempts[1] = now + LOGIN_LOCKOUT_MS;
                }
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.invalidCredentials();
                return jsonResponse(Response.Status.UNAUTHORIZED, authResponse.toJson());
            }
            
            LOGIN_FAILURES.remove(loginKey);
            }

            // Create session
            String userAgent = session.getHeaders().get("user-agent");
            String deviceInfo = clientIP + " - " + (userAgent != null ? userAgent : "unknown");
            com.fadcam.streaming.model.SessionToken token = authManager.createSession(deviceInfo);
            
            FLog.i(TAG, "✅ Successful login from " + clientIP);
            
            com.fadcam.streaming.model.AuthResponse authResponse = 
                com.fadcam.streaming.model.AuthResponse.success(token.getToken(), token.getExpiresAtMs());
            return jsonResponse(Response.Status.OK, authResponse.toJson());
            
        } catch (Exception e) {
            FLog.e(TAG, "Error handling login", e);
            com.fadcam.streaming.model.AuthResponse authResponse = 
                com.fadcam.streaming.model.AuthResponse.failure("Internal error");
            return jsonResponse(Response.Status.INTERNAL_ERROR, authResponse.toJson());
        }
    }
    
    /**
     * Handle POST /auth/logout - Invalidate session token
     */
    @NonNull
    private Response handleLogout(IHTTPSession session) {
        try {
            String token = extractAuthToken(session);
            if (token != null) {
                RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
                authManager.revokeSession(token);
                FLog.i(TAG, "Session logged out");
            }
            
            com.fadcam.streaming.model.AuthResponse authResponse = 
                new com.fadcam.streaming.model.AuthResponse(true, "Logged out successfully");
            return jsonResponse(Response.Status.OK, authResponse.toJson());
        } catch (Exception e) {
            FLog.e(TAG, "Error handling logout", e);
            com.fadcam.streaming.model.AuthResponse authResponse = 
                com.fadcam.streaming.model.AuthResponse.failure("Internal error");
            return jsonResponse(Response.Status.INTERNAL_ERROR, authResponse.toJson());
        }
    }
    
    /**
     * Handle GET /auth/check - Check if auth is enabled and token is valid
     */
    @NonNull
    private Response handleAuthCheck(IHTTPSession session) {
        try {
            RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
            
            boolean authEnabled = authManager.isAuthEnabled();
            String token = extractAuthToken(session);
            boolean tokenValid = false;
            
            if (authEnabled && token != null) {
                com.fadcam.streaming.model.SessionToken sessionToken = authManager.validateToken(token);
                tokenValid = sessionToken != null && sessionToken.isValid();
            }
            
            String json = String.format(java.util.Locale.US,
                "{\"authEnabled\":%b,\"authenticated\":%b,\"tokenValid\":%b}",
                authEnabled, !authEnabled || tokenValid, tokenValid
            );
            
            return jsonResponse(Response.Status.OK, json);
        } catch (Exception e) {
            FLog.e(TAG, "Error checking auth", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"error\":\"Internal error\"}");
        }
    }
    
    /**
     * Handle POST /auth/changePassword - Change password (requires valid session)
     */
    @NonNull
    private Response handleChangePassword(IHTTPSession session) {
        try {
            // Validate current session
            if (!validateAuthToken(session)) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.unauthorized();
                return jsonResponse(Response.Status.UNAUTHORIZED, authResponse.toJson());
            }
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            String body = files.get("postData");
            
            if (body == null || body.isEmpty()) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Missing request body");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            // Extract passwords from JSON
            String oldPassword, newPassword;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                oldPassword = json.getString("oldPassword");
                newPassword = json.getString("newPassword");
            } catch (Exception e) {
                FLog.e(TAG, "Failed to parse change password JSON", e);
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Invalid JSON");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
            
            // Verify old password
            if (!authManager.verifyPassword(oldPassword)) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Current password is incorrect");
                return jsonResponse(Response.Status.UNAUTHORIZED, authResponse.toJson());
            }
            
            // Set new password
            if (!authManager.setPassword(newPassword)) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("New password invalid (4-32 characters required)");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            FLog.i(TAG, "Password changed successfully");
            com.fadcam.streaming.model.AuthResponse authResponse = 
                new com.fadcam.streaming.model.AuthResponse(true, "Password changed successfully");
            return jsonResponse(Response.Status.OK, authResponse.toJson());
            
        } catch (Exception e) {
            FLog.e(TAG, "Error changing password", e);
            com.fadcam.streaming.model.AuthResponse authResponse = 
                com.fadcam.streaming.model.AuthResponse.failure("Internal error");
            return jsonResponse(Response.Status.INTERNAL_ERROR, authResponse.toJson());
        }
    }
    
    /** Paths reachable without signing in: the page, its files and the sign-in endpoints. */
    private static boolean isPublicPath(String uri) {
        return "/".equals(uri)
                || uri.startsWith("/css/") || uri.startsWith("/js/")
                || uri.startsWith("/assets/") || uri.startsWith("/fadex/")
                || "/auth/check".equals(uri) || "/auth/login".equals(uri)
                || "/auth/logout".equals(uri)
                || "/api/github/notification".equals(uri);
    }

    /**
     * Session token from the Authorization header, or from a {@code token} query
     * parameter (HLS players that can't set headers).
     */
    private String extractAuthToken(IHTTPSession session) {
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return queryToken(session);
    }

    @Nullable
    private static String queryToken(IHTTPSession session) {
        java.util.List<String> values = session.getParameters().get("token");
        if (values == null || values.isEmpty()) return null;
        String token = values.get(0);
        return token == null || token.isEmpty() ? null : token;
    }
    
    /**
     * Validate auth token for protected endpoints
     * Returns true if auth is disabled OR token is valid
     */
    private boolean validateAuthToken(IHTTPSession session) {
        RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
        
        // If auth is disabled, allow access
        if (!authManager.isAuthEnabled()) {
            return true;
        }
        
        // Extract and validate token
        String token = extractAuthToken(session);
        if (token == null) {
            return false;
        }
        
        com.fadcam.streaming.model.SessionToken sessionToken = authManager.validateToken(token);
        return sessionToken != null && sessionToken.isValid();
    }
    // END: Authentication Endpoints
    
    /**
     * Serve landing page HTML from streaming/web/index.html (stored in assets)
     */
    @NonNull
    /**
     * Serve static files (CSS, JS, images, fonts) from assets/web/
     */
    private Response serveStaticFile(String uri) {
        try {
            // Map URI to assets path: /css/theme.css -> web/css/theme.css
            String assetPath = "web" + uri;
            
            // Determine MIME type
            String mimeType = getMimeType(uri);
            
            // Load from assets
            FLog.d(TAG, "🔍 Attempting to load asset: " + assetPath);
            InputStream fileStream = context.getAssets().open(assetPath);
            Response response = newFixedLengthResponse(Response.Status.OK, mimeType, fileStream, -1);
            
            // Cache static assets for 1 hour (except HTML)
            if (!mimeType.equals("text/html")) {
                response.addHeader("Cache-Control", "public, max-age=3600");
            }
            
            FLog.d(TAG, "✅ Served static file: " + assetPath + " (" + mimeType + ")");
            return response;
        } catch (IOException e) {
            FLog.e(TAG, "❌ Failed to load static file: " + uri + " (asset path: web" + uri + ")", e);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found: " + uri);
        }
    }
    
    /**
     * Get MIME type from file extension
     */
    private String getMimeType(String uri) {
        if (uri.endsWith(".css")) return "text/css";
        if (uri.endsWith(".js")) return "application/javascript";
        if (uri.endsWith(".json") || uri.endsWith(".jsonc")) return "application/json; charset=utf-8";
        if (uri.endsWith(".png")) return "image/png";
        if (uri.endsWith(".jpg") || uri.endsWith(".jpeg")) return "image/jpeg";
        if (uri.endsWith(".svg")) return "image/svg+xml";
        if (uri.endsWith(".woff")) return "font/woff";
        if (uri.endsWith(".woff2")) return "font/woff2";
        if (uri.endsWith(".ttf")) return "font/ttf";
        if (uri.endsWith(".html")) return "text/html";
        return MIME_PLAINTEXT;
    }
    
    private Response serveLandingPage() {
        FLog.i(TAG, "🏠 [/] Dashboard page requested");
        try {
            // Load from assets/web/index.html at runtime
            InputStream htmlStream = context.getAssets().open("web/index.html");
            Response response = newFixedLengthResponse(Response.Status.OK, "text/html", htmlStream, -1);
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
            FLog.i(TAG, "🏠 [/] Dashboard served successfully");
            return response;
        } catch (IOException e) {
            FLog.e(TAG, "❌ [/] Failed to load index.html from assets/web", e);
            // Fallback to simple error message
            String html = "<!DOCTYPE html>\n" +
                    "<html><head><title>Error</title></head><body>\n" +
                    "<h1>Error Loading Player</h1>\n" +
                    "<p>Failed to load index.html from assets/web folder.</p>\n" +
                    "<p>Error: " + android.text.TextUtils.htmlEncode(String.valueOf(e.getMessage())) + "</p>\n" +
                    "<p>Stream URL: <a href=\"/live.m3u8\">/live.m3u8</a></p>\n" +
                    "</body></html>";
            Response response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", html);
            return response;
        }
    }
    
    /**
     * Get host address for display (gets WiFi IP address).
     */
    private String getHostAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    
                    // Get any IPv4 address that's not loopback (works with any private IP range)
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                        String ip = address.getHostAddress();
                        FLog.d(TAG, "Found network interface IP: " + ip);
                        return ip; // Return first valid local IP found
                    }
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error getting host address", e);
        }
        return "localhost";
    }

    /**
     * Ring alarm (security buzzer) with specified sound and duration.
     * Expects JSON: {"sound": "office_phone.mp3", "duration_ms": 10000} or {"sound": "office_phone.mp3", "duration_ms": -1} for infinite
     */
    private Response ringAlarm(IHTTPSession session) {
        try {
            FLog.i(TAG, "🚨 Alarm ring requested via web interface");
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            
            String postData = files.get("postData");
            if (postData == null || postData.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Missing request body\"}");
            }
            
            org.json.JSONObject json = new org.json.JSONObject(postData);
            
            // Get sound filename (required)
            String soundFile = json.optString("sound", "office_phone.mp3");
            if (soundFile.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Sound file not specified\"}");
            }
            
            // Get duration in milliseconds (-1 = infinite)
            long durationMs = json.optLong("duration_ms", -1);
            
            // Update stream manager state
            streamManager.setSelectedAlarmSound(soundFile);
            streamManager.setAlarmDurationMs(durationMs);
            streamManager.setAlarmRinging(true);
            
            // Start alarm playback via AlarmService
            android.content.Intent alarmIntent = new android.content.Intent(context, com.fadcam.services.AlarmService.class);
            alarmIntent.setAction("com.fadcam.action.RING_ALARM");
            alarmIntent.putExtra("sound", soundFile);
            alarmIntent.putExtra("duration_ms", durationMs);
            
            try {
                android.os.Build.VERSION_CODES versionCodes = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(alarmIntent);
                } else {
                    context.startService(alarmIntent);
                }
                FLog.i(TAG, "AlarmService started");
            } catch (Exception e) {
                FLog.e(TAG, "Error starting AlarmService", e);
                return jsonResponse(Response.Status.INTERNAL_ERROR, 
                    "{\"status\": \"error\", \"message\": \"Failed to start alarm service\"}");
            }
            
            return jsonResponse(Response.Status.OK, 
                "{\"status\": \"success\", \"message\": \"Alarm ringing\", \"sound\": " + org.json.JSONObject.quote(soundFile) + ", \"duration_ms\": " + durationMs + "}");
            
        } catch (Exception e) {
            FLog.e(TAG, "Error ringing alarm", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                errorJson(e.getMessage()));
        }
    }

    /**
     * Stop alarm (security buzzer).
     */
    private Response stopAlarm() {
        try {
            FLog.i(TAG, "🔇 Alarm stop requested via web interface");
            
            // Update stream manager state
            streamManager.setAlarmRinging(false);
            
            // Stop alarm playback via AlarmService
            android.content.Intent alarmIntent = new android.content.Intent(context, com.fadcam.services.AlarmService.class);
            alarmIntent.setAction("com.fadcam.action.STOP_ALARM");
            context.startService(alarmIntent);
            
            FLog.i(TAG, "Stop alarm service command sent");
            
            return jsonResponse(Response.Status.OK, 
                "{\"status\": \"success\", \"message\": \"Alarm stopped\"}");
            
        } catch (Exception e) {
            FLog.e(TAG, "Error stopping alarm", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                errorJson(e.getMessage()));
        }
    }

    /**
     * Cancel the alarm set by scheduleAlarm (the page's Cancel used to clear only
     * browser storage, so the phone still rang).
     */
    private Response cancelScheduledAlarm() {
        try {
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) context.getSystemService(android.content.Context.ALARM_SERVICE);
            android.content.Intent alarmIntent = new android.content.Intent(context, com.fadcam.services.AlarmService.class);
            alarmIntent.setAction("com.fadcam.action.RING_ALARM");
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getService(
                context,
                1001, // same id as scheduleAlarm
                alarmIntent,
                android.app.PendingIntent.FLAG_NO_CREATE | android.app.PendingIntent.FLAG_IMMUTABLE
            );
            if (pendingIntent != null && alarmManager != null) {
                alarmManager.cancel(pendingIntent);
                pendingIntent.cancel();
            }
            FLog.i(TAG, "Scheduled alarm cancelled via web interface");
            return jsonResponse(Response.Status.OK,
                "{\"status\": \"success\", \"message\": \"Scheduled alarm cancelled\"}");
        } catch (Exception e) {
            FLog.e(TAG, "Error cancelling scheduled alarm", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                "{\"status\": \"error\", \"message\": \"Could not cancel scheduled alarm\"}");
        }
    }

    /**
     * Schedule alarm for future time.
     */
    private Response scheduleAlarm(IHTTPSession session) {
        try {
            FLog.i(TAG, "📅 Alarm schedule requested via web interface");
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            files.putAll(postBody());
            
            String postData = files.get("postData");
            if (postData == null || postData.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Missing request body\"}");
            }
            
            org.json.JSONObject json = new org.json.JSONObject(postData);
            
            // Get scheduled time (required)
            long scheduledTime = json.optLong("scheduledTime", -1);
            if (scheduledTime <= 0) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Invalid scheduled time\"}");
            }
            
            // Get sound filename (required)
            String soundFile = json.optString("sound", "office_phone.mp3");
            if (soundFile.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Sound file not specified\"}");
            }
            
            // Get duration in milliseconds (-1 = infinite)
            long durationMs = json.optLong("duration_ms", 30000);
            
            // Calculate delay in milliseconds
            long currentTime = System.currentTimeMillis();
            long delayMs = scheduledTime - currentTime;
            
            if (delayMs <= 0) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Scheduled time must be in the future\"}");
            }
            
            // Use AlarmManager to schedule the alarm
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) context.getSystemService(android.content.Context.ALARM_SERVICE);
            android.content.Intent alarmIntent = new android.content.Intent(context, com.fadcam.services.AlarmService.class);
            alarmIntent.setAction("com.fadcam.action.RING_ALARM");
            alarmIntent.putExtra("sound", soundFile);
            alarmIntent.putExtra("duration_ms", durationMs);
            
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getService(
                context, 
                1001, // Unique ID for scheduled alarms
                alarmIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            );
            
            // Schedule using setAndAllowWhileIdle for reliability even in doze mode
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    scheduledTime,
                    pendingIntent
                );
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    scheduledTime,
                    pendingIntent
                );
            }
            
            FLog.i(TAG, "✅ Alarm scheduled for " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(scheduledTime)) + 
                " (in " + (delayMs / 1000) + " seconds)");
            
            return jsonResponse(Response.Status.OK, 
                "{\"status\": \"success\", \"message\": \"Alarm scheduled\", \"sound\": " + org.json.JSONObject.quote(soundFile) +
                ", \"duration_ms\": " + durationMs + ", \"scheduled_for\": " + scheduledTime + "}");
            
        } catch (Exception e) {
            FLog.e(TAG, "Error scheduling alarm", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                errorJson(e.getMessage()));
        }
    }

    /**
     * Handle POST /api/notifications
     * Body: { "action": "save" | "clear", "history": JSON array (for save action) }
     */
    private Response handleNotifications(IHTTPSession session) {
        try {
            // Read once (and in full) by serve()
            String body = postBody().get("postData");
            if (body == null || body.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                    "{\"status\": \"error\", \"message\": \"Empty request body\"}");
            }

            org.json.JSONObject request = new org.json.JSONObject(body);
            String action = request.optString("action");

            com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(context);

            if ("save".equals(action)) {
                // Save notification history
                // Only a real JSON array is stored, since GET splices it into its response
                Object raw = request.opt("history");
                org.json.JSONArray history;
                try {
                    history = raw instanceof org.json.JSONArray ? (org.json.JSONArray) raw
                            : new org.json.JSONArray(raw == null ? "[]" : raw.toString());
                } catch (org.json.JSONException bad) {
                    return jsonResponse(Response.Status.BAD_REQUEST, errorJson("history must be a JSON array"));
                }
                prefs.saveNotificationHistory(history.toString());
                FLog.d(TAG, "✅ Notification history saved from JS");
                return jsonResponse(Response.Status.OK, 
                    "{\"status\": \"success\", \"message\": \"Notifications saved\"}");
            } else if ("clear".equals(action)) {
                // Clear all notifications
                prefs.clearNotificationHistory();
                FLog.d(TAG, "✅ Notification history cleared");
                return jsonResponse(Response.Status.OK, 
                    "{\"status\": \"success\", \"message\": \"Notifications cleared\"}");
            } else {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Unknown action\"}");
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error handling notifications", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                errorJson(e.getMessage()));
        }
    }

    /**
     * Handle GET /api/notifications
     * Returns cached notification history
     */
    private Response getNotifications() {
        try {
            com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(context);
            String history = prefs.getNotificationHistory();
            try {
                history = new org.json.JSONArray(history == null ? "[]" : history).toString();
            } catch (org.json.JSONException bad) {
                history = "[]"; // an older bad save would otherwise break every GET
            }
            
            return jsonResponse(Response.Status.OK, 
                "{\"status\": \"success\", \"history\": " + history + "}");
        } catch (Exception e) {
            FLog.e(TAG, "Error getting notifications", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                errorJson(e.getMessage()));
        }
    }

    /**
     * Proxy GitHub notification file fetch to avoid browser CORS issues
     * GET /api/github/notification - Fetches JSONC from GitHub and returns as JSON
     */
    /** Unauthenticated endpoint: without a cache anyone on the network made the phone fetch on demand. */
    private static final long GITHUB_NOTIFICATION_CACHE_MS = 10 * 60 * 1000L;
    private static volatile String cachedGitHubNotification;
    private static volatile long cachedGitHubNotificationAt;

    private Response fetchGitHubNotification() {
        String cached = cachedGitHubNotification;
        if (cached != null && System.currentTimeMillis() - cachedGitHubNotificationAt < GITHUB_NOTIFICATION_CACHE_MS) {
            return jsonResponse(Response.Status.OK, cached);
        }
        java.net.HttpURLConnection conn = null;
        try {
            String githubUrl = "https://raw.githubusercontent.com/jabsch/FadCam/master/app/src/main/assets/web/fadex/pushnotification.jsonc";
            
            // Create URL connection
            java.net.URL url = new java.net.URL(githubUrl);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "FadCam-NotificationManager");
            
            // Read response (closed on failure too, which leaked a socket per error)
            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            }
            
            String jsonc = response.toString();
            
            // Parse JSONC (strip comments) to JSON
            String json = parseJSONCToJSON(jsonc);
            cachedGitHubNotification = json;
            cachedGitHubNotificationAt = System.currentTimeMillis();
            
            return jsonResponse(Response.Status.OK, json);
        } catch (Exception e) {
            FLog.e(TAG, "❌ Error fetching GitHub notification", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                errorJson(e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Parse JSONC (JSON with comments) to valid JSON
     * Strips single-line and multi-line comments before returning
     */
    private String parseJSONCToJSON(String jsonc) {
        // Remove multi-line comments first /* ... */
        String cleaned = jsonc.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        
        // Remove single-line comments // ... (handle all line endings: \r\n, \n, \r)
        // Split by lines, remove comment from each line, rejoin
        String[] lines = cleaned.split("\r?\n|\r");
        StringBuilder result = new StringBuilder();
        
        for (String line : lines) {
            // Find // that's not inside a string
            int commentIndex = -1;
            boolean inString = false;
            boolean escaped = false;
            
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                
                if (escaped) {
                    escaped = false;
                    continue;
                }
                
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                
                // If we find // outside a string, remove from here
                if (!inString && i < line.length() - 1 && c == '/' && line.charAt(i + 1) == '/') {
                    commentIndex = i;
                    break;
                }
            }
            
            // Add the line (without comment part if found)
            if (commentIndex >= 0) {
                result.append(line.substring(0, commentIndex));
            } else {
                result.append(line);
            }
            result.append("\n");
        }
        
        // Remove trailing commas before } and ]
        String finalCleaned = result.toString().replaceAll(",\\s*([}\\]])", "$1");
        
        return finalCleaned.trim();
    }

    /** Error body with the message JSON-escaped (exception text can quote the bad input). */
    private static String errorJson(String message) {
        return "{\"status\": \"error\", \"message\": " + org.json.JSONObject.quote(String.valueOf(message)) + "}";
    }
}
