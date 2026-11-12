package com.tixigx.braveorigin;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final String SEARCH_URL = "https://search.brave.com/search?q=";

    private WebView webView;
    private EditText urlBar;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private ImageButton btnReload;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        urlBar = findViewById(R.id.url_bar);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnReload = findViewById(R.id.btn_reload);
        progress = findViewById(R.id.progress);

        configureWebView();

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoBack()) {
                    webView.goBack();
                }
            }
        });

        btnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoForward()) {
                    webView.goForward();
                }
            }
        });

        btnReload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.getUrl() == null) {
                    webView.loadUrl(HOME_URL);
                } else {
                    webView.reload();
                }
            }
        });

        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigate(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });

        Intent intent = getIntent();
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (intent != null && intent.getData() != null) {
            navigate(intent.getData().toString());
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setGeolocationEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setBackgroundColor(0xFF0E0E10);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    return false;
                }
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!urlBar.hasFocus()) {
                    urlBar.setText(prettyUrl(url));
                }
                updateNav();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!urlBar.hasFocus()) {
                    urlBar.setText(prettyUrl(url));
                }
                updateNav();
            }
        });
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            // Nessuna app in grado di gestire lo schema: si resta nella pagina corrente.
        }
    }

    private static String prettyUrl(String url) {
        if (url == null || url.equals(HOME_URL)) {
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

    private void navigate(String input) {
        String value = input == null ? "" : input.trim();
        if (TextUtils.isEmpty(value)) {
            return;
        }

        String lower = value.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file://")) {
            load(value);
        } else if (value.contains(" ") || !value.contains(".")) {
            load(SEARCH_URL + Uri.encode(value));
        } else if (value.contains("://")) {
            openExternally(Uri.parse(value));
            load(webView.getUrl());
        } else {
            load("https://" + value);
        }
    }

    private void load(String url) {
        urlBar.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
        webView.loadUrl(url);
    }

    private void updateNav() {
        boolean back = webView.canGoBack();
        boolean forward = webView.canGoForward();
        btnBack.setEnabled(back);
        btnBack.setAlpha(back ? 1f : 0.35f);
        btnForward.setEnabled(forward);
        btnForward.setAlpha(forward ? 1f : 0.35f);
        if (!urlBar.hasFocus()) {
            urlBar.setText(prettyUrl(webView.getUrl()));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) {
            navigate(intent.getData().toString());
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        String current = webView.getUrl();
        if (current != null && !current.equals(HOME_URL)) {
            webView.loadUrl(HOME_URL);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
