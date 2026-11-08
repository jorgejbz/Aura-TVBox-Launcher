package com.jajua.aura;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the downloaded update to the system installer, which Android 7+ only accepts as content://.
 * Not exported: readable only through the one-time permission given with the install request.
 */
public final class InstallProvider extends ContentProvider {
    private static final String NAME = "Aura-update.apk";

    static File apkFile(Context c) {
        return new File(c.getCacheDir(), NAME);
    }

    static Uri uri(Context c) {
        return uri(c, NAME);
    }

    /** Also serves the apps from the online folder (AppsCatalog), by their cache file name. */
    static Uri uri(Context c, String name) {
        return Uri.parse("content://" + c.getPackageName() + ".install/" + name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        File f;
        if (NAME.equals(name)) f = apkFile(getContext());
        else if (name != null && name.matches("app-\\d+\\.apk")) f = new File(AppsCatalog.dir(getContext()), name);
        else throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
