package com.logseq.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

public class SafUtil {
    static private final String TAG = "Logseq/SafUtil";

    static public String[] statColumns() {
        return new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
    }

    static public String getFileName(Cursor cursor) {
        return cursor.getString(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME));
    }

    static public String getFileType(Cursor cursor) {
        return cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE))
                .equals(DocumentsContract.Document.MIME_TYPE_DIR) ?
                "directory" : "file";
    }

    static public Long getFileSize(Cursor cursor) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_SIZE));
    }

    static public Long getFileLastModifiedTime(Cursor cursor) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED));
    }

    static public String readFile(Uri uri, Charset charset,
                                  ContentResolver contentResolver) throws IOException {
        try (InputStream is = contentResolver.openInputStream(uri)) {
            if (is == null) {
                throw new IOException("Failed to open input stream");
            }

            return charset != null ? readFileAsString(is, charset.name()) :
                    readFileAsBase64EncodedData(is);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "file not found: " + e);
            throw e;
        } catch (IOException e) {
            Log.e(TAG, "IoException while read file: " + e);
            throw e;
        }
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

    static public void writeFile(Uri fileUri, String data, Charset charset,
                                 ContentResolver contentResolver) throws IOException {
        try (OutputStream os = contentResolver
                .openOutputStream(fileUri, "w")) {
            if (os == null) {
                Log.e(TAG, "failed to open file to write");
                throw new IOException("failed to open file to write");
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
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            throw e;
        }
    }

    static public Uri createFile(Uri folderUri, String filename,
                                 ContentResolver contentResolver) {
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
            Uri uri =
                    DocumentsContract.createDocument(
                            contentResolver, folderUri, mimeType, filename);
            Log.d(TAG, "created document: " + uri);
            if (uri == null) {
                Log.e(TAG, "failed to create document");
            }
            return uri;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "error while create document:" + e);
            return null;
        }
    }
}
