package com.tixigx.braveorigin;

import android.graphics.Bitmap;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BrowserTab {

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    public final int id;
    public final boolean isPrivate;
    public boolean pinned;
    public boolean desktopMode;
    public boolean audioMuted;
    public int zoomPercent = 100;
    public boolean hibernated = false;

    public String url;
    public String title;
    public Bitmap favicon;
    public WebView webView;
    public long lastActiveTime;

    private final AtomicInteger blockedCount = new AtomicInteger(0);
    private final List<String> blockedLog = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> consoleLog = Collections.synchronizedList(new ArrayList<String>());

    public BrowserTab(boolean isPrivate, String initialUrl, boolean desktopMode, int defaultZoom) {
        this.id = NEXT_ID.getAndIncrement();
        this.isPrivate = isPrivate;
        this.url = initialUrl != null ? initialUrl : "brave://newtab";
        this.title = isPrivate ? "Nuova scheda privata" : "Nuova scheda";
        this.desktopMode = desktopMode;
        this.zoomPercent = defaultZoom;
        this.lastActiveTime = System.currentTimeMillis();
    }

    public void resetPageStats() {
        blockedCount.set(0);
        blockedLog.clear();
    }

    public int incrementBlocked(String category, String host) {
        int c = blockedCount.incrementAndGet();
        if (blockedLog.size() < 120) {
            blockedLog.add("[" + category + "] " + host);
        }
        return c;
    }

    public int getBlockedCount() {
        return blockedCount.get();
    }

    public List<String> getBlockedLogSnapshot() {
        synchronized (blockedLog) {
            return new ArrayList<>(blockedLog);
        }
    }

    public void appendConsole(String level, String message, int line, String source) {
        if (consoleLog.size() >= 200) {
            consoleLog.remove(0);
        }
        consoleLog.add("[" + level + "] " + message + (source != null && !source.isEmpty() ? " (" + source + ":" + line + ")" : ""));
    }

    public List<String> getConsoleSnapshot() {
        synchronized (consoleLog) {
            return new ArrayList<>(consoleLog);
        }
    }

    public void clearConsole() {
        consoleLog.clear();
    }
}
