package org.lsposed.npatch.manager.mirror;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

public class WriteBackQueueTest {

    private File qf() throws Exception {
        return new File(Files.createTempDirectory("q").toFile(), "q.json");
    }

    @Test
    public void mark_and_query() throws Exception {
        File f = qf();
        WriteBackQueue.markReady(f, "com.x");
        assertTrue(WriteBackQueue.isPending(f, "com.x"));
        assertFalse(WriteBackQueue.isPending(f, "com.y"));
    }

    @Test
    public void clear_removes() throws Exception {
        File f = qf();
        WriteBackQueue.markReady(f, "com.x");
        WriteBackQueue.clear(f, "com.x");
        assertFalse(WriteBackQueue.isPending(f, "com.x"));
    }

    @Test
    public void persists_across_reads() throws Exception {
        File f = qf();
        WriteBackQueue.markReady(f, "com.x");
        assertEquals(Collections.singleton("com.x"), WriteBackQueue.all(f));
    }

    @Test
    public void mark_is_idempotent() throws Exception {
        File f = qf();
        WriteBackQueue.markReady(f, "com.x");
        WriteBackQueue.markReady(f, "com.x");
        assertEquals(1, WriteBackQueue.all(f).size());
    }

    @Test
    public void empty_when_missing() throws Exception {
        assertTrue(WriteBackQueue.all(qf()).isEmpty());
    }
}
