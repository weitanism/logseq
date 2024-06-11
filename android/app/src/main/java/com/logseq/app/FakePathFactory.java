package com.logseq.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Arrays;

// In order to keep the frontend code untouched, the FakePathFactory
// provides conversion methods between SAF URIs and faked classic
// file paths. The backend always return faked path to frontend.
public class FakePathFactory {
    static private final int COUNT_FAKE_PATH_PREFIX_SEGMENT = 6;

    static private final String TAG = "Logseq/FakePathFactory";

    static public String buildRootFakePath(Uri uri,
                                           ContentResolver contentResolver) {
        // Use the folder name as the suffix of faked root path to
        // make the path more human distinguishable on the frontend.
        String folderName;
        try (Cursor cursor = contentResolver.query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null,
                null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            folderName = SafUtil.getFileName(cursor);
        } catch (Exception e) {
            return null;
        }

        String authority = uri.getAuthority();
        String treeDocumentId = DocumentsContract.getTreeDocumentId(uri);
        String documentId = DocumentsContract.getDocumentId(uri);
        // Encode the `treeDocumentId` and
        // `documentId` to make them URL safe.
        return "file://" + authority
                + "/" + encodeComponent(treeDocumentId)
                + "/" + encodeComponent(documentId)
                + "/" + folderName;
    }

    static public String buildChildFakePath(String parentFakePath,
                                            String filename) {
        return parentFakePath + "/" + filename;
    }

    @Nullable
    static public Uri buildRootUri(String[] fakePathComponents) {
        if (fakePathComponents.length < COUNT_FAKE_PATH_PREFIX_SEGMENT) {
            return null;
        }

        String authority = fakePathComponents[2];
        String treeDocumentId = decodeComponent(fakePathComponents[3]);
        String documentId = decodeComponent(fakePathComponents[4]);
        return DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri(authority,
                        treeDocumentId),
                documentId);
    }

    @Nullable
    static public Uri fakePathToRootUri(@Nullable String str) {
        if (str == null) {
            return null;
        }
        if (!str.startsWith("file://")) {
            return null;
        }

        String[] components = str.split("/");
        return buildRootUri(components);
    }

    @Nullable
    static public Uri fakePathToUri(@Nullable String str,
                                    ContentResolver contentResolver) {
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
            Log.d(TAG, "check child " + components[componentIdx]);
            Log.d(TAG, "parent uri=" + parentUri);
            Uri child = queryChildUri(parentUri, components[componentIdx],
                    contentResolver);
            if (child == null) {
                Log.d(TAG, "child " + components[componentIdx] +
                        " not exists");
                return null;
            }
            parentUri = child;
        }

        return parentUri;
    }

    static public String[] getFakePathAdditionalPathSegments(String str) {
        String[] components = str.split("/");
        if (components.length <= COUNT_FAKE_PATH_PREFIX_SEGMENT) {
            // No additional path segments.
            return new String[]{};
        }
        return Arrays.copyOfRange(components, COUNT_FAKE_PATH_PREFIX_SEGMENT,
                components.length);
    }

    // TODO: Maybe we should cache the query result to
    // reduce interactions with the DocumentProvider.
    @Nullable
    static public Uri queryChildUri(Uri parentUri,
                                    String targetChildName,
                                    ContentResolver contentResolver) {
        Uri folderUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(parentUri,
                        DocumentsContract.getDocumentId(parentUri));
        try (Cursor cursor = contentResolver.query(folderUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null,
                null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }

            do {
                if (SafUtil.getFileName(cursor).equals(targetChildName)) {
                    return buildFileUri(cursor, folderUri);
                }
            } while (cursor.moveToNext());
        } catch (Exception e) {
            Log.e(TAG, "Unable to list directory, exception:" + e);
        }
        return null;
    }

    static private Uri buildFileUri(Cursor cursor, Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri,
                cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID)));
    }

    static public Boolean isSiblingPath(String path1, String path2) {
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

    static private final int BASE64_FLAGS =
            Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING;

    static private String encodeComponent(String component) {
        return Base64.encodeToString(component.getBytes(), BASE64_FLAGS);
    }

    static private String decodeComponent(String component) {
        return new String(Base64.decode(component, BASE64_FLAGS));
    }
}
