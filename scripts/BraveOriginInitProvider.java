package org.brave.origin;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Process;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Auto-initializes Brave Origin inside official Brave Android (BraveMono)
 * during ContentProvider installation — synchronously before Application.onCreate()
 * and before Chromium native loads Local State or Profile policies.
 */
public final class BraveOriginInitProvider extends ContentProvider {
    private static volatile boolean sInitialized;

    @Override
    public boolean onCreate() {
        init(getContext());
        return true;
    }

    public static void init(Context context) {
        if (sInitialized || context == null) {
            return;
        }
        sInitialized = true;
        try {
            if (isIsolatedOrChildProcess()) {
                return;
            }
            seedSharedPreferences(context);
            seedChromiumLocalState(context);
        } catch (Throwable ignored) {
            // Never disrupt browser startup under any condition.
        }
    }

    private static boolean isIsolatedOrChildProcess() {
        if (Build.VERSION.SDK_INT >= 28 && Process.isIsolated()) {
            return true;
        }
        int appId = Process.myUid() % 100000;
        if (appId >= 90000) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            String proc = Application.getProcessName();
            if (proc != null && proc.indexOf(':') >= 0) {
                return true;
            }
        }
        return false;
    }

    private static void seedSharedPreferences(Context context) {
        String prefName = context.getPackageName() + "_preferences";
        SharedPreferences sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        boolean changed = false;

        if (!sp.getBoolean("brave_origin_credential_summary_cached", false)) {
            ed.putBoolean("brave_origin_credential_summary_cached", true);
            changed = true;
        }
        if (!sp.getBoolean("brave_origin_init_v1", false)) {
            ed.putBoolean("brave_origin_init_v1", true);
            ed.putBoolean("brave_ad_free_callout_dialog", false);
            ed.putBoolean("open_yt_in_brave_dialog", false);
            ed.putBoolean("brave_rate_dont_show_again", true);
            changed = true;
        }
        if (changed) {
            ed.apply();
        }
    }

    private static void seedChromiumLocalState(Context context) throws Exception {
        File chromeDir = context.getDir("chrome", Context.MODE_PRIVATE);
        File localStateFile = new File(chromeDir, "Local State");

        boolean existed = localStateFile.isFile() && localStateFile.length() > 0;
        JSONObject root;
        if (existed) {
            String raw = readUtf8(localStateFile);
            root = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } else {
            root = new JSONObject();
        }

        JSONObject braveObj = root.optJSONObject("brave");
        if (braveObj == null) {
            braveObj = new JSONObject();
            root.put("brave", braveObj);
        }
        JSONObject originObj = braveObj.optJSONObject("origin");
        if (originObj == null) {
            originObj = new JSONObject();
            braveObj.put("origin", originObj);
        }

        boolean alreadyValidated = originObj.optBoolean("purchase_validated", false);
        boolean alreadyEnforced = originObj.optBoolean("policies_were_enforced", false);
        if (existed && alreadyValidated && alreadyEnforced) {
            return;
        }

        originObj.put("purchase_validated", true);
        originObj.put("policies_were_enforced", true);

        if (!existed && root.optJSONObject("p3a") == null) {
            JSONObject p3a = new JSONObject();
            p3a.put("enabled", false);
            p3a.put("notice_acknowledged", true);
            root.put("p3a", p3a);
        }

        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        File tmpFile = new File(chromeDir, "Local State.origin.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            fos.write(bytes);
            fos.getFD().sync();
        }
        if (!tmpFile.renameTo(localStateFile)) {
            try (FileOutputStream fos = new FileOutputStream(localStateFile)) {
                fos.write(bytes);
                fos.getFD().sync();
            }
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
        }
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
