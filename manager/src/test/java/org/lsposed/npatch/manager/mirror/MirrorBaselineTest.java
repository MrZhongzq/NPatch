package org.lsposed.npatch.manager.mirror;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.manager.mirror.MirrorBaseline.ChangeSet;
import org.lsposed.npatch.manager.mirror.MirrorBaseline.FileSig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class MirrorBaselineTest {

    private File tmp() throws Exception {
        return Files.createTempDirectory("mb").toFile();
    }

    private void write(File f, String s) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void snapshot_lists_files_relative() throws Exception {
        File root = tmp();
        write(new File(root, "databases/a.db"), "x");
        write(new File(root, "shared_prefs/p.xml"), "yy");
        Map<String, FileSig> snap = MirrorBaseline.snapshot(root);
        assertTrue(snap.containsKey("databases/a.db"));
        assertTrue(snap.containsKey("shared_prefs/p.xml"));
        assertEquals(1L, snap.get("databases/a.db").size);
        assertEquals(2L, snap.get("shared_prefs/p.xml").size);
    }

    @Test
    public void diff_detects_added_modified_deleted() {
        Map<String, FileSig> base = new HashMap<>();
        base.put("keep", new FileSig(1, 1));
        base.put("mod", new FileSig(1, 1));
        base.put("del", new FileSig(1, 1));
        Map<String, FileSig> cur = new HashMap<>();
        cur.put("keep", new FileSig(1, 1));
        cur.put("mod", new FileSig(2, 9));
        cur.put("new", new FileSig(1, 1));
        ChangeSet d = MirrorBaseline.diff(cur, base);
        assertEquals(java.util.Collections.singleton("new"), d.added);
        assertEquals(java.util.Collections.singleton("mod"), d.modified);
        assertEquals(java.util.Collections.singleton("del"), d.deleted);
    }

    @Test
    public void unchanged_snapshot_yields_empty_changeset() {
        Map<String, FileSig> m = new HashMap<>();
        m.put("a", new FileSig(3, 7));
        assertTrue(MirrorBaseline.diff(m, m).isEmpty());
    }

    @Test
    public void save_then_load_roundtrips() throws Exception {
        File dir = tmp();
        Map<String, FileSig> m = new HashMap<>();
        m.put("a/b.db", new FileSig(5, 123));
        MirrorBaseline.save(dir, "com.x", m);
        assertEquals(m, MirrorBaseline.load(dir, "com.x"));
    }

    @Test
    public void load_missing_returns_empty() throws Exception {
        assertTrue(MirrorBaseline.load(tmp(), "none").isEmpty());
    }
}
