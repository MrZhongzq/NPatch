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
import org.lsposed.npatch.share.PatchConfig
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object MirrorSyncManager {

    private const val TAG = "MirrorSyncManager"
    private const val META_DATA_KEY = "npatch"
    private const val MIRROR_DIR_NAME = "SAF"
    private const val PROVIDER_SUFFIX = ".MTDataFilesProvider"
    private const val METHOD_SET_LAST_MODIFIED = "mt:setLastModified"
    private const val EXTRA_URI = "uri"
    private const val EXTRA_TIME = "time"
    private const val TIMESTAMP_TOLERANCE_MS = 2000L

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
            for (target in loadMirrorTargets(context)) {
                runCatching {
                    syncTarget(context, target, File(baseDir, target.packageName))
                }.onFailure {
                    Log.w(TAG, "Mirror sync failed for ${target.packageName}", it)
                }
            }
        }
    }

    fun hasMirrorTargets(context: Context): Boolean {
        return runCatching { loadMirrorTargets(context).isNotEmpty() }.getOrDefault(false)
    }

    private fun syncTarget(context: Context, target: MirrorTarget, mirrorRoot: File) {
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
        syncDirectory(resolver, target.authority, rootEntry, mirrorRoot)
    }

    private fun syncDirectory(
        resolver: ContentResolver,
        authority: String,
        remoteDir: RemoteEntry,
        localDir: File
    ) {
        if (localDir.isFile) {
            localDir.delete()
        }
        if (!localDir.exists()) {
            localDir.mkdirs()
        }

        val remoteChildren = listRemoteChildren(resolver, authority, remoteDir.documentId).associateBy { it.displayName }
        val localChildren = localDir.listFiles().orEmpty().associateBy { it.name }
        val names = linkedSetOf<String>().apply {
            addAll(remoteChildren.keys)
            addAll(localChildren.keys)
        }

        for (name in names.sorted()) {
            val remoteChild = remoteChildren[name]
            val localChild = localChildren[name] ?: File(localDir, name)
            val documentId = remoteChild?.documentId ?: joinDocumentId(remoteDir.documentId, name)
            syncEntry(resolver, authority, documentId, remoteChild, localChild)
        }
    }

    private fun syncEntry(
        resolver: ContentResolver,
        authority: String,
        documentId: String,
        remoteEntry: RemoteEntry?,
        localFile: File
    ) {
        val remote = remoteEntry ?: queryRemoteEntry(resolver, authority, documentId)
        val localExists = localFile.exists()
        if (remote == null && !localExists) {
            return
        }

        if (remote != null && remote.isDirectory) {
            if (!localExists || localFile.isDirectory) {
                syncDirectory(resolver, authority, remote, localFile)
            } else {
                resolveTypeConflict(resolver, authority, documentId, remote, localFile)
            }
            return
        }

        if (localExists && localFile.isDirectory) {
            if (remote == null) {
                if (ensureRemoteDirectory(resolver, authority, documentId)) {
                    queryRemoteEntry(resolver, authority, documentId)?.let {
                        syncDirectory(resolver, authority, it, localFile)
                    }
                }
            } else {
                resolveTypeConflict(resolver, authority, documentId, remote, localFile)
            }
            return
        }

        when {
            remote == null && localExists -> copyLocalToRemote(resolver, authority, documentId, localFile)
            remote != null && !localExists -> copyRemoteToLocal(resolver, authority, remote, localFile)
            remote != null && localExists -> {
                val localNewer = localFile.lastModified() > remote.lastModified + TIMESTAMP_TOLERANCE_MS
                val remoteNewer = remote.lastModified > localFile.lastModified() + TIMESTAMP_TOLERANCE_MS
                val sizeDifferent = localFile.length() != remote.size
                when {
                    localNewer -> copyLocalToRemote(resolver, authority, documentId, localFile)
                    remoteNewer -> copyRemoteToLocal(resolver, authority, remote, localFile)
                    sizeDifferent -> {
                        if (localFile.lastModified() >= remote.lastModified) {
                            copyLocalToRemote(resolver, authority, documentId, localFile)
                        } else {
                            copyRemoteToLocal(resolver, authority, remote, localFile)
                        }
                    }
                }
            }
        }
    }

    private fun resolveTypeConflict(
        resolver: ContentResolver,
        authority: String,
        documentId: String,
        remoteEntry: RemoteEntry,
        localFile: File
    ) {
        val localWins = localFile.lastModified() >= remoteEntry.lastModified
        if (localWins) {
            deleteRemoteEntry(resolver, authority, documentId)
            if (localFile.isDirectory) {
                if (ensureRemoteDirectory(resolver, authority, documentId)) {
                    queryRemoteEntry(resolver, authority, documentId)?.let {
                        syncDirectory(resolver, authority, it, localFile)
                    }
                }
            } else {
                copyLocalToRemote(resolver, authority, documentId, localFile)
            }
        } else {
            localFile.deleteRecursively()
            if (remoteEntry.isDirectory) {
                syncDirectory(resolver, authority, remoteEntry, localFile)
            } else {
                copyRemoteToLocal(resolver, authority, remoteEntry, localFile)
            }
        }
    }

    private fun copyRemoteToLocal(
        resolver: ContentResolver,
        authority: String,
        remoteEntry: RemoteEntry,
        localFile: File
    ) {
        localFile.parentFile?.mkdirs()
        resolver.openInputStream(buildDocumentUri(authority, remoteEntry.documentId))?.use { input ->
            FileOutputStream(localFile, false).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Unable to open remote document ${remoteEntry.documentId}")
        if (remoteEntry.lastModified > 0L) {
            localFile.setLastModified(remoteEntry.lastModified)
        }
    }

    private fun copyLocalToRemote(
        resolver: ContentResolver,
        authority: String,
        documentId: String,
        localFile: File
    ) {
        ensureRemoteParentDirectory(resolver, authority, documentId)
        var remoteEntry = queryRemoteEntry(resolver, authority, documentId)
        if (remoteEntry?.isDirectory == true) {
            deleteRemoteEntry(resolver, authority, documentId)
            remoteEntry = null
        }
        if (remoteEntry == null) {
            val parentDocumentId = parentDocumentId(documentId) ?: return
            val parentUri = buildDocumentUri(authority, parentDocumentId)
            runCatching {
                DocumentsContract.createDocument(
                    resolver,
                    parentUri,
                    guessMimeType(localFile.name),
                    localFile.name
                )
            }
            remoteEntry = queryRemoteEntry(resolver, authority, documentId)
            if (remoteEntry == null) {
                return
            }
        }
        val targetUri = buildDocumentUri(authority, documentId)
        resolver.openFileDescriptor(targetUri, "rwt")?.use { descriptor ->
            localFile.inputStream().use { input ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
        } ?: throw IllegalStateException("Unable to open remote output ${documentId}")
        setRemoteLastModified(resolver, targetUri, localFile.lastModified())
    }

    private fun ensureRemoteParentDirectory(
        resolver: ContentResolver,
        authority: String,
        documentId: String
    ) {
        val parentDocumentId = parentDocumentId(documentId) ?: return
        if (parentDocumentId.contains('/')) {
            ensureRemoteDirectory(resolver, authority, parentDocumentId)
        }
    }

    private fun ensureRemoteDirectory(
        resolver: ContentResolver,
        authority: String,
        documentId: String
    ): Boolean {
        val existing = queryRemoteEntry(resolver, authority, documentId)
        if (existing != null) {
            return existing.isDirectory
        }
        val parentDocumentId = parentDocumentId(documentId) ?: return false
        if (!ensureRemoteDirectory(resolver, authority, parentDocumentId) && parentDocumentId.contains('/')) {
            return false
        }
        val name = documentId.substringAfterLast('/')
        val parentUri = buildDocumentUri(authority, parentDocumentId)
        runCatching {
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
            )
        }
        return queryRemoteEntry(resolver, authority, documentId)?.isDirectory == true
    }

    private fun deleteRemoteEntry(
        resolver: ContentResolver,
        authority: String,
        documentId: String
    ) {
        runCatching {
            DocumentsContract.deleteDocument(resolver, buildDocumentUri(authority, documentId))
        }.onFailure {
            Log.w(TAG, "Failed to delete remote entry $documentId", it)
        }
    }

    private fun listRemoteChildren(
        resolver: ContentResolver,
        authority: String,
        documentId: String
    ): List<RemoteEntry> {
        val uri = DocumentsContract.buildChildDocumentsUri(authority, documentId)
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
        return DocumentsContract.buildDocumentUri(authority, documentId)
    }

    private fun setRemoteLastModified(resolver: ContentResolver, uri: Uri, time: Long) {
        val extras = Bundle().apply {
            putParcelable(EXTRA_URI, uri)
            putLong(EXTRA_TIME, time)
        }
        runCatching {
            resolver.call(uri, METHOD_SET_LAST_MODIFIED, null, extras)
        }.onFailure {
            Log.w(TAG, "Failed to set last modified for $uri", it)
        }
    }

    private fun joinDocumentId(parentDocumentId: String, name: String): String {
        return if (parentDocumentId.isEmpty()) name else "$parentDocumentId/$name"
    }

    private fun parentDocumentId(documentId: String): String? {
        val slash = documentId.lastIndexOf('/')
        return if (slash >= 0) documentId.substring(0, slash) else null
    }

    private fun guessMimeType(name: String): String {
        val lowerName = name.lowercase(Locale.ROOT)
        return when {
            lowerName.endsWith(".xml") -> "text/xml"
            lowerName.endsWith(".json") -> "application/json"
            lowerName.endsWith(".txt") || lowerName.endsWith(".log") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun getMirrorBaseDir(context: Context): File? {
        val mediaDir = context.externalMediaDirs.firstOrNull() ?: return null
        return File(mediaDir, MIRROR_DIR_NAME)
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
