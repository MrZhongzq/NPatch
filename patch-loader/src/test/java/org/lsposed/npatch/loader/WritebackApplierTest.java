package org.lsposed.npatch.loader;

import static org.junit.Assert.*;

import com.google.gson.Gson;

import org.junit.Test;
import org.lsposed.npatch.share.WritebackManifest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class WritebackApplierTest {

    private File tmp() throws Exception {
        return Files.createTempDirectory("dd").toFile();
    }

    private void write(File f, String s) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    private String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void applies_put_and_delete_when_ready() throws Exception {
        File dd = tmp();
        write(new File(dd, "databases/msg.db"), "OLD");
        write(new File(dd, "databases/msg.db-wal"), "STALE"); // should be DELETEd
        write(new File(dd, "npatch_writeback/payload/databases/msg.db"), "NEW");
        WritebackManifest m = new WritebackManifest();
        m.changes.add(new WritebackManifest.Change("databases/msg.db", "PUT"));
        m.changes.add(new WritebackManifest.Change("databases/msg.db-wal", "DELETE"));
        write(new File(dd, "npatch_writeback/manifest.json"), new Gson().toJson(m));
        write(new File(dd, "npatch_writeback/.ready"), "");

        assertTrue(WritebackApplier.applyIfPending(dd));
        assertEquals("NEW", read(new File(dd, "databases/msg.db")));
        assertFalse(new File(dd, "databases/msg.db-wal").exists());
        assertFalse(new File(dd, "npatch_writeback").exists());
        assertTrue(new File(dd, "files/" + WritebackManifest.APPLIED_MARKER).exists());
    }

    @Test
    public void creates_new_file_when_put_target_absent() throws Exception {
        File dd = tmp();
        write(new File(dd, "npatch_writeback/payload/databases/new.db"), "FRESH");
        WritebackManifest m = new WritebackManifest();
        m.changes.add(new WritebackManifest.Change("databases/new.db", "PUT"));
        write(new File(dd, "npatch_writeback/manifest.json"), new Gson().toJson(m));
        write(new File(dd, "npatch_writeback/.ready"), "");

        assertTrue(WritebackApplier.applyIfPending(dd));
        assertEquals("FRESH", read(new File(dd, "databases/new.db")));
    }

    @Test
    public void noop_without_ready() throws Exception {
        File dd = tmp();
        write(new File(dd, "databases/msg.db"), "OLD");
        write(new File(dd, "npatch_writeback/manifest.json"), "{}"); // no .ready
        assertFalse(WritebackApplier.applyIfPending(dd));
        assertEquals("OLD", read(new File(dd, "databases/msg.db")));
        assertTrue(new File(dd, "npatch_writeback").exists()); // staging left intact for retry
    }

    @Test
    public void noop_without_staging() throws Exception {
        assertFalse(WritebackApplier.applyIfPending(tmp()));
    }
}
