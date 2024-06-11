package com.logseq.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;


@CapacitorPlugin(name = "SafBasedFs")
public class SafBasedFs extends Plugin {
    @PluginMethod()
    public void dirExists(PluginCall call) {
        if (call == null) {
            return;
        }

        String path = call.getString("path");
        Log.d(LOG_TAG, "invoking dirExists, path=" + path);
        if (path == null) {
            call.reject("path can not be null");
            return;
        }

        Uri uri = fakePathToUri(path);
        Log.d(LOG_TAG, "uri=" + uri);
        if (uri == null) {
            JSObject ret = new JSObject();
            ret.put("exists", false);
            call.resolve(ret);
            return;
        }

        String mimeType = null;
        try (Cursor cursor = getActivity().getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null,
                null, null)) {
            if (cursor != null) {
                cursor.moveToFirst();
                int mimeTypeColumnIndex =
                        cursor.getColumnIndexOrThrow(
                                DocumentsContract.Document.COLUMN_MIME_TYPE);
                if (mimeTypeColumnIndex != -1) {
                    mimeType = cursor.getString(mimeTypeColumnIndex);
                }
            }
        }
        Log.d(LOG_TAG, "mimeType: " + mimeType);
        boolean exists = mimeType != null &&
                mimeType.equals(DocumentsContract.Document.MIME_TYPE_DIR);

        JSObject ret = new JSObject();
        ret.put("exists", exists);
        call.resolve(ret);
    }

    @PluginMethod()
    public void stat(PluginCall call) {
        if (call == null) {
            return;
        }

        String fakePath = call.getString("path");
        Log.d(LOG_TAG, "invoking stat, path=" + fakePath);
        Uri uri = fakePathToUri(fakePath);
        Log.d(LOG_TAG, "uri=" + uri);
        if (uri == null) {
            call.reject("invalid path");
            return;
        }

        JSObject ret = new JSObject();
        try (Cursor cursor = getActivity().getContentResolver().query(uri,
                requiredColumns(), null,
                null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                call.reject("Unable to query uri");
                return;
            }

            ret.put("size", getFileSize(cursor));
            ret.put("type", getFileType(cursor));
            ret.put("mtime", getFileLastModifiedTime(cursor));
            ret.put("uri", fakePath);
            ret.put("ctime", null);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to query uri, exception:" + e);
        }
    }

    @PluginMethod()
    public void listDir(PluginCall call) {
        if (call == null) {
            return;
        }

        String fakePath = call.getString("path");
        Log.d(LOG_TAG, "invoking listDir, path=" + fakePath);
        Uri uri = fakePathToUri(fakePath);
        Log.d(LOG_TAG, "uri=" + uri);
        if (uri == null) {
            call.reject("invalid path");
            return;
        }

        Uri folderUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri,
                DocumentsContract.getDocumentId(uri));
        Log.d(LOG_TAG, "folderUri=" + folderUri
                + " document id=" + DocumentsContract.getDocumentId(uri));

        JSArray fileArray = new JSArray();
        try (Cursor cursor = getActivity().getContentResolver().query(folderUri,
                requiredColumns(), null, null, null)) {
            if (cursor == null) {
                call.reject("Unable to query the given uri");
                return;
            }

            if (cursor.moveToFirst()) {
                do {
                    JSObject file = new JSObject();
                    file.put("name", getFileName(cursor));
                    file.put("type", getFileType(cursor));
                    file.put("size", getFileSize(cursor));
                    file.put("mtime", getFileLastModifiedTime(cursor));
                    file.put("uri",
                            buildFakePath(fakePath, getFileName(cursor)));
                    file.put("ctime", null);
                    Log.d(LOG_TAG,
                            "child name=" + file.getString("name")
                                    + " type=" + file.getString("type")
                                    + " size=" + file.getLong("size")
                                    + " mtime=" + file.getLong("mtime")
                                    + " fakepath=" + file.getString("uri"));
                    fileArray.put(file);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            call.reject("Unable to list directory, exception:" + e);
            return;
        }

        JSObject ret = new JSObject();
        ret.put("files", fileArray);
        call.resolve(ret);
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        if (call == null) {
            return;
        }

        String path = call.getString("path");
        Log.d(LOG_TAG, "invoking readFile, path=" + path);
        Uri uri = fakePathToUri(path);
        Log.d(LOG_TAG, "uri=" + uri);
        if (uri == null) {
            call.reject("invalid path");
            return;
        }

        String encoding = call.getString("encoding");
        Charset charset = toCharset(encoding);
        if (encoding != null && charset == null) {
            call.reject("Unsupported encoding provided: " + encoding);
            return;
        }

        try {
            JSObject ret = new JSObject();
            ret.put("data", readFileImpl(uri, charset));
            call.resolve(ret);
        } catch (FileNotFoundException e) {
            call.reject("File does not exist", e);
        } catch (IOException e) {
            call.reject("Unable to read file", e);
        }
    }

    private String readFileImpl(Uri uri, Charset charset) throws IOException {
        try (InputStream is = getContext().getContentResolver()
                .openInputStream(uri)) {
            if (is == null) {
                throw new IOException("Failed to open input stream");
            }

            return charset != null ? readFileAsString(is, charset.name()) :
                    readFileAsBase64EncodedData(is);
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, "file not found: " + e);
            throw e;
        } catch (IOException e) {
            Log.e(LOG_TAG, "IoException while read file: " + e);
            throw e;
        }
    }

    @PluginMethod
    public void mkdir(PluginCall call) {
        if (call == null) {
            return;
        }

        String path = call.getString("path");
        Log.d(LOG_TAG, "invoking mkdir, path=" + path);
        if (path == null) {
            call.reject("missing argument path");
            return;
        }

        boolean recursive =
                Boolean.TRUE.equals(call.getBoolean("recursive", false));
        if (mkdirImpl(path, recursive, false) == null) {
            call.reject("failed to mkdir");
        } else {
            call.resolve();
        }
    }

    private Uri mkdirImpl(String fakePath, Boolean recursive,
                          Boolean ignoreLastSegment) {
        Uri parentUri = fakePathToRootUri(fakePath);
        Log.d(LOG_TAG, "uri=" + parentUri);
        if (parentUri == null) {
            return null;
        }
        String[] segments = getFakePathAdditionalPathSegments(fakePath);
        if (segments.length == (ignoreLastSegment ? 1 : 0)) {
            return parentUri;
        }
        if (ignoreLastSegment) {
            segments = Arrays.copyOfRange(segments, 0, segments.length - 1);
        }
        for (int idx = 0; idx < segments.length; idx++) {
            String folderName = segments[idx];
            Uri childUri = findChildUri(parentUri, folderName);
            if (childUri == null) {
                if (idx != segments.length - 1 && !recursive) {
                    // fail when not recursive and
                    // intermediate folder not exists.
                    return null;
                }
                try {
                    childUri = DocumentsContract.createDocument(
                            getContext().getContentResolver(),
                            parentUri,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            folderName
                    );
                    if (childUri == null) {
                        Log.e(LOG_TAG, "failed to create dir");
                        return null;
                    }
                } catch (FileNotFoundException e) {
                    Log.e(LOG_TAG, "failed to create dir:" + e);
                    return null;
                }
            }
            parentUri = childUri;
        }
        return parentUri;
    }

    private Uri ensureFileExists(String fakePath, Boolean recursive) {
        Uri parentUri = mkdirImpl(fakePath, recursive, true);
        if (parentUri == null) {
            return null;
        }

        Log.d(LOG_TAG, "folderUri=" + parentUri);
        String[] segments = getFakePathAdditionalPathSegments(fakePath);
        if (segments.length == 0) {
            return parentUri;
        }
        String filename = segments[segments.length - 1];
        Uri existedFile = findChildUri(parentUri, filename);
        return existedFile != null ? existedFile :
                createFile(parentUri, filename);
    }

    private Uri createFile(Uri folderUri, String filename) {
        try {
            String mimeType = null;
            String extension = MimeTypeMap.getFileExtensionFromUrl(filename);
            if (extension != null) {
                mimeType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(extension.toLowerCase());
            }
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }
            Uri uri = DocumentsContract.createDocument(
                    getContext().getContentResolver(),
                    folderUri,
                    mimeType,
                    filename
            );
            Log.d(LOG_TAG, "created document: " + uri);
            if (uri == null) {
                Log.e(LOG_TAG, "failed to create document");
            }
            return uri;
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, "error while create document:" + e);
            return null;
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        if (call == null) {
            return;
        }

        String fakePath = call.getString("path");
        Log.d(LOG_TAG, "invoking writeFile, path=" + fakePath);
        String encoding = call.getString("encoding");
        String data = call.getString("data");
        Boolean recursive = call.getBoolean("recursive", false);
        if (fakePath == null) {
            Log.d(LOG_TAG, "missing argument path");
            call.reject("missing argument path");
            return;
        }
        if (data == null) {
            Log.d(LOG_TAG, "missing argument data");
            call.reject("missing argument data");
            return;
        }

        Charset charset = toCharset(encoding);
        if (encoding != null && charset == null) {
            Log.d(LOG_TAG, "unsupported encoding=" + encoding);
            call.reject("Unsupported encoding provided: " + encoding);
            return;
        }

        Uri fileUri =
                ensureFileExists(fakePath, Boolean.TRUE.equals(recursive));
        Log.d(LOG_TAG, "uri=" + fileUri);
        if (fileUri == null) {
            Log.e(LOG_TAG, "failed to create file");
            call.reject("failed to create file");
            return;
        }
        try (OutputStream os = this.getContext()
                .getContentResolver()
                .openOutputStream(fileUri, "w")) {
            if (os == null) {
                Log.e(LOG_TAG, "failed to open file to write");
                call.reject("failed to open file to write");
                return;
            }

            if (charset != null) {
                BufferedWriter writer =
                        new BufferedWriter(new OutputStreamWriter(os, charset));
                writer.write(data);
                writer.close();
            } else {
                //remove header from data URL
                if (data.contains(",")) {
                    data = data.split(",")[1];
                }
                os.write(Base64.decode(data, Base64.NO_WRAP));
                os.close();
            }
            JSObject result = new JSObject();
            result.put("uri", fakePath);
            call.resolve(result);
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, e.toString());
            call.reject("FileNotFound:" + e);
        } catch (IOException e) {
            Log.e(LOG_TAG, e.toString());
            call.reject("IOException:" + e);
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        if (call == null) {
            return;
        }

        String path = call.getString("path");
        Log.d(LOG_TAG, "invoking deleteFile, path=" + path);
        if (path == null) {
            call.reject("missing argument path");
            return;
        }

        Uri uri = fakePathToUri(path);
        Log.d(LOG_TAG, "uri=" + uri);
        if (uri == null) {
            call.reject("invalid path");
            return;
        }
        try {
            if (!DocumentsContract.deleteDocument(
                    getContext().getContentResolver(), uri)) {
                call.reject("failed to delete file");
            } else {
                call.resolve();
            }
        } catch (FileNotFoundException e) {
            call.reject(e.toString());
        }
    }

    private Boolean isSiblingPath(String path1, String path2) {
        String[] segments1 = path1.split("/");
        String[] segments2 = path2.split("/");
        if (segments1.length != segments2.length) {
            return false;
        }
        for (int idx = 0; idx < segments1.length - 1; idx++) {
            if (segments1[idx].equals(segments2[idx])) {
                return false;
            }
        }
        return true;
    }

    @PluginMethod
    public void rename(PluginCall call) {
        if (call == null) {
            return;
        }

        String from = call.getString("from");
        String to = call.getString("to");
        if (from == null || to == null) {
            call.reject("missing argument from or to");
            return;
        }
        if (from.equals(to)) {
            call.reject("the from and to are same");
            return;
        }
        if (!isSiblingPath(from, to)) {
            call.reject("only rename under the same folder is supported");
            return;
        }

        Log.d(LOG_TAG,
                "invoking rename, from=" + from + " to=" + to);
        Uri uriFrom = fakePathToUri(from);
        Log.d(LOG_TAG, "uriFrom=" + uriFrom);
        if (uriFrom == null) {
            call.reject("invalid from path");
            return;
        }
        String[] segments = to.split("/");
        String newFileName = segments[segments.length - 1];
        try {
            Uri newUri = DocumentsContract.renameDocument(
                    getContext().getContentResolver(), uriFrom, newFileName);
            if (newUri == null) {
                call.reject("Error while rename, result uri is null");
                return;
            }
            JSObject result = new JSObject();
            result.put("uri", to);
            call.resolve(result);
        } catch (FileNotFoundException e) {
            call.reject("Error while rename:" + e);
        }
    }

    @PluginMethod
    public void copy(PluginCall call) {
        if (call == null) {
            return;
        }

        String from = call.getString("from");
        String to = call.getString("to");
        if (from == null || to == null) {
            call.reject("missing argument from or to");
            return;
        }

//        Log.d(LOG_TAG, "invoking copy, from=" + from + " to=" + to);
//        Uri uriFrom = fakePathToUri(from, true);
//        Log.d(LOG_TAG, "uriFrom=" + uriFrom);
//        Uri uriTo = fakePathToUri(from);
//        Log.d(LOG_TAG, "uriTo=" + uriTo);
//        if (uriFrom == null) {
//            call.reject("invalid from");
//            return;
//        }
//        // TODO
//        String[] segments = getFakePathAdditionalPathSegments(to);
        call.reject("not implemented");
    }

    static private String getFileName(Cursor cursor) {
        return cursor.getString(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME));
    }

    static private String getFileType(Cursor cursor) {
        return cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE))
                .equals(DocumentsContract.Document.MIME_TYPE_DIR) ?
                "directory" : "file";
    }

    static private Long getFileSize(Cursor cursor) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_SIZE));
    }

    static private Long getFileLastModifiedTime(Cursor cursor) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED));
    }


    static private Uri buildFileUri(Cursor cursor, Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri,
                cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID)));
    }

    static private String[] requiredColumns() {
        return new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
    }

    static private Charset toCharset(@Nullable String encoding) {
        if (encoding == null) {
            return null;
        }

        return switch (encoding) {
            case "utf8" -> StandardCharsets.UTF_8;
            case "utf16" -> StandardCharsets.UTF_16;
            case "ascii" -> StandardCharsets.US_ASCII;
            default -> null;
        };
    }

    static private String readFileAsString(InputStream is,
                                           String encoding) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        byte[] buffer = new byte[1024];
        int length = 0;

        while ((length = is.read(buffer)) != -1) {
            outputStream.write(buffer, 0, length);
        }

        return outputStream.toString(encoding);
    }

    static private String readFileAsBase64EncodedData(
            InputStream is) throws IOException {
        FileInputStream fileInputStreamReader = (FileInputStream) is;
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

        byte[] buffer = new byte[1024];

        int c;
        while ((c = fileInputStreamReader.read(buffer)) != -1) {
            byteStream.write(buffer, 0, c);
        }
        fileInputStreamReader.close();

        return Base64.encodeToString(byteStream.toByteArray(), Base64.NO_WRAP);
    }

    static private final int COUNT_FAKE_PATH_PREFIX_SEGMENT = 6;

    // Convert the uri to a faked path, as the
    // frontend code only works on classic filesystem.
    static public String buildRootFakePath(Uri uri,
                                           ContentResolver contentResolver) {
        String folderName;
        try (Cursor cursor = contentResolver.query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null,
                null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            folderName = getFileName(cursor);
        } catch (Exception e) {
            return null;
        }

        String authority = uri.getAuthority();
        String treeDocumentId = DocumentsContract.getTreeDocumentId(uri);
        String documentId = DocumentsContract.getDocumentId(uri);
        return "file://" + authority
                + "/" + encodeUri(treeDocumentId)
                + "/" + encodeUri(documentId)
                + "/" + folderName;
    }

    static public String buildFakePath(String parentFakePath, String filename) {
        return parentFakePath + "/" + filename;
    }

    private @Nullable Uri buildRootUri(String[] fakePathComponents) {
        if (fakePathComponents.length < COUNT_FAKE_PATH_PREFIX_SEGMENT) {
            return null;
        }

        String authority = fakePathComponents[2];
        String treeDocumentId = decodeUri(fakePathComponents[3]);
        String documentId = decodeUri(fakePathComponents[4]);
        return DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri(authority,
                        treeDocumentId),
                documentId);
    }

    public @Nullable Uri fakePathToRootUri(@Nullable String str) {
        if (str == null) {
            return null;
        }
        if (!str.startsWith("file://")) {
            return null;
        }

        String[] components = str.split("/");
        return buildRootUri(components);
    }

    public @Nullable Uri fakePathToUri(@Nullable String str) {
        if (str == null) {
            return null;
        }
        if (!str.startsWith("file://")) {
            return null;
        }

        String[] components = str.split("/");
        Uri parentUri = buildRootUri(components);
        if (parentUri == null) {
            return null;
        }

        for (int componentIdx = COUNT_FAKE_PATH_PREFIX_SEGMENT;
             componentIdx < components.length; componentIdx++) {
            Log.d(LOG_TAG, "check child " + components[componentIdx]);
            Log.d(LOG_TAG, "parent uri=" + parentUri);
            Uri child = findChildUri(parentUri, components[componentIdx]);
            if (child == null) {
                Log.d(LOG_TAG, "child " + components[componentIdx] +
                        " not exists");
                return null;
            }
            parentUri = child;
        }

        return parentUri;
    }

    private String[] getFakePathAdditionalPathSegments(String str) {
        String[] components = str.split("/");
        if (components.length <= COUNT_FAKE_PATH_PREFIX_SEGMENT) {
            // No additional path segments.
            return new String[]{};
        }
        return Arrays.copyOfRange(components, COUNT_FAKE_PATH_PREFIX_SEGMENT,
                components.length);
    }

    private @Nullable Uri findChildUri(Uri parentUri, String targetChildName) {
        Uri folderUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(parentUri,
                        DocumentsContract.getDocumentId(parentUri));
        try (Cursor cursor = getActivity().getContentResolver().query(folderUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null,
                null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }

            do {
                if (getFileName(cursor).equals(targetChildName)) {
                    return buildFileUri(cursor, folderUri);
                }
            } while (cursor.moveToNext());
        } catch (Exception e) {
            Log.e(LOG_TAG, "Unable to list directory, exception:" + e);
        }
        return null;
    }

    static private final int BASE64_FLAGS =
            Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING;

    static private String encodeUri(String uri) {
        return Base64.encodeToString(uri.getBytes(), BASE64_FLAGS);
    }

    static private String decodeUri(String uri) {
        return new String(Base64.decode(uri, BASE64_FLAGS));
    }

    static private final String LOG_TAG = "Logseq/FsUtil";
}
