package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PersonCheckModeTest {

    @Test
    public void everyFrameAlwaysDetects() {
        assertTrue(PersonCheckMode.EVERY_FRAME.shouldDetect(true, 0L));
        assertTrue(PersonCheckMode.EVERY_FRAME.shouldDetect(false, 0L));
    }

    @Test
    public void onMovementSkipsStillScenesUntilRefresh() {
        assertTrue(PersonCheckMode.ON_MOVEMENT.shouldDetect(false, 0L));
        assertFalse(PersonCheckMode.ON_MOVEMENT.shouldDetect(true, 0L));
        assertFalse(PersonCheckMode.ON_MOVEMENT.shouldDetect(true, 1999L));
        assertTrue(PersonCheckMode.ON_MOVEMENT.shouldDetect(true, 2000L));
    }

    @Test
    public void onMovementSlowRestsTenSecondsOnStillScenes() {
        assertTrue(PersonCheckMode.ON_MOVEMENT_SLOW.shouldDetect(false, 0L));
        assertFalse(PersonCheckMode.ON_MOVEMENT_SLOW.shouldDetect(true, 2000L));
        assertFalse(PersonCheckMode.ON_MOVEMENT_SLOW.shouldDetect(true, 9999L));
        assertTrue(PersonCheckMode.ON_MOVEMENT_SLOW.shouldDetect(true, 10_000L));
    }

    @Test
    public void idsRoundTrip() {
        for (PersonCheckMode m : PersonCheckMode.values()) {
            assertEquals(m, PersonCheckMode.fromId(m.id));
        }
        assertNull(PersonCheckMode.fromId("never"));
    }
}
