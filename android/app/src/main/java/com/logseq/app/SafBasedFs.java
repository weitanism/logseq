package com.logseq.app;

import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.Nullable;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;


// The SafBasedFs provides SAF based filesystem APIs
// which mimic the `@capacitor/filesystem`'s interface.
@CapacitorPlugin(name = "SafBasedFs")
public class SafBasedFs extends Plugin {
    static private final String TAG = "Logseq/FsUtil";

    @PluginMethod()
    public void dirExists(PluginCall call) {
        if (call == null) {
            return;
        }

        String path = call.getString("path");
        Log.d(TAG, "invoking dirExists, path=" + path);
        if (path == null) {
            call.reject("path can not be null");
            return;
        }

        Uri uri = FakePathFactory.fakePathToUri(path,
                getContext().getContentResolver());
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
            if (cursor != null && cursor.moveToFirst()) {
                mimeType = cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE));
            }
        }
        Log.d(TAG, "mimeType: " + mimeType);
        JSObject ret = new JSObject();
        ret.put("exists", mimeType != null &&
                mimeType.equals(DocumentsContract.Document.MIME_TYPE_DIR));
        call.resolve(ret);
    }

    @PluginMethod()
    public void stat(PluginCall call) {
        if (call == null) {
            return;
        }

        String fakePath = call.getString("path");
        Log.d(TAG, "invoking stat, path=" + fakePath);
        Uri uri = FakePathFactory.fakePathToUri(fakePath,
                getContext().getContentResolver());
        if (uri == null) {
            call.reject("invalid path");
            return;
        }

        JSObject ret = new JSObject();
        try (Cursor cursor = getActivity().getContentResolver().query(uri,
                SafUtil.statColumns(), null,
                null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                call.reject("unable to query uri");
                return;
            }

            ret.put("size", SafUtil.getFileSize(cursor));
            ret.put("type", SafUtil.getFileType(cursor));
            ret.put("mtime", SafUtil.getFileLastModifiedTime(cursor));
            ret.put("uri", fakePath);
            ret.put("ctime", null);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("failed to query uri, exception:" + e);
        }
    }

    @PluginMethod()
    public void listDir(PluginCall call) {
        if (call == null) {
            return;
        }

        String fakePath = call.getString("path");
        Log.d(TAG, "invoking listDir, path=" + fakePath);
        Uri uri = FakePathFactory.fakePathToUri(fakePath,
                getContext().getContentResolver());
        if (uri == null) {
            call.reject("invalid path");
            return;
        }

        JSArray fileArray = new JSArray();
        Uri folderUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri,
                DocumentsContract.getDocumentId(uri));
        try (Cursor cursor = getActivity().getContentResolver().query(folderUri,
                SafUtil.statColumns(), null, null, null)) {
            if (cursor == null) {
                call.reject("unable to query the given uri");
                return;
            }

            if (cursor.moveToFirst()) {
                do {
                    JSObject file = new JSObject();
                    file.put("name", SafUtil.getFileName(cursor));
                    file.put("type", SafUtil.getFileType(cursor));
                    file.put("size", SafUtil.getFileSize(cursor));
                    file.put("mtime", SafUtil.getFileLastModifiedTime(cursor));
                    file.put("uri",
                            FakePathFactory.buildChildFakePath(fakePath,
                                    SafUtil.getFileName(cursor)));
                    file.put("ctime", null);
                    fileArray.put(file);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            call.reject("unable to list directory, exception:" + e);
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
        Log.d(TAG, "invoking readFile, path=" + path);
        Uri uri = FakePathFactory.fakePathToUri(path,
                getContext().getContentResolver());
        if (uri == null) {
            call.reject("invalid path");
            return;
        }

        String encoding = call.getString("encoding");
        Charset charset = toCharset(encoding);
        if (encoding != null && charset == null) {
            call.reject("unsupported encoding provided: " + encoding);
            return;
        }

        try {
            JSObject ret = new JSObject();
            ret.put("data", SafUtil.readFile(uri, charset,
                    getContext().getContentResolver()));
            call.resolve(ret);
        } catch (FileNotFoundException e) {
            call.reject("file does not exist", e);
        } catch (IOException e) {
            call.reject("error occurred while reading file", e);
        }
    }

    @PluginMethod
    public void mkdir(PluginCall call) {
        if (call == null) {
            return;
        }

        String path = call.getString("path");
        Log.d(TAG, "invoking mkdir, path=" + path);
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

    @PluginMethod
    public void writeFile(PluginCall call) {
        if (call == null) {
            return;
        }

        String fakePath = call.getString("path");
        Log.d(TAG, "invoking writeFile, path=" + fakePath);
        String encoding = call.getString("encoding");
        String data = call.getString("data");
        Boolean recursive = call.getBoolean("recursive", false);
        if (fakePath == null) {
            Log.d(TAG, "missing argument path");
            call.reject("missing argument path");
            return;
        }
        if (data == null) {
            Log.d(TAG, "missing argument data");
            call.reject("missing argument data");
            return;
        }

        Charset charset = toCharset(encoding);
        if (encoding != null && charset == null) {
            Log.d(TAG, "unsupported encoding=" + encoding);
            call.reject("unsupported encoding provided: " + encoding);
            return;
        }

        Uri fileUri =
                ensureFileExists(fakePath, Boolean.TRUE.equals(recursive));
        Log.d(TAG, "uri=" + fileUri);
        if (fileUri == null) {
            Log.e(TAG, "failed to create file");
            call.reject("failed to create file");
            return;
        }
        try {
            SafUtil.writeFile(fileUri, data, charset,
                    getContext().getContentResolver());
            JSObject result = new JSObject();
            result.put("uri", fakePath);
            call.resolve(result);
        } catch (FileNotFoundException e) {
            call.reject("file does not exist", e);
        } catch (IOException e) {
            call.reject("error occurred while writing to file", e);
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        if (call == null) {
            return;
        }

        String path = call.getString("path");
        Log.d(TAG, "invoking deleteFile, path=" + path);
        if (path == null) {
            call.reject("missing argument path");
            return;
        }

        Uri uri = FakePathFactory.fakePathToUri(path,
                getContext().getContentResolver());
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
            call.reject("error occurred while deleting file", e);
        }
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
        if (!FakePathFactory.isSiblingPath(from, to)) {
            call.reject("only rename under the same folder is supported");
            return;
        }

        Log.d(TAG, "invoking rename, from=" + from + " to=" + to);
        Uri uriFrom = FakePathFactory.fakePathToUri(from,
                getContext().getContentResolver());
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
            call.reject("error occurred while rename", e);
        }
    }

    @PluginMethod
    public void copy(PluginCall call) {
        call.unimplemented("copy is not implemented yet");
    }

    private Uri mkdirImpl(String fakePath, Boolean recursive,
                          Boolean ignoreLastSegment) {
        Uri parentUri = FakePathFactory.fakePathToRootUri(fakePath);
        if (parentUri == null) {
            return null;
        }
        String[] segments =
                FakePathFactory.getFakePathAdditionalPathSegments(fakePath);
        if (segments.length == (ignoreLastSegment ? 1 : 0)) {
            return parentUri;
        }
        if (ignoreLastSegment) {
            segments = Arrays.copyOfRange(segments, 0, segments.length - 1);
        }
        for (int idx = 0; idx < segments.length; idx++) {
            String folderName = segments[idx];
            Uri childUri = FakePathFactory.queryChildUri(parentUri, folderName,
                    getContext().getContentResolver());
            if (childUri == null) {
                if (idx != segments.length - 1 && !recursive) {
                    // fail when intermediate folder not
                    // exists in non-recursive mode.
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
                        Log.e(TAG, "failed to create dir");
                        return null;
                    }
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "failed to create dir:" + e);
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

        String[] segments =
                FakePathFactory.getFakePathAdditionalPathSegments(fakePath);
        if (segments.length == 0) {
            return parentUri;
        }
        String filename = segments[segments.length - 1];
        Uri existedFile = FakePathFactory.queryChildUri(parentUri, filename,
                getContext().getContentResolver());
        return existedFile != null ? existedFile :
                SafUtil.createFile(parentUri, filename,
                        getContext().getContentResolver());
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
}
