package org.lsposed.npatch.manager.mirror;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.manager.mirror.SyncDecision.Action;

public class SyncDecisionTest {

    @Test
    public void manual_change_writes_back_even_if_remote_also_changed() {
        assertEquals(Action.WRITEBACK, SyncDecision.decide(true, true));
        assertEquals(Action.WRITEBACK, SyncDecision.decide(true, false));
    }

    @Test
    public void only_remote_change_exports() {
        assertEquals(Action.EXPORT, SyncDecision.decide(false, true));
    }

    @Test
    public void nothing_changed_skips() {
        assertEquals(Action.SKIP, SyncDecision.decide(false, false));
    }
}
