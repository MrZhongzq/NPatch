package org.lsposed.npatch.manager

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lsposed.npatch.manager.mirror.MirrorBaseline
import org.lsposed.npatch.manager.mirror.SyncDecision
import org.lsposed.npatch.manager.mirror.WriteBackQueue
import org.lsposed.npatch.share.PatchConfig
import org.lsposed.npatch.share.WritebackManifest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Mirrors a patched app's private data (via the app-process [NPatchDataProvider]) to shared storage
 * so it is browsable/backup-able with any file manager, and writes user edits back safely.
 *
 * IMPORTANT (see docs/superpowers/specs/2026-08-19-mirror-safe-writeback-design.md): the old design
 * did a bidirectional file sync every 30s, guessing direction from mtime/size. That truncated and
 * corrupted the LIVE SQLite databases of running apps (e.g. QQ chat records). This version:
 *   - EXPORT (app -> mirror) is always read-only ("r"); it never writes the real data.
 *   - WRITE-BACK (mirror -> app) happens ONLY for files the user manually changed in the mirror
 *     (detected against a baseline), and never touches the live databases directly. Instead it stages
 *     the changes under <dataDir>/npatch_writeback/ and the patch-loader applies them at app startup,
 *     before any database is opened.
 * Write-back is currently limited to the internal data root (where SQLite lives); other roots export
 * only for now.
 */
object MirrorSyncManager {

    private const val TAG = "MirrorSyncManager"
    private const val META_DATA_KEY = "npatch"
    private const val MIRROR_DIR_NAME = "SAF"
    private const val PROVIDER_SUFFIX = ".NPatchDataProvider"
    private const val PATH_CHILDREN = "children"
    private const val PATH_DOCUMENT = "document"
    private const val PATH_FILE = "file"
    private const val METHOD_DELETE = "npatch:delete"
    private const val EXTRA_DOCUMENT_ID = "id"
    private const val ROOT_DATA = "data"

    private const val BASELINE_DIR = "mirror_baseline"
    private const val QUEUE_FILE = "mirror_writeback_queue.json"

    private val gson = Gson()
    private val syncMutex = Mutex()
    private val documentProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE
    )

    private data class MirrorTarget(
        val packageName: String,
        val authority: String
    )

    private data class RemoteEntry(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val lastModified: Long,
        val size: Long
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    suspend fun syncConfiguredApps(context: Context) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val baseDir = getMirrorBaseDir(context) ?: return@withLock
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            val baselineDir = File(context.filesDir, BASELINE_DIR)
            val queueFile = File(context.filesDir, QUEUE_FILE)
            for (target in loadMirrorTargets(context)) {
                runCatching {
                    syncTarget(context, target, File(baseDir, target.packageName), baselineDir, queueFile)
                }.onFailure {
                    Log.w(TAG, "Mirror sync failed for ${target.packageName}", it)
                }
            }
        }
    }

    fun hasMirrorTargets(context: Context): Boolean {
        return runCatching { loadMirrorTargets(context).isNotEmpty() }.getOrDefault(false)
    }

    /** Whether a package has a ready write-back staging awaiting apply on next app start. */
    fun isWritebackPending(context: Context, packageName: String): Boolean {
        return WriteBackQueue.isPending(File(context.filesDir, QUEUE_FILE), packageName)
    }

    private fun syncTarget(
        context: Context,
        target: MirrorTarget,
        mirrorRoot: File,
        baselineDir: File,
        queueFile: File
    ) {
        val resolver = context.contentResolver
        val rootEntry = queryRemoteEntry(resolver, target.authority, target.packageName)
        if (rootEntry == null) {
            Log.w(TAG, "Provider root unavailable for ${target.packageName}")
            return
        }
        if (mirrorRoot.isFile) {
            mirrorRoot.delete()
        }
        if (!mirrorRoot.exists()) {
            mirrorRoot.mkdirs()
        }

        // The loader records an "applied" marker after it consumes a staging on app start. Seeing it
        // means our write-back landed: dequeue and reset the data-root baseline so the next round
        // re-exports from the app's new state (rather than re-detecting the just-applied files as
        // manual changes).
        handleAppliedMarker(resolver, target, baselineDir, queueFile)

        for (rootDir in listRemoteChildren(resolver, target.authority, target.packageName)) {
            if (!rootDir.isDirectory) continue
            runCatching {
                syncRoot(
                    resolver, target, rootDir.displayName, rootDir.documentId,
                    File(mirrorRoot, rootDir.displayName), baselineDir, queueFile
                )
            }.onFailure {
                Log.w(TAG, "Mirror root '${rootDir.displayName}' failed for ${target.packageName}", it)
            }
        }
    }

    private fun handleAppliedMarker(
        resolver: ContentResolver,
        target: MirrorTarget,
        baselineDir: File,
        queueFile: File
    ) {
        val markerId = "${target.packageName}/$ROOT_DATA/files/${WritebackManifest.APPLIED_MARKER}"
        if (queryRemoteEntry(resolver, target.authority, markerId) == null) {
            return
        }
        deleteRemoteEntry(resolver, target.authority, markerId)
        WriteBackQueue.clear(queueFile, target.packageName)
        File(baselineDir, "${target.packageName}__$ROOT_DATA.json").delete()
    }

    private fun syncRoot(
        resolver: ContentResolver,
        target: MirrorTarget,
        rootName: String,
        rootDocumentId: String,
        localRootDir: File,
        baselineDir: File,
        queueFile: File
    ) {
        if (localRootDir.isFile) localRootDir.delete()
        if (!localRootDir.exists()) localRootDir.mkdirs()

        val baselineKey = "${target.packageName}__$rootName"
        // First run for this root (no baseline yet): we have no "previous mirror state" to diff
        // against, so every existing mirror file would falsely look like a manual addition and be
        // written back. Instead, treat this round as export-only and just establish the baseline;
        // manual-change detection starts next round. Also applies after an applied-marker reset.
        val isFirstRun = !File(baselineDir, "$baselineKey.json").exists()
        val baseline = MirrorBaseline.load(baselineDir, baselineKey)
        val changeSet = if (isFirstRun) {
            MirrorBaseline.ChangeSet(emptySet(), emptySet(), emptySet())
        } else {
            MirrorBaseline.diff(MirrorBaseline.snapshot(localRootDir), baseline)
        }
        val remoteFiles = collectRemoteFiles(resolver, target.authority, rootDocumentId)
        val isDataRoot = rootName == ROOT_DATA

        val puts = ArrayList<String>()
        val deletes = ArrayList<String>()

        val allPaths = HashSet<String>()
        allPaths.addAll(remoteFiles.keys)
        allPaths.addAll(changeSet.added)
        allPaths.addAll(changeSet.modified)
        allPaths.addAll(changeSet.deleted)

        for (relPath in allPaths) {
            // Per-file isolation: one bad entry must never abort the whole root. QQ caches files
            // named by URL ('http://qh.qlogo.cn/...?b=qq&ek=...'), whose ':' '?' '&' are illegal on
            // the sdcardfs mirror -> open EPERM. Without this, that single file aborted the root and
            // databases/ never synced (and the baseline never got saved). Skip the file, carry on.
            runCatching {
                val manuallyChanged = changeSet.added.contains(relPath) ||
                    changeSet.modified.contains(relPath) ||
                    changeSet.deleted.contains(relPath)
                val remoteEntry = remoteFiles[relPath]
                val localFile = File(localRootDir, relPath)
                // 3s tolerance absorbs the ext4(ms) vs sdcardfs(second-rounded) mtime gap so we
                // don't re-export every file each round; a genuine app update moves mtime past that.
                val remoteChanged = remoteEntry != null &&
                    (!localFile.exists() || localFile.length() != remoteEntry.size ||
                        remoteEntry.lastModified > localFile.lastModified() + 3000L)

                when (SyncDecision.decide(manuallyChanged, remoteChanged)) {
                    SyncDecision.Action.EXPORT ->
                        if (remoteEntry != null) copyRemoteToLocal(resolver, target.authority, remoteEntry, localFile)
                    SyncDecision.Action.WRITEBACK ->
                        if (isDataRoot) {
                            if (changeSet.deleted.contains(relPath)) deletes.add(relPath) else puts.add(relPath)
                        }
                    SyncDecision.Action.SKIP -> {}
                }
            }.onFailure {
                Log.d(TAG, "Skip mirror entry $rootName/$relPath (${it.message})")
            }
        }

        // Baseline reflects the mirror as the sync engine left it, so the user's just-staged edits
        // are not re-detected next round (the loader's applied-marker resets it after apply).
        MirrorBaseline.save(baselineDir, baselineKey, MirrorBaseline.snapshot(localRootDir))

        if (isDataRoot && (puts.isNotEmpty() || deletes.isNotEmpty())) {
            writeStaging(resolver, target, localRootDir, puts, deletes)
            WriteBackQueue.markReady(queueFile, target.packageName)
        }
    }

    /** Recursively collect every remote file under [rootDocumentId], keyed by path relative to it. */
    private fun collectRemoteFiles(
        resolver: ContentResolver,
        authority: String,
        rootDocumentId: String
    ): Map<String, RemoteEntry> {
        val out = HashMap<String, RemoteEntry>()
        collectRemoteFilesInto(resolver, authority, rootDocumentId, "", out)
        return out
    }

    private fun collectRemoteFilesInto(
        resolver: ContentResolver,
        authority: String,
        documentId: String,
        prefix: String,
        out: HashMap<String, RemoteEntry>
    ) {
        for (child in listRemoteChildren(resolver, authority, documentId)) {
            val rel = if (prefix.isEmpty()) child.displayName else "$prefix/${child.displayName}"
            if (child.isDirectory) {
                collectRemoteFilesInto(resolver, authority, child.documentId, rel, out)
            } else {
                out[rel] = child
            }
        }
    }

    /**
     * Stage the manual changes into <dataDir>/npatch_writeback/ via the provider (writing to the
     * staging dir only — never the live databases). The loader applies them on next app start.
     */
    private fun writeStaging(
        resolver: ContentResolver,
        target: MirrorTarget,
        localDataRoot: File,
        puts: List<String>,
        deletes: List<String>
    ) {
        val stagingRoot = "${target.packageName}/$ROOT_DATA/${WritebackManifest.DIR}"
        // Clear any previous staging so a stale .ready/payload can't be applied.
        deleteRemoteEntry(resolver, target.authority, stagingRoot)

        val manifest = WritebackManifest()
        for (relPath in puts) {
            val local = File(localDataRoot, relPath)
            if (!local.isFile) continue
            writeRemoteFileFromLocal(resolver, target.authority,
                "$stagingRoot/${WritebackManifest.PAYLOAD}/$relPath", local)
            manifest.changes.add(WritebackManifest.Change(relPath, WritebackManifest.OP_PUT))
        }
        for (relPath in deletes) {
            manifest.changes.add(WritebackManifest.Change(relPath, WritebackManifest.OP_DELETE))
        }
        writeRemoteBytes(resolver, target.authority,
            "$stagingRoot/${WritebackManifest.MANIFEST}", gson.toJson(manifest).toByteArray())
        // .ready LAST: the loader only applies a staging that has it.
        writeRemoteBytes(resolver, target.authority,
            "$stagingRoot/${WritebackManifest.READY}", ByteArray(0))
    }

    private fun copyRemoteToLocal(
        resolver: ContentResolver,
        authority: String,
        remoteEntry: RemoteEntry,
        localFile: File
    ) {
        localFile.parentFile?.mkdirs()
        resolver.openFileDescriptor(buildFileUri(authority, remoteEntry.documentId), "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                FileOutputStream(localFile, false).use { output ->
                    input.copyTo(output)
                }
            }
        } ?: throw IllegalStateException("Unable to open remote document ${remoteEntry.documentId}")
        if (remoteEntry.lastModified > 0L) {
            localFile.setLastModified(remoteEntry.lastModified)
        }
    }

    private fun writeRemoteFileFromLocal(
        resolver: ContentResolver,
        authority: String,
        documentId: String,
        localFile: File
    ) {
        resolver.openFileDescriptor(buildFileUri(authority, documentId), "rwt")?.use { descriptor ->
            localFile.inputStream().use { input ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
        } ?: throw IllegalStateException("Unable to open remote output $documentId")
    }

    private fun writeRemoteBytes(
        resolver: ContentResolver,
        authority: String,
        documentId: String,
        bytes: ByteArray
    ) {
        resolver.openFileDescriptor(buildFileUri(authority, documentId), "rwt")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                output.write(bytes)
            }
        } ?: throw IllegalStateException("Unable to open remote output $documentId")
    }

    private fun deleteRemoteEntry(
        resolver: ContentResolver,
        authority: String,
        documentId: String
    ) {
        callProvider(
            resolver,
            buildDocumentUri(authority, documentId),
            METHOD_DELETE,
            Bundle().apply { putString(EXTRA_DOCUMENT_ID, documentId) }
        )
    }

    private fun listRemoteChildren(
        resolver: ContentResolver,
        authority: String,
        documentId: String
    ): List<RemoteEntry> {
        val uri = buildChildrenUri(authority, documentId)
        val entries = ArrayList<RemoteEntry>()
        resolver.query(uri, documentProjection, null, null, null)?.use { cursor ->
            val documentIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val displayNameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val lastModifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                entries += RemoteEntry(
                    documentId = cursor.getString(documentIdIndex),
                    displayName = cursor.getString(displayNameIndex),
                    mimeType = cursor.getString(mimeTypeIndex),
                    lastModified = cursor.getLong(lastModifiedIndex),
                    size = cursor.getLong(sizeIndex)
                )
            }
        }
        return entries
    }

    private fun queryRemoteEntry(
        resolver: ContentResolver,
        authority: String,
        documentId: String
    ): RemoteEntry? {
        val uri = buildDocumentUri(authority, documentId)
        return runCatching {
            resolver.query(uri, documentProjection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                RemoteEntry(
                    documentId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)),
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)),
                    mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)),
                    lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)),
                    size = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
                )
            }
        }.getOrNull()
    }

    private fun buildDocumentUri(authority: String, documentId: String): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(PATH_DOCUMENT)
            .appendQueryParameter(EXTRA_DOCUMENT_ID, documentId)
            .build()
    }

    private fun buildChildrenUri(authority: String, documentId: String): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(PATH_CHILDREN)
            .appendQueryParameter(EXTRA_DOCUMENT_ID, documentId)
            .build()
    }

    private fun buildFileUri(authority: String, documentId: String): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(PATH_FILE)
            .appendQueryParameter(EXTRA_DOCUMENT_ID, documentId)
            .build()
    }

    private fun callProvider(
        resolver: ContentResolver,
        uri: Uri,
        method: String,
        extras: Bundle
    ) {
        runCatching {
            resolver.call(uri, method, null, extras)
        }.onFailure {
            Log.w(TAG, "Provider call failed: $method $uri", it)
        }
    }

    private fun getMirrorBaseDir(context: Context): File? {
        // Mirror into shared internal storage /Android/media/<manager-pkg>/SAF so the data is
        // browsable with any file manager without scoped-storage restrictions. externalMediaDirs is
        // the /Android/media path; fall back to the well-known location when it returns nothing.
        val mediaDir = context.externalMediaDirs.firstOrNull(::isUsableDir)
            ?: File("/storage/emulated/0/Android/media/${context.packageName}").takeIf {
                it.parentFile?.exists() == true || it.mkdirs() || it.exists()
            }
            ?: return null
        return File(mediaDir, MIRROR_DIR_NAME)
    }

    private fun isUsableDir(dir: File?): Boolean {
        return dir != null && (dir.exists() || dir.mkdirs())
    }

    private fun loadMirrorTargets(context: Context): List<MirrorTarget> {
        return getInstalledApplications(context.packageManager).mapNotNull { appInfo ->
            val config = parsePatchConfig(appInfo) ?: return@mapNotNull null
            if (!config.mirrorMode) {
                return@mapNotNull null
            }
            MirrorTarget(
                packageName = appInfo.packageName,
                authority = appInfo.packageName + PROVIDER_SUFFIX
            )
        }.sortedBy { it.packageName }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledApplications(packageManager: PackageManager): List<ApplicationInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }
    }

    private fun parsePatchConfig(appInfo: ApplicationInfo): PatchConfig? {
        val encoded = appInfo.metaData?.getString(META_DATA_KEY) ?: return null
        return runCatching {
            val json = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            gson.fromJson(json, PatchConfig::class.java)
        }.getOrNull()
    }
}
