package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
/**
 * Helper class to manage location services specifically for embedding geotags in video metadata.
 * This is separate from LocationHelper which is used for watermark text.
 */
public class GeotagHelper {
    private static final String TAG = "GeotagHelper";
    // A geotag only needs one good fix per recording, so don't hold the GPS
    // radio at 1 Hz for it (the speed watermark has its own 1 s feed).
    private static final long GEOTAG_MIN_TIME_MS = 5000;
    private static final float GEOTAG_MIN_DISTANCE_M = 5f;
    private final LocationManager locationManager;
    private final Context context;
    private Location bestLocation;
    private long lastLocationUpdateTime = 0;
    private boolean isActive = false;
    
    // Location listeners
    private LocationListener gpsListener;
    private LocationListener networkListener;
    
    // Handler for delayed operations
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Receives the one fix asked for with {@link #stopAfterNextFix}. */
    public interface FixListener {
        void onFix(Location location);
    }

    // A late fix is taken once it is this good, or whatever is best at the timeout.
    private static final float GOOD_FIX_ACCURACY_M = 50f;
    private volatile FixListener pendingFixListener;
    private final Runnable fixTimeout = () -> deliverPendingFix();
    
    /**
     * Creates a new GeotagHelper
     * @param context Application context
     */
    public GeotagHelper(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        FLog.d(TAG, "GeotagHelper initialized");
    }
    
    /**
     * Starts location updates to prepare for metadata embedding
     * @return true if location providers are available and updates started
     */
    public boolean startUpdates() {
        if (isActive) {
            FLog.d(TAG, "Location updates already active");
            return true;
        }
        
        try {
            // Check if providers are enabled
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            
            if (!gpsEnabled && !networkEnabled) {
                FLog.w(TAG, "No location providers enabled");
                return false;
            }
            
            // Get last known location as initial position
            if (gpsEnabled) {
                try {
                    Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (lastKnown != null) {
                        FLog.d(TAG, "Using last known GPS location: " + com.fadcam.FLog.redactedCoords(lastKnown.getLatitude(), lastKnown.getLongitude()));
                        updateBestLocation(lastKnown);
                    }
                } catch (SecurityException se) {
                    FLog.e(TAG, "Security exception getting last GPS location", se);
                }
            }
            
            if (networkEnabled) {
                try {
                    Location lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (lastKnown != null) {
                        FLog.d(TAG, "Using last known network location: " + com.fadcam.FLog.redactedCoords(lastKnown.getLatitude(), lastKnown.getLongitude()));
                        updateBestLocation(lastKnown);
                    }
                } catch (SecurityException se) {
                    FLog.e(TAG, "Security exception getting last network location", se);
                }
            }
            
            // Create listeners
            gpsListener = createLocationListener("GPS");
            networkListener = createLocationListener("Network");
            
            // Request updates from both providers
            if (gpsEnabled) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 
                        GEOTAG_MIN_TIME_MS,
                        GEOTAG_MIN_DISTANCE_M,
                        gpsListener,
                        Looper.getMainLooper()
                    );
                    FLog.d(TAG, "GPS location updates requested");
                } catch (SecurityException se) {
                    FLog.e(TAG, "Security exception requesting GPS updates", se);
                }
            }
            
            if (networkEnabled) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        GEOTAG_MIN_TIME_MS,
                        GEOTAG_MIN_DISTANCE_M,
                        networkListener,
                        Looper.getMainLooper()
                    );
                    FLog.d(TAG, "Network location updates requested");
                } catch (SecurityException se) {
                    FLog.e(TAG, "Security exception requesting network updates", se);
                }
            }
            
            isActive = true;
            FLog.d(TAG, "Location updates started successfully");
            return true;
            
        } catch (Exception e) {
            FLog.e(TAG, "Error starting location updates", e);
            return false;
        }
    }
    
    /**
     * Stops location updates to save battery
     */
    public void stopUpdates() {
        pendingFixListener = null;
        mainHandler.removeCallbacks(fixTimeout);
        if (!isActive) {
            return;
        }
        
        try {
            if (gpsListener != null) {
                locationManager.removeUpdates(gpsListener);
            }
            if (networkListener != null) {
                locationManager.removeUpdates(networkListener);
            }
            isActive = false;
            FLog.d(TAG, "Location updates stopped");
        } catch (SecurityException se) {
            FLog.e(TAG, "Security exception stopping location updates", se);
        } catch (Exception e) {
            FLog.e(TAG, "Error stopping location updates", e);
        }
    }
    
    /**
     * Keeps updates on only until a good fix arrives, hands it to
     * {@code listener} on the main thread, then stops. At {@code timeoutMs}
     * it hands over the best fix so far (if any) and stops.
     */
    public void stopAfterNextFix(FixListener listener, long timeoutMs) {
        pendingFixListener = listener;
        mainHandler.removeCallbacks(fixTimeout);
        mainHandler.postDelayed(fixTimeout, timeoutMs);
    }

    private void deliverPendingFix() {
        FixListener listener = pendingFixListener;
        Location best = getCurrentLocation();
        stopUpdates();
        if (listener != null && best != null) {
            listener.onFix(best);
        }
    }

    /**
     * Creates a location listener with appropriate logging
     * @param providerName Name of the provider for logging
     * @return A configured LocationListener
     */
    private LocationListener createLocationListener(final String providerName) {
        return new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null) {
                    FLog.d(TAG, providerName + " location update: " + 
                                         com.fadcam.FLog.redactedCoords(location.getLatitude(), location.getLongitude()) + 
                                         " (accuracy: " + location.getAccuracy() + "m)");
                    updateBestLocation(location);
                    if (pendingFixListener != null && location.hasAccuracy()
                            && location.getAccuracy() <= GOOD_FIX_ACCURACY_M) {
                        deliverPendingFix();
                    }
                }
            }
            
            @Override
            public void onProviderEnabled(String provider) {
                FLog.d(TAG, providerName + " provider enabled");
            }
            
            @Override
            public void onProviderDisabled(String provider) {
                FLog.d(TAG, providerName + " provider disabled");
            }
        };
    }
    
    /**
     * Updates the best location based on accuracy and recency
     * @param location New location to evaluate
     */
    private synchronized void updateBestLocation(Location location) {
        if (location == null) return;
        
        lastLocationUpdateTime = System.currentTimeMillis();
        
        if (bestLocation == null) {
            bestLocation = location;
            return;
        }
        
        // Check if new location is more accurate
        float accuracy1 = bestLocation.getAccuracy();
        float accuracy2 = location.getAccuracy();
        long time1 = bestLocation.getTime();
        long time2 = location.getTime();
        
        // If the new location is significantly more accurate, use it
        if (accuracy2 < accuracy1 * 0.8) {
            bestLocation = location;
            return;
        }
        
        // If the new location is fresher by at least 1 minute and not substantially worse, use it
        if (time2 - time1 > 60000 && accuracy2 < accuracy1 * 1.5) {
            bestLocation = location;
        }
    }
    
    /**
     * Applies the best available location to a MediaRecorder instance
     * @param recorder The MediaRecorder to configure
     * @return true if location was applied, false otherwise
     */
    public boolean applyLocationToRecorder(MediaRecorder recorder) {
        if (recorder == null) {
            FLog.e(TAG, "Cannot apply location to null MediaRecorder");
            return false;
        }
        
        if (bestLocation == null) {
            FLog.w(TAG, "No location available to apply to MediaRecorder");
            return false;
        }
        
        try {
            float latitude = (float) bestLocation.getLatitude();
            float longitude = (float) bestLocation.getLongitude();
            recorder.setLocation(latitude, longitude);
            FLog.d(TAG, "Applied location to MediaRecorder: " + latitude + ", " + longitude);
            return true;
        } catch (Exception e) {
            FLog.e(TAG, "Failed to set location metadata in MediaRecorder", e);
            return false;
        }
    }
    
    /**
     * Gets the best current location
     * @return The most accurate recent location, or null if unavailable
     */
    public Location getCurrentLocation() {
        // Check if location is stale (older than 2 minutes)
        if (bestLocation != null) {
            long locationAge = System.currentTimeMillis() - bestLocation.getTime();
            if (locationAge > 120000) { // 2 minutes
                FLog.w(TAG, "Current location is stale (" + (locationAge/1000) + " seconds old)");
                // Don't return null, still return the stale location as it's better than nothing
                // Just log a warning
            }
        }
        return bestLocation;
    }
    
    /**
     * Checks if we have a valid location
     * @return true if we have a location, false otherwise
     */
    public boolean hasLocation() {
        return bestLocation != null;
    }
    
    /**
     * Gets the location as a formatted string for logging
     * @return String representing the current location data
     */
    public String getLocationString() {
        if (bestLocation != null) {
            return String.format("%.6f, %.6f (accuracy: %.1fm)", 
                bestLocation.getLatitude(), 
                bestLocation.getLongitude(),
                bestLocation.getAccuracy());
        }
        return "No location available";
    }
} 