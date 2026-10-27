package cn.local.imagepdf.platform;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.provider.DocumentsContract;
import android.util.Log;

/** Synchronous framework-only bridge that grants only synthetic test trees to this APK's target. */
public final class FolderTestGrantProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        Bundle result = new Bundle();
        try {
            Context context = getContext();
            if (context == null) throw new IllegalStateException("Provider is not initialized");
            if (!"grant".equals(method) && !"revoke".equals(method))
                throw new IllegalArgumentException("Unsupported fixture operation");
            if (extras == null || argument == null) throw new IllegalArgumentException("Missing fixture request");
            String ownPackage = context.getPackageName();
            String expectedTarget = ownPackage.endsWith(".test")
                ? ownPackage.substring(0, ownPackage.length() - 5) : ownPackage;
            String target = extras.getString("target");
            if (!expectedTarget.equals(target)) throw new IllegalArgumentException("Unexpected target");
            Uri tree = Uri.parse(argument);
            if (!"content".equals(tree.getScheme()) || !ownDocumentAuthority(context).equals(tree.getAuthority()))
                throw new IllegalArgumentException("Unexpected fixture authority");
            if (!DocumentsContract.isTreeUri(tree) ||
                    !FolderTestDocumentsProvider.isFixtureRoot(DocumentsContract.getTreeDocumentId(tree)))
                throw new IllegalArgumentException("Unexpected fixture root");
            // Only after validating the fixed fixture authority/root and the single allowed
            // target, perform the grant as the provider owner rather than its Binder caller.
            long identity = Binder.clearCallingIdentity();
            try {
                if ("revoke".equals(method)) {
                    context.revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
                    if (extras.getBoolean("persistable", true)) flags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
                    context.grantUriPermission(target, tree, flags);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            Log.i("FolderTestGrant", "Synchronous synthetic tree " + method + " completed for " + target);
            result.putBoolean("success", true);
            result.putString("detail", "ok");
        } catch (Exception failure) {
            String detail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            Log.w("FolderTestGrant", "Synthetic tree call failed: " + detail);
            result.putBoolean("success", false);
            result.putString("detail", detail);
        }
        return result;
    }

    private static String ownDocumentAuthority(Context context) throws PackageManager.NameNotFoundException {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PROVIDERS);
        if (info.providers != null) {
            for (ProviderInfo provider : info.providers) {
                if (FolderTestDocumentsProvider.class.getName().equals(provider.name)) return provider.authority;
            }
        }
        throw new IllegalStateException("Missing fixture documents provider");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Only synthetic fixture grant/revoke calls are supported");
    }
    @Override public String getType(Uri uri) { throw new UnsupportedOperationException("No documents are exposed here"); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read/write operations are not supported"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read/write operations are not supported"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read/write operations are not supported"); }
}
