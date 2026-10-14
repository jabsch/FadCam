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
        assertFalse(PersonCheckMode.ON_MOVEMENT.shouldDetect(true, PersonCheckMode.STILL_REFRESH_MS - 1));
        assertTrue(PersonCheckMode.ON_MOVEMENT.shouldDetect(true, PersonCheckMode.STILL_REFRESH_MS));
    }

    @Test
    public void idsRoundTrip() {
        for (PersonCheckMode m : PersonCheckMode.values()) {
            assertEquals(m, PersonCheckMode.fromId(m.id));
        }
        assertNull(PersonCheckMode.fromId("never"));
    }
}
