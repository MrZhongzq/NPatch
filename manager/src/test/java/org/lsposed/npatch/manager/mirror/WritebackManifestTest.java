package org.lsposed.npatch.manager.mirror;

import static org.junit.Assert.*;

import com.google.gson.Gson;

import org.junit.Test;
import org.lsposed.npatch.share.WritebackManifest;

public class WritebackManifestTest {

    @Test
    public void gson_roundtrip() {
        WritebackManifest m = new WritebackManifest();
        m.changes.add(new WritebackManifest.Change("databases/msg.db", WritebackManifest.OP_PUT));
        m.changes.add(new WritebackManifest.Change("databases/msg.db-wal", WritebackManifest.OP_DELETE));
        String json = new Gson().toJson(m);
        WritebackManifest back = new Gson().fromJson(json, WritebackManifest.class);
        assertEquals(1, back.version);
        assertEquals(2, back.changes.size());
        assertEquals("PUT", back.changes.get(0).op);
        assertEquals("databases/msg.db", back.changes.get(0).relPath);
        assertEquals("databases/msg.db-wal", back.changes.get(1).relPath);
        assertEquals("DELETE", back.changes.get(1).op);
    }

    @Test
    public void constants_present() {
        assertEquals("npatch_writeback", WritebackManifest.DIR);
        assertEquals(".ready", WritebackManifest.READY);
        assertEquals("npatch_writeback_applied", WritebackManifest.APPLIED_MARKER);
    }
}
