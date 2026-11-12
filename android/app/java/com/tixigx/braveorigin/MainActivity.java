package com.tixigx.braveorigin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.window.OnBackInvokedDispatcher;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final String[] INTERNAL_SUGGESTIONS = new String[]{
        "brave://newtab",
        "brave://settings/origin",
        "brave://settings/shields",
        "brave://settings/appearance",
        "brave://settings/search",
        "brave://settings/privacy",
        "brave://settings/system",
        "brave://bookmarks",
        "brave://history",
        "brave://downloads",
        "brave://adblock",
        "brave://policy",
        "brave://flags",
        "brave://version"
    };

    private OriginPrefs prefs;
    private ShieldsEngine shieldsEngine;
    private String sessionToken;
    private String chromiumMajor = "134";
    private String webViewVersionLabel = "System Chromium WebView";

    private final List<BrowserTab> tabs = new ArrayList<>();
    private BrowserTab activeTab;
    private final Deque<String> closedTabUrls = new ArrayDeque<>();
    private final Set<String> allowedHttpHosts = Collections.synchronizedSet(new HashSet<String>());

    private volatile boolean elementPickerArmed = false;
    private volatile boolean blobDownloadArmed = false;
    private boolean isImmersiveFullscreen = false;
    private int activeDevToolsTab = 0; // 0=console, 1=elements, 2=network, 3=storage

    // Views
    private LinearLayout tabStripBar;
    private LinearLayout privateBadge;
    private HorizontalScrollView tabStripScroll;
    private LinearLayout tabStripContainer;
    private ImageButton btnNewTabStrip;

    private FrameLayout topToolbarSlot;
    private FrameLayout bottomToolbarSlot;
    private LinearLayout omniboxBar;
    private LinearLayout omniboxPill;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private ImageButton btnReload;
    private ImageButton btnHome;
    private ImageButton btnSiteInfo;
    private EditText urlBar;
    private ImageButton btnUrlClear;
    private ImageButton btnReader;
    private ImageButton btnBookmark;
    private LinearLayout btnShields;
    private ImageView imgShields;
    private TextView txtShieldsCount;
    private TextView btnTabsOverview;
    private ImageButton btnMenu;

    private LinearLayout bookmarksBarRow;
    private LinearLayout bookmarksBarContainer;
    private ProgressBar progress;

    private LinearLayout findBar;
    private EditText findInput;
    private TextView findCount;

    private FrameLayout webViewContainer;
    private LinearLayout omniboxSuggestionsCard;

    private LinearLayout devToolsPanel;
    private TextView devTabConsole;
    private TextView devTabElements;
    private TextView devTabNetwork;
    private TextView devTabStorage;
    private TextView devToolsOutput;
    private LinearLayout devToolsReplRow;
    private EditText devToolsInput;

    private FrameLayout fullscreenContainer;
    private View customFullscreenView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> pendingFileChooser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionToken = generateSessionToken();
        prefs = new OriginPrefs(this);
        shieldsEngine = new ShieldsEngine(this, prefs);
        detectWebViewEngineInfo();

        setContentView(R.layout.activity_main);
        bindViews();
        wireChromeListeners();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                this::handleBrowserBack
            );
        }
        applyAppearanceLayout();
        rebuildBookmarksBar();

        String launchUrl = extractIntentUrl(getIntent());
        if (!restorePreviousSessionIfEnabled(launchUrl)) {
            createNewTab(launchUrl != null ? launchUrl : "brave://newtab", false, true);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyAppearanceLayout();
        syncToolbarWithActiveTab();
    }

    private boolean isWideDesktopToolbar() {
        return getResources().getConfiguration().screenWidthDp >= 520;
    }

    private static String generateSessionToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private void detectWebViewEngineInfo() {
        try {
            String defUa = WebSettings.getDefaultUserAgent(this);
            Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(defUa);
            if (m.find()) {
                chromiumMajor = m.group(1);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) {
                    webViewVersionLabel = pi.packageName + " " + pi.versionName + " (Chromium " + chromiumMajor + ")";
                    return;
                }
            }
            webViewVersionLabel = "Chromium " + chromiumMajor;
        } catch (Throwable ignored) {
        }
    }

    private String buildUserAgent(boolean desktop) {
        if (desktop) {
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromiumMajor + ".0.0.0 Safari/537.36";
        }
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromiumMajor + ".0.0.0 Mobile Safari/537.36";
    }

    private void bindViews() {
        tabStripBar = findViewById(R.id.tab_strip_bar);
        privateBadge = findViewById(R.id.private_badge);
        tabStripScroll = findViewById(R.id.tab_strip_scroll);
        tabStripContainer = findViewById(R.id.tab_strip_container);
        btnNewTabStrip = findViewById(R.id.btn_new_tab_strip);

        topToolbarSlot = findViewById(R.id.top_toolbar_slot);
        bottomToolbarSlot = findViewById(R.id.bottom_toolbar_slot);
        omniboxBar = findViewById(R.id.omnibox_bar);
        omniboxPill = findViewById(R.id.omnibox_pill);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnReload = findViewById(R.id.btn_reload);
        btnHome = findViewById(R.id.btn_home);
        btnSiteInfo = findViewById(R.id.btn_site_info);
        urlBar = findViewById(R.id.url_bar);
        btnUrlClear = findViewById(R.id.btn_url_clear);
        btnReader = findViewById(R.id.btn_reader);
        btnBookmark = findViewById(R.id.btn_bookmark);
        btnShields = findViewById(R.id.btn_shields);
        imgShields = findViewById(R.id.img_shields);
        txtShieldsCount = findViewById(R.id.txt_shields_count);
        btnTabsOverview = findViewById(R.id.btn_tabs_overview);
        btnMenu = findViewById(R.id.btn_menu);

        bookmarksBarRow = findViewById(R.id.bookmarks_bar_row);
        bookmarksBarContainer = findViewById(R.id.bookmarks_bar_container);
        progress = findViewById(R.id.progress);

        findBar = findViewById(R.id.find_bar);
        findInput = findViewById(R.id.find_input);
        findCount = findViewById(R.id.find_count);

        webViewContainer = findViewById(R.id.webview_container);
        omniboxSuggestionsCard = findViewById(R.id.omnibox_suggestions_card);

        devToolsPanel = findViewById(R.id.devtools_panel);
        devTabConsole = findViewById(R.id.dev_tab_console);
        devTabElements = findViewById(R.id.dev_tab_elements);
        devTabNetwork = findViewById(R.id.dev_tab_network);
        devTabStorage = findViewById(R.id.dev_tab_storage);
        devToolsOutput = findViewById(R.id.devtools_output);
        devToolsReplRow = findViewById(R.id.devtools_repl_row);
        devToolsInput = findViewById(R.id.devtools_input);

        fullscreenContainer = findViewById(R.id.fullscreen_container);
    }

    private void wireChromeListeners() {
        btnBack.setOnClickListener(v -> {
            if (activeTab != null && activeTab.webView != null && activeTab.webView.canGoBack()) {
                activeTab.webView.goBack();
            }
        });
        btnBack.setOnLongClickListener(v -> {
            showTabHistoryPopup();
            return true;
        });

        btnForward.setOnClickListener(v -> {
            if (activeTab != null && activeTab.webView != null && activeTab.webView.canGoForward()) {
                activeTab.webView.goForward();
            }
        });

        btnReload.setOnClickListener(v -> {
            if (activeTab == null || activeTab.webView == null) return;
            if (progress.getVisibility() == View.VISIBLE) {
                activeTab.webView.stopLoading();
                progress.setVisibility(View.GONE);
                btnReload.setImageResource(R.drawable.ic_reload);
            } else {
                reloadCurrentTabWithConfig();
            }
        });

        btnHome.setOnClickListener(v -> openUrlInCurrentTab(prefs.getHomePageUrl()));

        btnNewTabStrip.setOnClickListener(v -> {
            boolean priv = activeTab != null && activeTab.isPrivate;
            createNewTab("brave://newtab", priv, true);
        });
        btnNewTabStrip.setOnLongClickListener(v -> {
            createNewTab("brave://newtab", true, true);
            return true;
        });

        privateBadge.setOnClickListener(v -> BrowserDialogs.showTabSwitcherDialog(this));

        btnSiteInfo.setOnClickListener(v -> BrowserDialogs.showSiteInfoDialog(this, activeTab, prefs));

        btnUrlClear.setOnClickListener(v -> {
            urlBar.setText("");
            urlBar.requestFocus();
        });

        btnReader.setOnClickListener(v -> toggleSpeedreaderOnActiveTab());
        btnBookmark.setOnClickListener(v -> toggleBookmarkCurrentTab());
        btnBookmark.setOnLongClickListener(v -> {
            openUrlInCurrentTab("brave://bookmarks");
            return true;
        });

        btnShields.setOnClickListener(v -> BrowserDialogs.showShieldsDialog(this, activeTab, prefs));
        btnTabsOverview.setOnClickListener(v -> BrowserDialogs.showTabSwitcherDialog(this));
        btnTabsOverview.setOnLongClickListener(v -> {
            createNewTab("brave://newtab", false, true);
            return true;
        });
        btnMenu.setOnClickListener(v -> BrowserDialogs.showDesktopMenuDialog(this, activeTab, prefs));

        // Omnibox editing & autocomplete (expands to full width on compact screens)
        urlBar.setOnFocusChangeListener((v, hasFocus) -> {
            btnUrlClear.setVisibility(hasFocus && urlBar.getText().length() > 0 ? View.VISIBLE : View.GONE);
            updateToolbarButtonVisibilities(hasFocus);
            if (hasFocus) {
                if (activeTab != null && !WebUiRouter.isWebUiUrl(activeTab.url)) {
                    urlBar.setText(activeTab.url);
                    urlBar.selectAll();
                } else if (activeTab != null) {
                    String disp = WebUiRouter.toOmniboxDisplayUrl(activeTab.url);
                    urlBar.setText(disp);
                    urlBar.selectAll();
                }
                updateOmniboxSuggestions(urlBar.getText().toString());
            } else {
                omniboxSuggestionsCard.setVisibility(View.GONE);
                syncToolbarWithActiveTab();
            }
        });

        urlBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (urlBar.hasFocus()) {
                    btnUrlClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                    updateOmniboxSuggestions(s.toString());
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enterKey = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || enterKey) {
                submitOmniboxInput(urlBar.getText().toString());
                return true;
            }
            return false;
        });

        // Find in page
        findInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (activeTab != null && activeTab.webView != null) {
                    String q = s.toString();
                    if (q.isEmpty()) {
                        activeTab.webView.clearMatches();
                        findCount.setText("0/0");
                    } else {
                        activeTab.webView.findAllAsync(q);
                    }
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        findViewById(R.id.btn_find_next).setOnClickListener(v -> {
            if (activeTab != null && activeTab.webView != null) activeTab.webView.findNext(true);
        });
        findViewById(R.id.btn_find_prev).setOnClickListener(v -> {
            if (activeTab != null && activeTab.webView != null) activeTab.webView.findNext(false);
        });
        findViewById(R.id.btn_find_close).setOnClickListener(v -> closeFindInPageBar());

        // DevTools listeners
        devTabConsole.setOnClickListener(v -> switchDevToolsTab(0));
        devTabElements.setOnClickListener(v -> switchDevToolsTab(1));
        devTabNetwork.setOnClickListener(v -> switchDevToolsTab(2));
        devTabStorage.setOnClickListener(v -> switchDevToolsTab(3));
        findViewById(R.id.btn_dev_clear).setOnClickListener(v -> {
            if (activeTab != null) activeTab.clearConsole();
            refreshDevToolsContent();
        });
        findViewById(R.id.btn_dev_close).setOnClickListener(v -> devToolsPanel.setVisibility(View.GONE));
        findViewById(R.id.btn_dev_run).setOnClickListener(v -> executeDevToolsJs());
        devToolsInput.setOnEditorActionListener((v, actionId, ev) -> {
            executeDevToolsJs();
            return true;
        });
    }

    /* ==================== TAB MANAGEMENT ==================== */
    public BrowserTab getActiveTab() {
        return activeTab;
    }

    public List<BrowserTab> getTabsSnapshot(boolean privateMode) {
        List<BrowserTab> out = new ArrayList<>();
        for (BrowserTab t : tabs) {
            if (t.isPrivate == privateMode) out.add(t);
        }
        return out;
    }

    public BrowserTab createNewTab(String initialUrl, boolean isPrivate, boolean switchTo) {
        BrowserTab tab = new BrowserTab(
            isPrivate,
            TextUtils.isEmpty(initialUrl) ? "brave://newtab" : initialUrl,
            prefs.isDesktopModeDefault(),
            prefs.getDefaultZoom()
        );
        tabs.add(tab);
        ensureTabWebView(tab);
        navigateTab(tab, tab.url);

        if (switchTo || activeTab == null) {
            switchToTab(tab);
        } else {
            rebuildTabStrip();
            updateTabCounterBadge();
            persistTabsIfNeeded();
        }
        return tab;
    }

    public void switchToTab(BrowserTab tab) {
        if (tab == null || !tabs.contains(tab)) return;
        activeTab = tab;
        tab.lastActiveTime = System.currentTimeMillis();

        ensureTabWebView(tab);
        if (tab.hibernated) {
            tab.hibernated = false;
            navigateTab(tab, tab.url);
        }

        for (int i = 0; i < webViewContainer.getChildCount(); i++) {
            View child = webViewContainer.getChildAt(i);
            child.setVisibility(child == tab.webView ? View.VISIBLE : View.GONE);
        }
        if (tab.webView.getParent() == null) {
            webViewContainer.addView(tab.webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        tab.webView.onResume();

        applyPrivateWindowFlags();
        applyCookiePolicyForActiveTab();
        applyAppearanceLayout();
        rebuildTabStrip();
        syncToolbarWithActiveTab();
        refreshDevToolsContent();
        runMemorySaverCheck();
        persistTabsIfNeeded();
    }

    public void closeTab(BrowserTab tab) {
        if (tab == null || !tabs.contains(tab)) return;
        int idx = tabs.indexOf(tab);

        if (!tab.isPrivate && !TextUtils.isEmpty(tab.url) && !WebUiRouter.isWebUiUrl(tab.url)) {
            if (closedTabUrls.size() >= 25) closedTabUrls.removeLast();
            closedTabUrls.addFirst(tab.url);

            // Check "Forget me when I close this site"
            String host = Uri.parse(tab.url).getHost();
            if (!TextUtils.isEmpty(host) && prefs.getSiteShields(host).forgetMeOnClose) {
                clearSiteDataForUrl(tab.url);
            }
        }

        destroyTabWebView(tab);
        tabs.remove(tab);

        // If all private tabs closed, wipe private session cookies if no private tabs remain
        if (tab.isPrivate && getTabsSnapshot(true).isEmpty()) {
            CookieManager.getInstance().removeSessionCookies(null);
        }

        if (tabs.isEmpty()) {
            createNewTab("brave://newtab", false, true);
            return;
        }

        if (activeTab == tab) {
            int nextIdx = Math.min(idx, tabs.size() - 1);
            switchToTab(tabs.get(nextIdx));
        } else {
            rebuildTabStrip();
            updateTabCounterBadge();
            persistTabsIfNeeded();
        }
    }

    public void closeOtherTabs(BrowserTab keep) {
        List<BrowserTab> copy = new ArrayList<>(tabs);
        for (BrowserTab t : copy) {
            if (t != keep && t.isPrivate == keep.isPrivate && !t.pinned) {
                destroyTabWebView(t);
                tabs.remove(t);
            }
        }
        switchToTab(keep);
    }

    public void closeAllTabs(boolean privateOnly) {
        List<BrowserTab> copy = new ArrayList<>(tabs);
        for (BrowserTab t : copy) {
            if (!privateOnly || t.isPrivate) {
                destroyTabWebView(t);
                tabs.remove(t);
            }
        }
        if (privateOnly) {
            CookieManager.getInstance().removeSessionCookies(null);
        }
        if (tabs.isEmpty()) {
            createNewTab("brave://newtab", false, true);
        } else {
            switchToTab(tabs.get(0));
        }
    }

    public boolean canReopenClosedTab() {
        return !closedTabUrls.isEmpty();
    }

    public void reopenLastClosedTab() {
        if (!closedTabUrls.isEmpty()) {
            String url = closedTabUrls.removeFirst();
            createNewTab(url, false, true);
        }
    }

    public void togglePinTab(BrowserTab tab) {
        if (tab == null) return;
        tab.pinned = !tab.pinned;
        // Keep pinned tabs ordered at front
        tabs.remove(tab);
        if (tab.pinned) {
            tabs.add(0, tab);
        } else {
            tabs.add(tab);
        }
        rebuildTabStrip();
        persistTabsIfNeeded();
    }

    public void reloadTab(BrowserTab tab) {
        if (tab != null && tab.webView != null) {
            tab.webView.reload();
        }
    }

    public void toggleMuteTab(BrowserTab tab) {
        if (tab == null || tab.webView == null) return;
        tab.audioMuted = !tab.audioMuted;
        String js = "document.querySelectorAll('video,audio').forEach(function(m){m.muted=" + tab.audioMuted + ";});";
        tab.webView.evaluateJavascript(js, null);
        Toast.makeText(this, tab.audioMuted ? "Audio scheda disattivato" : "Audio scheda riattivato", Toast.LENGTH_SHORT).show();
        rebuildTabStrip();
    }

    private void runMemorySaverCheck() {
        if (!prefs.isMemorySaver() || tabs.size() <= 6) return;
        long now = System.currentTimeMillis();
        for (BrowserTab t : tabs) {
            if (t != activeTab && !t.pinned && !t.hibernated && t.webView != null && (now - t.lastActiveTime) > 300_000L) {
                t.url = t.webView.getUrl() != null ? t.webView.getUrl() : t.url;
                destroyTabWebView(t);
                t.hibernated = true;
            }
        }
    }

    private void destroyTabWebView(BrowserTab tab) {
        if (tab != null && tab.webView != null) {
            WebView wv = tab.webView;
            tab.webView = null;
            webViewContainer.removeView(wv);
            wv.stopLoading();
            wv.setWebChromeClient(null);
            wv.setWebViewClient(null);
            wv.destroy();
        }
    }

    /* ==================== WEBVIEW CONFIGURATION ==================== */
    @SuppressWarnings("SetJavaScriptEnabled")
    private void ensureTabWebView(final BrowserTab tab) {
        if (tab.webView != null) return;

        final WebView wv = new WebView(this);
        tab.webView = wv;

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(!prefs.isBlockPopups());
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setTextZoom(tab.zoomPercent);
        s.setUserAgentString(buildUserAgent(tab.desktopMode));

        if (tab.isPrivate) {
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                saveFormDataLegacy(s, false);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.setAlgorithmicDarkeningAllowed(prefs.isForceDarkWeb());
        }

        wv.setBackgroundColor(isLightThemeEffective() ? 0xFFF6F6F9 : 0xFF0F0F13);
        wv.addJavascriptInterface(new WebUiBridge(), "BraveOriginBridge");
        wv.addJavascriptInterface(new PageHelperBridge(), "BravePageHelper");

        wv.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
            if (tab == activeTab) {
                int dispIdx = numberOfMatches > 0 ? (activeMatchOrdinal + 1) : 0;
                findCount.setText(dispIdx + "/" + numberOfMatches);
            }
        });

        wv.setOnLongClickListener(v -> {
            WebView.HitTestResult hr = wv.getHitTestResult();
            if (hr == null) return false;
            int type = hr.getType();
            String extra = hr.getExtra();
            if (TextUtils.isEmpty(extra) || WebUiRouter.isWebUiUrl(wv.getUrl())) {
                return false;
            }
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                BrowserDialogs.showWebContextMenu(MainActivity.this, extra, null);
                return true;
            } else if (type == WebView.HitTestResult.IMAGE_TYPE) {
                BrowserDialogs.showWebContextMenu(MainActivity.this, null, extra);
                return true;
            } else if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                Message msg = new android.os.Handler(getMainLooper()).obtainMessage();
                wv.requestFocusNodeHref(msg);
                String href = msg.getData() != null ? msg.getData().getString("url") : null;
                BrowserDialogs.showWebContextMenu(MainActivity.this, href, extra);
                return true;
            }
            return false;
        });

        wv.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                enqueueDownload(url, userAgent, contentDisposition, mimetype);
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (tab == activeTab) {
                    progress.setProgress(newProgress);
                    if (newProgress >= 100) {
                        progress.setVisibility(View.GONE);
                        btnReload.setImageResource(R.drawable.ic_reload);
                    } else {
                        progress.setVisibility(View.VISIBLE);
                        btnReload.setImageResource(R.drawable.ic_close);
                    }
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (!TextUtils.isEmpty(title)) {
                    tab.title = title;
                    if (!tab.isPrivate && !WebUiRouter.isWebUiUrl(tab.url)) {
                        prefs.recordHistory(title, tab.url);
                    }
                    rebuildTabStrip();
                }
            }

            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                tab.favicon = icon;
                rebuildTabStrip();
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null) {
                    tab.appendConsole(cm.messageLevel().name(), cm.message(), cm.lineNumber(), cm.sourceId());
                    if (tab == activeTab && devToolsPanel.getVisibility() == View.VISIBLE && activeDevToolsTab == 0) {
                        refreshDevToolsContent();
                    }
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (!isUserGesture && prefs.isBlockPopups()) {
                    return false;
                }
                WebView.HitTestResult hr = view.getHitTestResult();
                if (hr != null && !TextUtils.isEmpty(hr.getExtra())) {
                    createNewTab(hr.getExtra(), tab.isPrivate, true);
                    return false;
                }
                final WebView transportView = new WebView(MainActivity.this);
                transportView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        String target = req.getUrl().toString();
                        transportView.destroy();
                        createNewTab(target, tab.isPrivate, true);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(transportView);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                if (request == null) return;
                final String origin = request.getOrigin() != null ? request.getOrigin().toString() : "Questo sito";
                final String[] resources = request.getResources();
                runOnUiThread(() -> {
                    new AlertDialog.Builder(MainActivity.this, R.style.Theme_BraveOrigin_Dialog)
                        .setTitle("Richiesta autorizzazione sito")
                        .setMessage(origin + " richiede accesso a risorse multimediali (" + TextUtils.join(", ", resources) + "). Consentire?")
                        .setPositiveButton("Consenti", (d, w) -> request.grant(resources))
                        .setNegativeButton("Blocca", (d, w) -> request.deny())
                        .setOnCancelListener(d -> request.deny())
                        .show();
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (pendingFileChooser != null) {
                    pendingFileChooser.onReceiveValue(null);
                }
                pendingFileChooser = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    pendingFileChooser = null;
                    Toast.makeText(MainActivity.this, "Nessun selettore file trovato", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customFullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customFullscreenView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                setImmersiveSystemUi(true);
            }

            @Override
            public void onHideCustomView() {
                if (customFullscreenView == null) return;
                fullscreenContainer.removeView(customFullscreenView);
                fullscreenContainer.setVisibility(View.GONE);
                customFullscreenView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                setImmersiveSystemUi(isImmersiveFullscreen);
            }
        });

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && WebUiRouter.HOST.equalsIgnoreCase(uri.getHost())) {
                    return WebUiRouter.serve(uri, MainActivity.this, sessionToken);
                }

                String pageUrl = tab.url != null ? tab.url : "";
                String pageHost = Uri.parse(pageUrl).getHost();
                OriginPrefs.SiteShieldsConfig siteCfg = prefs.getSiteShields(pageHost);

                ShieldsEngine.BlockResult res = shieldsEngine.shouldBlockRequest(request, pageHost, siteCfg, prefs);
                if (res.blocked) {
                    final int newCount = tab.incrementBlocked(res.category, res.host);
                    prefs.recordBlockedItem();
                    if (tab == activeTab) {
                        runOnUiThread(() -> txtShieldsCount.setText(String.valueOf(newCount)));
                    }
                    return res.response;
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return false;
                String raw = uri.toString();
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";

                if ("brave".equals(scheme) || "chrome".equals(scheme)) {
                    view.loadUrl(WebUiRouter.toInternalUrl(raw));
                    return true;
                }
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    if (WebUiRouter.HOST.equalsIgnoreCase(uri.getHost())) {
                        return false;
                    }
                    if (request.isForMainFrame()) {
                        OriginPrefs.SiteShieldsConfig cfg = prefs.getSiteShields(uri.getHost());
                        String cleaned = shieldsEngine.cleanUrl(raw, cfg.shieldsUp && prefs.isCleanUrlsEnabled(), cfg.shieldsUp && prefs.isDeAmpEnabled());
                        String upgraded = ShieldsEngine.maybeUpgradeHttps(cleaned, cfg.shieldsUp ? cfg.httpsUpgrade : 0, allowedHttpHosts);

                        if (cfg.shieldsUp && cfg.httpsUpgrade == 2 && upgraded.startsWith("http://") && !allowedHttpHosts.contains(uri.getHost().toLowerCase())) {
                            promptStrictHttpsWarning(tab, raw, uri.getHost().toLowerCase());
                            return true;
                        }
                        if (!upgraded.equals(raw)) {
                            view.loadUrl(upgraded);
                            return true;
                        }
                    }
                    return false;
                }
                if ("about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)) {
                    return false;
                }
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.url = url;
                tab.resetPageStats();

                // Apply per-site JS permission dynamically
                if (!WebUiRouter.isWebUiUrl(url)) {
                    String host = Uri.parse(url).getHost();
                    OriginPrefs.SiteShieldsConfig cfg = prefs.getSiteShields(host);
                    boolean allowJs = !(cfg.shieldsUp && cfg.blockScripts);
                    if (view.getSettings().getJavaScriptEnabled() != allowJs) {
                        view.getSettings().setJavaScriptEnabled(allowJs);
                    }
                    if (allowJs && cfg.shieldsUp) {
                        String script = shieldsEngine.buildPageProtectionScript(url, cfg, prefs);
                        if (!script.isEmpty()) {
                            view.evaluateJavascript(script, null);
                        }
                    }
                } else {
                    if (!view.getSettings().getJavaScriptEnabled()) {
                        view.getSettings().setJavaScriptEnabled(true);
                    }
                }

                if (tab == activeTab) {
                    syncToolbarWithActiveTab();
                }
                rebuildTabStrip();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                tab.url = url;
                if (!WebUiRouter.isWebUiUrl(url)) {
                    String host = Uri.parse(url).getHost();
                    OriginPrefs.SiteShieldsConfig cfg = prefs.getSiteShields(host);
                    if (cfg.shieldsUp && !cfg.blockScripts) {
                        String script = shieldsEngine.buildPageProtectionScript(url, cfg, prefs);
                        if (!script.isEmpty()) {
                            view.evaluateJavascript(script, null);
                        }
                    }
                    if (!tab.isPrivate) {
                        prefs.recordHistory(view.getTitle(), url);
                    }
                }
                if (tab == activeTab) {
                    syncToolbarWithActiveTab();
                    refreshDevToolsContent();
                }
                rebuildTabStrip();
                persistTabsIfNeeded();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() == 404) {
                    if (prefs.isOriginFeatureEnabled("BraveWaybackMachineEnabled", false)) {
                        final String missingUrl = request.getUrl().toString();
                        Toast.makeText(MainActivity.this, "Pagina 404 rilevata — usa il menu per Wayback Machine", Toast.LENGTH_LONG).show();
                    }
                }
            }

            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
                if (handler == null) return;
                String url = error != null && error.getUrl() != null ? error.getUrl() : "";
                new AlertDialog.Builder(MainActivity.this, R.style.Theme_BraveOrigin_Dialog)
                    .setTitle("⚠️ La connessione non è privata")
                    .setMessage("Il certificato SSL di questo sito non è attendibile (" + url + ").\n\nBrave Origin blocca per sicurezza i certificati non validi.")
                    .setPositiveButton("Torna al sicuro", (d, w) -> handler.cancel())
                    .setNegativeButton("Avanzate: Procedi", (d, w) -> handler.proceed())
                    .setOnCancelListener(d -> handler.cancel())
                    .show();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // Automatic HTTP fallback in Standard HTTPS upgrade mode if HTTPS connection refused
                if (request != null && request.isForMainFrame()) {
                    Uri u = request.getUrl();
                    if (u != null && "https".equalsIgnoreCase(u.getScheme())) {
                        String host = u.getHost() != null ? u.getHost().toLowerCase() : "";
                        OriginPrefs.SiteShieldsConfig cfg = prefs.getSiteShields(host);
                        if (cfg.httpsUpgrade == 1 && !allowedHttpHosts.contains(host)) {
                            // Keep HTTPS unless user explicitly navigated to http; no silent downgrade without safety
                        }
                    }
                }
            }
        });
    }

    private void promptStrictHttpsWarning(final BrowserTab tab, final String rawHttpUrl, final String host) {
        new AlertDialog.Builder(this, R.style.Theme_BraveOrigin_Dialog)
            .setTitle("🔒 Modalità Solo HTTPS Attiva")
            .setMessage("Il sito " + host + " non utilizza una connessione HTTPS crittografata.\n\nVuoi continuare comunque verso la versione HTTP non sicura?")
            .setPositiveButton("Torna indietro", null)
            .setNegativeButton("Procedi su HTTP", (d, w) -> {
                allowedHttpHosts.add(host);
                if (tab.webView != null) tab.webView.loadUrl(rawHttpUrl);
            })
            .show();
    }

    /* ==================== NAVIGATION & OMNIBOX ==================== */
    public void openUrlInCurrentTab(String url) {
        if (activeTab == null) {
            createNewTab(url, false, true);
        } else {
            navigateTab(activeTab, url);
        }
    }

    private void submitOmniboxInput(String rawInput) {
        String val = rawInput == null ? "" : rawInput.trim();
        if (val.isEmpty()) return;

        omniboxSuggestionsCard.setVisibility(View.GONE);
        urlBar.clearFocus();
        hideKeyboard();

        String lower = val.toLowerCase();
        if (lower.startsWith("brave://") || lower.startsWith("chrome://") || lower.startsWith("about:")) {
            openUrlInCurrentTab(val);
            return;
        }
        if (lower.startsWith("javascript:")) {
            if (activeTab != null && activeTab.webView != null && !WebUiRouter.isWebUiUrl(activeTab.url)) {
                activeTab.webView.evaluateJavascript(val.substring(11), null);
            }
            return;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            openUrlInCurrentTab(val);
            return;
        }
        if (val.startsWith("!") || val.contains(" ") || (!val.contains(".") && !lower.startsWith("localhost"))) {
            openUrlInCurrentTab(prefs.buildSearchUrl(val));
            return;
        }
        if (val.contains("://")) {
            openExternally(Uri.parse(val));
            return;
        }
        openUrlInCurrentTab("https://" + val);
    }

    private void navigateTab(BrowserTab tab, String targetInput) {
        if (tab == null) return;
        ensureTabWebView(tab);

        String target = targetInput != null ? targetInput.trim() : "brave://newtab";
        if (WebUiRouter.isWebUiUrl(target)) {
            String internal = WebUiRouter.toInternalUrl(target);
            tab.url = internal;
            tab.webView.loadUrl(internal);
            return;
        }

        if (target.startsWith("http://") || target.startsWith("https://")) {
            String host = Uri.parse(target).getHost();
            OriginPrefs.SiteShieldsConfig cfg = prefs.getSiteShields(host);
            target = shieldsEngine.cleanUrl(target, cfg.shieldsUp && prefs.isCleanUrlsEnabled(), cfg.shieldsUp && prefs.isDeAmpEnabled());
            target = ShieldsEngine.maybeUpgradeHttps(target, cfg.shieldsUp ? cfg.httpsUpgrade : 0, allowedHttpHosts);
        }
        tab.url = target;
        tab.webView.loadUrl(target);
    }

    private void updateOmniboxSuggestions(String query) {
        if (!prefs.isOmniboxSuggestions()) {
            omniboxSuggestionsCard.setVisibility(View.GONE);
            return;
        }
        String q = query != null ? query.trim().toLowerCase() : "";
        omniboxSuggestionsCard.removeAllViews();

        List<OriginPrefs.LinkItem> matches = new ArrayList<>();

        // Clipboard quick action when input is empty or just focused
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
            if (clip != null) {
                String clipStr = clip.toString().trim();
                if (!clipStr.isEmpty() && clipStr.length() < 200 && q.isEmpty()) {
                    matches.add(new OriginPrefs.LinkItem("📋 Incolla e vai: " + clipStr, clipStr, 0, "clip"));
                }
            }
        }

        if (!q.isEmpty()) {
            for (String internal : INTERNAL_SUGGESTIONS) {
                if (internal.contains(q)) {
                    matches.add(new OriginPrefs.LinkItem("🦁 Pagina interna Brave Origin", internal, 0, "internal"));
                    if (matches.size() >= 6) break;
                }
            }
            if (matches.size() < 6) {
                for (OriginPrefs.LinkItem bm : prefs.getBookmarks()) {
                    if (bm.title.toLowerCase().contains(q) || bm.url.toLowerCase().contains(q)) {
                        matches.add(new OriginPrefs.LinkItem("★ " + bm.title, bm.url, 0, "bm"));
                        if (matches.size() >= 6) break;
                    }
                }
            }
            if (matches.size() < 6) {
                for (OriginPrefs.LinkItem hi : prefs.getHistory()) {
                    if (hi.title.toLowerCase().contains(q) || hi.url.toLowerCase().contains(q)) {
                        matches.add(new OriginPrefs.LinkItem("🕘 " + hi.title, hi.url, 0, "hi"));
                        if (matches.size() >= 6) break;
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            omniboxSuggestionsCard.setVisibility(View.GONE);
            return;
        }

        int padH = dp(14);
        int padV = dp(9);
        for (final OriginPrefs.LinkItem m : matches) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(padH, padV, padH, padV);

            TextView t1 = new TextView(this);
            t1.setText(m.title);
            t1.setTextColor(0xFFF4F4F6);
            t1.setTextSize(13f);
            t1.setSingleLine(true);
            t1.setEllipsize(TextUtils.TruncateAt.END);

            TextView t2 = new TextView(this);
            t2.setText(m.url);
            t2.setTextColor(0xFF9A9AA6);
            t2.setTextSize(11.5f);
            t2.setSingleLine(true);
            t2.setEllipsize(TextUtils.TruncateAt.END);

            row.addView(t1);
            row.addView(t2);
            row.setOnClickListener(v -> submitOmniboxInput(m.url));
            omniboxSuggestionsCard.addView(row);
        }
        omniboxSuggestionsCard.setVisibility(View.VISIBLE);
    }

    /* ==================== CHROME UI SYNC ==================== */
    private void syncToolbarWithActiveTab() {
        if (activeTab == null) return;
        WebView wv = activeTab.webView;
        boolean canBack = wv != null && wv.canGoBack();
        boolean canFwd = wv != null && wv.canGoForward();

        btnBack.setEnabled(canBack);
        btnBack.setAlpha(canBack ? 1f : 0.35f);
        btnForward.setEnabled(canFwd);
        btnForward.setAlpha(canFwd ? 1f : 0.35f);

        String url = activeTab.url != null ? activeTab.url : "";
        if (!urlBar.hasFocus()) {
            urlBar.setText(WebUiRouter.toOmniboxDisplayUrl(url));
        }

        boolean isInternal = WebUiRouter.isWebUiUrl(url);
        if (isInternal) {
            btnSiteInfo.setImageResource(R.drawable.ic_brave_lion);
            btnSiteInfo.setColorFilter(isLightThemeEffective() ? 0xFF14141A : 0xFFF4F4F6);
            btnReader.setVisibility(View.GONE);
            txtShieldsCount.setText("✓");
            imgShields.setColorFilter(0xFFFB542B);
        } else {
            boolean https = url.startsWith("https://");
            btnSiteInfo.setImageResource(https ? R.drawable.ic_lock : R.drawable.ic_warning);
            btnSiteInfo.setColorFilter(https ? 0xFF34D399 : 0xFFFBBF24);

            boolean showReader = prefs.isOriginFeatureEnabled("BraveSpeedreaderEnabled", false);
            btnReader.setVisibility(showReader ? View.VISIBLE : View.GONE);

            String host = Uri.parse(url).getHost();
            OriginPrefs.SiteShieldsConfig cfg = prefs.getSiteShields(host);
            imgShields.setColorFilter(cfg.shieldsUp ? 0xFFFB542B : 0xFF7C7C8E);
            txtShieldsCount.setText(cfg.shieldsUp ? String.valueOf(activeTab.getBlockedCount()) : "OFF");
        }

        String bmCheckUrl = WebUiRouter.toBraveSchemeUrl(url);
        boolean starred = prefs.isBookmarked(bmCheckUrl);
        btnBookmark.setImageResource(starred ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
        btnBookmark.setColorFilter(starred ? 0xFFFB542B : (isLightThemeEffective() ? 0xFF565666 : 0xFF9A9AA6));

        updateToolbarButtonVisibilities(urlBar.hasFocus());
        updateTabCounterBadge();
    }

    private void updateToolbarButtonVisibilities(boolean omniboxFocused) {
        boolean wide = isWideDesktopToolbar();
        if (omniboxFocused && !wide) {
            btnBack.setVisibility(View.GONE);
            btnForward.setVisibility(View.GONE);
            btnReload.setVisibility(View.GONE);
            btnHome.setVisibility(View.GONE);
            btnBookmark.setVisibility(View.GONE);
            btnReader.setVisibility(View.GONE);
            btnShields.setVisibility(View.GONE);
            btnTabsOverview.setVisibility(View.GONE);
            return;
        }
        btnBack.setVisibility(View.VISIBLE);
        btnForward.setVisibility(wide ? View.VISIBLE : View.GONE);
        btnReload.setVisibility(wide ? View.VISIBLE : View.GONE);
        btnHome.setVisibility(wide && prefs.isShowHomeButton() ? View.VISIBLE : View.GONE);
        btnBookmark.setVisibility(wide ? View.VISIBLE : View.GONE);
        btnShields.setVisibility(View.VISIBLE);
        btnTabsOverview.setVisibility(View.VISIBLE);
    }

    private void updateTabCounterBadge() {
        boolean priv = activeTab != null && activeTab.isPrivate;
        int count = getTabsSnapshot(priv).size();
        btnTabsOverview.setText(String.valueOf(Math.max(1, count)));
        privateBadge.setVisibility(priv ? View.VISIBLE : View.GONE);
    }

    private void rebuildTabStrip() {
        if (tabStripContainer == null) return;
        tabStripContainer.removeAllViews();

        boolean privMode = activeTab != null && activeTab.isPrivate;
        boolean light = isLightThemeEffective() && !privMode;
        List<BrowserTab> modeTabs = getTabsSnapshot(privMode);

        for (final BrowserTab t : modeTabs) {
            boolean isActive = (t == activeTab);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);

            int bgFill;
            int stroke;
            if (isActive) {
                bgFill = privMode ? 0xFF221738 : (light ? 0xFFFFFFFF : 0xFF18181E);
                stroke = privMode ? 0xFFA78BFA : (light ? 0xFFD4D4DF : 0xFF383846);
            } else {
                bgFill = privMode ? 0xFF160E26 : (light ? 0xFFE6E6EE : 0xFF101015);
                stroke = 0;
            }

            GradientDrawable gd = new GradientDrawable();
            gd.setColor(bgFill);
            float r = dp(10);
            gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            if (stroke != 0) gd.setStroke(dp(1), stroke);
            chip.setBackground(gd);

            int tabWidth = t.pinned ? dp(42) : dp(156);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tabWidth, dp(33));
            lp.rightMargin = dp(3);
            chip.setLayoutParams(lp);
            chip.setPadding(dp(8), 0, dp(6), 0);

            ImageView icon = new ImageView(this);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(15), dp(15));
            icon.setLayoutParams(ilp);
            if (t.favicon != null && !WebUiRouter.isWebUiUrl(t.url)) {
                icon.setImageBitmap(t.favicon);
            } else {
                icon.setImageResource(WebUiRouter.isWebUiUrl(t.url) ? R.drawable.ic_brave_lion : R.drawable.ic_globe);
                icon.setColorFilter(isActive ? (light ? 0xFF14141A : 0xFFF4F4F6) : 0xFF8A8A98);
            }
            chip.addView(icon);

            if (!t.pinned) {
                TextView title = new TextView(this);
                String label = !TextUtils.isEmpty(t.title) ? t.title : "Nuova scheda";
                if (t.audioMuted) label = "🔇 " + label;
                title.setText(label);
                title.setTextSize(12f);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                title.setTextColor(isActive ? (light ? 0xFF14141A : 0xFFF4F4F6) : (light ? 0xFF565666 : 0xFF9A9AA6));
                title.setTypeface(isActive ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.leftMargin = dp(6);
                title.setLayoutParams(tlp);
                chip.addView(title);

                TextView closeX = new TextView(this);
                closeX.setText("×");
                closeX.setTextSize(15f);
                closeX.setTextColor(light ? 0xFF565666 : 0xFF9A9AA6);
                closeX.setPadding(dp(5), 0, dp(2), 0);
                closeX.setOnClickListener(v -> closeTab(t));
                chip.addView(closeX);
            }

            chip.setOnClickListener(v -> switchToTab(t));
            chip.setOnLongClickListener(v -> {
                BrowserDialogs.showTabContextMenu(MainActivity.this, t);
                return true;
            });

            tabStripContainer.addView(chip);
        }
    }

    private void rebuildBookmarksBar() {
        if (bookmarksBarContainer == null) return;
        bookmarksBarContainer.removeAllViews();

        boolean light = isLightThemeEffective() && !(activeTab != null && activeTab.isPrivate);
        List<OriginPrefs.LinkItem> bms = prefs.getBookmarks();
        for (final OriginPrefs.LinkItem bm : bms) {
            TextView chip = new TextView(this);
            chip.setText(bm.title);
            chip.setTextSize(11.5f);
            chip.setSingleLine(true);
            chip.setMaxWidth(dp(140));
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setTextColor(light ? 0xFF22222A : 0xFFD8D8E2);
            chip.setPadding(dp(9), dp(3), dp(9), dp(3));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(light ? 0xFFECECF3 : 0xFF23232C);
            bg.setCornerRadius(dp(8));
            chip.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> openUrlInCurrentTab(bm.url));
            chip.setOnLongClickListener(v -> {
                final String[] opts = new String[]{"Apri in nuova scheda", "Apri in scheda privata", "Rimuovi dai preferiti"};
                new AlertDialog.Builder(MainActivity.this, R.style.Theme_BraveOrigin_Dialog)
                    .setTitle(bm.title)
                    .setItems(opts, (d, w) -> {
                        if (w == 0) createNewTab(bm.url, false, true);
                        else if (w == 1) createNewTab(bm.url, true, true);
                        else {
                            prefs.removeBookmark(bm.url);
                            rebuildBookmarksBar();
                            syncToolbarWithActiveTab();
                            refreshActiveWebUiIfNeeded();
                        }
                    })
                    .show();
                return true;
            });
            bookmarksBarContainer.addView(chip);
        }
    }

    private boolean isLightThemeEffective() {
        String mode = prefs.getThemeMode();
        if ("light".equals(mode)) return true;
        if ("system".equals(mode)) {
            int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_NO;
        }
        return false;
    }

    private void applyAppearanceLayout() {
        tabStripBar.setVisibility(prefs.isShowTabStrip() ? View.VISIBLE : View.GONE);
        bookmarksBarRow.setVisibility(prefs.isShowBookmarksBar() ? View.VISIBLE : View.GONE);
        btnHome.setVisibility(prefs.isShowHomeButton() ? View.VISIBLE : View.GONE);

        // Move Omnibox between Top and Bottom slot
        boolean bottom = prefs.isBottomToolbar();
        ViewGroup curParent = (ViewGroup) omniboxBar.getParent();
        FrameLayout targetSlot = bottom ? bottomToolbarSlot : topToolbarSlot;
        if (curParent != targetSlot) {
            if (curParent != null) curParent.removeView(omniboxBar);
            targetSlot.addView(omniboxBar);
        }

        boolean priv = activeTab != null && activeTab.isPrivate;
        boolean light = isLightThemeEffective() && !priv;

        int frameColor = priv ? 0xFF160E26 : (light ? 0xFFDDDDE6 : 0xFF0B0B0E);
        int surfaceColor = priv ? 0xFF221738 : (light ? 0xFFFFFFFF : 0xFF18181E);
        int pillColor = priv ? 0xFF2E204A : (light ? 0xFFF0F0F6 : 0xFF25252E);
        int pillStroke = priv ? 0xFF4C3575 : (light ? 0xFFD2D2DE : 0xFF2E2E3A);
        int textColor = light ? 0xFF14141A : 0xFFF4F4F6;
        int hintColor = light ? 0xFF6E6E7E : 0xFF9A9AA6;

        tabStripBar.setBackgroundColor(frameColor);
        omniboxBar.setBackgroundColor(surfaceColor);
        bookmarksBarRow.setBackgroundColor(surfaceColor);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(pillColor);
        pillBg.setStroke(dp(1), pillStroke);
        pillBg.setCornerRadius(dp(18));
        omniboxPill.setBackground(pillBg);

        urlBar.setTextColor(textColor);
        urlBar.setHintTextColor(hintColor);
        btnBack.setColorFilter(textColor);
        btnForward.setColorFilter(textColor);
        btnReload.setColorFilter(textColor);
        btnHome.setColorFilter(textColor);
        btnMenu.setColorFilter(textColor);
        txtShieldsCount.setTextColor(textColor);
        btnTabsOverview.setTextColor(textColor);

        getWindow().setStatusBarColor(frameColor);
        getWindow().setNavigationBarColor(surfaceColor);
        applyLightStatusBarFlag(light);

        updateToolbarButtonVisibilities(urlBar.hasFocus());
        rebuildTabStrip();
        rebuildBookmarksBar();
    }

    @SuppressWarnings("deprecation")
    private static void saveFormDataLegacy(WebSettings s, boolean save) {
        s.setSaveFormData(save);
    }

    @SuppressWarnings("deprecation")
    private void applyLightStatusBarFlag(boolean light) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                ic.setSystemBarsAppearance(
                    light ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                );
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int vis = getWindow().getDecorView().getSystemUiVisibility();
            if (light) vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(vis);
        }
    }

    private void applyPrivateWindowFlags() {
        boolean blockShots = activeTab != null && activeTab.isPrivate && prefs.isIncognitoSecure();
        if (blockShots) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    public void applyCookiePolicyForActiveTab() {
        if (activeTab == null || activeTab.webView == null) return;
        CookieManager cm = CookieManager.getInstance();
        String host = activeTab.url != null ? Uri.parse(activeTab.url).getHost() : null;
        OriginPrefs.SiteShieldsConfig cfg = prefs.getSiteShields(host);
        int policy = cfg.shieldsUp ? cfg.cookiePolicy : 0;
        cm.setAcceptCookie(policy != 2);
        cm.setAcceptThirdPartyCookies(activeTab.webView, policy == 0);
    }

    /* ==================== BROWSER FEATURES & ACTIONS ==================== */
    public void reloadCurrentTabWithConfig() {
        if (activeTab == null || activeTab.webView == null) return;
        applyCookiePolicyForActiveTab();
        activeTab.webView.reload();
    }

    public void toggleBookmarkCurrentTab() {
        if (activeTab == null || TextUtils.isEmpty(activeTab.url)) return;
        String url = WebUiRouter.toBraveSchemeUrl(activeTab.url);
        prefs.addOrToggleBookmark(activeTab.title, url);
        boolean nowStarred = prefs.isBookmarked(url);
        Toast.makeText(this, nowStarred ? "Aggiunto ai preferiti ★" : "Rimosso dai preferiti", Toast.LENGTH_SHORT).show();
        rebuildBookmarksBar();
        syncToolbarWithActiveTab();
        refreshActiveWebUiIfNeeded();
    }

    public void toggleDesktopModeCurrentTab() {
        if (activeTab == null || activeTab.webView == null) return;
        activeTab.desktopMode = !activeTab.desktopMode;
        WebSettings s = activeTab.webView.getSettings();
        s.setUserAgentString(buildUserAgent(activeTab.desktopMode));
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        Toast.makeText(this, activeTab.desktopMode ? "Modalità Sito Desktop attivata" : "Modalità Mobile attivata", Toast.LENGTH_SHORT).show();
        activeTab.webView.reload();
    }

    public int adjustActiveTabZoom(int delta) {
        if (activeTab == null || activeTab.webView == null) return 100;
        activeTab.zoomPercent = Math.max(50, Math.min(200, activeTab.zoomPercent + delta));
        activeTab.webView.getSettings().setTextZoom(activeTab.zoomPercent);
        return activeTab.zoomPercent;
    }

    public void toggleImmersiveFullscreen() {
        isImmersiveFullscreen = !isImmersiveFullscreen;
        setImmersiveSystemUi(isImmersiveFullscreen);
        tabStripBar.setVisibility(isImmersiveFullscreen ? View.GONE : (prefs.isShowTabStrip() ? View.VISIBLE : View.GONE));
        bookmarksBarRow.setVisibility(isImmersiveFullscreen ? View.GONE : (prefs.isShowBookmarksBar() ? View.VISIBLE : View.GONE));
        Toast.makeText(this, isImmersiveFullscreen ? "Schermo intero attivo (premi Indietro o Menu per uscire)" : "Schermo intero disattivato", Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("deprecation")
    private void setImmersiveSystemUi(boolean immersive) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                if (immersive) {
                    ic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    ic.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    applyAppearanceLayout();
                }
            }
            return;
        }
        View decor = getWindow().getDecorView();
        if (immersive) {
            decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            applyAppearanceLayout();
        }
    }

    public void startElementPickerOnActiveTab() {
        if (activeTab == null || activeTab.webView == null || WebUiRouter.isWebUiUrl(activeTab.url)) {
            Toast.makeText(this, "Apri un sito web per bloccare elementi sulla pagina", Toast.LENGTH_SHORT).show();
            return;
        }
        elementPickerArmed = true;
        activeTab.webView.evaluateJavascript(ShieldsEngine.buildElementPickerScript(), null);
    }

    public void toggleSpeedreaderOnActiveTab() {
        if (activeTab == null || activeTab.webView == null || WebUiRouter.isWebUiUrl(activeTab.url)) {
            Toast.makeText(this, "Apri un articolo web per attivare Speedreader", Toast.LENGTH_SHORT).show();
            return;
        }
        activeTab.webView.evaluateJavascript(ShieldsEngine.buildSpeedreaderToggleScript(), null);
    }

    public void showQuickPageSummary() {
        if (activeTab == null || activeTab.webView == null || WebUiRouter.isWebUiUrl(activeTab.url)) return;
        String js = "(function(){var p=Array.from(document.querySelectorAll('p')).map(e=>e.innerText.trim()).filter(t=>t.length>60).slice(0,5);return p.join('\\n\\n');})();";
        activeTab.webView.evaluateJavascript(js, value -> {
            try {
                String decoded = value != null && value.startsWith("\"")
                    ? new JSONArray("[" + value + "]").getString(0)
                    : value;
                if (TextUtils.isEmpty(decoded)) decoded = "Nessun testo articolo rilevato nella pagina corrente.";
                new AlertDialog.Builder(MainActivity.this, R.style.Theme_BraveOrigin_Dialog)
                    .setTitle("✨ Estratto Rapido Pagina (Locale)")
                    .setMessage(decoded)
                    .setPositiveButton("Chiudi", null)
                    .show();
            } catch (Exception ignored) {
            }
        });
    }

    public void saveActiveTabToPlaylist() {
        if (activeTab == null || WebUiRouter.isWebUiUrl(activeTab.url)) return;
        prefs.addPlaylistItem(activeTab.title, activeTab.url);
        Toast.makeText(this, "Salvato in Brave Playlist", Toast.LENGTH_SHORT).show();
    }

    public void showFindInPageBar() {
        findBar.setVisibility(View.VISIBLE);
        findInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeFindInPageBar() {
        findBar.setVisibility(View.GONE);
        findInput.setText("");
        if (activeTab != null && activeTab.webView != null) {
            activeTab.webView.clearMatches();
        }
        hideKeyboard();
    }

    public void copyCleanLinkCurrentTab() {
        if (activeTab == null || TextUtils.isEmpty(activeTab.url)) return;
        copyCleanUrlToClipboard(WebUiRouter.toBraveSchemeUrl(activeTab.url));
    }

    public void copyCleanUrlToClipboard(String rawUrl) {
        String clean = shieldsEngine.cleanUrl(rawUrl, true, true);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Brave Clean Link", clean));
            Toast.makeText(this, "✨ Link pulito copiato negli appunti", Toast.LENGTH_SHORT).show();
        }
    }

    public void copyRawToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("URL", text));
            Toast.makeText(this, "Indirizzo copiato", Toast.LENGTH_SHORT).show();
        }
    }

    public void shareCurrentTab() {
        if (activeTab == null || TextUtils.isEmpty(activeTab.url)) return;
        shareUrl(shieldsEngine.cleanUrl(WebUiRouter.toBraveSchemeUrl(activeTab.url), true, true));
    }

    public void shareUrl(String url) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(send, "Condividi con"));
    }

    public void printCurrentTab() {
        if (activeTab == null || activeTab.webView == null) return;
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (pm != null) {
            String jobName = "BraveOrigin_" + (!TextUtils.isEmpty(activeTab.title) ? activeTab.title : "Pagina");
            PrintDocumentAdapter adapter = activeTab.webView.createPrintDocumentAdapter(jobName);
            pm.print(jobName, adapter, new PrintAttributes.Builder().build());
        }
    }

    public void saveOfflineArchiveCurrentTab() {
        if (activeTab == null || activeTab.webView == null || WebUiRouter.isWebUiUrl(activeTab.url)) {
            Toast.makeText(this, "Apri una pagina web per salvarla offline", Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        String safeTitle = (activeTab.title != null ? activeTab.title : "pagina").replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeTitle.length() > 40) safeTitle = safeTitle.substring(0, 40);
        File target = new File(dir, safeTitle + "_" + System.currentTimeMillis() + ".mht");
        activeTab.webView.saveWebArchive(target.getAbsolutePath(), false, savedPath -> {
            if (savedPath != null) {
                prefs.recordDownload(target.getName(), "file://" + savedPath, "multipart/related");
                Toast.makeText(MainActivity.this, "Pagina salvata offline: " + target.getName(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(MainActivity.this, "Impossibile salvare l'archivio pagina", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void enqueueDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (TextUtils.isEmpty(url)) return;
        try {
            if (url.startsWith("data:")) {
                saveDataUriDownload(url, URLUtil.guessFileName("download", contentDisposition, mimeType));
                return;
            }
            if (url.startsWith("blob:")) {
                if (activeTab != null && activeTab.webView != null) {
                    blobDownloadArmed = true;
                    String fn = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    String js = "fetch(" + JSONObject.quote(url) + ").then(r=>r.blob()).then(b=>{var fr=new FileReader();fr.onloadend=function(){if(window.BravePageHelper)window.BravePageHelper.onBlobDownloaded(fr.result," + JSONObject.quote(fn) + ");};fr.readAsDataURL(b);});";
                    activeTab.webView.evaluateJavascript(js, null);
                }
                return;
            }
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mimeType);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(cookies)) {
                req.addRequestHeader("Cookie", cookies);
            }
            req.addRequestHeader("User-Agent", userAgent != null ? userAgent : buildUserAgent(false));
            req.setTitle(fileName);
            req.setDescription("Download con Brave Origin");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                prefs.recordDownload(fileName, url, mimeType != null ? mimeType : "application/octet-stream");
                Toast.makeText(this, "⬇ Download avviato: " + fileName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            openExternally(Uri.parse(url));
        }
    }

    private void saveDataUriDownload(String dataUri, String preferredName) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma < 0) return;
            String meta = dataUri.substring(5, comma);
            String payload = dataUri.substring(comma + 1);
            byte[] bytes = meta.contains(";base64")
                ? Base64.decode(payload, Base64.DEFAULT)
                : Uri.decode(payload).getBytes("UTF-8");
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            String fn = !TextUtils.isEmpty(preferredName) ? preferredName : ("brave_download_" + System.currentTimeMillis() + ".bin");
            File out = new File(dir, fn);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            prefs.recordDownload(fn, "data:download", meta);
            Toast.makeText(this, "⬇ File salvato in Download: " + fn, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Errore salvataggio file scaricato", Toast.LENGTH_SHORT).show();
        }
    }

    public void clearSiteDataForUrl(String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            Uri u = Uri.parse(url);
            String origin = u.getScheme() + "://" + u.getAuthority();
            WebStorage.getInstance().deleteOrigin(origin);
            String host = u.getHost();
            if (!TextUtils.isEmpty(host)) {
                CookieManager cm = CookieManager.getInstance();
                String cookieHeader = cm.getCookie(url);
                if (!TextUtils.isEmpty(cookieHeader)) {
                    for (String part : cookieHeader.split(";")) {
                        int eq = part.indexOf('=');
                        String name = (eq >= 0 ? part.substring(0, eq) : part).trim();
                        cm.setCookie(url, name + "=; Max-Age=0; path=/");
                    }
                    cm.flush();
                }
            }
            Toast.makeText(this, "Cookie e storage cancellati per " + u.getHost(), Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    public void clearBrowsingDataByScope(String scope) {
        boolean all = "all".equals(scope);
        if (all || "history".equals(scope)) {
            prefs.clearHistory();
        }
        if (all || "cookies".equals(scope)) {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            WebStorage.getInstance().deleteAllData();
        }
        if (all || "cache".equals(scope)) {
            if (activeTab != null && activeTab.webView != null) {
                activeTab.webView.clearCache(true);
            }
        }
    }

    public void exitAndCleanSession() {
        clearBrowsingDataByScope("all");
        prefs.saveTabSession(new JSONArray(), 0);
        for (BrowserTab t : new ArrayList<>(tabs)) {
            destroyTabWebView(t);
        }
        tabs.clear();
        finishAndRemoveTask();
    }

    private void showTabHistoryPopup() {
        if (activeTab == null || activeTab.webView == null) return;
        WebBackForwardList list = activeTab.webView.copyBackForwardList();
        int size = list.getSize();
        if (size <= 1) return;

        final List<String> titles = new ArrayList<>();
        final List<Integer> steps = new ArrayList<>();
        int cur = list.getCurrentIndex();
        for (int i = size - 1; i >= 0; i--) {
            WebHistoryItem item = list.getItemAtIndex(i);
            String prefix = (i == cur) ? "● " : "   ";
            String t = !TextUtils.isEmpty(item.getTitle()) ? item.getTitle() : WebUiRouter.toBraveSchemeUrl(item.getUrl());
            titles.add(prefix + t);
            steps.add(i - cur);
        }

        new AlertDialog.Builder(this, R.style.Theme_BraveOrigin_Dialog)
            .setTitle("Cronologia scheda")
            .setItems(titles.toArray(new String[0]), (d, which) -> {
                int delta = steps.get(which);
                if (delta != 0 && activeTab.webView.canGoBackOrForward(delta)) {
                    activeTab.webView.goBackOrForward(delta);
                }
            })
            .show();
    }

    /* ==================== DEVTOOLS DOCK ==================== */
    public void toggleDevToolsPanel() {
        boolean show = devToolsPanel.getVisibility() != View.VISIBLE;
        devToolsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            refreshDevToolsContent();
        }
    }

    private void switchDevToolsTab(int idx) {
        activeDevToolsTab = idx;
        devTabConsole.setTextColor(idx == 0 ? 0xFFFB542B : 0xFF9A9AA6);
        devTabElements.setTextColor(idx == 1 ? 0xFFFB542B : 0xFF9A9AA6);
        devTabNetwork.setTextColor(idx == 2 ? 0xFFFB542B : 0xFF9A9AA6);
        devTabStorage.setTextColor(idx == 3 ? 0xFFFB542B : 0xFF9A9AA6);
        devToolsReplRow.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        refreshDevToolsContent();
    }

    private void refreshDevToolsContent() {
        if (devToolsPanel.getVisibility() != View.VISIBLE || activeTab == null) return;

        if (activeDevToolsTab == 0) {
            List<String> logs = activeTab.getConsoleSnapshot();
            devToolsOutput.setText(logs.isEmpty() ? "// Console pronta. Inserisci un'espressione JS qui sotto." : TextUtils.join("\n", logs));
        } else if (activeDevToolsTab == 1) {
            if (activeTab.webView != null) {
                activeTab.webView.evaluateJavascript("document.documentElement.outerHTML.slice(0, 12000)", val -> {
                    try {
                        String html = val != null && val.startsWith("\"") ? new JSONArray("[" + val + "]").getString(0) : String.valueOf(val);
                        devToolsOutput.setText(html);
                    } catch (Exception e) {
                        devToolsOutput.setText(String.valueOf(val));
                    }
                });
            }
        } else if (activeDevToolsTab == 2) {
            List<String> blocked = activeTab.getBlockedLogSnapshot();
            StringBuilder sb = new StringBuilder();
            sb.append("URL Pagina: ").append(WebUiRouter.toBraveSchemeUrl(activeTab.url)).append("\n");
            sb.append("Richieste bloccate da Brave Shields: ").append(activeTab.getBlockedCount()).append("\n\n");
            for (String b : blocked) sb.append(b).append("\n");
            devToolsOutput.setText(sb.toString());
        } else if (activeDevToolsTab == 3) {
            if (activeTab.webView != null) {
                String js = "'User-Agent: '+navigator.userAgent+'\\nGPC: '+navigator.globalPrivacyControl+'\\nCookies: '+(document.cookie||'(nessuno)')+'\\nlocalStorage keys: '+Object.keys(localStorage).length";
                activeTab.webView.evaluateJavascript(js, val -> {
                    try {
                        String out = val != null && val.startsWith("\"") ? new JSONArray("[" + val + "]").getString(0) : String.valueOf(val);
                        devToolsOutput.setText(out);
                    } catch (Exception e) {
                        devToolsOutput.setText(String.valueOf(val));
                    }
                });
            }
        }
    }

    private void executeDevToolsJs() {
        if (activeTab == null || activeTab.webView == null) return;
        final String expr = devToolsInput.getText().toString().trim();
        if (expr.isEmpty()) return;
        devToolsInput.setText("");
        activeTab.appendConsole("INPUT", "> " + expr, 0, "");
        activeTab.webView.evaluateJavascript("try{String(eval(" + JSONObject.quote(expr) + "))}catch(e){'Error: '+e.message}", res -> {
            activeTab.appendConsole("RESULT", "<- " + res, 0, "");
            refreshDevToolsContent();
        });
    }

    /* ==================== SESSION PERSISTENCE & INTENTS ==================== */
    private void persistTabsIfNeeded() {
        if (!prefs.isRestoreSession()) {
            prefs.saveTabSession(new JSONArray(), 0);
            return;
        }
        JSONArray arr = new JSONArray();
        int activeNormalIdx = 0;
        int curIdx = 0;
        for (BrowserTab t : tabs) {
            if (t.isPrivate) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("url", WebUiRouter.toBraveSchemeUrl(t.url));
                o.put("title", t.title);
                o.put("pinned", t.pinned);
                o.put("desktop", t.desktopMode);
                arr.put(o);
                if (t == activeTab) activeNormalIdx = curIdx;
                curIdx++;
            } catch (JSONException ignored) {
            }
        }
        prefs.saveTabSession(arr, activeNormalIdx);
    }

    private boolean restorePreviousSessionIfEnabled(String externalLaunchUrl) {
        if (!prefs.isRestoreSession()) return false;
        JSONArray saved = prefs.loadTabSession();
        if (saved.length() == 0) return false;

        int activeIdx = prefs.loadTabSessionActiveIndex();
        BrowserTab toFocus = null;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject o = saved.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url", "brave://newtab");
            BrowserTab t = new BrowserTab(false, WebUiRouter.toInternalUrl(url), o.optBoolean("desktop", prefs.isDesktopModeDefault()), prefs.getDefaultZoom());
            t.title = o.optString("title", "Nuova scheda");
            t.pinned = o.optBoolean("pinned", false);
            t.hibernated = (i != activeIdx || externalLaunchUrl != null);
            tabs.add(t);
            if (i == activeIdx) toFocus = t;
        }

        if (externalLaunchUrl != null) {
            createNewTab(externalLaunchUrl, false, true);
            return true;
        }
        if (toFocus != null) {
            switchToTab(toFocus);
            return true;
        }
        return false;
    }

    private static String extractIntentUrl(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String txt = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(txt)) {
                Matcher m = Pattern.compile("(https?://\\S+)").matcher(txt);
                return m.find() ? m.group(1) : txt.trim();
            }
        }
        if (Intent.ACTION_WEB_SEARCH.equals(intent.getAction())) {
            String q = intent.getStringExtra("query");
            if (!TextUtils.isEmpty(q)) return "https://search.brave.com/search?q=" + Uri.encode(q);
        }
        return null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String target = extractIntentUrl(intent);
        if (!TextUtils.isEmpty(target)) {
            createNewTab(target, false, true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (pendingFileChooser != null) {
                Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                pendingFileChooser.onReceiveValue(results);
                pendingFileChooser = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ==================== KEYBOARD SHORTCUTS & BACK NAVIGATION ==================== */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            boolean ctrl = event.isCtrlPressed() || event.isMetaPressed();
            boolean shift = event.isShiftPressed();

            if (code == KeyEvent.KEYCODE_F12 && prefs.isDevToolsEnabled()) {
                toggleDevToolsPanel();
                return true;
            }
            if (code == KeyEvent.KEYCODE_F5 || (ctrl && code == KeyEvent.KEYCODE_R)) {
                reloadCurrentTabWithConfig();
                return true;
            }
            if (ctrl && shift && code == KeyEvent.KEYCODE_T) {
                reopenLastClosedTab();
                return true;
            }
            if (ctrl && shift && code == KeyEvent.KEYCODE_N) {
                createNewTab("brave://newtab", true, true);
                return true;
            }
            if (ctrl && code == KeyEvent.KEYCODE_T) {
                createNewTab("brave://newtab", false, true);
                return true;
            }
            if (ctrl && code == KeyEvent.KEYCODE_W) {
                if (activeTab != null) closeTab(activeTab);
                return true;
            }
            if (ctrl && code == KeyEvent.KEYCODE_L) {
                urlBar.requestFocus();
                urlBar.selectAll();
                return true;
            }
            if (ctrl && code == KeyEvent.KEYCODE_F) {
                showFindInPageBar();
                return true;
            }
            if (ctrl && code == KeyEvent.KEYCODE_D) {
                toggleBookmarkCurrentTab();
                return true;
            }
            if (ctrl && code == KeyEvent.KEYCODE_H) {
                openUrlInCurrentTab("brave://history");
                return true;
            }
            if (ctrl && code == KeyEvent.KEYCODE_J) {
                openUrlInCurrentTab("brave://downloads");
                return true;
            }
            if (ctrl && code == KeyEvent.KEYCODE_P) {
                printCurrentTab();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleBrowserBack() {
        if (customFullscreenView != null && customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            return;
        }
        if (isImmersiveFullscreen) {
            toggleImmersiveFullscreen();
            return;
        }
        if (omniboxSuggestionsCard.getVisibility() == View.VISIBLE || urlBar.hasFocus()) {
            omniboxSuggestionsCard.setVisibility(View.GONE);
            urlBar.clearFocus();
            hideKeyboard();
            return;
        }
        if (findBar.getVisibility() == View.VISIBLE) {
            closeFindInPageBar();
            return;
        }
        if (devToolsPanel.getVisibility() == View.VISIBLE) {
            devToolsPanel.setVisibility(View.GONE);
            return;
        }
        if (activeTab != null && activeTab.webView != null && activeTab.webView.canGoBack()) {
            activeTab.webView.goBack();
            return;
        }
        if (tabs.size() > 1 && activeTab != null) {
            closeTab(activeTab);
            return;
        }
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBrowserBack();
    }

    @Override
    protected void onPause() {
        persistTabsIfNeeded();
        if (activeTab != null && activeTab.webView != null) {
            activeTab.webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activeTab != null && activeTab.webView != null) {
            activeTab.webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && prefs.isClearOnExit()) {
            clearBrowsingDataByScope("all");
        }
        for (BrowserTab t : tabs) {
            destroyTabWebView(t);
        }
        tabs.clear();
        super.onDestroy();
    }

    /* ==================== BRIDGES ==================== */
    private void refreshActiveWebUiIfNeeded() {
        if (activeTab != null && activeTab.webView != null && WebUiRouter.isWebUiUrl(activeTab.url)) {
            activeTab.webView.evaluateJavascript("if(window.__braveOriginRefresh)window.__braveOriginRefresh();", null);
        }
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
    }

    private int dp(float val) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val, getResources().getDisplayMetrics()));
    }

    private final class PageHelperBridge {
        @JavascriptInterface
        public void onElementZapped(final String host, final String selector) {
            if (!elementPickerArmed) return;
            elementPickerArmed = false;
            runOnUiThread(() -> {
                prefs.addZappedSelector(host, selector);
                Toast.makeText(MainActivity.this, "Elemento bloccato su " + OriginPrefs.normalizeHost(host), Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void onBlobDownloaded(final String dataUri, final String fileName) {
            if (!blobDownloadArmed) return;
            blobDownloadArmed = false;
            runOnUiThread(() -> saveDataUriDownload(dataUri, fileName));
        }
    }

    private final class WebUiBridge {
        private boolean check(String token) {
            return sessionToken != null && sessionToken.equals(token);
        }

        @JavascriptInterface
        public String getFullStateJson(String token) {
            if (!check(token)) return "{}";
            String ua = buildUserAgent(activeTab != null && activeTab.desktopMode);
            return prefs.buildFullStateJson(shieldsEngine, webViewVersionLabel, ua);
        }

        @JavascriptInterface
        public void submitOmnibox(String token, final String query) {
            if (!check(token)) return;
            runOnUiThread(() -> submitOmniboxInput(query));
        }

        @JavascriptInterface
        public void openUrl(String token, final String url, final boolean inNewTab) {
            if (!check(token)) return;
            runOnUiThread(() -> {
                if (inNewTab) createNewTab(url, activeTab != null && activeTab.isPrivate, true);
                else openUrlInCurrentTab(url);
            });
        }

        @JavascriptInterface
        public void setOriginPolicy(String token, final String key, final boolean value) {
            if (!check(token)) return;
            prefs.setOriginPolicy(key, value);
            runOnUiThread(MainActivity.this::syncToolbarWithActiveTab);
        }

        @JavascriptInterface
        public void applyOriginPreset(String token, final String preset) {
            if (!check(token)) return;
            prefs.applyOriginPreset(preset);
            runOnUiThread(() -> {
                applyAppearanceLayout();
                syncToolbarWithActiveTab();
            });
        }

        @JavascriptInterface
        public void setPrefBoolean(String token, final String key, final boolean val) {
            if (!check(token)) return;
            prefs.setBoolean(key, val);
            runOnUiThread(() -> {
                applyAppearanceLayout();
                applyPrivateWindowFlags();
                syncToolbarWithActiveTab();
            });
        }

        @JavascriptInterface
        public void setPrefInt(String token, final String key, final int val) {
            if (!check(token)) return;
            prefs.setInt(key, val);
            runOnUiThread(() -> {
                if ("defaultZoom".equals(key) && activeTab != null && activeTab.webView != null) {
                    activeTab.zoomPercent = val;
                    activeTab.webView.getSettings().setTextZoom(val);
                }
                syncToolbarWithActiveTab();
            });
        }

        @JavascriptInterface
        public void setPrefString(String token, final String key, final String val) {
            if (!check(token)) return;
            prefs.setString(key, val);
            runOnUiThread(() -> {
                applyAppearanceLayout();
                syncToolbarWithActiveTab();
            });
        }

        @JavascriptInterface
        public void saveCustomFilters(String token, final String rules) {
            if (!check(token)) return;
            prefs.setCustomFilters(rules);
            shieldsEngine.reloadCustomRules(rules);
        }

        @JavascriptInterface
        public void clearZappedElements(String token) {
            if (!check(token)) return;
            prefs.clearAllZappedSelectors();
        }

        @JavascriptInterface
        public void addTopSite(String token, final String title, final String url) {
            if (!check(token)) return;
            prefs.addTopSite(title, url);
        }

        @JavascriptInterface
        public void removeTopSite(String token, final int index) {
            if (!check(token)) return;
            prefs.removeTopSite(index);
        }

        @JavascriptInterface
        public void addBookmark(String token, final String title, final String url) {
            if (!check(token)) return;
            prefs.addBookmark(title, url);
            runOnUiThread(() -> {
                rebuildBookmarksBar();
                syncToolbarWithActiveTab();
            });
        }

        @JavascriptInterface
        public void removeBookmark(String token, final String url) {
            if (!check(token)) return;
            prefs.removeBookmark(url);
            runOnUiThread(() -> {
                rebuildBookmarksBar();
                syncToolbarWithActiveTab();
            });
        }

        @JavascriptInterface
        public void removeHistoryAt(String token, final int index) {
            if (!check(token)) return;
            prefs.removeHistoryAt(index);
        }

        @JavascriptInterface
        public void removePlaylistAt(String token, final int index) {
            if (!check(token)) return;
            prefs.removePlaylistAt(index);
        }

        @JavascriptInterface
        public void clearDownloadsList(String token) {
            if (!check(token)) return;
            prefs.clearDownloads();
        }

        @JavascriptInterface
        public void openSystemDownloads(String token) {
            if (!check(token)) return;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void clearBrowsingData(String token, final String scope) {
            if (!check(token)) return;
            runOnUiThread(() -> clearBrowsingDataByScope(scope));
        }

        @JavascriptInterface
        public void toggleDevTools(String token) {
            if (!check(token)) return;
            runOnUiThread(MainActivity.this::toggleDevToolsPanel);
        }

        @JavascriptInterface
        public void openDefaultBrowserSettings(String token) {
            if (!check(token)) return;
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
                    } else {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                } catch (Exception ignored) {
                }
            });
        }
    }
}
