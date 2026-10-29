package io.github.appcommentfilter.config;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/** Narrow read-only bridge: module UID and scoped host UID only. */
public final class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private void checkCaller(HostApp app) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = providerContext().getPackageManager().getPackagesForUid(uid);
        if (!app.allowsPackages(packages)) {
            throw new SecurityException("Caller cannot read module rules");
        }
    }

    private android.content.Context providerContext() {
        return java.util.Objects.requireNonNull(getContext());
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        HostApp app = ConfigStore.appForUri(uri);
        checkCaller(app);
        MatrixCursor cursor = new MatrixCursor(new String[]{"json", "revision"});
        synchronized (ConfigStore.class) {
            cursor.addRow(new Object[]{ConfigStore.load(providerContext(), app), ConfigStore.revision(providerContext(), app)});
        }
        cursor.setNotificationUri(providerContext().getContentResolver(), uri);
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.appcommentfilter.rules"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new SecurityException("Read only"); }
}
