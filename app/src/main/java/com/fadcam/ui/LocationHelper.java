package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import static android.content.ContentValues.TAG;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;

import java.util.concurrent.atomic.AtomicBoolean;

public class LocationHelper {

    private final GpsMyLocationProvider provider;
    // volatile: written on the location callback thread, read from the
    // watermark/GL thread — without this the reader can see a stale null even
    // though fixes are arriving (a classic invisible-reader bug).
    private volatile GeoPoint currentLocation;
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long lastLocationUpdateTime = 0;
    private volatile Location rawLocation;
    private final boolean fastUpdates;

    /**
     * @param fastUpdates true for the 1 s / 1 m speedometer cadence; false for a
     *        5 s / 5 m feed that is enough for location text, UTM and weather
     *        and lets the GPS radio idle between fixes.
     */
    public LocationHelper(Context context, boolean fastUpdates) {
        FLog.d(TAG, "LOCATION_HELPER: Initializing LocationHelper");
        provider = new GpsMyLocationProvider(context);
        this.fastUpdates = fastUpdates;

        // Standard speedometer cadence: 1000ms. GPS chipsets natively deliver
        // ~1 Hz fixes regardless of a lower minTime, so polling at 300ms just
        // wasted battery without producing more data. 1s matches the watermark
        // refresh tick and is the industry-standard speedometer sample rate.
        provider.setLocationUpdateMinTime(fastUpdates ? 1000 : 5000);
        provider.setLocationUpdateMinDistance(fastUpdates ? 1 : 5);
        FLog.d(TAG, "LOCATION_HELPER: Set up location provider, fastUpdates=" + fastUpdates);

        // Start location updates immediately
        startLocationUpdates();
    }

    public boolean isFastUpdates() {
        return fastUpdates;
    }

    public void startLocationUpdates() {
        if (provider != null && !isInitializing.getAndSet(true)) {
            FLog.d(TAG, "🗺️ LOCATION_HELPER: Starting location updates with 1s polling");
            provider.startLocationProvider(new IMyLocationConsumer() {
                @Override
                public void onLocationChanged(Location location, IMyLocationProvider source) {
                    if (location != null) {
                        // Only trust GPS/network-fused fixes that carry real motion
                        // data. Network-only fixes (no speed, coarse accuracy)
                        // would poison the speed/altitude computation below.
                        String providerName = location.getProvider();
                        boolean isGpsFix = providerName == null
                                || android.location.LocationManager.GPS_PROVIDER.equals(providerName)
                                || "fused".equalsIgnoreCase(providerName);
                        if (isGpsFix) {
                            rawLocation = location;
                        }
                        lastLocationUpdateTime = System.currentTimeMillis();
                        currentLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
                        FLog.d(TAG, "✅ LOCATION_HELPER: GPS updated to " + 
                            com.fadcam.FLog.redactedCoords(currentLocation.getLatitude(), currentLocation.getLongitude()) + 
                            " (accuracy: " + String.format("%.0f", location.getAccuracy()) + "m)");
                    } else {
                        FLog.w(TAG, "❌ LOCATION_HELPER: Received null location update");
                    }
                    isInitializing.set(false);
                }
            });

            // If we don't get a location update in 30 seconds, log a warning.
            // Cold GPS start can take 30-60s, so 5s was a false alarm on many devices.
            mainHandler.postDelayed(() -> {
                if (currentLocation == null) {
                    FLog.w(TAG, "⚠️ LOCATION_HELPER: No GPS fix after 30 seconds (check permission / GPS enabled)");
                    isInitializing.set(false);
                }
            }, 30000);
        } else {
            FLog.w(TAG, "❌ LOCATION_HELPER: Provider null or already initializing");
        }
    }

    public void stopLocationUpdates() {
        if (provider != null) {
            FLog.d(TAG, "LOCATION_HELPER: Stopping location updates");
            provider.stopLocationProvider();
        }
        // Reset the init gate so a later startLocationUpdates() actually
        // re-registers the provider (previously it stayed true forever after a
        // stop, making subsequent recordings log "Provider null or already
        // initializing" and never feed speed/altitude).
        isInitializing.set(false);
    }

    /**
     * Gets the current location. If no location is available, tries to start
     * updates again in case there was an issue.
     * 
     * @return The current GeoPoint, or null if location not available
     */
    public org.osmdroid.util.GeoPoint getCurrentLocation() {
        if (currentLocation == null) {
            FLog.w(TAG, "LOCATION_HELPER: getCurrentLocation requested but no location available");
            
            // Check if a recent location update was received
            boolean staleLocation = lastLocationUpdateTime == 0 || 
                System.currentTimeMillis() - lastLocationUpdateTime > 10000; // 10 seconds
                
            if (staleLocation && !isInitializing.get()) {
                FLog.d(TAG, "LOCATION_HELPER: Trying to restart location updates due to stale data");
                startLocationUpdates();
            }
            return null;
        }
        
        // Redact to ~1km precision for log privacy
        FLog.d(TAG, "LOCATION_HELPER: Providing location: ~" +
            String.format(java.util.Locale.US, "%.2f", currentLocation.getLatitude()) + ", ~" +
            String.format(java.util.Locale.US, "%.2f", currentLocation.getLongitude()));
        return currentLocation;
    }

    public Location getRawLocation() {
        // Return the REAL fix only. Never synthesize a fake Location — a
        // fabricated fix would carry no real speed/altitude and pollute the
        // watermark with made-up "0 km/h" that looks genuine. Callers must treat
        // null as "no fix yet" and fall back to an honest placeholder.
        return rawLocation;
    }

    public String getLocationData() {
        FLog.d(TAG, "LOCATION_HELPER: getLocationData called");
        if (currentLocation != null) {
            FLog.d(TAG, "LOCATION_HELPER: Location data found (~" +
                String.format(java.util.Locale.US, "%.2f", currentLocation.getLatitude()) + ", ~" +
                String.format(java.util.Locale.US, "%.2f", currentLocation.getLongitude()) + ")");
            // Return full precision for the actual watermark text
            return "\nLat: " + currentLocation.getLatitude() + ", Long: " + currentLocation.getLongitude();
        }
        FLog.d(TAG, "LOCATION_HELPER: Location data not found");
        return "\nLocation not available";
    }
}
