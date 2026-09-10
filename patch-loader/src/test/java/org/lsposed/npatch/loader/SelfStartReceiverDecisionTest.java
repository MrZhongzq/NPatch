package org.lsposed.npatch.loader;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.share.SelfStartDecision;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SelfStartReceiverDecisionTest {

    private static final String RCV = "com.evil.app.BootReceiver";

    private Set<String> disabled(String... c) {
        return new HashSet<>(java.util.Arrays.asList(c));
    }

    @Test public void masterOffAllowsAll() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(false, RCV, disabled(RCV), false, false);
        assertFalse(r.block); assertEquals("ALLOW_DISABLED", r.reason);
    }

    @Test public void nullReceiverAllowed() {
        SelfStartDecision.Result nullResult = SelfStartDecision.decideReceiver(true, null, disabled(RCV), false, false);
        assertFalse(nullResult.block); assertEquals("ALLOW_NULL", nullResult.reason);
        SelfStartDecision.Result emptyResult = SelfStartDecision.decideReceiver(true, "", disabled(RCV), false, false);
        assertFalse(emptyResult.block); assertEquals("ALLOW_NULL", emptyResult.reason);
    }

    @Test public void foregroundAllows() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(true, RCV, disabled(RCV), true, false);
        assertFalse(r.block); assertEquals("ALLOW_UI_PRESENT", r.reason);
    }

    @Test public void selfSentAllows() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(true, RCV, disabled(RCV), false, true);
        assertFalse(r.block); assertEquals("ALLOW_SELF_SENT", r.reason);
    }

    @Test public void disabledReceiverBlocked() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(true, RCV, disabled(RCV), false, false);
        assertTrue(r.block); assertEquals("BLOCK_RECEIVER_DISABLED", r.reason);
    }

    @Test public void enabledReceiverAllowed() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(true, RCV, disabled("com.other.X"), false, false);
        assertFalse(r.block); assertEquals("ALLOW_DEFAULT", r.reason);
    }

    @Test public void nullDisabledSetAllows() {
        assertFalse(SelfStartDecision.decideReceiver(true, RCV, null, false, false).block);
    }
}
