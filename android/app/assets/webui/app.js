(function () {
  "use strict";

  const TOKEN = window.__BO_TOKEN || "";
  const Bridge = window.BraveOriginBridge;

  function call(method, arg1, arg2, arg3) {
    if (!Bridge || typeof Bridge[method] !== "function") return null;
    try {
      if (arg3 !== undefined) return Bridge[method](TOKEN, arg1, arg2, arg3);
      if (arg2 !== undefined) return Bridge[method](TOKEN, arg1, arg2);
      if (arg1 !== undefined) return Bridge[method](TOKEN, arg1);
      return Bridge[method](TOKEN);
    } catch (e) {
      console.error("Bridge error:", method, e);
      return null;
    }
  }

  function getState() {
    const raw = call("getFullStateJson");
    if (!raw) return {};
    try {
      return JSON.parse(raw);
    } catch (e) {
      return {};
    }
  }

  function esc(str) {
    return String(str == null ? "" : str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function showToast(msg) {
    const root = document.getElementById("toast-root");
    if (!root) return;
    root.innerHTML = '<div class="toast">' + esc(msg) + '</div>';
    clearTimeout(window.__toastTimer);
    window.__toastTimer = setTimeout(function () {
      root.innerHTML = "";
    }, 2400);
  }

  function formatBytes(bytes) {
    const b = Number(bytes || 0);
    if (b < 1024) return b + " B";
    if (b < 1048576) return (b / 1024).toFixed(1) + " KB";
    if (b < 1073741824) return (b / 1048576).toFixed(1) + " MB";
    return (b / 1073741824).toFixed(2) + " GB";
  }

  function formatTimeSaved(ms) {
    const sec = Math.round(Number(ms || 0) / 1000);
    if (sec < 60) return sec + " s";
    const min = Math.floor(sec / 60);
    if (min < 60) return min + " min";
    return (min / 60).toFixed(1) + " h";
  }

  function formatDate(ts) {
    if (!ts) return "";
    try {
      return new Date(Number(ts)).toLocaleString("it-IT", {
        day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit"
      });
    } catch (e) {
      return "";
    }
  }

  function navigateInternal(braveUrl) {
    call("openUrl", braveUrl, false);
  }

  function applyTheme(state) {
    const mode = (state.prefs && state.prefs.themeMode) || "dark";
    let effective = mode;
    if (mode === "system") {
      effective = window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
    }
    document.documentElement.setAttribute("data-theme", effective);
  }

  /* ================== ROUTE DISPATCH ================== */
  function getRoute() {
    let p = (location.pathname || "/newtab").replace(/^\/+/, "");
    if (!p) p = "newtab";
    return p;
  }

  function render() {
    const state = getState();
    applyTheme(state);
    const route = getRoute();

    if (route === "newtab") {
      document.title = "Nuova scheda - Brave Origin";
      renderNewTab(state);
    } else if (route.startsWith("settings")) {
      const sub = route.split("/")[1] || "origin";
      document.title = "Impostazioni - Brave Origin";
      renderSettingsShell(state, sub);
    } else if (route === "bookmarks") {
      document.title = "Preferiti - Brave Origin";
      renderSettingsShell(state, "bookmarks");
    } else if (route === "history") {
      document.title = "Cronologia - Brave Origin";
      renderSettingsShell(state, "history");
    } else if (route === "downloads") {
      document.title = "Download - Brave Origin";
      renderSettingsShell(state, "downloads");
    } else if (route === "adblock") {
      document.title = "Filtri contenuti - Brave Origin";
      renderSettingsShell(state, "adblock");
    } else if (route === "policy") {
      document.title = "Criteri Brave Origin - brave://policy";
      renderSettingsShell(state, "policy");
    } else if (route === "flags") {
      document.title = "Funzioni sperimentali - brave://flags";
      renderSettingsShell(state, "flags");
    } else if (route === "version") {
      document.title = "Informazioni su Brave Origin";
      renderSettingsShell(state, "version");
    } else if (route === "playlist") {
      document.title = "Brave Playlist";
      renderSettingsShell(state, "playlist");
    } else {
      renderNewTab(state);
    }
  }

  /* ================== NEW TAB PAGE (brave://newtab) ================== */
  function renderNewTab(state) {
    const prefs = state.prefs || {};
    const stats = state.stats || {};
    const sites = state.topSites || [];
    const policies = state.policies || {};
    const bgStyle = prefs.ntpBgStyle || "origin_mesh";
    const engineNames = {
      brave: "Brave Search",
      duckduckgo: "DuckDuckGo",
      startpage: "Startpage",
      qwant: "Qwant",
      ecosia: "Ecosia",
      google: "Google",
      mojeek: "Mojeek",
      custom: "Motore Custom"
    };
    const engineLabel = engineNames[prefs.searchEngine || "brave"] || "Brave Search";

    let statsHtml = "";
    if (prefs.ntpShowStats !== false) {
      statsHtml =
        '<div class="ntp-stats">' +
          '<div class="stat-item">' +
            '<span class="stat-value orange">' + Number(stats.adsBlocked || 0).toLocaleString("it-IT") + '</span>' +
            '<span class="stat-label">Tracker e annunci bloccati</span>' +
          '</div>' +
          '<div class="stat-item">' +
            '<span class="stat-value purple">' + esc(formatBytes(stats.bytesSaved || 0)) + '</span>' +
            '<span class="stat-label">Dati risparmiati</span>' +
          '</div>' +
          '<div class="stat-item">' +
            '<span class="stat-value silver">' + esc(formatTimeSaved(stats.msSaved || 0)) + '</span>' +
            '<span class="stat-label">Tempo risparmiato</span>' +
          '</div>' +
        '</div>';
    } else {
      statsHtml = '<div></div>';
    }

    let clockHtml = "";
    if (prefs.ntpShowClock !== false) {
      clockHtml =
        '<div class="ntp-clock">' +
          '<div class="ntp-clock-time" id="ntp-time">--:--</div>' +
          '<div class="ntp-clock-date" id="ntp-date"></div>' +
        '</div>';
    }

    let topSitesHtml = "";
    if (prefs.ntpShowTopSites !== false) {
      const tiles = sites.map(function (s, idx) {
        const letter = (s.title || s.url || "?").trim().charAt(0).toUpperCase();
        return (
          '<div class="site-tile" data-url="' + esc(s.url) + '">' +
            '<button class="site-remove" data-del-idx="' + idx + '" title="Rimuovi">×</button>' +
            '<div class="site-icon">' + esc(letter) + '</div>' +
            '<div class="site-title">' + esc(s.title || s.url) + '</div>' +
          '</div>'
        );
      }).join("");
      topSitesHtml =
        '<div class="top-sites-grid">' +
          tiles +
          '<div class="site-tile" id="btn-add-topsite">' +
            '<div class="site-icon">+</div>' +
            '<div class="site-title">Aggiungi sito</div>' +
          '</div>' +
        '</div>';
    }

    let newsHtml = "";
    if (policies.BraveNewsDisabled === false) {
      newsHtml =
        '<div class="card" style="width:100%;margin-top:8px;padding:16px;">' +
          '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">' +
            '<strong>Brave News (Attivato da Brave Origin)</strong>' +
            '<span class="policy-tag">BraveNewsDisabled = false</span>' +
          '</div>' +
          '<div style="color:var(--text-secondary);font-size:12.5px;">' +
            'Feed RSS privato attivo senza profilazione. Puoi disattivarlo in qualsiasi momento da <code>brave://settings/origin</code>.' +
          '</div>' +
        '</div>';
    }

    const app = document.getElementById("app");
    app.innerHTML =
      '<div class="ntp-root bg-' + esc(bgStyle) + '">' +
        '<div class="ntp-top">' + statsHtml + clockHtml + '</div>' +
        '<div class="ntp-center">' +
          '<div class="ntp-brand">' +
            '<img src="/__assets/lion.svg" alt="Brave">' +
            '<span class="origin-pill">Origin v1.99.57</span>' +
          '</div>' +
          '<form class="ntp-search-box" id="ntp-search-form">' +
            '<span class="ntp-search-engine-tag">' + esc(engineLabel) + '</span>' +
            '<input id="ntp-q" type="search" placeholder="Cerca sul Web in privato o inserisci un URL…" autocomplete="off" autocapitalize="off" spellcheck="false">' +
            '<button type="submit" class="ntp-search-btn">Cerca</button>' +
          '</form>' +
          topSitesHtml +
          newsHtml +
        '</div>' +
        '<div class="ntp-bottom">' +
          '<div class="ntp-quick-links">' +
            '<button class="ghost-pill" data-nav="brave://settings/origin">🦁 Brave Origin</button>' +
            '<button class="ghost-pill" data-nav="brave://bookmarks">★ Preferiti</button>' +
            '<button class="ghost-pill" data-nav="brave://history">🕘 Cronologia</button>' +
            '<button class="ghost-pill" data-nav="brave://downloads">⬇ Download</button>' +
            '<button class="ghost-pill" data-nav="brave://adblock">🛡️ Filtri Scudi</button>' +
          '</div>' +
          '<button class="ghost-pill" id="btn-customize-ntp">⚙ Personalizza Nuova Scheda</button>' +
        '</div>' +
      '</div>';

    updateClock();
    clearInterval(window.__clockTimer);
    window.__clockTimer = setInterval(updateClock, 10000);

    document.getElementById("ntp-search-form").addEventListener("submit", function (e) {
      e.preventDefault();
      const q = document.getElementById("ntp-q").value.trim();
      if (q) call("submitOmnibox", q);
    });

    app.querySelectorAll("[data-nav]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        navigateInternal(btn.getAttribute("data-nav"));
      });
    });

    app.querySelectorAll(".site-tile[data-url]").forEach(function (tile) {
      tile.addEventListener("click", function (e) {
        if (e.target && e.target.hasAttribute("data-del-idx")) return;
        call("openUrl", tile.getAttribute("data-url"), false);
      });
    });

    app.querySelectorAll("[data-del-idx]").forEach(function (btn) {
      btn.addEventListener("click", function (e) {
        e.stopPropagation();
        call("removeTopSite", Number(btn.getAttribute("data-del-idx")));
        render();
      });
    });

    const addBtn = document.getElementById("btn-add-topsite");
    if (addBtn) {
      addBtn.addEventListener("click", openAddTopSiteModal);
    }

    const custBtn = document.getElementById("btn-customize-ntp");
    if (custBtn) {
      custBtn.addEventListener("click", function () {
        openCustomizeNtpModal(state);
      });
    }
  }

  function updateClock() {
    const t = document.getElementById("ntp-time");
    const d = document.getElementById("ntp-date");
    if (!t) return;
    const now = new Date();
    t.textContent = now.toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" });
    if (d) {
      d.textContent = now.toLocaleDateString("it-IT", {
        weekday: "long", day: "numeric", month: "long"
      });
    }
  }

  function openAddTopSiteModal() {
    const modal = document.getElementById("modal-root");
    modal.innerHTML =
      '<div class="modal-backdrop" id="m-bg">' +
        '<div class="modal-card">' +
          '<h3 style="margin:0 0 12px 0;">Aggiungi sito rapido</h3>' +
          '<div style="display:flex;flex-direction:column;gap:10px;">' +
            '<input id="ts-title" class="text-ctrl" placeholder="Nome (es. GitHub)">' +
            '<input id="ts-url" class="text-ctrl" placeholder="URL (es. https://github.com)">' +
            '<div style="display:flex;justify-content:flex-end;gap:8px;margin-top:6px;">' +
              '<button class="btn" id="ts-cancel">Annulla</button>' +
              '<button class="btn primary" id="ts-save">Salva</button>' +
            '</div>' +
          '</div>' +
        '</div>' +
      '</div>';
    document.getElementById("ts-cancel").onclick = function () { modal.innerHTML = ""; };
    document.getElementById("ts-save").onclick = function () {
      const title = document.getElementById("ts-title").value.trim();
      let url = document.getElementById("ts-url").value.trim();
      if (!url) return;
      if (!/^[a-zA-Z]+:\/\//.test(url)) url = "https://" + url;
      call("addTopSite", title || url, url);
      modal.innerHTML = "";
      render();
    };
  }

  function openCustomizeNtpModal(state) {
    const prefs = state.prefs || {};
    const modal = document.getElementById("modal-root");
    modal.innerHTML =
      '<div class="modal-backdrop">' +
        '<div class="modal-card">' +
          '<h3 style="margin:0 0 14px 0;">Personalizza Nuova Scheda</h3>' +
          '<div class="card">' +
            rowSwitch("ntp-stats-sw", "Mostra statistiche Scudi Brave", "Tracker bloccati, dati e tempo risparmiato", prefs.ntpShowStats !== false) +
            rowSwitch("ntp-clock-sw", "Mostra orologio e data", "Orologio in alto a destra stile Desktop", prefs.ntpShowClock !== false) +
            rowSwitch("ntp-sites-sw", "Mostra siti principali", "Griglia scorciatoie rapide personalizzabili", prefs.ntpShowTopSites !== false) +
            '<div class="setting-row">' +
              '<div class="setting-info"><div class="setting-title">Sfondo Nuova Scheda</div></div>' +
              '<select id="ntp-bg-sel" class="select-ctrl">' +
                opt("origin_mesh", "Origin Grafite & Bagliore", prefs.ntpBgStyle || "origin_mesh") +
                opt("origin_dark", "Origin Scuro Classico", prefs.ntpBgStyle) +
                opt("pure_black", "OLED Nero Assoluto", prefs.ntpBgStyle) +
              '</select>' +
            '</div>' +
          '</div>' +
          '<div style="display:flex;justify-content:flex-end;margin-top:14px;">' +
            '<button class="btn primary" id="ntp-cust-done">Chiudi</button>' +
          '</div>' +
        '</div>' +
      '</div>';

    bindPrefSwitch("ntp-stats-sw", "ntpShowStats", true);
    bindPrefSwitch("ntp-clock-sw", "ntpShowClock", true);
    bindPrefSwitch("ntp-sites-sw", "ntpShowTopSites", true);
    document.getElementById("ntp-bg-sel").onchange = function (e) {
      call("setPrefString", "ntpBgStyle", e.target.value);
      render();
    };
    document.getElementById("ntp-cust-done").onclick = function () {
      modal.innerHTML = "";
      render();
    };
  }

  /* ================== DESKTOP SETTINGS & TOOLS SHELL ================== */
  function renderSettingsShell(state, section) {
    const navItems = [
      { id: "origin", url: "brave://settings/origin", label: "🦁 Brave Origin", badge: "ATTIVO" },
      { id: "shields", url: "brave://settings/shields", label: "🛡️ Scudi e Privacy Siti" },
      { id: "adblock", url: "brave://adblock", label: "📋 Filtri Contenuti (Adblock)" },
      { id: "appearance", url: "brave://settings/appearance", label: "🎨 Aspetto e Layout Desktop" },
      { id: "search", url: "brave://settings/search", label: "🔍 Motore di Ricerca" },
      { id: "privacy", url: "brave://settings/privacy", label: "🔒 Sicurezza e Dati" },
      { id: "system", url: "brave://settings/system", label: "⚡ Sistema e Memory Saver" },
      { id: "bookmarks", url: "brave://bookmarks", label: "★ Gestione Preferiti" },
      { id: "history", url: "brave://history", label: "🕘 Cronologia" },
      { id: "downloads", url: "brave://downloads", label: "⬇ Download" },
      { id: "policy", url: "brave://policy", label: "🏛️ Criteri (brave://policy)" },
      { id: "flags", url: "brave://flags", label: "🧪 Esperimenti (brave://flags)" },
      { id: "version", url: "brave://version", label: "ℹ️ Info su Brave Origin" }
    ];

    const navHtml = navItems.map(function (it) {
      const cls = "nav-item" + (it.id === section ? " active" : "");
      const badge = it.badge ? '<span class="nav-badge">' + esc(it.badge) + '</span>' : "";
      return '<button class="' + cls + '" data-route-url="' + esc(it.url) + '">' + esc(it.label) + badge + '</button>';
    }).join("");

    const app = document.getElementById("app");
    app.innerHTML =
      '<div class="webui-layout">' +
        '<header class="webui-topbar">' +
          '<div class="webui-brand">' +
            '<img src="/__assets/lion.svg" alt="Brave">' +
            '<span>Origin Desktop UI</span>' +
          '</div>' +
          '<div style="display:flex;gap:8px;align-items:center;">' +
            '<button class="btn" id="top-newtab-btn">Nuova scheda</button>' +
          '</div>' +
        '</header>' +
        '<div class="webui-body">' +
          '<nav class="webui-sidebar">' + navHtml + '</nav>' +
          '<main class="webui-content" id="webui-main"></main>' +
        '</div>' +
      '</div>';

    document.getElementById("top-newtab-btn").onclick = function () {
      navigateInternal("brave://newtab");
    };
    app.querySelectorAll("[data-route-url]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        navigateInternal(btn.getAttribute("data-route-url"));
      });
    });

    const main = document.getElementById("webui-main");
    if (section === "origin") renderOriginSection(main, state);
    else if (section === "shields") renderShieldsSection(main, state);
    else if (section === "adblock") renderAdblockSection(main, state);
    else if (section === "appearance") renderAppearanceSection(main, state);
    else if (section === "search") renderSearchSection(main, state);
    else if (section === "privacy") renderPrivacySection(main, state);
    else if (section === "system") renderSystemSection(main, state);
    else if (section === "bookmarks") renderBookmarksSection(main, state);
    else if (section === "history") renderHistorySection(main, state);
    else if (section === "downloads") renderDownloadsSection(main, state);
    else if (section === "policy") renderPolicySection(main, state);
    else if (section === "flags") renderFlagsSection(main, state);
    else if (section === "playlist") renderPlaylistSection(main, state);
    else renderVersionSection(main, state);
  }

  /* ---------- 1. BRAVE ORIGIN PANEL (brave://settings/origin) ---------- */
  function renderOriginSection(container, state) {
    const p = state.policies || {};

    const adsRows = [
      originPolicyRow("BraveRewardsDisabled", true, "Brave Rewards e Annunci Brave", "Disattiva BAT, notifiche pubblicitarie Brave e sfondi sponsorizzati")
    ];

    const analyticsRows = [
      originPolicyRow("BraveP3AEnabled", false, "Analisi nel rispetto della privacy (P3A)", "Si applica a tutti i profili del browser"),
      originPolicyRow("BraveStatsPingEnabled", false, "Invio statistiche giornaliere di utilizzo", "Si applica a tutti i profili del browser")
    ];

    const featureRows = [
      originPolicyRow("BraveAIChatEnabled", false, "Leo AI", "Assistente AI laterale nelle pagine web"),
      originPolicyRow("BraveLocalAIEnabled", false, "AI sul dispositivo (On-Device AI)", "Modelli locali e indicizzazione semantica cronologia"),
      originPolicyRow("BraveNewsDisabled", true, "Brave News", "Feed di notizie nella pagina Nuova Scheda"),
      originPolicyRow("BraveSpeedreaderEnabled", false, "Speedreader (Modalità lettura)", "Estrazione pulita degli articoli senza distrazioni nella barra indirizzi"),
      originPolicyRow("BravePlaylistEnabled", false, "Brave Playlist", "Raccolta di contenuti audio e video salvati"),
      originPolicyRow("BraveTalkDisabled", true, "Brave Talk", "Scorciatoia videochiamate private talk.brave.com"),
      originPolicyRow("TorDisabled", true, "Finestre Tor / Navigazione .onion", "Supporto collegamenti Onion e instradamento Tor"),
      originPolicyRow("BraveVPNDisabled", true, "Brave VPN", "Integrazione servizio VPN a pagamento"),
      originPolicyRow("BraveWalletDisabled", true, "Brave Wallet & Web3", "Portafoglio criptovalute e domini decentralizzati"),
      originPolicyRow("BraveWaybackMachineEnabled", false, "Wayback Machine", "Suggerisce versioni archiviate su Internet Archive quando una pagina restituisce 404"),
      originPolicyRow("EmailAliasesEnabled", false, "Alias Email", "Generazione rapida alias email nei moduli"),
      originPolicyRow("PsstEnabled", false, "Strumento ottimizzazione privacy (PSST)", "Suggerimenti automatici impostazioni privacy per sito"),
      originPolicyRow("BraveWebDiscoveryEnabled", false, "Web Discovery Project", "Contributo anonimo all'indice di Brave Search")
    ];

    function originPolicyRow(key, inverted, title, sub) {
      const rawVal = p[key];
      const featureEnabled = inverted ? (rawVal === false) : (rawVal === true);
      return (
        '<div class="setting-row">' +
          '<div class="setting-info">' +
            '<div class="setting-title">' + esc(title) + '</div>' +
            '<div class="setting-sub">' + esc(sub) + '</div>' +
            '<span class="policy-tag">' + esc(key) + ' = ' + esc(String(rawVal)) + '</span>' +
          '</div>' +
          '<label class="switch">' +
            '<input type="checkbox" data-origin-key="' + esc(key) + '" data-inverted="' + (inverted ? "1" : "0") + '"' + (featureEnabled ? " checked" : "") + '>' +
            '<span class="slider"></span>' +
          '</label>' +
        '</div>'
      );
    }

    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Brave Origin</h1>' +
        '<p class="page-desc">Controllo granulare tramite criteri nativi (identico a Brave Origin v1.99.57 Desktop).</p>' +
      '</div>' +
      '<div class="origin-hero">' +
        '<div class="origin-hero-top">' +
          '<strong>Personalizza il browser senza componenti superflui</strong>' +
          '<span class="status-active">● Edizione Origin Sbloccata</span>' +
        '</div>' +
        '<div style="color:var(--text-secondary);font-size:13px;">' +
          'Brave Origin rimuove o disattiva per impostazione predefinita tutte le funzioni commerciali, crypto, AI e telemetria, mantenendo intatti <strong>Brave Shields</strong>, il blocco annunci/tracker e la protezione anti-fingerprinting.' +
        '</div>' +
        '<div class="preset-bar">' +
          '<button class="btn primary" data-preset="origin">Preset: Origin Puro</button>' +
          '<button class="btn" data-preset="hard">Preset: Hard (Consigliato)</button>' +
          '<button class="btn" data-preset="extreme">Preset: Estremo</button>' +
          '<button class="btn" id="btn-view-policies">Vedi brave://policy</button>' +
        '</div>' +
      '</div>' +
      '<div class="section-label">Annunci</div>' +
      '<div class="card">' + adsRows.join("") + '</div>' +
      '<div class="section-label">Analisi e Telemetria</div>' +
      '<div class="card">' + analyticsRows.join("") + '</div>' +
      '<div class="section-label">Funzionalità Extra</div>' +
      '<div class="card">' + featureRows.join("") + '</div>' +
      '<div style="margin-top:18px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px;">' +
        '<button class="btn danger" id="btn-origin-reset">↺ Ripristina tutti i valori predefiniti Origin</button>' +
      '</div>';

    container.querySelectorAll("[data-origin-key]").forEach(function (inp) {
      inp.addEventListener("change", function () {
        const key = inp.getAttribute("data-origin-key");
        const inverted = inp.getAttribute("data-inverted") === "1";
        const policyVal = inverted ? !inp.checked : inp.checked;
        call("setOriginPolicy", key, policyVal);
        showToast("Criterio aggiornato: " + key);
        render();
      });
    });

    container.querySelectorAll("[data-preset]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        const preset = btn.getAttribute("data-preset");
        call("applyOriginPreset", preset);
        showToast("Profilo applicato: " + preset.toUpperCase());
        render();
      });
    });

    document.getElementById("btn-origin-reset").onclick = function () {
      call("applyOriginPreset", "origin");
      showToast("Valori predefiniti Brave Origin ripristinati");
      render();
    };
    document.getElementById("btn-view-policies").onclick = function () {
      navigateInternal("brave://policy");
    };
  }

  /* ---------- 2. SHIELDS GLOBAL SETTINGS ---------- */
  function renderShieldsSection(container, state) {
    const p = state.prefs || {};
    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Scudi Brave (Impostazioni Globali)</h1>' +
        '<p class="page-desc">Protezioni predefinite applicate a tutti i siti. Puoi personalizzarle per singolo sito toccando il Leone nella barra degli indirizzi.</p>' +
      '</div>' +
      '<div class="card">' +
        rowSwitch("sh-master", "Scudi Brave attivi per impostazione predefinita", "Protezione di rete, cosmetica e anti-tracciamento attiva", p.shieldsMaster !== false) +
        rowSelect("sh-adblock", "Blocco tracker e annunci", "Livello di filtraggio EasyList + EasyPrivacy + Brave", p.adblockLevel ?? 1, [
          [2, "Aggressivo (1ª e 3ª parte)"],
          [1, "Standard (Consigliato)"],
          [0, "Disattivato"]
        ]) +
        rowSelect("sh-https", "Aggiorna connessioni a HTTPS", "Forza HTTPS quando disponibile", p.httpsUpgrade ?? 1, [
          [2, "Rigoroso (avvisa su HTTP)"],
          [1, "Standard"],
          [0, "Disattivato"]
        ]) +
        rowSwitch("sh-scripts", "Blocca script JavaScript globalmente", "Disattiva l'esecuzione JS salvo eccezioni per sito", !!p.blockScripts) +
        rowSelect("sh-fp", "Blocco impronte digitali (Fingerprinting Farbling)", "Protegge Canvas, WebGL, AudioContext e Navigator", p.fingerprintingLevel ?? 1, [
          [2, "Rigoroso (massima randomizzazione)"],
          [1, "Standard (Farbling sessione)"],
          [0, "Disattivato"]
        ]) +
        rowSelect("sh-cookies", "Blocco cookie", "Controlla i cookie accettati dai siti web", p.cookiePolicy ?? 1, [
          [1, "Blocca cookie di terze parti"],
          [2, "Blocca tutti i cookie"],
          [0, "Consenti tutti i cookie"]
        ]) +
        rowSwitch("sh-gpc", "Global Privacy Control (Sec-GPC: 1 & Do Not Track)", "Richiede legalmente ai siti di non vendere o condividere i tuoi dati", p.sendGpc !== false) +
        rowSwitch("sh-cleanurl", "Rimozione automatica parametri traccianti (Clean URLs)", "Rimuove utm_*, fbclid, gclid, msclkid e 80+ tracker dagli URL", p.filterCleanUrls !== false) +
        rowSwitch("sh-deamp", "De-AMP automatico", "Apre direttamente l'URL canonico dell'editore evitando i wrapper AMP", p.filterDeAmp !== false) +
      '</div>' +
      '<div style="margin-top:16px;">' +
        '<button class="btn shields" id="go-adblock-lists">Gestisci Liste di Filtri & Selettori Personalizzati (brave://adblock) →</button>' +
      '</div>';

    bindPrefSwitch("sh-master", "shieldsMaster");
    bindPrefIntSelect("sh-adblock", "adblockLevel");
    bindPrefIntSelect("sh-https", "httpsUpgrade");
    bindPrefSwitch("sh-scripts", "blockScripts");
    bindPrefIntSelect("sh-fp", "fingerprintingLevel");
    bindPrefIntSelect("sh-cookies", "cookiePolicy");
    bindPrefSwitch("sh-gpc", "sendGpc");
    bindPrefSwitch("sh-cleanurl", "filterCleanUrls");
    bindPrefSwitch("sh-deamp", "filterDeAmp");
    document.getElementById("go-adblock-lists").onclick = function () {
      navigateInternal("brave://adblock");
    };
  }

  /* ---------- 3. CONTENT FILTERS (brave://adblock) ---------- */
  function renderAdblockSection(container, state) {
    const p = state.prefs || {};
    const counts = state.shieldsCounts || {};
    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Filtri Contenuti (brave://adblock)</h1>' +
        '<p class="page-desc">Motore integrato con ' + Number(counts.adDomains || 54656).toLocaleString("it-IT") + ' domini pubblicitari/tracker e migliaia di regole cosmetiche.</p>' +
      '</div>' +
      '<div class="section-label">Liste di Filtri Integrate</div>' +
      '<div class="card">' +
        rowSwitch("fl-easy", "EasyList + EasyPrivacy + Brave Specific", "Blocca network pubblicitari, tracker, CNAME cloaking e miner (" + Number(counts.adDomains || 54656).toLocaleString("it-IT") + " domini)", p.filterEasylist !== false) +
        rowSwitch("fl-cookie", "EasyList & Fanboy Cookie Notices", "Nasconde automaticamente banner GDPR/cookie e script CMP", p.filterCookies !== false) +
        rowSwitch("fl-social", "Brave Social & Anti-Widget", "Blocca pulsanti e pixel social incorporati di terze parti", p.filterSocial !== false) +
        rowSwitch("fl-ytads", "Brave Anti-YouTube Ads", "Salta e nasconde annunci pre-roll e sponsorizzazioni su YouTube", p.filterYtAds !== false) +
        rowSwitch("fl-ytshorts", "Blocca YouTube Shorts (Lista ufficiale Brave yt-shorts)", "Rimuove scaffali, schede e suggerimenti YouTube Shorts", !!p.filterYtShorts) +
        rowSwitch("fl-ytdist", "Nascondi elementi distraenti YouTube", "Rimuove schede finali, consigli correlati e merchandising", !!p.filterYtDistracting) +
      '</div>' +
      '<div class="section-label">Filtri Personalizzati (Sintassi Adblock / CSS)</div>' +
      '<div class="card" style="padding:16px;">' +
        '<div class="setting-sub" style="margin-bottom:10px;">Una regola per riga: <code>||ads.esempio.it^</code> per bloccare un dominio, <code>@@||consentito.it^</code> per eccezione, <code>##.banner-popup</code> o <code>sito.it##.box-sponsor</code> per nascondere elementi CSS.</div>' +
        '<textarea id="custom-filters-box" class="area-ctrl" placeholder="||esempio-tracker.com^\nesempio.it##.cookie-wall">' + esc(state.customFilters || "") + '</textarea>' +
        '<div style="display:flex;gap:10px;margin-top:12px;flex-wrap:wrap;">' +
          '<button class="btn primary" id="save-custom-filters">Salva regole personalizzate</button>' +
          '<button class="btn" id="clear-zapped">Ripristina elementi nascosti manualmente</button>' +
        '</div>' +
      '</div>';

    bindPrefSwitch("fl-easy", "filterEasylist");
    bindPrefSwitch("fl-cookie", "filterCookies");
    bindPrefSwitch("fl-social", "filterSocial");
    bindPrefSwitch("fl-ytads", "filterYtAds");
    bindPrefSwitch("fl-ytshorts", "filterYtShorts");
    bindPrefSwitch("fl-ytdist", "filterYtDistracting");

    document.getElementById("save-custom-filters").onclick = function () {
      const txt = document.getElementById("custom-filters-box").value;
      call("saveCustomFilters", txt);
      showToast("Filtri personalizzati salvati e compilati");
    };
    document.getElementById("clear-zapped").onclick = function () {
      call("clearZappedElements");
      showToast("Elementi bloccati manualmente ripristinati");
    };
  }

  /* ---------- 4. APPEARANCE & DESKTOP LAYOUT ---------- */
  function renderAppearanceSection(container, state) {
    const p = state.prefs || {};
    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Aspetto e Interfaccia Desktop</h1>' +
        '<p class="page-desc">Configura barra delle schede, barra dei preferiti, tema e modalità Desktop.</p>' +
      '</div>' +
      '<div class="card">' +
        rowSelectStr("ap-theme", "Tema Brave Origin", "Colori interfaccia e pagine interne brave://", p.themeMode || "dark", [
          ["dark", "Scuro (Origin Dark)"],
          ["light", "Chiaro (Origin Light)"],
          ["system", "Predefinito di sistema"]
        ]) +
        rowSwitch("ap-tabstrip", "Mostra barra delle schede stile Desktop", "Visualizza le schede aperte in alto proprio come su Windows/macOS/Linux", p.showTabStrip !== false) +
        rowSwitch("ap-bmbar", "Mostra barra dei preferiti", "Accesso rapido ai siti salvati sotto la barra degli indirizzi", p.showBookmarksBar !== false) +
        rowSwitch("ap-bottom", "Barra degli indirizzi in basso", "Sposta la barra indirizzi nella parte inferiore dello schermo per l'uso a una mano", !!p.bottomToolbar) +
        rowSwitch("ap-homebtn", "Mostra pulsante Pagina Iniziale (Home)", "Visualizza l'icona Home accanto al pulsante Ricarica", p.showHomeButton !== false) +
        '<div class="setting-row">' +
          '<div class="setting-info">' +
            '<div class="setting-title">Indirizzo pulsante Home</div>' +
            '<div class="setting-sub">Usa <code>brave://newtab</code>, <code>about:blank</code> o un URL personalizzato</div>' +
          '</div>' +
          '<div style="display:flex;gap:6px;">' +
            '<input id="ap-homeurl" class="text-ctrl" style="width:190px;" value="' + esc(p.homePageUrl || "brave://newtab") + '">' +
            '<button class="btn" id="ap-homesave">Salva</button>' +
          '</div>' +
        '</div>' +
        rowSwitch("ap-desktop", "Richiedi sempre Sito Desktop per impostazione predefinita", "Usa User-Agent Desktop (Brave/Chrome Linux x86_64) e layout desktop su tutte le nuove schede", !!p.desktopModeDefault) +
        rowSelect("ap-zoom", "Zoom pagina predefinito", "Ridimensionamento testo e layout pagine web", p.defaultZoom ?? 100, [
          [75, "75%"], [90, "90%"], [100, "100% (Predefinito)"], [110, "110%"], [125, "125%"], [150, "150%"]
        ]) +
      '</div>';

    bindPrefStrSelect("ap-theme", "themeMode", true);
    bindPrefSwitch("ap-tabstrip", "showTabStrip");
    bindPrefSwitch("ap-bmbar", "showBookmarksBar");
    bindPrefSwitch("ap-bottom", "bottomToolbar");
    bindPrefSwitch("ap-homebtn", "showHomeButton");
    bindPrefSwitch("ap-desktop", "desktopModeDefault");
    bindPrefIntSelect("ap-zoom", "defaultZoom");
    document.getElementById("ap-homesave").onclick = function () {
      const val = document.getElementById("ap-homeurl").value.trim() || "brave://newtab";
      call("setPrefString", "homePageUrl", val);
      showToast("Pagina iniziale aggiornata");
    };
  }

  /* ---------- 5. SEARCH ENGINE ---------- */
  function renderSearchSection(container, state) {
    const p = state.prefs || {};
    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Motore di Ricerca</h1>' +
        '<p class="page-desc">Motore utilizzato nella barra degli indirizzi e nella pagina Nuova Scheda, con supporto scorciatoie !bang.</p>' +
      '</div>' +
      '<div class="card">' +
        rowSelectStr("se-engine", "Motore di ricerca standard", "Brave Search è indipendente e non traccia le ricerche", p.searchEngine || "brave", [
          ["brave", "Brave Search (Predefinito)"],
          ["duckduckgo", "DuckDuckGo"],
          ["startpage", "Startpage"],
          ["qwant", "Qwant"],
          ["ecosia", "Ecosia"],
          ["mojeek", "Mojeek"],
          ["google", "Google"],
          ["custom", "URL Personalizzato"]
        ]) +
        '<div class="setting-row">' +
          '<div class="setting-info">' +
            '<div class="setting-title">URL motore personalizzato</div>' +
            '<div class="setting-sub">Inserisci <code>%s</code> al posto dei termini di ricerca</div>' +
          '</div>' +
          '<div style="display:flex;gap:6px;">' +
            '<input id="se-custom" class="text-ctrl" style="width:210px;" value="' + esc(p.customSearchUrl || "https://search.brave.com/search?q=%s") + '">' +
            '<button class="btn" id="se-save">Salva</button>' +
          '</div>' +
        '</div>' +
        rowSwitch("se-sugg", "Mostra suggerimenti locali durante la digitazione", "Suggerisce corrispondenze da Preferiti, Cronologia e pagine brave://", p.omniboxLocalSuggestions !== false) +
      '</div>' +
      '<div class="section-label">Scorciatoie Rapide (!Bangs) Attive</div>' +
      '<div class="card" style="padding:14px 18px;color:var(--text-secondary);font-size:13px;">' +
        'Digita nella barra indirizzi: <code>!b</code> Brave Search &middot; <code>!g</code> Google &middot; <code>!ddg</code> DuckDuckGo &middot; <code>!w</code> Wikipedia &middot; <code>!yt</code> YouTube &middot; <code>!gh</code> GitHub &middot; <code>!sp</code> Startpage &middot; <code>!wayback</code> Internet Archive.' +
      '</div>';

    bindPrefStrSelect("se-engine", "searchEngine");
    bindPrefSwitch("se-sugg", "omniboxLocalSuggestions");
    document.getElementById("se-save").onclick = function () {
      call("setPrefString", "customSearchUrl", document.getElementById("se-custom").value.trim());
      showToast("URL di ricerca salvato");
    };
  }

  /* ---------- 6. PRIVACY & SECURITY ---------- */
  function renderPrivacySection(container, state) {
    const p = state.prefs || {};
    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Privacy e Sicurezza</h1>' +
        '<p class="page-desc">Gestione dati di navigazione, protezione sessioni private e isolamento.</p>' +
      '</div>' +
      '<div class="card">' +
        rowSwitch("pr-clearexit", "Cancella tutti i dati di navigazione all'uscita", "Elimina cronologia, cache, cookie e WebStorage quando chiudi completamente l'app", !!p.clearOnExit) +
        rowSwitch("pr-flagsec", "Impedisci screenshot in modalità Privata (FLAG_SECURE)", "Oscura l'anteprima nelle app recenti quando è attiva una scheda Privata", p.incognitoSecure !== false) +
        rowSwitch("pr-popups", "Blocca apertura finestre popup automatiche", "Impedisce ai siti di aprire nuove schede senza un tocco esplicito", p.blockPopups !== false) +
        rowSwitch("pr-webrtc", "Protezione perdite IP WebRTC", "Limita l'esposizione di candidati IP locali nelle chiamate WebRTC", p.protectWebRtc !== false) +
      '</div>' +
      '<div class="section-label">Cancella Dati di Navigazione Ora</div>' +
      '<div class="card" style="padding:16px;display:flex;gap:10px;flex-wrap:wrap;">' +
        '<button class="btn danger" data-wipe="all">Elimina Tutto (Cronologia + Cookie + Cache)</button>' +
        '<button class="btn" data-wipe="cookies">Cancella Cookie e Storage Siti</button>' +
        '<button class="btn" data-wipe="cache">Svuota Cache Immagini/Rete</button>' +
        '<button class="btn" data-wipe="history">Cancella Solo Cronologia</button>' +
      '</div>';

    bindPrefSwitch("pr-clearexit", "clearOnExit");
    bindPrefSwitch("pr-flagsec", "incognitoSecure");
    bindPrefSwitch("pr-popups", "blockPopups");
    bindPrefSwitch("pr-webrtc", "protectWebRtc");

    container.querySelectorAll("[data-wipe]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        call("clearBrowsingData", btn.getAttribute("data-wipe"));
        showToast("Dati selezionati eliminati con successo");
      });
    });
  }

  /* ---------- 7. SYSTEM & PERFORMANCE ---------- */
  function renderSystemSection(container, state) {
    const p = state.prefs || {};
    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Sistema e Prestazioni</h1>' +
        '<p class="page-desc">Avvio sessione, Memory Saver e Strumenti per sviluppatori Desktop.</p>' +
      '</div>' +
      '<div class="card">' +
        rowSwitch("sy-mem", "Memory Saver (Risparmio memoria)", "Sospende le schede inattive in background quando molte schede sono aperte, liberando RAM", p.memorySaver !== false) +
        rowSwitch("sy-restore", "Continua da dove avevi interrotto all'avvio", "Ripristina automaticamente le schede normali aperte nella sessione precedente", p.restoreSession !== false) +
        rowSwitch("sy-devtools", "Abilita Strumenti per sviluppatori (DevTools / F12)", "Consente Console JS, Ispezione DOM, Log Scudi/Rete e Archiviazione", p.devToolsEnabled !== false) +
      '</div>' +
      '<div style="margin-top:16px;display:flex;gap:10px;flex-wrap:wrap;">' +
        '<button class="btn" id="btn-open-devtools">Apri pannello DevTools ora</button>' +
        '<button class="btn" id="btn-set-default-browser">Imposta come Browser Predefinito Android</button>' +
      '</div>';

    bindPrefSwitch("sy-mem", "memorySaver");
    bindPrefSwitch("sy-restore", "restoreSession");
    bindPrefSwitch("sy-devtools", "devToolsEnabled");
    document.getElementById("btn-open-devtools").onclick = function () {
      call("toggleDevTools");
    };
    document.getElementById("btn-set-default-browser").onclick = function () {
      call("openDefaultBrowserSettings");
    };
  }

  /* ---------- 8. BOOKMARKS (brave://bookmarks) ---------- */
  function renderBookmarksSection(container, state) {
    const list = state.bookmarks || [];
    const rows = list.length ? list.map(function (b, idx) {
      return (
        '<div class="item-row" data-bm-q="' + esc((b.title + " " + b.url).toLowerCase()) + '">' +
          '<div class="item-main" data-open="' + esc(b.url) + '">' +
            '<div class="item-title">★ ' + esc(b.title || b.url) + '</div>' +
            '<div class="item-url">' + esc(b.url) + '</div>' +
          '</div>' +
          '<button class="btn" data-newtab="' + esc(b.url) + '">Nuova scheda</button>' +
          '<button class="btn danger" data-del-bm="' + esc(b.url) + '">Elimina</button>' +
        '</div>'
      );
    }).join("") : '<div style="padding:24px;text-align:center;color:var(--text-secondary);">Nessun preferito salvato. Tocca ★ nella barra degli indirizzi o aggiungine uno qui sopra.</div>';

    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Gestione Preferiti</h1>' +
        '<p class="page-desc">Tutti i siti salvati compaiono anche nella Barra dei Preferiti Desktop.</p>' +
      '</div>' +
      '<div style="display:flex;gap:8px;margin-bottom:14px;flex-wrap:wrap;">' +
        '<input id="bm-search" class="text-ctrl" style="flex:1;min-width:180px;" placeholder="Cerca nei preferiti…">' +
        '<button class="btn primary" id="bm-add-new">+ Aggiungi preferito</button>' +
      '</div>' +
      '<div class="card" id="bm-list">' + rows + '</div>';

    document.getElementById("bm-search").oninput = function (e) {
      const q = e.target.value.toLowerCase();
      container.querySelectorAll("[data-bm-q]").forEach(function (el) {
        el.style.display = el.getAttribute("data-bm-q").indexOf(q) >= 0 ? "flex" : "none";
      });
    };
    document.getElementById("bm-add-new").onclick = function () {
      const modal = document.getElementById("modal-root");
      modal.innerHTML =
        '<div class="modal-backdrop"><div class="modal-card">' +
          '<h3 style="margin:0 0 12px 0;">Nuovo preferito</h3>' +
          '<div style="display:flex;flex-direction:column;gap:10px;">' +
            '<input id="nbm-t" class="text-ctrl" placeholder="Titolo">' +
            '<input id="nbm-u" class="text-ctrl" placeholder="https://esempio.it">' +
            '<div style="display:flex;justify-content:flex-end;gap:8px;">' +
              '<button class="btn" id="nbm-c">Annulla</button>' +
              '<button class="btn primary" id="nbm-s">Aggiungi</button>' +
            '</div>' +
          '</div>' +
        '</div></div>';
      document.getElementById("nbm-c").onclick = function () { modal.innerHTML = ""; };
      document.getElementById("nbm-s").onclick = function () {
        let u = document.getElementById("nbm-u").value.trim();
        const t = document.getElementById("nbm-t").value.trim();
        if (!u) return;
        if (!/^[a-zA-Z]+:\/\//.test(u)) u = "https://" + u;
        call("addBookmark", t || u, u);
        modal.innerHTML = "";
        render();
      };
    };
    bindItemListActions(container, "removeBookmark", "data-del-bm");
  }

  /* ---------- 9. HISTORY (brave://history) ---------- */
  function renderHistorySection(container, state) {
    const list = state.history || [];
    const rows = list.length ? list.map(function (h, idx) {
      return (
        '<div class="item-row" data-hi-q="' + esc((h.title + " " + h.url).toLowerCase()) + '">' +
          '<div class="item-main" data-open="' + esc(h.url) + '">' +
            '<div class="item-title">' + esc(h.title || h.url) + '</div>' +
            '<div class="item-url">' + esc(formatDate(h.time)) + ' &middot; ' + esc(h.url) + '</div>' +
          '</div>' +
          '<button class="btn danger" data-del-hi="' + idx + '">×</button>' +
        '</div>'
      );
    }).join("") : '<div style="padding:24px;text-align:center;color:var(--text-secondary);">La cronologia di navigazione è vuota. Le schede Private non vengono mai registrate.</div>';

    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Cronologia</h1>' +
        '<p class="page-desc">Cronologia memorizzata solo localmente sul tuo dispositivo.</p>' +
      '</div>' +
      '<div style="display:flex;gap:8px;margin-bottom:14px;flex-wrap:wrap;">' +
        '<input id="hi-search" class="text-ctrl" style="flex:1;min-width:180px;" placeholder="Cerca nella cronologia…">' +
        '<button class="btn danger" id="hi-clear-all">Cancella cronologia</button>' +
      '</div>' +
      '<div class="card">' + rows + '</div>';

    document.getElementById("hi-search").oninput = function (e) {
      const q = e.target.value.toLowerCase();
      container.querySelectorAll("[data-hi-q]").forEach(function (el) {
        el.style.display = el.getAttribute("data-hi-q").indexOf(q) >= 0 ? "flex" : "none";
      });
    };
    document.getElementById("hi-clear-all").onclick = function () {
      call("clearBrowsingData", "history");
      showToast("Cronologia cancellata");
      render();
    };
    container.querySelectorAll("[data-open]").forEach(function (el) {
      el.onclick = function () { call("openUrl", el.getAttribute("data-open"), false); };
    });
    container.querySelectorAll("[data-del-hi]").forEach(function (btn) {
      btn.onclick = function () {
        call("removeHistoryAt", Number(btn.getAttribute("data-del-hi")));
        render();
      };
    });
  }

  /* ---------- 10. DOWNLOADS (brave://downloads) ---------- */
  function renderDownloadsSection(container, state) {
    const list = state.downloads || [];
    const rows = list.length ? list.map(function (d) {
      return (
        '<div class="item-row">' +
          '<div class="item-main" data-open="' + esc(d.url) + '">' +
            '<div class="item-title">⬇ ' + esc(d.fileName || "File scaricato") + '</div>' +
            '<div class="item-url">' + esc(formatDate(d.time)) + ' &middot; ' + esc(d.mimeType || "file") + ' &middot; ' + esc(d.url) + '</div>' +
          '</div>' +
        '</div>'
      );
    }).join("") : '<div style="padding:24px;text-align:center;color:var(--text-secondary);">Nessun download recente.</div>';

    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Download</h1>' +
        '<p class="page-desc">File scaricati tramite il gestore di download di sistema.</p>' +
      '</div>' +
      '<div style="display:flex;gap:8px;margin-bottom:14px;flex-wrap:wrap;">' +
        '<button class="btn primary" id="dl-open-sys">Apri cartella Download di sistema</button>' +
        '<button class="btn danger" id="dl-clear">Svuota registro</button>' +
      '</div>' +
      '<div class="card">' + rows + '</div>';

    document.getElementById("dl-open-sys").onclick = function () { call("openSystemDownloads"); };
    document.getElementById("dl-clear").onclick = function () {
      call("clearDownloadsList");
      render();
    };
    container.querySelectorAll("[data-open]").forEach(function (el) {
      el.onclick = function () { call("openUrl", el.getAttribute("data-open"), false); };
    });
  }

  /* ---------- 11. POLICIES (brave://policy) ---------- */
  function renderPolicySection(container, state) {
    const p = state.policies || {};
    const keys = Object.keys(p).sort();
    const rows = keys.map(function (k) {
      return (
        '<tr>' +
          '<td class="mono">' + esc(k) + '</td>' +
          '<td class="mono"><strong>' + esc(String(p[k])) + '</strong></td>' +
          '<td>Brave Origin</td>' +
          '<td><span style="color:var(--success);">Attivo</span></td>' +
        '</tr>'
      );
    }).join("");

    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Criteri Attivi (brave://policy)</h1>' +
        '<p class="page-desc">Stato in tempo reale del motore BraveOriginPolicyManager.</p>' +
      '</div>' +
      '<div class="card">' +
        '<table class="policy-table">' +
          '<thead><tr><th>Nome Criterio</th><th>Valore</th><th>Origine</th><th>Stato</th></tr></thead>' +
          '<tbody>' + rows + '</tbody>' +
        '</table>' +
      '</div>';
  }

  /* ---------- 12. FLAGS (brave://flags) ---------- */
  function renderFlagsSection(container, state) {
    const p = state.prefs || {};
    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Funzioni Sperimentali (brave://flags)</h1>' +
        '<p class="page-desc">Impostazioni avanzate del motore di rendering e della privacy.</p>' +
      '</div>' +
      '<div class="card">' +
        rowSwitch("fg-darkweb", "#brave-force-dark-web-contents", "Applica tema scuro algoritmico ai siti web chiari quando il tema Scuro è attivo", !!p.forceDarkWeb) +
        rowSwitch("fg-farble", "#brave-farbling-fingerprinting", "Inietta rumore deterministico di sessione su Canvas, AudioContext e WebGL", p.fingerprintingLevel !== 0) +
        rowSwitch("fg-clean", "#brave-query-filter", "Rimozione automatica dei parametri di tracciamento URL durante la navigazione", p.filterCleanUrls !== false) +
        rowSwitch("fg-deamp", "#brave-de-amp", "Riscrittura immediata dei link AMP verso l'editore originale", p.filterDeAmp !== false) +
        rowSwitch("fg-smooth", "#smooth-scrolling", "Scorrimento fluido accelerato via hardware", p.smoothScroll !== false) +
      '</div>';

    bindPrefSwitch("fg-darkweb", "forceDarkWeb");
    bindPrefSwitch("fg-clean", "filterCleanUrls");
    bindPrefSwitch("fg-deamp", "filterDeAmp");
    bindPrefSwitch("fg-smooth", "smoothScroll");
    const fb = document.getElementById("fg-farble");
    if (fb) {
      fb.onchange = function () {
        call("setPrefInt", "fingerprintingLevel", fb.checked ? 1 : 0);
        showToast("Flag aggiornato");
      };
    }
  }

  /* ---------- 13. PLAYLIST (brave://playlist) ---------- */
  function renderPlaylistSection(container, state) {
    const items = state.playlist || [];
    const rows = items.length ? items.map(function (it, idx) {
      return (
        '<div class="item-row">' +
          '<div class="item-main" data-open="' + esc(it.url) + '">' +
            '<div class="item-title">▶ ' + esc(it.title || it.url) + '</div>' +
            '<div class="item-url">' + esc(it.url) + '</div>' +
          '</div>' +
          '<button class="btn danger" data-del-pl="' + idx + '">Rimuovi</button>' +
        '</div>'
      );
    }).join("") : '<div style="padding:24px;text-align:center;color:var(--text-secondary);">Nessun elemento salvato in Playlist.</div>';

    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Brave Playlist</h1>' +
        '<p class="page-desc"> Pagine audio/video salvate per la riproduzione rapida.</p>' +
      '</div>' +
      '<div class="card">' + rows + '</div>';

    container.querySelectorAll("[data-open]").forEach(function (el) {
      el.onclick = function () { call("openUrl", el.getAttribute("data-open"), false); };
    });
    container.querySelectorAll("[data-del-pl]").forEach(function (btn) {
      btn.onclick = function () {
        call("removePlaylistAt", Number(btn.getAttribute("data-del-pl")));
        render();
      };
    });
  }

  /* ---------- 14. VERSION (brave://version) ---------- */
  function renderVersionSection(container, state) {
    const v = state.version || {};
    const counts = state.shieldsCounts || {};
    container.innerHTML =
      '<div class="page-header">' +
        '<h1 class="page-title">Informazioni su Brave Origin</h1>' +
        '<p class="page-desc">Dettagli diagnostici build e motore di sistema.</p>' +
      '</div>' +
      '<div class="card">' +
        infoRow("Brave Origin", "1.99.57 (Build ufficiale Universal Android — 32/64 bit)") +
        infoRow("Pacchetto", esc(v.packageName || "com.tixigx.braveorigin")) +
        infoRow("Sistema Operativo", esc(v.osInfo || "Android")) +
        infoRow("Motore WebKit / Blink", esc(v.webViewVersion || "System Chromium WebView")) +
        infoRow("User-Agent Attivo", '<span class="mono" style="font-size:11.5px;word-break:break-all;">' + esc(v.userAgent || navigator.userAgent) + '</span>') +
        infoRow("Motore Scudi Brave", esc(Number(counts.adDomains || 54656).toLocaleString("it-IT") + " domini + " + Number(counts.cookieDomains || 179) + " CMP + 4.000+ selettori cosmetici")) +
        infoRow("Stato Criteri Origin", "Attivi (Zero telemetria, Zero Ads, Zero Crypto/Bloat)") +
      '</div>';

    function infoRow(label, htmlVal) {
      return (
        '<div class="setting-row">' +
          '<div class="setting-info" style="max-width:200px;"><div class="setting-title">' + esc(label) + '</div></div>' +
          '<div style="flex:1;color:var(--text-secondary);text-align:right;">' + htmlVal + '</div>' +
        '</div>'
      );
    }
  }

  /* ---------- UI HELPERS ---------- */
  function rowSwitch(id, title, sub, checked) {
    return (
      '<div class="setting-row">' +
        '<div class="setting-info">' +
          '<div class="setting-title">' + esc(title) + '</div>' +
          (sub ? '<div class="setting-sub">' + esc(sub) + '</div>' : '') +
        '</div>' +
        '<label class="switch">' +
          '<input type="checkbox" id="' + esc(id) + '"' + (checked ? " checked" : "") + '>' +
          '<span class="slider"></span>' +
        '</label>' +
      '</div>'
    );
  }

  function opt(val, label, current) {
    return '<option value="' + esc(val) + '"' + (String(val) === String(current) ? " selected" : "") + '>' + esc(label) + '</option>';
  }

  function rowSelect(id, title, sub, current, options) {
    return (
      '<div class="setting-row">' +
        '<div class="setting-info">' +
          '<div class="setting-title">' + esc(title) + '</div>' +
          (sub ? '<div class="setting-sub">' + esc(sub) + '</div>' : '') +
        '</div>' +
        '<select id="' + esc(id) + '" class="select-ctrl">' +
          options.map(function (o) { return opt(o[0], o[1], current); }).join("") +
        '</select>' +
      '</div>'
    );
  }

  function rowSelectStr(id, title, sub, current, options) {
    return rowSelect(id, title, sub, current, options);
  }

  function bindPrefSwitch(id, key, rerender) {
    const el = document.getElementById(id);
    if (!el) return;
    el.addEventListener("change", function () {
      call("setPrefBoolean", key, el.checked);
      showToast("Impostazione salvata");
      if (rerender) render();
    });
  }

  function bindPrefIntSelect(id, key) {
    const el = document.getElementById(id);
    if (!el) return;
    el.addEventListener("change", function () {
      call("setPrefInt", key, parseInt(el.value, 10));
      showToast("Impostazione salvata");
    });
  }

  function bindPrefStrSelect(id, key, rerender) {
    const el = document.getElementById(id);
    if (!el) return;
    el.addEventListener("change", function () {
      call("setPrefString", key, el.value);
      showToast("Impostazione salvata");
      if (rerender) render();
    });
  }

  function bindItemListActions(container, delMethod, delAttr) {
    container.querySelectorAll("[data-open]").forEach(function (el) {
      el.onclick = function () { call("openUrl", el.getAttribute("data-open"), false); };
    });
    container.querySelectorAll("[data-newtab]").forEach(function (btn) {
      btn.onclick = function () { call("openUrl", btn.getAttribute("data-newtab"), true); };
    });
    container.querySelectorAll("[" + delAttr + "]").forEach(function (btn) {
      btn.onclick = function () {
        call(delMethod, btn.getAttribute(delAttr));
        render();
      };
    });
  }

  window.__braveOriginRefresh = render;
  render();
})();
