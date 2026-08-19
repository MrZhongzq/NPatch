package org.lsposed.npatch.manager.mirror;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.share.MirrorManifest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MirrorManifestTest {

    private File tmp() throws Exception {
        return Files.createTempDirectory("mm").toFile();
    }

    private void write(File f, String s) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void write_lists_files_and_empty_dirs() throws Exception {
        File root = tmp();
        write(new File(root, "databases/a.db"), "xx");   // file, size 2
        //noinspection ResultOfMethodCallIgnored
        new File(root, "emptydir").mkdirs();             // empty dir
        write(new File(root, "files/sub/b.txt"), "y");   // nested file, size 1
        StringBuilder sb = new StringBuilder();
        MirrorManifest.write(root, sb);
        String out = sb.toString();
        assertTrue(out.contains("f\t2\t"));
        assertTrue(out.contains("\tdatabases/a.db\n"));
        assertTrue(out.lines().anyMatch(l -> l.startsWith("d\t") && l.endsWith("\temptydir")));
        assertTrue(out.lines().anyMatch(l -> l.startsWith("d\t") && l.endsWith("\tfiles")));
        assertTrue(out.lines().anyMatch(l -> l.startsWith("d\t") && l.endsWith("\tfiles/sub")));
        assertTrue(out.lines().anyMatch(l -> l.startsWith("f\t1\t") && l.endsWith("\tfiles/sub/b.txt")));
    }

    @Test
    public void parseLine_ok_and_special_chars() {
        MirrorManifest.Entry e = MirrorManifest.parseLine("f\t5\t123\tcache/http:/x?b=qq&e=1");
        assertNotNull(e);
        assertEquals('f', e.type);
        assertEquals(5, e.size);
        assertEquals(123, e.mtime);
        assertEquals("cache/http:/x?b=qq&e=1", e.path);
        MirrorManifest.Entry d = MirrorManifest.parseLine("d\t0\t9\temptydir");
        assertNotNull(d);
        assertEquals('d', d.type);
        assertTrue(d.isDirectory());
        assertEquals("emptydir", d.path);
    }

    @Test
    public void parseLine_malformed_returns_null() {
        assertNull(MirrorManifest.parseLine("garbage"));
        assertNull(MirrorManifest.parseLine("f\tNaN\t1\tp"));
        assertNull(MirrorManifest.parseLine(""));
        assertNull(MirrorManifest.parseLine(null));
    }

    @Test
    public void isSdcardfsSafe_rejects_illegal_chars() {
        assertTrue(MirrorManifest.isSdcardfsSafe("databases/a.db"));
        assertTrue(MirrorManifest.isSdcardfsSafe("files/sub/b.txt"));
        assertFalse(MirrorManifest.isSdcardfsSafe("cache/http:/x?b=qq&e=1")); // ':' and '?'
        assertFalse(MirrorManifest.isSdcardfsSafe("databases/beacon_db:qzone")); // ':'
        assertFalse(MirrorManifest.isSdcardfsSafe("a/b*c"));
    }

    @Test
    public void write_then_parse_roundtrip() throws Exception {
        File root = tmp();
        write(new File(root, "d1/f1"), "abc");
        StringBuilder sb = new StringBuilder();
        MirrorManifest.write(root, sb);
        for (String line : sb.toString().split("\n")) {
            if (line.isEmpty()) continue;
            assertNotNull("parse: " + line, MirrorManifest.parseLine(line));
        }
    }
}
