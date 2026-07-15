package org.lsposed.npatch.metaloader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

public final class NPatchDataProvider extends ContentProvider {

    private static final String PATH_DOCUMENT = "document";
    private static final String PATH_CHILDREN = "children";
    private static final String PATH_FILE = "file";

    private static final String METHOD_MKDIRS = "npatch:mkdirs";
    private static final String METHOD_DELETE = "npatch:delete";
    private static final String METHOD_SET_LAST_MODIFIED = "npatch:setLastModified";

    private static final String EXTRA_DOCUMENT_ID = "id";
    private static final String EXTRA_TIME = "time";

    private static final String ROOT_DATA = "data";
    private static final String ROOT_ANDROID_DATA = "android_data";
    private static final String ROOT_ANDROID_OBB = "android_obb";
    private static final String ROOT_USER_DE_DATA = "user_de_data";

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
    };

    private String packageName;
    private File dataDir;
    private File deviceProtectedDataDir;
    private File externalDataDir;
    private File obbDir;

    @Override
    public boolean onCreate() {
        if (getContext() == null) {
            return false;
        }
        packageName = getContext().getPackageName();
        File filesDir = getContext().getFilesDir();
        if (filesDir != null) {
            dataDir = filesDir.getParentFile();
            if (dataDir != null) {
                String path = dataDir.getPath();
                String prefix = "/data/user/";
                if (path.startsWith(prefix)) {
                    deviceProtectedDataDir = new File("/data/user_de/" + path.substring(prefix.length()));
                }
            }
        }
        File appExternalFiles = getContext().getExternalFilesDir(null);
        if (appExternalFiles != null) {
            externalDataDir = appExternalFiles.getParentFile();
        }
        obbDir = getContext().getObbDir();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String pathType = firstPathSegment(uri);
        String documentId = uri.getQueryParameter(EXTRA_DOCUMENT_ID);
        String[] resolvedProjection = projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(resolvedProjection);
        try {
            if (PATH_DOCUMENT.equals(pathType)) {
                includeDocument(cursor, documentId, resolveDocumentFile(documentId, true), null);
            } else if (PATH_CHILDREN.equals(pathType)) {
                File parent = resolveDocumentFile(documentId, true);
                if (parent == null) {
                    includeRootDirectory(cursor, ROOT_DATA, dataDir);
                    includeRootDirectory(cursor, ROOT_ANDROID_DATA, externalDataDir);
                    includeRootDirectory(cursor, ROOT_ANDROID_OBB, obbDir);
                    includeRootDirectory(cursor, ROOT_USER_DE_DATA, deviceProtectedDataDir);
                    return cursor;
                }
                File[] children = parent.listFiles();
                if (children == null) {
                    return cursor;
                }
                Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File child : children) {
                    includeDocument(cursor, toDocumentId(child), child, null);
                }
            }
        } catch (FileNotFoundException ignored) {
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String documentId = uri.getQueryParameter(EXTRA_DOCUMENT_ID);
        try {
            File file = resolveDocumentFile(documentId, true);
            return getMimeType(file, documentId);
        } catch (FileNotFoundException e) {
            return "application/octet-stream";
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!PATH_FILE.equals(firstPathSegment(uri))) {
            throw new FileNotFoundException(uri.toString());
        }
        String documentId = uri.getQueryParameter(EXTRA_DOCUMENT_ID);
        File target = resolveDocumentFile(documentId, false);
        if (target == null) {
            throw new FileNotFoundException(uri.toString());
        }
        if (requiresWrite(mode)) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        String documentId = extras != null ? extras.getString(EXTRA_DOCUMENT_ID) : null;
        if (documentId == null || documentId.isEmpty()) {
            return result;
        }
        try {
            switch (method) {
                case METHOD_MKDIRS: {
                    File directory = resolveDocumentFile(documentId, false);
                    result.putBoolean("result", directory != null && (directory.exists() || directory.mkdirs()));
                    return result;
                }
                case METHOD_DELETE: {
                    File target = resolveDocumentFile(documentId, true);
                    result.putBoolean("result", target != null && deleteRecursively(target));
                    return result;
                }
                case METHOD_SET_LAST_MODIFIED: {
                    File target = resolveDocumentFile(documentId, true);
                    long time = extras != null ? extras.getLong(EXTRA_TIME) : 0L;
                    result.putBoolean("result", target != null && target.setLastModified(time));
                    return result;
                }
                default:
                    return super.call(method, arg, extras);
            }
        } catch (FileNotFoundException e) {
            result.putBoolean("result", false);
            return result;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private String firstPathSegment(Uri uri) {
        return uri.getPathSegments().isEmpty() ? "" : uri.getPathSegments().get(0);
    }

    private boolean requiresWrite(String mode) {
        return mode != null && (mode.contains("w") || mode.contains("+"));
    }

    private void includeRootDirectory(MatrixCursor cursor, String rootName, File root) throws FileNotFoundException {
        if (root != null && root.exists()) {
            includeDocument(cursor, packageName + "/" + rootName, root, rootName);
        }
    }

    private void includeDocument(MatrixCursor cursor, String documentId, File file, String displayNameOverride)
            throws FileNotFoundException {
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, getMimeType(file, documentId));
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, getDisplayName(documentId, file, displayNameOverride));
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file != null ? file.lastModified() : 0L);
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
        row.add(DocumentsContract.Document.COLUMN_SIZE, file != null && file.isFile() ? file.length() : 0L);
    }

    private String getDisplayName(String documentId, File file, String displayNameOverride) {
        if (displayNameOverride != null) {
            return displayNameOverride;
        }
        if (packageName.equals(documentId)) {
            return packageName;
        }
        if (file != null) {
            String name = file.getName();
            if (!name.isEmpty()) {
                return name;
            }
        }
        int separator = documentId.lastIndexOf('/');
        return separator >= 0 ? documentId.substring(separator + 1) : documentId;
    }

    private String getMimeType(File file, String documentId) {
        if (packageName.equals(documentId) || file == null || file.isDirectory()) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            String ext = name.substring(lastDot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    private File resolveDocumentFile(String documentId, boolean requireExists) throws FileNotFoundException {
        if (documentId == null || !documentId.startsWith(packageName)) {
            throw new FileNotFoundException(documentId + " not found");
        }
        String relative = documentId.substring(packageName.length());
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isEmpty()) {
            return null;
        }
        int slashIndex = relative.indexOf('/');
        String rootName = slashIndex >= 0 ? relative.substring(0, slashIndex) : relative;
        String childPath = slashIndex >= 0 ? relative.substring(slashIndex + 1) : "";
        File root = getRootDirectory(rootName);
        if (root == null) {
            throw new FileNotFoundException(documentId + " not found");
        }
        File target = childPath.isEmpty() ? root : new File(root, childPath);
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonicalTarget = childPath.isEmpty()
                    ? canonicalRoot
                    : new File(canonicalRoot, childPath).getCanonicalFile();
            if (!isFileUnder(canonicalRoot, canonicalTarget)) {
                throw new FileNotFoundException(documentId + " not found");
            }
            if (requireExists && !canonicalTarget.exists()) {
                throw new FileNotFoundException(documentId + " not found");
            }
            return canonicalTarget;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private File getRootDirectory(String rootName) {
        switch (rootName) {
            case ROOT_DATA:
                return dataDir;
            case ROOT_ANDROID_DATA:
                return externalDataDir;
            case ROOT_ANDROID_OBB:
                return obbDir;
            case ROOT_USER_DE_DATA:
                return deviceProtectedDataDir;
            default:
                return null;
        }
    }

    private String toDocumentId(File file) throws FileNotFoundException {
        try {
            File canonical = file.getCanonicalFile();
            for (String rootName : new String[]{ROOT_DATA, ROOT_ANDROID_DATA, ROOT_ANDROID_OBB, ROOT_USER_DE_DATA}) {
                File root = getRootDirectory(rootName);
                if (root == null) {
                    continue;
                }
                File canonicalRoot = root.getCanonicalFile();
                if (!isFileUnder(canonicalRoot, canonical)) {
                    continue;
                }
                String rootPath = canonicalRoot.getPath();
                String filePath = canonical.getPath();
                if (rootPath.equals(filePath)) {
                    return packageName + "/" + rootName;
                }
                return packageName + "/" + rootName + "/" + filePath.substring(rootPath.length() + 1);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        throw new FileNotFoundException(file + " not found");
    }

    private boolean isFileUnder(File root, File child) {
        String rootPath = root.getPath();
        String childPath = child.getPath();
        return rootPath.equals(childPath) || childPath.startsWith(rootPath + File.separator);
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }
}
