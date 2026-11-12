package com.tixigx.braveorigin;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OriginPrefs {

    private static final String PREFS_FILE = "brave_origin_prefs_v2";

    public static final class SiteShieldsConfig {
        public boolean shieldsUp = true;
        public int adblockLevel = 1;       // 0=off, 1=standard, 2=aggressive
        public int httpsUpgrade = 1;       // 0=off, 1=standard, 2=strict
        public boolean blockScripts = false;
        public int fingerprintingLevel = 1;// 0=off, 1=standard, 2=strict
        public int cookiePolicy = 1;       // 0=allow all, 1=block 3p, 2=block all
        public boolean forgetMeOnClose = false;
    }

    public static final class LinkItem {
        public final String title;
        public final String url;
        public final long time;
        public final String extra;

        public LinkItem(String title, String url, long time, String extra) {
            this.title = title != null ? title : "";
            this.url = url != null ? url : "";
            this.time = time;
            this.extra = extra != null ? extra : "";
        }
    }

    private final SharedPreferences sp;

    // Default Brave Origin policies (matches brave/brave-core standalone Origin defaults)
    private static final Map<String, Boolean> DEFAULT_ORIGIN_POLICIES;
    static {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("BraveRewardsDisabled", true);
        m.put("BraveP3AEnabled", false);
        m.put("BraveStatsPingEnabled", false);
        m.put("BraveAIChatEnabled", false);
        m.put("BraveLocalAIEnabled", false);
        m.put("BraveNewsDisabled", true);
        m.put("BraveSpeedreaderEnabled", false);
        m.put("BravePlaylistEnabled", false);
        m.put("BraveTalkDisabled", true);
        m.put("TorDisabled", true);
        m.put("BraveVPNDisabled", true);
        m.put("BraveWalletDisabled", true);
        m.put("BraveWaybackMachineEnabled", false);
        m.put("EmailAliasesEnabled", false);
        m.put("PsstEnabled", false);
        m.put("BraveWebDiscoveryEnabled", false);
        DEFAULT_ORIGIN_POLICIES = Collections.unmodifiableMap(m);
    }

    public OriginPrefs(Context context) {
        this.sp = context.getApplicationContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        ensureDefaultsInitialized();
    }

    private void ensureDefaultsInitialized() {
        if (sp.getBoolean("_initialized_v2", false)) {
            return;
        }
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Boolean> e : DEFAULT_ORIGIN_POLICIES.entrySet()) {
            ed.putBoolean("policy_" + e.getKey(), e.getValue());
        }
        // Seed clean Top Sites & Bookmarks for first launch
        JSONArray top = new JSONArray();
        putSite(top, "Brave Search", "https://search.brave.com");
        putSite(top, "Wikipedia", "https://it.wikipedia.org");
        putSite(top, "GitHub", "https://github.com");
        putSite(top, "Reddit", "https://www.reddit.com");
        putSite(top, "DuckDuckGo", "https://duckduckgo.com");
        putSite(top, "YouTube", "https://www.youtube.com");
        ed.putString("json_top_sites", top.toString());

        JSONArray bm = new JSONArray();
        putBookmarkObj(bm, "Brave Origin", "brave://settings/origin");
        putBookmarkObj(bm, "Brave Search", "https://search.brave.com");
        putBookmarkObj(bm, "Filtri Scudi", "brave://adblock");
        putBookmarkObj(bm, "Wikipedia", "https://it.wikipedia.org");
        putBookmarkObj(bm, "GitHub", "https://github.com");
        ed.putString("json_bookmarks", bm.toString());

        ed.putBoolean("_initialized_v2", true);
        ed.apply();
    }

    private static void putSite(JSONArray arr, String title, String url) {
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("url", url);
            arr.put(o);
        } catch (JSONException ignored) {
        }
    }

    private static void putBookmarkObj(JSONArray arr, String title, String url) {
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("url", url);
            o.put("time", System.currentTimeMillis());
            arr.put(o);
        } catch (JSONException ignored) {
        }
    }

    /* ================= ORIGIN POLICIES & PRESETS ================= */
    public synchronized boolean getOriginPolicy(String key) {
        Boolean def = DEFAULT_ORIGIN_POLICIES.get(key);
        return sp.getBoolean("policy_" + key, def != null ? def : false);
    }

    public synchronized void setOriginPolicy(String key, boolean value) {
        if (DEFAULT_ORIGIN_POLICIES.containsKey(key)) {
            sp.edit().putBoolean("policy_" + key, value).apply();
        }
    }

    public synchronized boolean isOriginFeatureEnabled(String policyKey, boolean inverted) {
        boolean raw = getOriginPolicy(policyKey);
        return inverted ? !raw : raw;
    }

    public synchronized void applyOriginPreset(String preset) {
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Boolean> e : DEFAULT_ORIGIN_POLICIES.entrySet()) {
            ed.putBoolean("policy_" + e.getKey(), e.getValue());
        }
        if ("hard".equalsIgnoreCase(preset)) {
            ed.putBoolean("policy_BraveSpeedreaderEnabled", true);
            ed.putBoolean("policy_BraveWaybackMachineEnabled", true);
            ed.putInt("pref_adblockLevel", 1);
            ed.putInt("pref_httpsUpgrade", 1);
            ed.putInt("pref_fingerprintingLevel", 1);
            ed.putBoolean("pref_memorySaver", true);
            ed.putBoolean("pref_showBookmarksBar", true);
        } else if ("extreme".equalsIgnoreCase(preset)) {
            ed.putInt("pref_adblockLevel", 2);
            ed.putInt("pref_httpsUpgrade", 2);
            ed.putInt("pref_fingerprintingLevel", 2);
            ed.putInt("pref_cookiePolicy", 1);
            ed.putBoolean("pref_clearOnExit", true);
            ed.putBoolean("pref_devToolsEnabled", false);
            ed.putBoolean("pref_omniboxLocalSuggestions", false);
        } else {
            // Pure Origin default
            ed.putInt("pref_adblockLevel", 1);
            ed.putInt("pref_httpsUpgrade", 1);
            ed.putInt("pref_fingerprintingLevel", 1);
        }
        ed.apply();
    }

    /* ================= GENERIC PREFS ================= */
    public synchronized boolean getBoolean(String key, boolean def) {
        return sp.getBoolean("pref_" + key, def);
    }

    public synchronized void setBoolean(String key, boolean val) {
        sp.edit().putBoolean("pref_" + key, val).apply();
    }

    public synchronized int getInt(String key, int def) {
        return sp.getInt("pref_" + key, def);
    }

    public synchronized void setInt(String key, int val) {
        sp.edit().putInt("pref_" + key, val).apply();
    }

    public synchronized String getString(String key, String def) {
        return sp.getString("pref_" + key, def);
    }

    public synchronized void setString(String key, String val) {
        sp.edit().putString("pref_" + key, val != null ? val : "").apply();
    }

    /* ================= CONVENIENCE GETTERS ================= */
    public boolean isShieldsMasterEnabled() { return getBoolean("shieldsMaster", true); }
    public int getGlobalAdblockLevel() { return getInt("adblockLevel", 1); }
    public int getGlobalHttpsUpgrade() { return getInt("httpsUpgrade", 1); }
    public boolean isGlobalBlockScripts() { return getBoolean("blockScripts", false); }
    public int getGlobalFingerprintingLevel() { return getInt("fingerprintingLevel", 1); }
    public int getGlobalCookiePolicy() { return getInt("cookiePolicy", 1); }
    public boolean isSendGpc() { return getBoolean("sendGpc", true); }
    public boolean isCleanUrlsEnabled() { return getBoolean("filterCleanUrls", true); }
    public boolean isDeAmpEnabled() { return getBoolean("filterDeAmp", true); }

    public boolean isFilterEasylist() { return getBoolean("filterEasylist", true); }
    public boolean isFilterCookies() { return getBoolean("filterCookies", true); }
    public boolean isFilterSocial() { return getBoolean("filterSocial", true); }
    public boolean isFilterYtAds() { return getBoolean("filterYtAds", true); }
    public boolean isFilterYtShorts() { return getBoolean("filterYtShorts", false); }
    public boolean isFilterYtDistracting() { return getBoolean("filterYtDistracting", false); }

    public String getThemeMode() { return getString("themeMode", "dark"); }
    public boolean isShowTabStrip() { return getBoolean("showTabStrip", true); }
    public boolean isShowBookmarksBar() { return getBoolean("showBookmarksBar", true); }
    public boolean isBottomToolbar() { return getBoolean("bottomToolbar", false); }
    public boolean isShowHomeButton() { return getBoolean("showHomeButton", true); }
    public String getHomePageUrl() {
        String u = getString("homePageUrl", "brave://newtab");
        return TextUtils.isEmpty(u) ? "brave://newtab" : u;
    }
    public boolean isDesktopModeDefault() { return getBoolean("desktopModeDefault", false); }
    public int getDefaultZoom() { return getInt("defaultZoom", 100); }
    public String getSearchEngine() { return getString("searchEngine", "brave"); }
    public String getCustomSearchUrl() { return getString("customSearchUrl", "https://search.brave.com/search?q=%s"); }
    public boolean isOmniboxSuggestions() { return getBoolean("omniboxLocalSuggestions", true); }

    public boolean isClearOnExit() { return getBoolean("clearOnExit", false); }
    public boolean isIncognitoSecure() { return getBoolean("incognitoSecure", true); }
    public boolean isBlockPopups() { return getBoolean("blockPopups", true); }
    public boolean isProtectWebRtc() { return getBoolean("protectWebRtc", true); }
    public boolean isMemorySaver() { return getBoolean("memorySaver", true); }
    public boolean isRestoreSession() { return getBoolean("restoreSession", true); }
    public boolean isDevToolsEnabled() { return getBoolean("devToolsEnabled", true); }
    public boolean isForceDarkWeb() { return getBoolean("forceDarkWeb", false); }

    public String buildSearchUrl(String query) {
        String q = query == null ? "" : query.trim();
        // Support !bang shortcuts like Brave Search
        if (q.startsWith("!")) {
            int spIdx = q.indexOf(' ');
            if (spIdx > 1) {
                String bang = q.substring(0, spIdx).toLowerCase();
                String rest = android.net.Uri.encode(q.substring(spIdx + 1).trim());
                switch (bang) {
                    case "!b":
                    case "!brave": return "https://search.brave.com/search?q=" + rest;
                    case "!g": return "https://www.google.com/search?q=" + rest;
                    case "!ddg": return "https://duckduckgo.com/?q=" + rest;
                    case "!w": return "https://it.wikipedia.org/w/index.php?search=" + rest;
                    case "!yt": return "https://www.youtube.com/results?search_query=" + rest;
                    case "!gh": return "https://github.com/search?q=" + rest;
                    case "!sp": return "https://www.startpage.com/do/dsearch?query=" + rest;
                    case "!wayback": return "https://web.archive.org/web/*/" + q.substring(spIdx + 1).trim();
                }
            }
        }
        String enc = android.net.Uri.encode(q);
        switch (getSearchEngine()) {
            case "duckduckgo": return "https://duckduckgo.com/?q=" + enc;
            case "startpage": return "https://www.startpage.com/do/dsearch?query=" + enc;
            case "qwant": return "https://www.qwant.com/?q=" + enc;
            case "ecosia": return "https://www.ecosia.org/search?q=" + enc;
            case "mojeek": return "https://www.mojeek.com/search?q=" + enc;
            case "google": return "https://www.google.com/search?q=" + enc;
            case "custom":
                String tpl = getCustomSearchUrl();
                if (tpl.contains("%s")) return tpl.replace("%s", enc);
                return tpl + enc;
            case "brave":
            default:
                return "https://search.brave.com/search?q=" + enc;
        }
    }

    /* ================= PER-SITE SHIELDS CONFIG ================= */
    public synchronized SiteShieldsConfig getSiteShields(String host) {
        SiteShieldsConfig cfg = new SiteShieldsConfig();
        cfg.shieldsUp = isShieldsMasterEnabled();
        cfg.adblockLevel = getGlobalAdblockLevel();
        cfg.httpsUpgrade = getGlobalHttpsUpgrade();
        cfg.blockScripts = isGlobalBlockScripts();
        cfg.fingerprintingLevel = getGlobalFingerprintingLevel();
        cfg.cookiePolicy = getGlobalCookiePolicy();
        cfg.forgetMeOnClose = false;

        if (TextUtils.isEmpty(host)) {
            return cfg;
        }
        String norm = normalizeHost(host);
        String raw = sp.getString("site_shields_" + norm, null);
        if (raw == null) {
            return cfg;
        }
        try {
            JSONObject o = new JSONObject(raw);
            cfg.shieldsUp = o.optBoolean("up", cfg.shieldsUp);
            cfg.adblockLevel = o.optInt("ad", cfg.adblockLevel);
            cfg.httpsUpgrade = o.optInt("https", cfg.httpsUpgrade);
            cfg.blockScripts = o.optBoolean("noscript", cfg.blockScripts);
            cfg.fingerprintingLevel = o.optInt("fp", cfg.fingerprintingLevel);
            cfg.cookiePolicy = o.optInt("cookie", cfg.cookiePolicy);
            cfg.forgetMeOnClose = o.optBoolean("forget", false);
        } catch (JSONException ignored) {
        }
        return cfg;
    }

    public synchronized void saveSiteShields(String host, SiteShieldsConfig cfg) {
        if (TextUtils.isEmpty(host) || cfg == null) return;
        String norm = normalizeHost(host);
        try {
            JSONObject o = new JSONObject();
            o.put("up", cfg.shieldsUp);
            o.put("ad", cfg.adblockLevel);
            o.put("https", cfg.httpsUpgrade);
            o.put("noscript", cfg.blockScripts);
            o.put("fp", cfg.fingerprintingLevel);
            o.put("cookie", cfg.cookiePolicy);
            o.put("forget", cfg.forgetMeOnClose);
            sp.edit().putString("site_shields_" + norm, o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public static String normalizeHost(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase();
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }

    /* ================= PRIVACY STATS ================= */
    public synchronized void recordBlockedItem() {
        long ads = sp.getLong("stat_ads_blocked", 0L) + 1L;
        long bytes = sp.getLong("stat_bytes_saved", 0L) + 18400L;
        long ms = sp.getLong("stat_ms_saved", 0L) + 52L;
        sp.edit()
            .putLong("stat_ads_blocked", ads)
            .putLong("stat_bytes_saved", bytes)
            .putLong("stat_ms_saved", ms)
            .apply();
    }

    public synchronized long getAdsBlockedTotal() { return sp.getLong("stat_ads_blocked", 0L); }
    public synchronized long getBytesSavedTotal() { return sp.getLong("stat_bytes_saved", 0L); }
    public synchronized long getMsSavedTotal() { return sp.getLong("stat_ms_saved", 0L); }

    /* ================= CUSTOM FILTERS & ZAPPED SELECTORS ================= */
    public synchronized String getCustomFilters() {
        return sp.getString("custom_filters_raw", "");
    }

    public synchronized void setCustomFilters(String rules) {
        sp.edit().putString("custom_filters_raw", rules != null ? rules : "").apply();
    }

    public synchronized void addZappedSelector(String host, String selector) {
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(selector)) return;
        String norm = normalizeHost(host);
        try {
            JSONObject map = new JSONObject(sp.getString("json_zapped", "{}"));
            JSONArray arr = map.optJSONArray(norm);
            if (arr == null) arr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (selector.equals(arr.optString(i))) return;
            }
            arr.put(selector);
            map.put(norm, arr);
            sp.edit().putString("json_zapped", map.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public synchronized List<String> getZappedSelectorsForHost(String host) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(host)) return out;
        String norm = normalizeHost(host);
        try {
            JSONObject map = new JSONObject(sp.getString("json_zapped", "{}"));
            JSONArray arr = map.optJSONArray(norm);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i);
                    if (!TextUtils.isEmpty(s)) out.add(s);
                }
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public synchronized void clearAllZappedSelectors() {
        sp.edit().remove("json_zapped").apply();
    }

    /* ================= BOOKMARKS, TOP SITES, HISTORY, DOWNLOADS, PLAYLIST ================= */
    public synchronized List<LinkItem> getBookmarks() {
        return readLinkList("json_bookmarks");
    }

    public synchronized boolean isBookmarked(String url) {
        if (TextUtils.isEmpty(url)) return false;
        for (LinkItem b : getBookmarks()) {
            if (url.equals(b.url)) return true;
        }
        return false;
    }

    public synchronized void addOrToggleBookmark(String title, String url) {
        if (TextUtils.isEmpty(url)) return;
        List<LinkItem> list = getBookmarks();
        for (int i = 0; i < list.size(); i++) {
            if (url.equals(list.get(i).url)) {
                list.remove(i);
                writeLinkList("json_bookmarks", list);
                return;
            }
        }
        list.add(0, new LinkItem(TextUtils.isEmpty(title) ? url : title, url, System.currentTimeMillis(), ""));
        writeLinkList("json_bookmarks", list);
    }

    public synchronized void addBookmark(String title, String url) {
        if (TextUtils.isEmpty(url)) return;
        List<LinkItem> list = getBookmarks();
        for (int i = 0; i < list.size(); i++) {
            if (url.equals(list.get(i).url)) {
                list.set(i, new LinkItem(TextUtils.isEmpty(title) ? url : title, url, System.currentTimeMillis(), ""));
                writeLinkList("json_bookmarks", list);
                return;
            }
        }
        list.add(0, new LinkItem(TextUtils.isEmpty(title) ? url : title, url, System.currentTimeMillis(), ""));
        writeLinkList("json_bookmarks", list);
    }

    public synchronized void removeBookmark(String url) {
        List<LinkItem> list = getBookmarks();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).url.equals(url)) list.remove(i);
        }
        writeLinkList("json_bookmarks", list);
    }

    public synchronized List<LinkItem> getTopSites() {
        return readLinkList("json_top_sites");
    }

    public synchronized void addTopSite(String title, String url) {
        List<LinkItem> list = getTopSites();
        list.add(new LinkItem(title, url, System.currentTimeMillis(), ""));
        writeLinkList("json_top_sites", list);
    }

    public synchronized void removeTopSite(int index) {
        List<LinkItem> list = getTopSites();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            writeLinkList("json_top_sites", list);
        }
    }

    public synchronized void recordHistory(String title, String url) {
        if (TextUtils.isEmpty(url) || url.startsWith("brave://") || url.startsWith("about:")) return;
        List<LinkItem> list = readLinkList("json_history");
        if (!list.isEmpty() && url.equals(list.get(0).url)) {
            list.set(0, new LinkItem(TextUtils.isEmpty(title) ? url : title, url, System.currentTimeMillis(), ""));
        } else {
            list.add(0, new LinkItem(TextUtils.isEmpty(title) ? url : title, url, System.currentTimeMillis(), ""));
        }
        if (list.size() > 350) {
            list = new ArrayList<>(list.subList(0, 350));
        }
        writeLinkList("json_history", list);
    }

    public synchronized List<LinkItem> getHistory() {
        return readLinkList("json_history");
    }

    public synchronized void removeHistoryAt(int index) {
        List<LinkItem> list = readLinkList("json_history");
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            writeLinkList("json_history", list);
        }
    }

    public synchronized void clearHistory() {
        sp.edit().remove("json_history").apply();
    }

    public synchronized void recordDownload(String fileName, String url, String mimeType) {
        List<LinkItem> list = readLinkList("json_downloads");
        list.add(0, new LinkItem(fileName, url, System.currentTimeMillis(), mimeType));
        if (list.size() > 150) {
            list = new ArrayList<>(list.subList(0, 150));
        }
        writeLinkList("json_downloads", list);
    }

    public synchronized List<LinkItem> getDownloads() {
        return readLinkList("json_downloads");
    }

    public synchronized void clearDownloads() {
        sp.edit().remove("json_downloads").apply();
    }

    public synchronized void addPlaylistItem(String title, String url) {
        List<LinkItem> list = readLinkList("json_playlist");
        list.add(0, new LinkItem(title, url, System.currentTimeMillis(), ""));
        writeLinkList("json_playlist", list);
    }

    public synchronized void removePlaylistAt(int index) {
        List<LinkItem> list = readLinkList("json_playlist");
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            writeLinkList("json_playlist", list);
        }
    }

    /* ================= SESSION PERSISTENCE ================= */
    public synchronized void saveTabSession(JSONArray tabsJson, int activeIndex) {
        sp.edit()
            .putString("session_tabs_json", tabsJson != null ? tabsJson.toString() : "[]")
            .putInt("session_active_idx", activeIndex)
            .apply();
    }

    public synchronized JSONArray loadTabSession() {
        try {
            return new JSONArray(sp.getString("session_tabs_json", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public synchronized int loadTabSessionActiveIndex() {
        return sp.getInt("session_active_idx", 0);
    }

    private List<LinkItem> readLinkList(String storageKey) {
        List<LinkItem> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(storageKey, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(new LinkItem(
                        o.optString("title", ""),
                        o.optString("url", ""),
                        o.optLong("time", 0L),
                        o.optString("extra", "")
                    ));
                }
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void writeLinkList(String storageKey, List<LinkItem> items) {
        JSONArray arr = new JSONArray();
        for (LinkItem it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("title", it.title);
                o.put("url", it.url);
                o.put("time", it.time);
                o.put("extra", it.extra);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        sp.edit().putString(storageKey, arr.toString()).apply();
    }

    /* ================= FULL WEBUI SNAPSHOT ================= */
    public synchronized String buildFullStateJson(ShieldsEngine shields, String webViewVer, String activeUa) {
        try {
            JSONObject root = new JSONObject();

            JSONObject pol = new JSONObject();
            for (String k : DEFAULT_ORIGIN_POLICIES.keySet()) {
                pol.put(k, getOriginPolicy(k));
            }
            root.put("policies", pol);

            JSONObject prefs = new JSONObject();
            prefs.put("shieldsMaster", isShieldsMasterEnabled());
            prefs.put("adblockLevel", getGlobalAdblockLevel());
            prefs.put("httpsUpgrade", getGlobalHttpsUpgrade());
            prefs.put("blockScripts", isGlobalBlockScripts());
            prefs.put("fingerprintingLevel", getGlobalFingerprintingLevel());
            prefs.put("cookiePolicy", getGlobalCookiePolicy());
            prefs.put("sendGpc", isSendGpc());
            prefs.put("filterCleanUrls", isCleanUrlsEnabled());
            prefs.put("filterDeAmp", isDeAmpEnabled());
            prefs.put("filterEasylist", isFilterEasylist());
            prefs.put("filterCookies", isFilterCookies());
            prefs.put("filterSocial", isFilterSocial());
            prefs.put("filterYtAds", isFilterYtAds());
            prefs.put("filterYtShorts", isFilterYtShorts());
            prefs.put("filterYtDistracting", isFilterYtDistracting());
            prefs.put("themeMode", getThemeMode());
            prefs.put("showTabStrip", isShowTabStrip());
            prefs.put("showBookmarksBar", isShowBookmarksBar());
            prefs.put("bottomToolbar", isBottomToolbar());
            prefs.put("showHomeButton", isShowHomeButton());
            prefs.put("homePageUrl", getHomePageUrl());
            prefs.put("desktopModeDefault", isDesktopModeDefault());
            prefs.put("defaultZoom", getDefaultZoom());
            prefs.put("searchEngine", getSearchEngine());
            prefs.put("customSearchUrl", getCustomSearchUrl());
            prefs.put("omniboxLocalSuggestions", isOmniboxSuggestions());
            prefs.put("clearOnExit", isClearOnExit());
            prefs.put("incognitoSecure", isIncognitoSecure());
            prefs.put("blockPopups", isBlockPopups());
            prefs.put("protectWebRtc", isProtectWebRtc());
            prefs.put("memorySaver", isMemorySaver());
            prefs.put("restoreSession", isRestoreSession());
            prefs.put("devToolsEnabled", isDevToolsEnabled());
            prefs.put("forceDarkWeb", isForceDarkWeb());
            prefs.put("smoothScroll", getBoolean("smoothScroll", true));
            prefs.put("ntpShowStats", getBoolean("ntpShowStats", true));
            prefs.put("ntpShowClock", getBoolean("ntpShowClock", true));
            prefs.put("ntpShowTopSites", getBoolean("ntpShowTopSites", true));
            prefs.put("ntpBgStyle", getString("ntpBgStyle", "origin_mesh"));
            root.put("prefs", prefs);

            JSONObject stats = new JSONObject();
            stats.put("adsBlocked", getAdsBlockedTotal());
            stats.put("bytesSaved", getBytesSavedTotal());
            stats.put("msSaved", getMsSavedTotal());
            root.put("stats", stats);

            root.put("topSites", toJsonArray(getTopSites(), false));
            root.put("bookmarks", toJsonArray(getBookmarks(), false));
            root.put("history", toJsonArray(getHistory(), false));
            root.put("downloads", toJsonArray(getDownloads(), true));
            root.put("playlist", toJsonArray(readLinkList("json_playlist"), false));
            root.put("customFilters", getCustomFilters());

            JSONObject sc = new JSONObject();
            sc.put("adDomains", shields != null ? shields.getAdDomainCount() : 54656);
            sc.put("cookieDomains", shields != null ? shields.getCookieDomainCount() : 179);
            root.put("shieldsCounts", sc);

            JSONObject ver = new JSONObject();
            ver.put("packageName", "com.tixigx.braveorigin");
            ver.put("osInfo", "Android " + android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + ")");
            ver.put("webViewVersion", webViewVer != null ? webViewVer : "Chromium System WebView");
            ver.put("userAgent", activeUa != null ? activeUa : "");
            root.put("version", ver);

            return root.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private static JSONArray toJsonArray(List<LinkItem> items, boolean asDownload) {
        JSONArray arr = new JSONArray();
        for (LinkItem it : items) {
            try {
                JSONObject o = new JSONObject();
                if (asDownload) {
                    o.put("fileName", it.title);
                    o.put("mimeType", it.extra);
                } else {
                    o.put("title", it.title);
                }
                o.put("url", it.url);
                o.put("time", it.time);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        return arr;
    }
}
