package com.fadcam.services;

import static org.junit.Assert.assertEquals;

import com.fadcam.services.RecordingThermalGuard.Action;
import org.junit.Test;

public class RecordingThermalGuardTest {

    @Test
    public void coolStatusesDoNothing() {
        assertEquals(Action.NONE, RecordingThermalGuard.decide(0, false));
        assertEquals(Action.NONE, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_MODERATE, false));
    }

    @Test
    public void severeReducesOnce() {
        assertEquals(Action.REDUCE, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_SEVERE, false));
        assertEquals(Action.NONE, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_SEVERE, true));
    }

    @Test
    public void restoresOnlyOnceCooledBelowModerate() {
        assertEquals(Action.NONE, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_MODERATE, true));
        assertEquals(Action.RESTORE, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_LIGHT, true));
        assertEquals(Action.RESTORE, RecordingThermalGuard.decide(0, true));
    }

    @Test
    public void criticalAndAboveStop() {
        assertEquals(Action.STOP, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_CRITICAL, false));
        assertEquals(Action.STOP, RecordingThermalGuard.decide(5, true));
        assertEquals(Action.STOP, RecordingThermalGuard.decide(6, false));
    }
}
