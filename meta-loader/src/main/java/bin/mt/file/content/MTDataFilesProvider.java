package bin.mt.file.content;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.Os;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class MTDataFilesProvider extends DocumentsProvider {

    private static final String METHOD_PREFIX = "mt:";
    private static final String METHOD_CREATE_SYMLINK = "mt:createSymlink";
    private static final String METHOD_SET_PERMISSIONS = "mt:setPermissions";
    private static final String METHOD_SET_LAST_MODIFIED = "mt:setLastModified";

    private static final String EXTRA_MESSAGE = "message";
    private static final String EXTRA_RESULT = "result";
    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_PATH = "path";
    private static final String EXTRA_PERMISSIONS = "permissions";
    private static final String EXTRA_TIME = "time";

    private static final String ROOT_DATA = "data";
    private static final String ROOT_ANDROID_DATA = "android_data";
    private static final String ROOT_ANDROID_OBB = "android_obb";
    private static final String ROOT_USER_DE_DATA = "user_de_data";

    private static final String[] DEFAULT_ROOT_PROJECTION = {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON
    };

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
    public void attachInfo(android.content.Context context, android.content.pm.ProviderInfo info) {
        super.attachInfo(context, info);
        packageName = context.getPackageName();
        File filesDir = context.getFilesDir();
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
        File appExternalFiles = context.getExternalFilesDir(null);
        if (appExternalFiles != null) {
            externalDataDir = appExternalFiles.getParentFile();
        }
        obbDir = context.getObbDir();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        android.content.Context context = getContext();
        android.content.pm.ApplicationInfo appInfo = context != null ? context.getApplicationInfo() : null;
        String title = packageName;
        int icon = 0;
        if (context != null && appInfo != null) {
            title = appInfo.loadLabel(context.getPackageManager()).toString();
            icon = appInfo.icon;
        }

        MatrixCursor cursor = new MatrixCursor(resolveRootProjection(projection));
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, packageName);
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, packageName);
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, packageName);
        row.add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(DocumentsContract.Root.COLUMN_TITLE, title);
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        row.add(DocumentsContract.Root.COLUMN_ICON, icon);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(resolveDocumentProjection(projection));
        includeDocument(cursor, documentId, resolveDocumentFile(documentId, true), null);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(resolveDocumentProjection(projection));
        File parent = resolveDocumentFile(parentDocumentId, true);
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
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = resolveDocumentFile(documentId, true);
        if (file == null) {
            throw new FileNotFoundException(documentId + " not found");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = resolveDocumentFile(documentId, true);
        return getMimeType(file, documentId);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = resolveDocumentFile(parentDocumentId, true);
        if (parent == null || !parent.isDirectory()) {
            throw new FileNotFoundException(parentDocumentId + " not found");
        }
        String safeName = sanitizeName(displayName);
        File target = buildUniqueTarget(parent, safeName);
        try {
            boolean created;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                created = target.mkdir();
            } else {
                created = target.createNewFile();
            }
            if (!created) {
                throw new FileNotFoundException("Failed to create document " + safeName);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return toDocumentId(target);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = resolveDocumentFile(documentId, true);
        if (file == null || !deleteRecursively(file)) {
            throw new FileNotFoundException("Failed to delete document " + documentId);
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File file = resolveDocumentFile(documentId, true);
        if (file == null) {
            throw new FileNotFoundException("Failed to rename document " + documentId);
        }
        File renamed = new File(file.getParentFile(), sanitizeName(displayName));
        if (!file.renameTo(renamed)) {
            throw new FileNotFoundException("Failed to rename document " + documentId + " to " + displayName);
        }
        return toDocumentId(renamed);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId)
            throws FileNotFoundException {
        File source = resolveDocumentFile(sourceDocumentId, true);
        File targetParent = resolveDocumentFile(targetParentDocumentId, true);
        if (source == null || targetParent == null || !targetParent.isDirectory()) {
            throw new FileNotFoundException("Failed to move document " + sourceDocumentId);
        }
        File target = buildUniqueTarget(targetParent, source.getName());
        if (!source.renameTo(target)) {
            throw new FileNotFoundException("Failed to move document " + sourceDocumentId + " to " + targetParentDocumentId);
        }
        return toDocumentId(target);
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        deleteDocument(documentId);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            if (packageName.equals(parentDocumentId)) {
                return documentId.startsWith(packageName + "/");
            }
            File parent = resolveDocumentFile(parentDocumentId, true);
            File child = resolveDocumentFile(documentId, true);
            return parent != null && child != null && isFileUnder(parent, child);
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle delegated = super.call(method, arg, extras);
        if (delegated != null) {
            return delegated;
        }
        if (method == null || !method.startsWith(METHOD_PREFIX)) {
            return null;
        }
        Bundle result = new Bundle();
        try {
            Uri uri = extras != null ? extras.getParcelable(EXTRA_URI) : null;
            String documentId = uri != null ? extractDocumentId(uri) : null;
            if (documentId == null) {
                return fail(result, "Document URI is missing");
            }
            switch (method) {
                case METHOD_SET_LAST_MODIFIED:
                    return setLastModified(result, documentId, extras);
                case METHOD_SET_PERMISSIONS:
                    return setPermissions(result, documentId, extras);
                case METHOD_CREATE_SYMLINK:
                    return createSymlink(result, documentId, extras);
                default:
                    return fail(result, "Unsupported method: " + method);
            }
        } catch (Exception e) {
            return fail(result, e.toString());
        }
    }

    private Bundle setLastModified(Bundle result, String documentId, Bundle extras) throws FileNotFoundException {
        File file = resolveDocumentFile(documentId, true);
        if (file == null) {
            return fail(result, documentId + " not found");
        }
        long time = extras != null ? extras.getLong(EXTRA_TIME) : 0L;
        result.putBoolean(EXTRA_RESULT, file.setLastModified(time));
        if (!result.getBoolean(EXTRA_RESULT)) {
            result.putString(EXTRA_MESSAGE, "Failed to set last modified");
        }
        return result;
    }

    private Bundle setPermissions(Bundle result, String documentId, Bundle extras) throws FileNotFoundException {
        File file = resolveDocumentFile(documentId, true);
        if (file == null) {
            return fail(result, documentId + " not found");
        }
        int permissions = extras != null ? extras.getInt(EXTRA_PERMISSIONS) : 0;
        try {
            Os.chmod(file.getPath(), permissions);
            result.putBoolean(EXTRA_RESULT, true);
            return result;
        } catch (ErrnoException e) {
            return fail(result, e.getMessage());
        }
    }

    private Bundle createSymlink(Bundle result, String documentId, Bundle extras) throws FileNotFoundException {
        File file = resolveDocumentFile(documentId, false);
        if (file == null) {
            return fail(result, documentId + " not found");
        }
        String path = extras != null ? extras.getString(EXTRA_PATH) : null;
        if (path == null || path.isEmpty()) {
            return fail(result, "Path is missing");
        }
        try {
            Os.symlink(path, file.getPath());
            result.putBoolean(EXTRA_RESULT, true);
            return result;
        } catch (ErrnoException e) {
            return fail(result, e.getMessage());
        }
    }

    private Bundle fail(Bundle bundle, String message) {
        bundle.putBoolean(EXTRA_RESULT, false);
        bundle.putString(EXTRA_MESSAGE, message);
        return bundle;
    }

    private String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
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
        row.add(DocumentsContract.Document.COLUMN_FLAGS, getDocumentFlags(file, documentId));
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

    private int getDocumentFlags(File file, String documentId) {
        if (packageName.equals(documentId)) {
            return 0;
        }
        int flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                | DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
        boolean isDirectory = file == null || file.isDirectory();
        if (isDirectory) {
            flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        } else {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_REMOVE;
        }
        return flags;
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
            File canonicalTarget = requireExists ? target.getCanonicalFile() : target.getAbsoluteFile();
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

    private File buildUniqueTarget(File parent, String displayName) {
        File target = new File(parent, displayName);
        if (!target.exists()) {
            return target;
        }
        int dot = displayName.lastIndexOf('.');
        String baseName = dot > 0 ? displayName.substring(0, dot) : displayName;
        String ext = dot > 0 ? displayName.substring(dot) : "";
        int index = 2;
        while (target.exists()) {
            target = new File(parent, baseName + " (" + index + ")" + ext);
            index++;
        }
        return target;
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

    private String sanitizeName(String displayName) {
        return displayName.replace('/', '_');
    }

    private String extractDocumentId(Uri uri) {
        try {
            return DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            return DocumentsContract.getTreeDocumentId(uri);
        } catch (IllegalArgumentException ignored) {
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() >= 4) {
            return segments.get(3);
        }
        return segments.size() >= 2 ? segments.get(1) : null;
    }
}
