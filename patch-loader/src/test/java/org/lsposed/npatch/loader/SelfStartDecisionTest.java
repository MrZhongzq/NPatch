package org.lsposed.npatch.loader;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.share.SelfStartDecision;
import org.lsposed.npatch.share.SelfStartDefaults;

import java.util.Set;

public class SelfStartDecisionTest {

    private static final String BOOT = "android.intent.action.BOOT_COMPLETED";
    private static final String MIPUSH = "com.xiaomi.mipush.RECEIVE_MESSAGE";
    private static final String CONN = "android.net.conn.CONNECTIVITY_CHANGE";

    private Set<String> bl() {
        return SelfStartDefaults.defaultBlacklistSet();
    }

    @Test
    public void nullOrEmptyActionAllowed() {
        assertFalse(SelfStartDecision.decide(true, null, bl(), false, false).block);
        assertFalse(SelfStartDecision.decide(true, "", bl(), false, false).block);
    }

    @Test
    public void disabledAllowsEverything() {
        SelfStartDecision.Result r = SelfStartDecision.decide(false, BOOT, bl(), false, false);
        assertFalse(r.block);
        assertEquals("ALLOW_DISABLED", r.reason);
    }

    @Test
    public void pushAlwaysAllowedEvenIfBlacklisted() {
        Set<String> withPush = SelfStartDefaults.defaultBlacklistSet();
        withPush.add(MIPUSH); // even if user wrongly blacklists it
        SelfStartDecision.Result r = SelfStartDecision.decide(true, MIPUSH, withPush, false, false);
        assertFalse(r.block);
        assertEquals("ALLOW_PUSH", r.reason);
    }

    @Test
    public void foregroundAllowsBlacklisted() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, BOOT, bl(), true, false);
        assertFalse(r.block);
        assertEquals("ALLOW_UI_PRESENT", r.reason);
    }

    @Test
    public void selfSentAllowed() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, BOOT, bl(), false, true);
        assertFalse(r.block);
        assertEquals("ALLOW_SELF_SENT", r.reason);
    }

    @Test
    public void blacklistedBootBlocked() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, BOOT, bl(), false, false);
        assertTrue(r.block);
        assertEquals("BLOCK_BLACKLIST", r.reason);
    }

    @Test
    public void nonBlacklistedAllowedByDefault() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, CONN, bl(), false, false);
        assertFalse(r.block);
        assertEquals("ALLOW_DEFAULT", r.reason);
    }

    @Test
    public void nullBlacklistTreatedAsEmpty() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, BOOT, null, false, false);
        assertFalse(r.block);
        assertEquals("ALLOW_DEFAULT", r.reason);
    }

    @Test
    public void defaultBlacklistContainsBootNotMipushNotConnectivity() {
        Set<String> d = SelfStartDefaults.defaultBlacklistSet();
        assertTrue(d.contains(BOOT));
        assertFalse(d.contains(MIPUSH));
        assertFalse(d.contains(CONN));
    }

    @Test
    public void isPushActionCoversMipushAndFcm() {
        assertTrue(SelfStartDefaults.isPushAction(MIPUSH));
        assertTrue(SelfStartDefaults.isPushAction("com.google.firebase.MESSAGING_EVENT"));
        assertFalse(SelfStartDefaults.isPushAction(BOOT));
        assertFalse(SelfStartDefaults.isPushAction(null));
    }
}
