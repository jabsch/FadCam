package com.fadcam.services;

import static org.junit.Assert.assertEquals;

import com.fadcam.services.RecordingThermalGuard.Action;
import com.fadcam.services.RecordingThermalGuard.WarmAction;
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

    @Test
    public void autoFpsDropsAtModerateAndRisesOnlyWhenCool() {
        WarmAction none = WarmAction.NONE;
        assertEquals(none, RecordingThermalGuard.decideWarmStep(RecordingThermalGuard.STATUS_LIGHT, false));
        assertEquals(WarmAction.WARM, RecordingThermalGuard.decideWarmStep(RecordingThermalGuard.STATUS_MODERATE, false));
        assertEquals(WarmAction.WARM, RecordingThermalGuard.decideWarmStep(RecordingThermalGuard.STATUS_SEVERE, false));
        assertEquals(none, RecordingThermalGuard.decideWarmStep(RecordingThermalGuard.STATUS_MODERATE, true));
        assertEquals(WarmAction.COOLED, RecordingThermalGuard.decideWarmStep(RecordingThermalGuard.STATUS_LIGHT, true));
        assertEquals(WarmAction.COOLED, RecordingThermalGuard.decideWarmStep(0, true));
    }

    @Test
    public void autoFpsLeavesCriticalToTheStop() {
        assertEquals(WarmAction.NONE, RecordingThermalGuard.decideWarmStep(RecordingThermalGuard.STATUS_CRITICAL, false));
    }

    @Test
    public void earlyCutsAtModerateAndRestoresWhenCool() {
        assertEquals(Action.NONE, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_LIGHT, false, true));
        assertEquals(Action.REDUCE, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_MODERATE, false, true));
        assertEquals(Action.NONE, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_LIGHT, true, true));
        assertEquals(Action.RESTORE, RecordingThermalGuard.decide(0, true, true));
        assertEquals(Action.STOP, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_CRITICAL, false, true));
        assertEquals(Action.NONE, RecordingThermalGuard.decide(RecordingThermalGuard.STATUS_SEVERE, true, true));
    }

    @Test
    public void earlyWarmStepsAtLightAndCoolsAtNone() {
        assertEquals(WarmAction.WARM, RecordingThermalGuard.decideWarmStep(RecordingThermalGuard.STATUS_LIGHT, false, true));
        assertEquals(WarmAction.NONE, RecordingThermalGuard.decideWarmStep(RecordingThermalGuard.STATUS_LIGHT, true, true));
        assertEquals(WarmAction.COOLED, RecordingThermalGuard.decideWarmStep(0, true, true));
        assertEquals(WarmAction.NONE, RecordingThermalGuard.decideWarmStep(RecordingThermalGuard.STATUS_CRITICAL, false, true));
    }
}
