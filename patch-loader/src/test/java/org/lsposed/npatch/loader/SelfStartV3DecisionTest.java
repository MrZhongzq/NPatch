package org.lsposed.npatch.loader;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.share.SelfStartDecision;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SelfStartV3DecisionTest {

    // ---- shouldSkipJob ----
    @Test public void jobSkippedWhenOnAndBackground() {
        assertTrue(SelfStartDecision.shouldSkipJob(true, false));
    }
    @Test public void jobNotSkippedWhenForeground() {
        assertFalse(SelfStartDecision.shouldSkipJob(true, true));
    }
    @Test public void jobNotSkippedWhenOff() {
        assertFalse(SelfStartDecision.shouldSkipJob(false, false));
    }

    // ---- shouldSkipService ----
    private Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }
    private static final String SVC = "com.evil.KeepAliveService";

    @Test public void serviceSkippedWhenDisabledAndBackground() {
        assertTrue(SelfStartDecision.shouldSkipService(SVC, set(SVC), false));
    }
    @Test public void serviceNotSkippedWhenForeground() {
        assertFalse(SelfStartDecision.shouldSkipService(SVC, set(SVC), true));
    }
    @Test public void serviceNotSkippedWhenNotInSet() {
        assertFalse(SelfStartDecision.shouldSkipService(SVC, set("com.other.X"), false));
    }
    @Test public void serviceNullSafe() {
        assertFalse(SelfStartDecision.shouldSkipService(null, set(SVC), false));
        assertFalse(SelfStartDecision.shouldSkipService(SVC, null, false));
    }
}
