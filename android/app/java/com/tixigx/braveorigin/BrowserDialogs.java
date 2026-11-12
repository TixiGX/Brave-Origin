package com.tixigx.braveorigin;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

public final class BrowserDialogs {

    private BrowserDialogs() {}

    private static int dp(Context ctx, float val) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val, ctx.getResources().getDisplayMetrics()));
    }

    private static GradientDrawable roundedBg(int fillColor, int strokeColor, int radiusDp, Context ctx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(ctx, radiusDp));
        if (strokeColor != 0) {
            d.setStroke(dp(ctx, 1), strokeColor);
        }
        return d;
    }

    /* ==================== 1. BRAVE SHIELDS PANEL ==================== */
    public static void showShieldsDialog(final MainActivity act, final BrowserTab tab, final OriginPrefs prefs) {
        if (tab == null) return;
        final String currentUrl = tab.url != null ? tab.url : "";
        if (WebUiRouter.isWebUiUrl(currentUrl)) {
            AlertDialog.Builder b = new AlertDialog.Builder(act, R.style.Theme_BraveOrigin_Dialog);
            b.setTitle("🦁 Brave Origin");
            b.setMessage("Questa è una pagina interna sicura di Brave Origin (" + WebUiRouter.toBraveSchemeUrl(currentUrl) + "). Non ci sono tracker né richieste esterne.");
            b.setPositiveButton("Impostazioni Scudi", (d, w) -> act.openUrlInCurrentTab("brave://settings/shields"));
            b.setNegativeButton("Chiudi", null);
            b.show();
            return;
        }

        Uri u = Uri.parse(currentUrl);
        final String host = u.getHost() != null ? u.getHost() : currentUrl;
        final OriginPrefs.SiteShieldsConfig cfg = prefs.getSiteShields(host);

        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        // Header
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView statusTxt = new TextView(act);
        statusTxt.setText(cfg.shieldsUp ? "🦁 Scudi ATTIVI per questo sito" : "⚪ Scudi DISATTIVATI per questo sito");
        statusTxt.setTextColor(cfg.shieldsUp ? 0xFFFB542B : 0xFF9A9AA6);
        statusTxt.setTextSize(15f);
        statusTxt.setTypeface(Typeface.DEFAULT_BOLD);

        TextView hostTxt = new TextView(act);
        hostTxt.setText(OriginPrefs.normalizeHost(host));
        hostTxt.setTextColor(0xFFF4F4F6);
        hostTxt.setTextSize(13f);

        titleCol.addView(statusTxt);
        titleCol.addView(hostTxt);

        Switch masterSw = new Switch(act);
        masterSw.setChecked(cfg.shieldsUp);

        header.addView(titleCol);
        header.addView(masterSw);
        root.addView(header);

        // Blocked Counter Card
        LinearLayout counterCard = new LinearLayout(act);
        counterCard.setOrientation(LinearLayout.VERTICAL);
        counterCard.setBackground(roundedBg(0xFF16161C, 0xFF2E2E3A, 12, act));
        int cp = dp(act, 12);
        counterCard.setPadding(cp, cp, cp, cp);
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp.topMargin = dp(act, 14);
        ccLp.bottomMargin = dp(act, 12);
        counterCard.setLayoutParams(ccLp);

        TextView countBig = new TextView(act);
        countBig.setText(tab.getBlockedCount() + " tracker, annunci ed elementi bloccati");
        countBig.setTextColor(0xFFF4F4F6);
        countBig.setTextSize(14f);
        countBig.setTypeface(Typeface.DEFAULT_BOLD);
        counterCard.addView(countBig);

        final List<String> blockedItems = tab.getBlockedLogSnapshot();
        if (!blockedItems.isEmpty()) {
            final TextView toggleLog = new TextView(act);
            toggleLog.setText("Mostra dettagli risorse bloccate ▾");
            toggleLog.setTextColor(0xFF9A9AA6);
            toggleLog.setTextSize(12f);
            toggleLog.setPadding(0, dp(act, 4), 0, 0);

            final TextView logBody = new TextView(act);
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(blockedItems.size(), 30);
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append('\n');
                sb.append("• ").append(blockedItems.get(i));
            }
            logBody.setText(sb.toString());
            logBody.setTextColor(0xFFA0A0AE);
            logBody.setTextSize(11f);
            logBody.setTypeface(Typeface.MONOSPACE);
            logBody.setPadding(0, dp(act, 8), 0, 0);
            logBody.setVisibility(View.GONE);

            counterCard.setOnClickListener(v -> {
                boolean vis = logBody.getVisibility() == View.VISIBLE;
                logBody.setVisibility(vis ? View.GONE : View.VISIBLE);
                toggleLog.setText(vis ? "Mostra dettagli risorse bloccate ▾" : "Nascondi dettagli ▴");
            });
            counterCard.addView(toggleLog);
            counterCard.addView(logBody);
        }
        root.addView(counterCard);

        // Controls Container
        final LinearLayout controlsBox = new LinearLayout(act);
        controlsBox.setOrientation(LinearLayout.VERTICAL);
        controlsBox.setAlpha(cfg.shieldsUp ? 1f : 0.4f);

        addSegmentedRow(act, controlsBox, "Blocco tracker e annunci",
            new String[]{"Off", "Standard", "Aggressivo"}, cfg.adblockLevel, val -> {
                cfg.adblockLevel = val;
                prefs.saveSiteShields(host, cfg);
                act.reloadCurrentTabWithConfig();
            });

        addSegmentedRow(act, controlsBox, "Aggiorna connessioni a HTTPS",
            new String[]{"Off", "Standard", "Rigoroso"}, cfg.httpsUpgrade, val -> {
                cfg.httpsUpgrade = val;
                prefs.saveSiteShields(host, cfg);
            });

        addSegmentedRow(act, controlsBox, "Blocco impronte digitali (Fingerprinting)",
            new String[]{"Off", "Standard", "Rigoroso"}, cfg.fingerprintingLevel, val -> {
                cfg.fingerprintingLevel = val;
                prefs.saveSiteShields(host, cfg);
                act.reloadCurrentTabWithConfig();
            });

        addSegmentedRow(act, controlsBox, "Blocco cookie",
            new String[]{"Consenti", "Terze parti", "Tutti"}, cfg.cookiePolicy, val -> {
                cfg.cookiePolicy = val;
                prefs.saveSiteShields(host, cfg);
                act.applyCookiePolicyForActiveTab();
            });

        addSwitchRow(act, controlsBox, "Blocca script JavaScript", cfg.blockScripts, checked -> {
            cfg.blockScripts = checked;
            prefs.saveSiteShields(host, cfg);
            act.reloadCurrentTabWithConfig();
        });

        addSwitchRow(act, controlsBox, "Dimenticami quando chiudo questo sito", cfg.forgetMeOnClose, checked -> {
            cfg.forgetMeOnClose = checked;
            prefs.saveSiteShields(host, cfg);
        });

        root.addView(controlsBox);

        final AlertDialog dialog = new AlertDialog.Builder(act, R.style.Theme_BraveOrigin_Dialog)
            .setView(scroll)
            .create();

        masterSw.setOnCheckedChangeListener((btn, checked) -> {
            cfg.shieldsUp = checked;
            statusTxt.setText(checked ? "🦁 Scudi ATTIVI per questo sito" : "⚪ Scudi DISATTIVATI per questo sito");
            statusTxt.setTextColor(checked ? 0xFFFB542B : 0xFF9A9AA6);
            controlsBox.setAlpha(checked ? 1f : 0.4f);
            prefs.saveSiteShields(host, cfg);
            act.reloadCurrentTabWithConfig();
        });

        // Bottom Action Buttons
        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(act, 12), 0, 0);

        TextView btnZap = buildActionBtn(act, "🎯 Blocca elemento", true);
        btnZap.setOnClickListener(v -> {
            dialog.dismiss();
            act.startElementPickerOnActiveTab();
        });

        TextView btnGlobal = buildActionBtn(act, "⚙ Impostazioni Scudi", false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, dp(act, 38), 1f);
        glp.leftMargin = dp(act, 8);
        btnGlobal.setLayoutParams(glp);
        btnGlobal.setOnClickListener(v -> {
            dialog.dismiss();
            act.openUrlInCurrentTab("brave://settings/shields");
        });

        btnZap.setLayoutParams(new LinearLayout.LayoutParams(0, dp(act, 38), 1f));
        actions.addView(btnZap);
        actions.addView(btnGlobal);
        root.addView(actions);

        dialog.show();
    }

    /* ==================== 2. DESKTOP MAIN MENU (≡) ==================== */
    public static void showDesktopMenuDialog(final MainActivity act, final BrowserTab tab, final OriginPrefs prefs) {
        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 12);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(act, R.style.Theme_BraveOrigin_Dialog)
            .setView(scroll)
            .create();

        // Top Quick Navigation Row (Back, Forward, Reload, Home, Bookmark, Share)
        LinearLayout quickNav = new LinearLayout(act);
        quickNav.setOrientation(LinearLayout.HORIZONTAL);
        quickNav.setGravity(Gravity.CENTER);
        quickNav.setBackground(roundedBg(0xFF17171E, 0xFF2C2C38, 12, act));
        quickNav.setPadding(dp(act, 4), dp(act, 6), dp(act, 4), dp(act, 6));
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp.bottomMargin = dp(act, 8);
        quickNav.setLayoutParams(qLp);

        boolean canBack = tab != null && tab.webView != null && tab.webView.canGoBack();
        boolean canFwd = tab != null && tab.webView != null && tab.webView.canGoForward();
        boolean isStarredNow = tab != null && prefs.isBookmarked(WebUiRouter.toBraveSchemeUrl(tab.url));

        addQuickNavBtn(act, quickNav, "←", canBack, () -> {
            dialog.dismiss();
            if (tab != null && tab.webView != null && tab.webView.canGoBack()) tab.webView.goBack();
        });
        addQuickNavBtn(act, quickNav, "→", canFwd, () -> {
            dialog.dismiss();
            if (tab != null && tab.webView != null && tab.webView.canGoForward()) tab.webView.goForward();
        });
        addQuickNavBtn(act, quickNav, "↻", true, () -> {
            dialog.dismiss();
            act.reloadCurrentTabWithConfig();
        });
        addQuickNavBtn(act, quickNav, "⌂", true, () -> {
            dialog.dismiss();
            act.openUrlInCurrentTab(prefs.getHomePageUrl());
        });
        addQuickNavBtn(act, quickNav, isStarredNow ? "★" : "☆", true, () -> {
            dialog.dismiss();
            act.toggleBookmarkCurrentTab();
        });
        addQuickNavBtn(act, quickNav, "📤", true, () -> {
            dialog.dismiss();
            act.shareCurrentTab();
        });
        root.addView(quickNav);

        addMenuItem(act, root, "＋  Nuova scheda", "Ctrl+T", () -> {
            dialog.dismiss();
            act.createNewTab("brave://newtab", false, true);
        });
        addMenuItem(act, root, "🕶  Nuova scheda privata", "Ctrl+Shift+N", () -> {
            dialog.dismiss();
            act.createNewTab("brave://newtab", true, true);
        });
        if (act.canReopenClosedTab()) {
            addMenuItem(act, root, "↺  Riapri scheda chiusa", "Ctrl+Shift+T", () -> {
                dialog.dismiss();
                act.reopenLastClosedTab();
            });
        }

        addDivider(act, root);

        // Inline Desktop Zoom + Fullscreen Controls
        LinearLayout zoomRow = new LinearLayout(act);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomRow.setPadding(dp(act, 12), dp(act, 6), dp(act, 8), dp(act, 6));

        TextView zoomLbl = new TextView(act);
        zoomLbl.setText("Zoom");
        zoomLbl.setTextColor(0xFFF4F4F6);
        zoomLbl.setTextSize(14f);
        zoomLbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView zoomVal = new TextView(act);
        int curZoom = tab != null ? tab.zoomPercent : prefs.getDefaultZoom();
        zoomVal.setText(curZoom + "%");
        zoomVal.setTextColor(0xFFF4F4F6);
        zoomVal.setPadding(dp(act, 10), 0, dp(act, 10), 0);
        zoomVal.setTextSize(13f);

        TextView btnMinus = buildSmallPillBtn(act, " − ");
        btnMinus.setOnClickListener(v -> {
            int z = act.adjustActiveTabZoom(-10);
            zoomVal.setText(z + "%");
        });
        TextView btnPlus = buildSmallPillBtn(act, " + ");
        btnPlus.setOnClickListener(v -> {
            int z = act.adjustActiveTabZoom(+10);
            zoomVal.setText(z + "%");
        });
        TextView btnFull = buildSmallPillBtn(act, " ⛶ ");
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, 32));
        flp.leftMargin = dp(act, 6);
        btnFull.setLayoutParams(flp);
        btnFull.setOnClickListener(v -> {
            dialog.dismiss();
            act.toggleImmersiveFullscreen();
        });

        zoomRow.addView(zoomLbl);
        zoomRow.addView(btnMinus);
        zoomRow.addView(zoomVal);
        zoomRow.addView(btnPlus);
        zoomRow.addView(btnFull);
        root.addView(zoomRow);

        addDivider(act, root);

        boolean starred = tab != null && prefs.isBookmarked(WebUiRouter.toBraveSchemeUrl(tab.url));
        addMenuItem(act, root, (starred ? "★  Rimuovi dai preferiti" : "☆  Aggiungi pagina ai preferiti"), "Ctrl+D", () -> {
            dialog.dismiss();
            act.toggleBookmarkCurrentTab();
        });
        addMenuItem(act, root, "📑  Gestione Preferiti", "brave://bookmarks", () -> {
            dialog.dismiss();
            act.openUrlInCurrentTab("brave://bookmarks");
        });
        addMenuItem(act, root, "🕘  Cronologia", "Ctrl+H", () -> {
            dialog.dismiss();
            act.openUrlInCurrentTab("brave://history");
        });
        addMenuItem(act, root, "⬇  Download", "Ctrl+J", () -> {
            dialog.dismiss();
            act.openUrlInCurrentTab("brave://downloads");
        });

        // Dynamic Origin-controlled features (shown ONLY if enabled in Brave Origin!)
        boolean speedreaderOn = prefs.isOriginFeatureEnabled("BraveSpeedreaderEnabled", false);
        boolean leoOn = prefs.isOriginFeatureEnabled("BraveAIChatEnabled", false);
        boolean playlistOn = prefs.isOriginFeatureEnabled("BravePlaylistEnabled", false);
        boolean talkOn = prefs.isOriginFeatureEnabled("BraveTalkDisabled", true);
        boolean waybackOn = prefs.isOriginFeatureEnabled("BraveWaybackMachineEnabled", false);

        if (speedreaderOn || leoOn || playlistOn || talkOn || waybackOn) {
            addDivider(act, root);
            if (speedreaderOn) {
                addMenuItem(act, root, "📖  Modalità lettura (Speedreader)", "Origin", () -> {
                    dialog.dismiss();
                    act.toggleSpeedreaderOnActiveTab();
                });
            }
            if (playlistOn) {
                addMenuItem(act, root, "▶  Salva in Brave Playlist", "", () -> {
                    dialog.dismiss();
                    act.saveActiveTabToPlaylist();
                });
                addMenuItem(act, root, "🎵  Apri Brave Playlist", "brave://playlist", () -> {
                    dialog.dismiss();
                    act.openUrlInCurrentTab("brave://playlist");
                });
            }
            if (waybackOn && tab != null && !WebUiRouter.isWebUiUrl(tab.url)) {
                addMenuItem(act, root, "🏛  Vedi su Wayback Machine", "Archivio", () -> {
                    dialog.dismiss();
                    act.openUrlInCurrentTab("https://web.archive.org/web/*/" + tab.url);
                });
            }
            if (talkOn) {
                addMenuItem(act, root, "📹  Avvia Brave Talk", "talk.brave.com", () -> {
                    dialog.dismiss();
                    act.createNewTab("https://talk.brave.com", false, true);
                });
            }
            if (leoOn) {
                addMenuItem(act, root, "✨  Riepilogo rapido pagina", "Leo", () -> {
                    dialog.dismiss();
                    act.showQuickPageSummary();
                });
            }
        }

        addDivider(act, root);

        addMenuItem(act, root, "🔍  Trova nella pagina…", "Ctrl+F", () -> {
            dialog.dismiss();
            act.showFindInPageBar();
        });
        addMenuItem(act, root, "📖  Vista lettura rapida", "", () -> {
            dialog.dismiss();
            act.toggleSpeedreaderOnActiveTab();
        });
        addMenuItem(act, root, "🎯  Blocca elemento nella pagina…", "", () -> {
            dialog.dismiss();
            act.startElementPickerOnActiveTab();
        });
        boolean isDesktop = tab != null && tab.desktopMode;
        addMenuItem(act, root, (isDesktop ? "☑  Sito desktop (Attivo)" : "☐  Sito desktop"), "", () -> {
            dialog.dismiss();
            act.toggleDesktopModeCurrentTab();
        });

        addDivider(act, root);

        addMenuItem(act, root, "✨  Copia link pulito (senza tracker)", "", () -> {
            dialog.dismiss();
            act.copyCleanLinkCurrentTab();
        });
        addMenuItem(act, root, "📤  Condividi pagina…", "", () -> {
            dialog.dismiss();
            act.shareCurrentTab();
        });
        addMenuItem(act, root, "🖨  Stampa / Salva come PDF…", "Ctrl+P", () -> {
            dialog.dismiss();
            act.printCurrentTab();
        });
        addMenuItem(act, root, "💾  Salva pagina offline (.mht)", "", () -> {
            dialog.dismiss();
            act.saveOfflineArchiveCurrentTab();
        });
        if (prefs.isDevToolsEnabled()) {
            addMenuItem(act, root, "🛠  Strumenti per sviluppatori (DevTools)", "F12", () -> {
                dialog.dismiss();
                act.toggleDevToolsPanel();
            });
        }

        addDivider(act, root);

        addMenuItem(act, root, "🦁  Brave Origin", "ATTIVO", () -> {
            dialog.dismiss();
            act.openUrlInCurrentTab("brave://settings/origin");
        });
        addMenuItem(act, root, "🛡  Filtri contenuti (Adblock)", "brave://adblock", () -> {
            dialog.dismiss();
            act.openUrlInCurrentTab("brave://adblock");
        });
        addMenuItem(act, root, "⚙  Impostazioni", "brave://settings", () -> {
            dialog.dismiss();
            act.openUrlInCurrentTab("brave://settings/appearance");
        });
        addMenuItem(act, root, "⏻  Esci e pulisci sessione", "", () -> {
            dialog.dismiss();
            act.exitAndCleanSession();
        });

        dialog.show();
    }

    /* ==================== 3. TAB SWITCHER SHEET ==================== */
    public static void showTabSwitcherDialog(final MainActivity act) {
        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(act, R.style.Theme_BraveOrigin_Dialog)
            .setView(scroll)
            .create();

        // Top Actions
        LinearLayout topBtns = new LinearLayout(act);
        topBtns.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnNewNorm = buildActionBtn(act, "＋ Nuova Scheda", true);
        btnNewNorm.setLayoutParams(new LinearLayout.LayoutParams(0, dp(act, 38), 1f));
        btnNewNorm.setOnClickListener(v -> {
            dialog.dismiss();
            act.createNewTab("brave://newtab", false, true);
        });

        TextView btnNewPriv = buildActionBtn(act, "🕶 Nuova Privata", false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(act, 38), 1f);
        plp.leftMargin = dp(act, 8);
        btnNewPriv.setLayoutParams(plp);
        btnNewPriv.setOnClickListener(v -> {
            dialog.dismiss();
            act.createNewTab("brave://newtab", true, true);
        });

        topBtns.addView(btnNewNorm);
        topBtns.addView(btnNewPriv);
        root.addView(topBtns);

        // Normal Tabs
        List<BrowserTab> normalTabs = act.getTabsSnapshot(false);
        addSectionHeader(act, root, "Schede Normali (" + normalTabs.size() + ")");
        for (final BrowserTab t : normalTabs) {
            addTabSwitcherRow(act, root, t, t == act.getActiveTab(), dialog);
        }

        // Private Tabs
        List<BrowserTab> privTabs = act.getTabsSnapshot(true);
        if (!privTabs.isEmpty()) {
            addSectionHeader(act, root, "🕶 Schede Private (" + privTabs.size() + ")");
            for (final BrowserTab t : privTabs) {
                addTabSwitcherRow(act, root, t, t == act.getActiveTab(), dialog);
            }
        }

        // Footer actions
        LinearLayout foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(0, dp(act, 14), 0, 0);

        if (act.canReopenClosedTab()) {
            TextView btnReopen = buildActionBtn(act, "↺ Riapri chiusa", false);
            btnReopen.setLayoutParams(new LinearLayout.LayoutParams(0, dp(act, 36), 1f));
            btnReopen.setOnClickListener(v -> {
                dialog.dismiss();
                act.reopenLastClosedTab();
            });
            foot.addView(btnReopen);
        }

        if (!privTabs.isEmpty()) {
            TextView btnClosePriv = buildActionBtn(act, "Chiudi tutte le Private", false);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(act, 36), 1f);
            if (foot.getChildCount() > 0) clp.leftMargin = dp(act, 8);
            btnClosePriv.setLayoutParams(clp);
            btnClosePriv.setOnClickListener(v -> {
                dialog.dismiss();
                act.closeAllTabs(true);
            });
            foot.addView(btnClosePriv);
        }

        if (foot.getChildCount() > 0) {
            root.addView(foot);
        }

        dialog.show();
    }

    private static void addTabSwitcherRow(final MainActivity act, LinearLayout parent, final BrowserTab tab, boolean isActive, final AlertDialog dialog) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundedBg(isActive ? 0xFF282834 : 0xFF17171E, isActive ? 0xFFFB542B : 0xFF2C2C38, 10, act));
        int p = dp(act, 10);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, 6);
        row.setLayoutParams(lp);

        LinearLayout info = new LinearLayout(act);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(act);
        String prefix = (tab.pinned ? "📌 " : "") + (tab.isPrivate ? "🕶 " : "");
        title.setText(prefix + (!TextUtils.isEmpty(tab.title) ? tab.title : WebUiRouter.toBraveSchemeUrl(tab.url)));
        title.setTextColor(0xFFF4F4F6);
        title.setTextSize(13.5f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(isActive ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

        TextView urlTxt = new TextView(act);
        urlTxt.setText(WebUiRouter.toBraveSchemeUrl(tab.url));
        urlTxt.setTextColor(0xFF9A9AA6);
        urlTxt.setTextSize(11.5f);
        urlTxt.setSingleLine(true);
        urlTxt.setEllipsize(TextUtils.TruncateAt.END);

        info.addView(title);
        info.addView(urlTxt);

        info.setOnClickListener(v -> {
            dialog.dismiss();
            act.switchToTab(tab);
        });

        TextView closeBtn = new TextView(act);
        closeBtn.setText("✕");
        closeBtn.setTextColor(0xFF9A9AA6);
        closeBtn.setTextSize(14f);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(act, 10), dp(act, 4), dp(act, 6), dp(act, 4));
        closeBtn.setOnClickListener(v -> {
            dialog.dismiss();
            act.closeTab(tab);
        });

        row.addView(info);
        row.addView(closeBtn);
        parent.addView(row);
    }

    /* ==================== 4. TAB RIGHT-CLICK / LONG-PRESS MENU ==================== */
    public static void showTabContextMenu(final MainActivity act, final BrowserTab tab) {
        if (tab == null) return;
        final String[] items = new String[]{
            tab.pinned ? "📌 Sblocca scheda" : "📌 Fissa scheda (Pin)",
            "⧉ Duplica scheda",
            "↻ Ricarica scheda",
            tab.audioMuted ? "🔊 Riattiva audio scheda" : "🔇 Silenzia scheda",
            "✕ Chiudi le altre schede",
            "✕ Chiudi questa scheda"
        };
        new AlertDialog.Builder(act, R.style.Theme_BraveOrigin_Dialog)
            .setTitle(tab.title)
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: act.togglePinTab(tab); break;
                    case 1: act.createNewTab(tab.url, tab.isPrivate, true); break;
                    case 2: act.reloadTab(tab); break;
                    case 3: act.toggleMuteTab(tab); break;
                    case 4: act.closeOtherTabs(tab); break;
                    case 5: act.closeTab(tab); break;
                }
            })
            .show();
    }

    /* ==================== 5. SITE SECURITY & SSL INFO ==================== */
    public static void showSiteInfoDialog(final MainActivity act, final BrowserTab tab, final OriginPrefs prefs) {
        if (tab == null) return;
        String url = tab.url != null ? tab.url : "";
        boolean isInternal = WebUiRouter.isWebUiUrl(url);
        boolean isHttps = url.startsWith("https://") && !isInternal;

        StringBuilder msg = new StringBuilder();
        if (isInternal) {
            msg.append("🦁 Pagina interna sicura di Brave Origin\n\n");
            msg.append("Indirizzo: ").append(WebUiRouter.toBraveSchemeUrl(url));
        } else {
            msg.append(isHttps ? "🔒 Connessione crittografata (HTTPS)\n" : "⚠️ Connessione non crittografata (HTTP)\n");
            msg.append("Indirizzo: ").append(url).append("\n\n");
            if (tab.webView != null && tab.webView.getCertificate() != null) {
                SslCertificate cert = tab.webView.getCertificate();
                msg.append("Emesso per: ").append(cert.getIssuedTo().getDName()).append("\n");
                msg.append("Emesso da: ").append(cert.getIssuedBy().getDName()).append("\n");
            }
            msg.append("\nTracker bloccati in questa pagina: ").append(tab.getBlockedCount());
        }

        AlertDialog.Builder b = new AlertDialog.Builder(act, R.style.Theme_BraveOrigin_Dialog)
            .setTitle("Informazioni sito e sicurezza")
            .setMessage(msg.toString())
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Copia link pulito", (d, w) -> act.copyCleanLinkCurrentTab());

        if (!isInternal) {
            b.setNegativeButton("Cancella dati sito", (d, w) -> act.clearSiteDataForUrl(url));
        }
        b.show();
    }

    /* ==================== 6. LONG PRESS CONTEXT MENU ==================== */
    public static void showWebContextMenu(final MainActivity act, final String linkUrl, final String imageUrl) {
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> actions = new java.util.ArrayList<>();

        if (!TextUtils.isEmpty(linkUrl)) {
            labels.add("Apri link in una nuova scheda");
            actions.add(() -> act.createNewTab(linkUrl, false, true));

            labels.add("Apri link in scheda in incognito");
            actions.add(() -> act.createNewTab(linkUrl, true, true));

            labels.add("✨ Copia link pulito (senza tracker)");
            actions.add(() -> act.copyCleanUrlToClipboard(linkUrl));

            labels.add("Copia indirizzo link");
            actions.add(() -> act.copyRawToClipboard(linkUrl));

            labels.add("Condividi link…");
            actions.add(() -> act.shareUrl(linkUrl));
        }

        if (!TextUtils.isEmpty(imageUrl)) {
            labels.add("Apri immagine in nuova scheda");
            actions.add(() -> act.createNewTab(imageUrl, false, true));

            labels.add("⬇ Scarica immagine");
            actions.add(() -> act.enqueueDownload(imageUrl, null, null, null));

            labels.add("Copia indirizzo immagine");
            actions.add(() -> act.copyRawToClipboard(imageUrl));
        }

        if (labels.isEmpty()) return;

        String header = !TextUtils.isEmpty(linkUrl) ? linkUrl : imageUrl;
        new AlertDialog.Builder(act, R.style.Theme_BraveOrigin_Dialog)
            .setTitle(header.length() > 70 ? header.substring(0, 70) + "…" : header)
            .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    /* ==================== UI WIDGET HELPERS ==================== */
    private interface IntConsumer { void accept(int val); }
    private interface BoolConsumer { void accept(boolean val); }

    private static void addSegmentedRow(final Context ctx, LinearLayout parent, String label, final String[] opts, int selected, final IntConsumer onSelect) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));

        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextColor(0xFFA0A0AE);
        lbl.setTextSize(12f);
        wrap.addView(lbl);

        final LinearLayout seg = new LinearLayout(ctx);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(roundedBg(0xFF14141A, 0xFF2E2E3A, 9, ctx));
        int sp = dp(ctx, 3);
        seg.setPadding(sp, sp, sp, sp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(ctx, 5);
        seg.setLayoutParams(slp);

        final TextView[] btns = new TextView[opts.length];
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView tv = new TextView(ctx);
            tv.setText(opts[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(12f);
            tv.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            styleSegBtn(ctx, tv, idx == selected);
            tv.setOnClickListener(v -> {
                for (int j = 0; j < btns.length; j++) {
                    styleSegBtn(ctx, btns[j], j == idx);
                }
                onSelect.accept(idx);
            });
            btns[i] = tv;
            seg.addView(tv);
        }

        wrap.addView(seg);
        parent.addView(wrap);
    }

    private static void styleSegBtn(Context ctx, TextView tv, boolean active) {
        if (active) {
            tv.setBackground(roundedBg(0xFFFB542B, 0, 7, ctx));
            tv.setTextColor(Color.WHITE);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            tv.setBackground(null);
            tv.setTextColor(0xFFA0A0AE);
            tv.setTypeface(Typeface.DEFAULT);
        }
    }

    private static void addSwitchRow(Context ctx, LinearLayout parent, String label, boolean checked, final BoolConsumer onChange) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFFF4F4F6);
        tv.setTextSize(13f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, c) -> onChange.accept(c));

        row.addView(tv);
        row.addView(sw);
        parent.addView(row);
    }

    private static void addMenuItem(Context ctx, LinearLayout parent, String label, String shortcut, final Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        TypedValue outValue = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFFF4F4F6);
        tv.setTextSize(14f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        if (!TextUtils.isEmpty(shortcut)) {
            TextView sc = new TextView(ctx);
            sc.setText(shortcut);
            sc.setTextColor("ATTIVO".equals(shortcut) ? 0xFFFB542B : 0xFF7C7C8E);
            sc.setTextSize(11f);
            if ("ATTIVO".equals(shortcut)) sc.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(sc);
        }

        row.setOnClickListener(v -> onClick.run());
        parent.addView(row);
    }

    private static void addDivider(Context ctx, LinearLayout parent) {
        View div = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.topMargin = dp(ctx, 4);
        lp.bottomMargin = dp(ctx, 4);
        div.setLayoutParams(lp);
        div.setBackgroundColor(0xFF2C2C38);
        parent.addView(div);
    }

    private static void addSectionHeader(Context ctx, LinearLayout parent, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFA0A0AE);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(ctx, 2), dp(ctx, 14), 0, dp(ctx, 4));
        parent.addView(tv);
    }

    private static TextView buildActionBtn(Context ctx, String label, boolean accent) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(accent ? 0xFF0B0B0E : 0xFFF4F4F6);
        tv.setBackground(roundedBg(accent ? 0xFFFFFFFF : 0xFF25252E, accent ? 0 : 0xFF3A3A48, 19, ctx));
        return tv;
    }

    private static void addQuickNavBtn(Context ctx, LinearLayout parent, String symbol, boolean enabled, final Runnable onClick) {
        TextView tv = new TextView(ctx);
        tv.setText(symbol);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(17f);
        tv.setTextColor("★".equals(symbol) ? 0xFFFB542B : 0xFFF4F4F6);
        tv.setAlpha(enabled ? 1f : 0.32f);
        tv.setEnabled(enabled);
        tv.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (enabled) {
            tv.setOnClickListener(v -> onClick.run());
        }
        parent.addView(tv);
    }

    private static TextView buildSmallPillBtn(Context ctx, String label) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(0xFFF4F4F6);
        tv.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        tv.setBackground(roundedBg(0xFF25252E, 0xFF3A3A48, 8, ctx));
        return tv;
    }
}
