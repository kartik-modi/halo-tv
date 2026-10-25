package com.kartikmodi.halo.tonight;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * The top matches for a search, for the launcher's Jarvis link, so Alexa can say what was found:
 * content://com.kartikmodi.halo.tonight.search/top?q=inception. Read-only, and only public TMDB data.
 */
public final class SearchProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        // runs whenever the app's process starts, even without its screen: keeps the index jobs scheduled
        if (getContext() != null) IndexJob.schedule(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"id", "tv", "title", "year", "services", "on"});
        String q = uri.getQueryParameter("q");
        if (q == null || getContext() == null) return c;
        if (!SearchIndex.get(getContext()).exists()) return null;   // not built yet: the caller just says it is searching
        for (SearchIndex.Hit h : SearchIndex.get(getContext()).query(q, 5)) {
            StringBuilder on = new StringBuilder();   // "Netflix and Prime Video", for Jarvis to say
            for (char k : h.services.toCharArray()) {
                Services.Service s = Services.of(k);
                if (s != null) on.append(on.length() == 0 ? "" : " and ").append(s.name.replace("+", " Plus"));
            }
            c.addRow(new Object[]{h.id, h.tv ? 1 : 0, h.name, h.year, h.services, on.toString()});
        }
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
