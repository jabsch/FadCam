package com.fadcam.shortcuts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fadcam.shortcuts.VolumeKeyGestureDetector.Gesture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class VolumeKeyGestureDetectorTest {
    private static final int UP = 24;   // KeyEvent.KEYCODE_VOLUME_UP
    private static final int DOWN = 25; // KeyEvent.KEYCODE_VOLUME_DOWN
    private static final long HOLD_MS = 400;
    private static final long DOUBLE_MS = 300;

    private final Map<String, String> bindings = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private final Map<Runnable, Long> timers = new HashMap<>();
    private long now;
    private VolumeKeyGestureDetector detector;

    @Before
    public void setUp() {
        detector = new VolumeKeyGestureDetector(new VolumeKeyGestureDetector.Host() {
            @Override public String binding(int keyCode, Gesture gesture) {
                return bindings.get(keyCode + ":" + gesture);
            }
            @Override public void runShortcut(String shortcutId) { log.add("run " + shortcutId); }
            @Override public void adjustVolume(int keyCode) { log.add("vol " + keyCode); }
            @Override public void schedule(Runnable task, long delayMs) { timers.put(task, now + delayMs); }
            @Override public void cancel(Runnable task) { timers.remove(task); }
        }, HOLD_MS, DOUBLE_MS);
    }

    private void bind(int key, Gesture g, String id) { bindings.put(key + ":" + g, id); }

    private void advance(long ms) {
        now += ms;
        boolean ran = true;
        while (ran) {
            ran = false;
            for (Map.Entry<Runnable, Long> e : new ArrayList<>(timers.entrySet())) {
                if (e.getValue() <= now && timers.remove(e.getKey()) != null) {
                    e.getKey().run();
                    ran = true;
                }
            }
        }
    }

    private void tap(int key) {
        detector.onKeyDown(key, 0);
        advance(50);
        detector.onKeyUp(key);
    }

    @Test
    public void unboundKeysPassThrough() {
        assertFalse(detector.onKeyDown(UP, 0));
        assertFalse(detector.onKeyUp(UP));
        assertTrue(log.isEmpty());
    }

    @Test
    public void holdRunsShortcut() {
        bind(UP, Gesture.HOLD, "record");
        detector.onKeyDown(UP, 0);
        advance(HOLD_MS);
        detector.onKeyUp(UP);
        assertEquals(List.of("run record"), log);
    }

    @Test
    public void shortPressWithOnlyHoldBoundChangesVolumeAtOnce() {
        bind(UP, Gesture.HOLD, "record");
        tap(UP);
        assertEquals(List.of("vol " + UP), log);
    }

    @Test
    public void doubleTapRunsShortcutWithoutVolumeChange() {
        bind(DOWN, Gesture.DOUBLE_TAP, "torch");
        tap(DOWN);
        advance(100);
        tap(DOWN);
        advance(1000);
        assertEquals(List.of("run torch"), log);
    }

    @Test
    public void singleTapWithDoubleBoundChangesVolumeAfterWindow() {
        bind(DOWN, Gesture.DOUBLE_TAP, "torch");
        tap(DOWN);
        assertTrue(log.isEmpty());
        advance(DOUBLE_MS);
        assertEquals(List.of("vol " + DOWN), log);
    }

    @Test
    public void slowSecondTapIsTwoVolumeSteps() {
        bind(DOWN, Gesture.DOUBLE_TAP, "torch");
        tap(DOWN);
        advance(DOUBLE_MS + 50);
        tap(DOWN);
        advance(DOUBLE_MS);
        assertEquals(List.of("vol " + DOWN, "vol " + DOWN), log);
    }

    @Test
    public void holdAndDoubleTapOnSameKeyBothWork() {
        bind(UP, Gesture.HOLD, "record");
        bind(UP, Gesture.DOUBLE_TAP, "photo");
        tap(UP);
        advance(100);
        tap(UP);
        advance(1000);
        detector.onKeyDown(UP, 0);
        advance(HOLD_MS);
        detector.onKeyUp(UP);
        advance(1000);
        assertEquals(List.of("run photo", "run record"), log);
    }

    @Test
    public void otherKeyFlushesPendingTap() {
        bind(UP, Gesture.DOUBLE_TAP, "photo");
        bind(DOWN, Gesture.DOUBLE_TAP, "torch");
        tap(UP);
        advance(50);
        tap(DOWN);
        advance(DOUBLE_MS);
        assertEquals(List.of("vol " + UP, "vol " + DOWN), log);
    }

    @Test
    public void holdingKeyWithOnlyDoubleBoundStepsVolume() {
        bind(UP, Gesture.DOUBLE_TAP, "photo");
        detector.onKeyDown(UP, 0);
        advance(500);
        detector.onKeyDown(UP, 1);
        detector.onKeyDown(UP, 2);
        detector.onKeyUp(UP);
        advance(1000);
        assertEquals(List.of("vol " + UP, "vol " + UP), log);
    }
}
