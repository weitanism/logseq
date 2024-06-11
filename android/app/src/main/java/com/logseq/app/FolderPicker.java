package com.logseq.app;

import static com.logseq.app.SafBasedFs.buildRootFakePath;

import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;


@CapacitorPlugin(name = "FolderPicker")
public class FolderPicker extends Plugin {
    @PluginMethod()
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(call, intent, "folderPickerResult");
    }

    @PluginMethod()
    public void openFile(PluginCall call) {
        Uri uri = Uri.parse(call.getString("uri"));
        File file = new File(uri.getPath());

        // Get URI and MIME type of file
        String appId = getAppId();
        uri = FileProvider.getUriForFile(getActivity(), appId + ".fileprovider",
                file);
        String mime = getContext().getContentResolver().getType(uri);

        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getContext().startActivity(intent);
    }

    @ActivityCallback
    private void folderPickerResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }

        JSObject ret = new JSObject();
        Intent intent = result.getData();
        if (intent == null) {
            call.reject("canceled");
            return;
        }

        Uri treeUri = intent.getData();
        if (treeUri == null) {
            call.reject("got null treeUri");
            return;
        }

        // Keep permission across reboots.
        getContext().getContentResolver()
                .takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri));
        Log.d("Logseq/FolderPicker", "got uri=" + uri);
        String fakeRootPath =
                buildRootFakePath(uri, getActivity().getContentResolver());
        Log.d("Logseq/FolderPicker", "faked path=" + fakeRootPath);
        if (fakeRootPath != null &&
                DocumentsContract.isDocumentUri(getContext(), uri)) {
            ret.put("path", fakeRootPath);
            call.resolve(ret);
        } else {
            call.reject("Cannot support this directory type: " + uri);
        }
    }
}
