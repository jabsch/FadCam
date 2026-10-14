package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CloudUpdateRateTest {

    @Test
    public void slowerRatesNeverPollFasterThanFast() {
        for (CloudUpdateRate r : CloudUpdateRate.values()) {
            assertTrue(r.statusIntervalMs >= CloudUpdateRate.FAST.statusIntervalMs);
            assertTrue(r.commandIntervalMs >= CloudUpdateRate.FAST.commandIntervalMs);
        }
        assertTrue(CloudUpdateRate.SAVER.statusIntervalMs > CloudUpdateRate.BALANCED.statusIntervalMs);
    }

    @Test
    public void saverStaysWithinTheExistingOfflineBackoff() {
        // Failure backoff already stretches status pushes to 8 s without the dashboard dropping the phone.
        assertTrue(CloudUpdateRate.SAVER.statusIntervalMs <= 8000L);
    }

    @Test
    public void idsRoundTrip() {
        for (CloudUpdateRate r : CloudUpdateRate.values()) {
            assertEquals(r, CloudUpdateRate.fromId(r.id));
        }
        assertNull(CloudUpdateRate.fromId("realtime"));
    }
}
