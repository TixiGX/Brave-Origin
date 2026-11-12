package com.tixigx.braveorigin;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class WebUiRouter {

    public static final String HOST = "webui.braveorigin.local";
    public static final String BASE = "https://" + HOST;
    public static final String NEWTAB_INTERNAL = BASE + "/newtab";

    private WebUiRouter() {}

    public static boolean isWebUiUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("brave://")
            || lower.startsWith("chrome://")
            || lower.startsWith(BASE);
    }

    public static String toInternalUrl(String raw) {
        if (TextUtils.isEmpty(raw)) return NEWTAB_INTERNAL;
        String s = raw.trim();
        String lower = s.toLowerCase();
        if (lower.startsWith(BASE)) {
            return s;
        }
        String path = null;
        if (lower.startsWith("brave://")) {
            path = s.substring(8);
        } else if (lower.startsWith("chrome://")) {
            path = s.substring(9);
        }
        if (path == null) return s;

        path = path.replaceAll("^/+", "").replaceAll("/+$", "");
        if (path.isEmpty() || "newtab".equalsIgnoreCase(path) || "home".equalsIgnoreCase(path)) {
            return NEWTAB_INTERNAL;
        }
        if ("origin".equalsIgnoreCase(path)) {
            return BASE + "/settings/origin";
        }
        if ("settings".equalsIgnoreCase(path)) {
            return BASE + "/settings/origin";
        }
        return BASE + "/" + path.toLowerCase();
    }

    public static String toBraveSchemeUrl(String url) {
        if (TextUtils.isEmpty(url)) return "brave://newtab";
        if (url.startsWith(BASE)) {
            String p = url.substring(BASE.length()).replaceAll("^/+", "");
            if (p.isEmpty()) p = "newtab";
            return "brave://" + p;
        }
        return url;
    }

    public static String toOmniboxDisplayUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        if (url.startsWith(BASE)) {
            String p = url.substring(BASE.length()).replaceAll("^/+", "");
            if (p.isEmpty() || "newtab".equalsIgnoreCase(p)) {
                return "";
            }
            return "brave://" + p;
        }
        if ("brave://newtab".equalsIgnoreCase(url)) {
            return "";
        }
        if (url.startsWith("https://")) {
            return url.substring(8);
        }
        if (url.startsWith("http://")) {
            return url.substring(7);
        }
        return url;
    }

    public static WebResourceResponse serve(Uri uri, Context context, String sessionToken) {
        String path = uri.getPath() != null ? uri.getPath() : "/newtab";
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        headers.put("Content-Security-Policy", "default-src 'self' 'unsafe-inline' data:; img-src 'self' data: https:;");

        try {
            if ("/__assets/app.css".equals(path)) {
                InputStream is = context.getAssets().open("webui/app.css");
                return new WebResourceResponse("text/css", "UTF-8", 200, "OK", headers, is);
            }
            if ("/__assets/app.js".equals(path)) {
                InputStream is = context.getAssets().open("webui/app.js");
                return new WebResourceResponse("application/javascript", "UTF-8", 200, "OK", headers, is);
            }
            if ("/__assets/lion.svg".equals(path)) {
                InputStream is = context.getAssets().open("webui/lion.svg");
                return new WebResourceResponse("image/svg+xml", "UTF-8", 200, "OK", headers, is);
            }

            String html = ShieldsEngine.loadTextAsset(context.getAssets(), "webui/app.html");
            html = html.replace("__BO_SESSION_TOKEN__", sessionToken);
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            byte[] err = "Errore caricamento interfaccia Brave Origin".getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse("text/plain", "UTF-8", 500, "Error", headers, new ByteArrayInputStream(err));
        }
    }
}
