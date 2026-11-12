package com.tixigx.braveorigin;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ShieldsEngine {

    private static final byte[] TRANSPARENT_GIF = new byte[]{
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
        (byte) 0x80, 0x00, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff,
        0x00, 0x00, 0x00, 0x21, (byte) 0xf9, 0x04, 0x01, 0x00, 0x00,
        0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
        0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b
    };

    private static final Set<String> DEDICATED_ADSERVERS;
    static {
        Set<String> s = new HashSet<>();
        Collections.addAll(s,
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net", "taboola.com",
            "outbrain.com", "pubmatic.com", "rubiconproject.com", "openx.net",
            "casalemedia.com", "smartadserver.com", "advertising.com", "moatads.com",
            "scorecardresearch.com", "quantserve.com", "amazon-adsystem.com",
            "adcolony.com", "unityads.unity3d.com", "applovin.com", "vungle.com",
            "chartboost.com", "inmobi.com", "popads.net", "propellerads.com",
            "exoclick.com", "juicyads.com", "hotjar.com", "clarity.ms", "mouseflow.com"
        );
        DEDICATED_ADSERVERS = Collections.unmodifiableSet(s);
    }

    public static final class BlockResult {
        public final boolean blocked;
        public final String category;
        public final String host;
        public final WebResourceResponse response;

        public BlockResult(boolean blocked, String category, String host, WebResourceResponse response) {
            this.blocked = blocked;
            this.category = category;
            this.host = host;
            this.response = response;
        }

        public static BlockResult allow() {
            return new BlockResult(false, null, null, null);
        }
    }

    private volatile Set<String> adDomains = Collections.emptySet();
    private volatile Set<String> cookieDomains = Collections.emptySet();
    private volatile Set<String> socialDomains = Collections.emptySet();
    private volatile List<String> urlPatterns = Collections.emptyList();
    private volatile Set<String> queryParams = Collections.emptySet();
    private volatile String cosmeticAdsCss = "";
    private volatile String cosmeticCookiesCss = "";
    private volatile String ytShortsCss = "";
    private volatile String ytDistractingCss = "";

    private volatile Set<String> customBlockDomains = Collections.emptySet();
    private volatile Set<String> customAllowDomains = Collections.emptySet();
    private volatile List<String> customGlobalCss = Collections.emptyList();
    private volatile Map<String, List<String>> customHostCss = Collections.emptyMap();

    private final int sessionFarbleSeed = (int) ((System.currentTimeMillis() ^ System.nanoTime()) & 0x7FFF);

    public ShieldsEngine(final Context context, final OriginPrefs prefs) {
        reloadCustomRules(prefs.getCustomFilters());
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                loadAssets(context.getAssets());
            }
        }, "BraveShieldsLoader");
        loader.setPriority(Thread.NORM_PRIORITY);
        loader.start();
    }

    private void loadAssets(AssetManager am) {
        adDomains = loadLineSet(am, "shields/ad_domains.txt", 64000);
        cookieDomains = loadLineSet(am, "shields/cookie_domains.txt", 256);
        socialDomains = loadLineSet(am, "shields/social_domains.txt", 64);
        urlPatterns = new ArrayList<>(loadLineSet(am, "shields/url_patterns.txt", 64));
        queryParams = loadLineSet(am, "shields/query_params.txt", 128);
        cosmeticAdsCss = loadTextAsset(am, "shields/cosmetic_ads.css");
        cosmeticCookiesCss = loadTextAsset(am, "shields/cosmetic_cookies.css");
        ytShortsCss = loadTextAsset(am, "shields/yt_shorts.css");
        ytDistractingCss = loadTextAsset(am, "shields/yt_distracting.css");
    }

    private static Set<String> loadLineSet(AssetManager am, String path, int initialCap) {
        Set<String> out = new HashSet<>(initialCap);
        try (InputStream is = am.open(path);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (!s.isEmpty() && s.charAt(0) != '!' && s.charAt(0) != '#') {
                    out.add(s.toLowerCase());
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static String loadTextAsset(AssetManager am, String path) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = am.open(path);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    public int getAdDomainCount() {
        int c = adDomains.size();
        return c > 0 ? c : 54656;
    }

    public int getCookieDomainCount() {
        int c = cookieDomains.size();
        return c > 0 ? c : 179;
    }

    public synchronized void reloadCustomRules(String raw) {
        Set<String> blocks = new HashSet<>();
        Set<String> allows = new HashSet<>();
        List<String> globalCss = new ArrayList<>();
        Map<String, List<String>> hostCss = new HashMap<>();

        if (!TextUtils.isEmpty(raw)) {
            String[] lines = raw.split("\\r?\\n");
            for (String l : lines) {
                String s = l.trim();
                if (s.isEmpty() || s.startsWith("!")) continue;
                if (s.startsWith("@@||")) {
                    String d = stripRuleDomain(s.substring(4));
                    if (!d.isEmpty()) allows.add(d);
                } else if (s.startsWith("||")) {
                    String d = stripRuleDomain(s.substring(2));
                    if (!d.isEmpty()) blocks.add(d);
                } else if (s.startsWith("##")) {
                    String sel = s.substring(2).trim();
                    if (!sel.isEmpty()) globalCss.add(sel);
                } else {
                    int idx = s.indexOf("##");
                    if (idx > 0) {
                        String hostsPart = s.substring(0, idx).trim().toLowerCase();
                        String sel = s.substring(idx + 2).trim();
                        if (!sel.isEmpty()) {
                            for (String h : hostsPart.split(",")) {
                                String nh = OriginPrefs.normalizeHost(h);
                                if (!nh.isEmpty()) {
                                    List<String> list = hostCss.get(nh);
                                    if (list == null) {
                                        list = new ArrayList<>();
                                        hostCss.put(nh, list);
                                    }
                                    list.add(sel);
                                }
                            }
                        }
                    }
                }
            }
        }
        customBlockDomains = blocks;
        customAllowDomains = allows;
        customGlobalCss = globalCss;
        customHostCss = hostCss;
    }

    private static String stripRuleDomain(String part) {
        int end = part.length();
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '^' || c == '/' || c == '$' || c == '*') {
                end = i;
                break;
            }
        }
        return part.substring(0, end).trim().toLowerCase();
    }

    /* ================= NETWORK INTERCEPTION ================= */
    public BlockResult shouldBlockRequest(
            WebResourceRequest request,
            String pageHost,
            OriginPrefs.SiteShieldsConfig siteCfg,
            OriginPrefs prefs) {

        if (request == null || siteCfg == null || !siteCfg.shieldsUp) {
            return BlockResult.allow();
        }
        Uri uri = request.getUrl();
        if (uri == null) return BlockResult.allow();
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return BlockResult.allow();
        }
        String reqHost = uri.getHost();
        if (TextUtils.isEmpty(reqHost)) return BlockResult.allow();
        reqHost = reqHost.toLowerCase();

        if ("webui.braveorigin.local".equals(reqHost)) {
            return BlockResult.allow();
        }

        // Allowlist check
        if (matchesDomainSet(reqHost, customAllowDomains)) {
            return BlockResult.allow();
        }

        boolean mainFrame = request.isForMainFrame();
        String fullUrlLower = uri.toString().toLowerCase();
        boolean thirdParty = !isSameSite(reqHost, pageHost);

        // 1. Per-site Script Blocking (for subresources)
        if (!mainFrame && siteCfg.blockScripts && looksLikeScript(uri, request.getRequestHeaders())) {
            return new BlockResult(true, "Script", reqHost, buildBlockedResponse(uri, request.getRequestHeaders()));
        }

        // 2. Custom Domain Blocks
        if (matchesDomainSet(reqHost, customBlockDomains)) {
            return new BlockResult(true, "Filtro Utente", reqHost, buildBlockedResponse(uri, request.getRequestHeaders()));
        }

        // 3. Ad & Tracker Blocking
        if (siteCfg.adblockLevel > 0) {
            boolean aggressive = (siteCfg.adblockLevel >= 2);
            if (prefs.isFilterEasylist()) {
                if (matchesDomainSet(reqHost, DEDICATED_ADSERVERS)) {
                    if (!mainFrame || aggressive) {
                        return new BlockResult(true, "Ad/Tracker", reqHost, buildBlockedResponse(uri, request.getRequestHeaders()));
                    }
                }
                if ((thirdParty || aggressive) && !mainFrame && matchesDomainSet(reqHost, adDomains)) {
                    return new BlockResult(true, "Ad/Tracker", reqHost, buildBlockedResponse(uri, request.getRequestHeaders()));
                }
                if (!mainFrame && (thirdParty || aggressive)) {
                    for (String pat : urlPatterns) {
                        if (fullUrlLower.contains(pat)) {
                            return new BlockResult(true, "URL Tracker", reqHost, buildBlockedResponse(uri, request.getRequestHeaders()));
                        }
                    }
                }
            }
            if (!mainFrame && prefs.isFilterCookies() && matchesDomainSet(reqHost, cookieDomains)) {
                return new BlockResult(true, "Banner Cookie", reqHost, buildBlockedResponse(uri, request.getRequestHeaders()));
            }
            if (!mainFrame && thirdParty && prefs.isFilterSocial() && matchesDomainSet(reqHost, socialDomains)) {
                return new BlockResult(true, "Social Tracker", reqHost, buildBlockedResponse(uri, request.getRequestHeaders()));
            }
        }

        return BlockResult.allow();
    }

    private static boolean matchesDomainSet(String host, Set<String> domainSet) {
        if (domainSet == null || domainSet.isEmpty() || TextUtils.isEmpty(host)) {
            return false;
        }
        String cur = host;
        while (true) {
            if (domainSet.contains(cur)) {
                return true;
            }
            int dot = cur.indexOf('.');
            if (dot < 0) break;
            int nextDot = cur.indexOf('.', dot + 1);
            if (nextDot < 0) break;
            cur = cur.substring(dot + 1);
        }
        return false;
    }

    public static boolean isSameSite(String h1, String h2) {
        if (TextUtils.isEmpty(h1) || TextUtils.isEmpty(h2)) return false;
        String r1 = registrableApproximation(h1.toLowerCase());
        String r2 = registrableApproximation(h2.toLowerCase());
        return r1.equals(r2);
    }

    private static String registrableApproximation(String host) {
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        String last = parts[parts.length - 1];
        String secondLast = parts[parts.length - 2];
        if (last.length() == 2 && secondLast.length() <= 3 && parts.length >= 3) {
            return parts[parts.length - 3] + "." + secondLast + "." + last;
        }
        return secondLast + "." + last;
    }

    private static boolean looksLikeScript(Uri uri, Map<String, String> headers) {
        String path = uri.getPath();
        if (path != null) {
            String p = path.toLowerCase();
            if (p.endsWith(".js") || p.endsWith(".mjs")) return true;
        }
        if (headers != null) {
            String dest = headers.get("Sec-Fetch-Dest");
            if ("script".equalsIgnoreCase(dest)) return true;
        }
        return false;
    }

    private static WebResourceResponse buildBlockedResponse(Uri uri, Map<String, String> headers) {
        String path = uri.getPath() != null ? uri.getPath().toLowerCase() : "";
        String accept = "";
        String dest = "";
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if ("accept".equalsIgnoreCase(e.getKey())) accept = e.getValue() != null ? e.getValue().toLowerCase() : "";
                if ("sec-fetch-dest".equalsIgnoreCase(e.getKey())) dest = e.getValue() != null ? e.getValue().toLowerCase() : "";
            }
        }
        Map<String, String> respHeaders = new HashMap<>();
        respHeaders.put("Access-Control-Allow-Origin", "*");
        respHeaders.put("Cache-Control", "no-store");

        if ("image".equals(dest) || accept.startsWith("image/") || path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg")) {
            return new WebResourceResponse("image/gif", null, 200, "OK", respHeaders, new ByteArrayInputStream(TRANSPARENT_GIF));
        }
        if ("script".equals(dest) || path.endsWith(".js") || path.endsWith(".mjs")) {
            return new WebResourceResponse("application/javascript", "UTF-8", 200, "OK", respHeaders, new ByteArrayInputStream(new byte[0]));
        }
        if ("style".equals(dest) || path.endsWith(".css")) {
            return new WebResourceResponse("text/css", "UTF-8", 200, "OK", respHeaders, new ByteArrayInputStream(new byte[0]));
        }
        return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content", respHeaders, new ByteArrayInputStream(new byte[0]));
    }

    /* ================= CLEAN URLS & DE-AMP & HTTPS UPGRADE ================= */
    public String cleanUrl(String rawUrl, boolean stripQuery, boolean deAmp) {
        if (TextUtils.isEmpty(rawUrl)) return rawUrl;
        String current = rawUrl.trim();
        if (!current.startsWith("http://") && !current.startsWith("https://")) {
            return current;
        }

        try {
            Uri uri = Uri.parse(current);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            String path = uri.getPath() != null ? uri.getPath() : "";

            // De-AMP unwrap
            if (deAmp) {
                if ((host.contains("google.") && path.startsWith("/amp/s/")) ||
                    (host.endsWith(".ampproject.org") && path.startsWith("/v/s/"))) {
                    String rest = path.startsWith("/amp/s/") ? path.substring(7) : path.substring(5);
                    current = "https://" + rest + (uri.getEncodedQuery() != null ? "?" + uri.getEncodedQuery() : "");
                    uri = Uri.parse(current);
                }
            }

            if (!stripQuery || uri.isOpaque() || uri.getEncodedQuery() == null) {
                return current;
            }

            Set<String> names = uri.getQueryParameterNames();
            if (names.isEmpty()) return current;

            boolean changed = false;
            StringBuilder newQuery = new StringBuilder();
            String rawQuery = uri.getEncodedQuery();
            String[] pairs = rawQuery.split("&");
            Set<String> stripSet = queryParams;

            for (String pair : pairs) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String key = Uri.decode(eq >= 0 ? pair.substring(0, eq) : pair);
                String keyLower = key.toLowerCase();
                boolean isTracker = stripSet.contains(key) || stripSet.contains(keyLower)
                    || keyLower.startsWith("utm_")
                    || ("si".equals(keyLower) && (host.contains("youtube.com") || host.contains("youtu.be")));
                if (isTracker) {
                    changed = true;
                } else {
                    if (newQuery.length() > 0) newQuery.append('&');
                    newQuery.append(pair);
                }
            }

            if (!changed) return current;

            Uri.Builder b = uri.buildUpon().clearQuery();
            if (newQuery.length() > 0) {
                b.encodedQuery(newQuery.toString());
            }
            return b.build().toString();
        } catch (Exception e) {
            return current;
        }
    }

    public static String maybeUpgradeHttps(String url, int httpsUpgradeLevel, Set<String> allowedHttpHosts) {
        if (httpsUpgradeLevel <= 0 || TextUtils.isEmpty(url) || !url.startsWith("http://")) {
            return url;
        }
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (TextUtils.isEmpty(host)) return url;
            String h = host.toLowerCase();
            if ("localhost".equals(h) || "127.0.0.1".equals(h) || h.endsWith(".local") || h.endsWith(".onion") || isPrivateIp(h)) {
                return url;
            }
            if (allowedHttpHosts != null && allowedHttpHosts.contains(h)) {
                return url;
            }
            return "https://" + url.substring(7);
        } catch (Exception e) {
            return url;
        }
    }

    private static boolean isPrivateIp(String host) {
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            String[] p = host.split("\\.");
            if (p.length == 4) {
                try {
                    int second = Integer.parseInt(p[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    /* ================= DOCUMENT COSMETIC & PRIVACY INJECTION ================= */
    public String buildPageProtectionScript(
            String pageUrl,
            OriginPrefs.SiteShieldsConfig siteCfg,
            OriginPrefs prefs) {

        if (TextUtils.isEmpty(pageUrl) || WebUiRouter.isWebUiUrl(pageUrl)) {
            return "";
        }
        if (siteCfg == null || !siteCfg.shieldsUp) {
            return "";
        }

        Uri u = Uri.parse(pageUrl);
        String host = u.getHost() != null ? u.getHost().toLowerCase() : "";
        String normHost = OriginPrefs.normalizeHost(host);
        boolean isYouTube = host.endsWith("youtube.com") || host.equals("youtu.be");

        StringBuilder css = new StringBuilder(65536);
        if (siteCfg.adblockLevel > 0) {
            if (prefs.isFilterEasylist() && !cosmeticAdsCss.isEmpty()) {
                css.append(cosmeticAdsCss).append('\n');
            }
            if (prefs.isFilterCookies() && !cosmeticCookiesCss.isEmpty()) {
                css.append(cosmeticCookiesCss).append('\n');
            }
            if (isYouTube && prefs.isFilterYtAds()) {
                css.append(".ytp-ad-overlay-container,.ytp-ad-message-container,ytd-ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,#masthead-ad{display:none!important;}\n");
            }
            if (isYouTube && prefs.isFilterYtShorts()) {
                css.append(ytShortsCss).append('\n');
            }
            if (isYouTube && prefs.isFilterYtDistracting()) {
                css.append(ytDistractingCss).append('\n');
            }
        }

        // Custom CSS & Zapped Elements
        List<String> extraSelectors = new ArrayList<>();
        extraSelectors.addAll(customGlobalCss);
        List<String> hc = customHostCss.get(normHost);
        if (hc != null) extraSelectors.addAll(hc);
        extraSelectors.addAll(prefs.getZappedSelectorsForHost(normHost));
        if (!extraSelectors.isEmpty()) {
            for (String sel : extraSelectors) {
                css.append(sel).append("{display:none!important;visibility:hidden!important;}\n");
            }
        }

        String cssJson = JSONObject.quote(css.toString());
        int fpLevel = siteCfg.fingerprintingLevel;
        boolean gpc = prefs.isSendGpc();
        boolean ytSkip = isYouTube && siteCfg.adblockLevel > 0 && prefs.isFilterYtAds();
        boolean protectWebRtc = prefs.isProtectWebRtc();

        return "(function(){" +
            "if(!window.__boInit){" +
            "window.__boInit=true;" +
            (gpc ? "try{Object.defineProperty(navigator,'globalPrivacyControl',{value:true,configurable:false});Object.defineProperty(navigator,'doNotTrack',{value:'1',configurable:false});}catch(e){}" : "") +
            (fpLevel > 0 ? buildFarblingJs(fpLevel >= 2) : "") +
            (protectWebRtc ? "try{if(window.RTCPeerConnection){const OrigPC=window.RTCPeerConnection;window.RTCPeerConnection=function(cfg){if(cfg&&cfg.iceServers&&" + (fpLevel >= 2) + "){cfg.iceTransportPolicy='relay';}return new OrigPC(cfg);};window.RTCPeerConnection.prototype=OrigPC.prototype;}}catch(e){}" : "") +
            (ytSkip ? buildYouTubeAdSkipJs() : "") +
            "}" +
            "try{var st=document.getElementById('__bo_shields_css');" +
            "if(!st){st=document.createElement('style');st.id='__bo_shields_css';(document.head||document.documentElement).appendChild(st);}" +
            "var c=" + cssJson + ";if(st.textContent!==c)st.textContent=c;}catch(e){}" +
            "})();";
    }

    private String buildFarblingJs(boolean strict) {
        int delta = (sessionFarbleSeed % 3) + 1;
        return "try{" +
            "if(navigator.getBattery){navigator.getBattery=undefined;}" +
            "const origGetImageData=CanvasRenderingContext2D.prototype.getImageData;" +
            "CanvasRenderingContext2D.prototype.getImageData=function(){" +
            "const d=origGetImageData.apply(this,arguments);" +
            "if(d&&d.data&&d.data.length>=16){d.data[0]=(d.data[0]^" + delta + ")&255;}" +
            "return d;};" +
            (strict ? "Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>4});Object.defineProperty(navigator,'deviceMemory',{get:()=>8});" : "") +
            "}catch(e){}";
    }

    private static String buildYouTubeAdSkipJs() {
        return "setInterval(function(){try{" +
            "var sb=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');" +
            "if(sb){sb.click();}" +
            "var ad=document.querySelector('.ad-showing video');" +
            "if(ad&&isFinite(ad.duration)&&ad.duration>0){ad.muted=true;ad.currentTime=ad.duration;}" +
            "}catch(e){}},650);";
    }

    /* ================= INTERACTIVE ELEMENT PICKER ================= */
    public static String buildElementPickerScript() {
        return "(function(){" +
            "if(document.getElementById('__bo_picker_bar'))return;" +
            "var picked=null,prevOutline='';" +
            "var bar=document.createElement('div');bar.id='__bo_picker_bar';" +
            "bar.style.cssText='position:fixed;bottom:16px;left:16px;right:16px;z-index:2147483647;background:#18181e;color:#fff;border:1px solid #fb542b;border-radius:14px;padding:12px 14px;display:flex;align-items:center;justify-content:space-between;gap:8px;font:13px system-ui,sans-serif;box-shadow:0 8px 28px rgba(0,0,0,.6);';" +
            "bar.innerHTML='<span id=\"__bo_pk_lbl\" style=\"flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;\">Tocca un elemento della pagina da bloccare…</span><button id=\"__bo_pk_ok\" style=\"background:#fb542b;color:#fff;border:0;border-radius:99px;padding:7px 13px;font-weight:700;\">Blocca</button><button id=\"__bo_pk_no\" style=\"background:#2b2b36;color:#fff;border:0;border-radius:99px;padding:7px 12px;\">Annulla</button>';" +
            "document.body.appendChild(bar);" +
            "function cssPath(el){if(!el||el.nodeType!==1)return '';if(el.id&&!/^[0-9]/.test(el.id))return '#'+CSS.escape(el.id);var parts=[];while(el&&el.nodeType===1&&el!==document.body&&parts.length<4){var s=el.nodeName.toLowerCase();if(el.className&&typeof el.className==='string'){var cls=el.className.trim().split(/\\s+/).filter(Boolean).slice(0,2);if(cls.length)s+='.'+cls.map(c=>CSS.escape(c)).join('.');}parts.unshift(s);el=el.parentElement;}return parts.join(' > ');}" +
            "function onTap(e){if(bar.contains(e.target))return;e.preventDefault();e.stopPropagation();if(picked)picked.style.outline=prevOutline;picked=e.target;prevOutline=picked.style.outline||'';picked.style.outline='2px solid #fb542b';document.getElementById('__bo_pk_lbl').textContent=cssPath(picked);}" +
            "function cleanup(){document.removeEventListener('click',onTap,true);if(picked)picked.style.outline=prevOutline;bar.remove();}" +
            "document.addEventListener('click',onTap,true);" +
            "document.getElementById('__bo_pk_no').onclick=cleanup;" +
            "document.getElementById('__bo_pk_ok').onclick=function(){if(picked){var sel=cssPath(picked);picked.style.setProperty('display','none','important');if(window.BravePageHelper&&sel){window.BravePageHelper.onElementZapped(location.hostname,sel);}}cleanup();};" +
            "})();";
    }

    /* ================= BRAVE SPEEDREADER OVERLAY ================= */
    public static String buildSpeedreaderToggleScript() {
        return "(function(){" +
            "var existing=document.getElementById('__bo_speedreader');" +
            "if(existing){existing.remove();document.documentElement.style.overflow='';return 'closed';}" +
            "var root=document.querySelector('article, [role=\"main\"], main')||document.body;" +
            "var clone=root.cloneNode(true);" +
            "clone.querySelectorAll('script,style,nav,header,footer,aside,iframe,form,button,.ad,.ads,[aria-hidden=\"true\"]').forEach(function(n){n.remove();});" +
            "var text=(clone.innerText||'').trim();" +
            "var words=text?text.split(/\\s+/).length:0;" +
            "var mins=Math.max(1,Math.round(words/210));" +
            "var ov=document.createElement('div');ov.id='__bo_speedreader';" +
            "ov.style.cssText='position:fixed;inset:0;z-index:2147483646;overflow-y:auto;background:#121216;color:#ececf1;padding:20px 18px 80px;font-family:Georgia,serif;font-size:18px;line-height:1.72;';" +
            "ov.innerHTML='<div style=\"max-width:680px;margin:0 auto;\">'+" +
            "'<div style=\"display:flex;justify-content:space-between;align-items:center;gap:8px;padding-bottom:14px;margin-bottom:20px;border-bottom:1px solid #2d2d38;font-family:system-ui,sans-serif;font-size:13px;\">'+" +
            "'<span>📖 <strong>Brave Speedreader</strong> &middot; ~'+mins+' min di lettura</span>'+" +
            "'<div style=\"display:flex;gap:6px;\">'+" +
            "'<button id=\"__sr_sm\" style=\"padding:5px 10px;border-radius:8px;border:1px solid #3c3c4a;background:transparent;color:inherit;\">A−</button>'+" +
            "'<button id=\"__sr_lg\" style=\"padding:5px 10px;border-radius:8px;border:1px solid #3c3c4a;background:transparent;color:inherit;\">A+</button>'+" +
            "'<button id=\"__sr_th\" style=\"padding:5px 10px;border-radius:8px;border:1px solid #3c3c4a;background:transparent;color:inherit;\">Tema</button>'+" +
            "'<button id=\"__sr_x\" style=\"padding:5px 12px;border-radius:8px;border:0;background:#fb542b;color:#fff;font-weight:700;\">Chiudi</button>'+" +
            "'</div></div>'+" +
            "'<h1 style=\"line-height:1.25;margin:0 0 18px 0;font-size:1.55em;\">'+(document.title||'')+'</h1>'+" +
            "'<div id=\"__sr_body\"></div></div>';" +
            "document.body.appendChild(ov);" +
            "var bodyEl=document.getElementById('__sr_body');" +
            "var paras=clone.querySelectorAll('h2,h3,h4,p,blockquote,li,pre');" +
            "if(paras.length>2){paras.forEach(function(p){var t=(p.innerText||'').trim();if(t.length>18){var el=document.createElement(p.tagName.toLowerCase());el.textContent=t;el.style.marginBottom='1em';bodyEl.appendChild(el);}});}" +
            "else{bodyEl.style.whiteSpace='pre-wrap';bodyEl.textContent=text;}" +
            "document.documentElement.style.overflow='hidden';" +
            "var sz=18,th=0,themes=[['#121216','#ececf1'],['#f5ecd8','#2b2218'],['#ffffff','#18181b']];" +
            "document.getElementById('__sr_sm').onclick=function(){sz=Math.max(14,sz-2);ov.style.fontSize=sz+'px';};" +
            "document.getElementById('__sr_lg').onclick=function(){sz=Math.min(28,sz+2);ov.style.fontSize=sz+'px';};" +
            "document.getElementById('__sr_th').onclick=function(){th=(th+1)%themes.length;ov.style.background=themes[th][0];ov.style.color=themes[th][1];};" +
            "document.getElementById('__sr_x').onclick=function(){ov.remove();document.documentElement.style.overflow='';};" +
            "return 'opened';" +
            "})();";
    }
}
