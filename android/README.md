# Brave Origin per Android (Desktop-Parity Edition)

Applicazione Android completa **Brave Origin v1.99.57**, riprogettata da zero per replicare
l'aspetto, l'interfaccia Nala Dark monocromatica e tutte le funzioni della versione **Desktop di Brave Origin**.

- **Package**: `com.tixigx.braveorigin`
- **Versione**: `1.99.57` (`versionCode` `19957`)
- **Compatibilità**: Android 7.0+ (`minSdkVersion 24`, `targetSdkVersion 34`)
- **APK universale firmato (v2 + v3)**: [`BraveOrigin-1.99.57-universal.apk`](BraveOrigin-1.99.57-universal.apk) — funziona su tutte le architetture (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`)

## Funzionalità incluse (parità con Brave Origin Desktop)

1. **Interfaccia Desktop Nala Monocromatica**:
   - **Tab Strip superiore in stile Desktop** con schede scorrevoli, schede fissate (*pin*), disattivazione audio per scheda, duplicazione, riapertura scheda chiusa (`Ctrl+Shift+T`) e **Finestre Private (Incognito)** isolate con `FLAG_SECURE`.
   - **Omnibox completa** con indicatore SSL/sicurezza sito, suggerimenti istantanei da Preferiti e Cronologia, scorciatoie `!bang` (`!b`, `!g`, `!ddg`, `!sp`, `!w`, `!gh`, `!yt`), pulsante Speedreader, stella Preferiti, contatore **Brave Shields** in tempo reale e selettore schede.
   - **Barra dei Preferiti** sotto l'Omnibox e opzione **Barra degli indirizzi in basso** per uso a una mano.
   - Supporto completo ** scorciatoie da tastiera Desktop** (`Ctrl+T`, `Ctrl+W`, `Ctrl+Shift+T`, `Ctrl+Shift+N`, `Ctrl+L`, `Ctrl+F`, `Ctrl+R`, `Ctrl+D`, `Ctrl+H`, `Ctrl+J`, `Ctrl+P`, `F12`).

2. **Brave Shields Reale (Rete + Cosmetico + Anti-Fingerprinting)**:
   - Oltre **54.600 domini pubblicitari e tracker** compilati da *EasyList*, *EasyPrivacy* e *Brave Adblock Lists* (`tools/build_shields_lists.py`).
   - **Filtraggio cosmetico CSS** (> 4.000 selettori annunci e cookie banner), **Blocco YouTube Shorts** e rimozione distrazioni YouTube, ad-skipper automatico e selettore interattivo **Blocca elemento sulla pagina** (*Element Picker*).
   - **Clean URLs** (rimozione automatica di 84 parametri traccianti `utm_*`, `fbclid`, `gclid`, `mc_eid`, ecc. + **Copia link pulito**), **De-AMP** automatico e **Upgrade HTTPS** (*Standard* o *Restrittivo*).
   - **Protezione impronta digitale (Farbling)** per Canvas, WebGL, AudioContext e HardwareConcurrency, header `Global Privacy Control (Sec-GPC: 1)` e protezione WebRTC IP leak.
   - Configurazione granulare globale e **per singolo sito** tramite il pannello nativo del Leone Brave.

3. **Pannello `brave://settings/origin` & Tutte le Pagine `brave://` Desktop**:
   - `brave://newtab` — Dashboard Nuova Scheda con statistiche Shields (tracker bloccati, banda e tempo risparmiati), Top Sites personalizzabili e ricerca rapida.
   - `brave://settings/origin` — Tutte le **16 policy ufficiali Brave Origin** (*Brave Rewards, Leo AI, AI Locale, Brave Wallet, Brave VPN, Brave News, Brave Talk, Tor, P3A, Stats Ping, Speedreader, Playlist, Wayback Machine, Email Aliases, PSST, Web Discovery*) disattivate di default e attivabili singolarmente o tramite preset (*Origin Puro*, *Bilanciato*, *Completo*).
   - `brave://settings/shields` e `brave://shields/filters` — Liste filtri regionali e regole personalizzate in sintassi Adblock/uBlock (`||dominio.com^`, `##.selettore`).
   - `brave://settings/privacy`, `brave://settings/search`, `brave://settings/appearance`, `brave://settings/system` (con **Risparmio Memoria** per schede inattive).
   - `brave://bookmarks` (con import/export JSON), `brave://history`, `brave://downloads`, `brave://playlist`, `brave://flags`, `brave://policy` (esportabile in `policies.json` compatibile con Brave Desktop) e `brave://version`.

4. **Strumenti Avanzati Integrati**:
   - **DevTools integrato (`F12`)**: Console JS interattiva (REPL), ispettore DOM/HTML, registro richieste bloccate da Shields e visualizzatore Storage/Cookie.
   - **Trova nella pagina**, **Speedreader** (modalità lettura pulita), **Zoom testo** (50%–200%), **Modalità Sito Desktop**, **Stampa / Esporta PDF** e **Salva pagina offline (`.mht`)**.

## Compilazione

Richiede JDK 17 e Android SDK (`build-tools;34.0.0` e `platforms;android-34`):

```bash
# Opzionale: ricompila le liste Brave Shields dai sorgenti upstream
python3 tools/build_shields_lists.py /percorso/sorgenti-filtri

# Compila, allinea e firma l'APK universale (senza Gradle)
ANDROID_HOME=$HOME/android-sdk JAVA_HOME=$HOME/jdk-17 ./build.sh
```

## Installazione

```bash
adb install -r BraveOrigin-1.99.57-universal.apk
```
oppure copia `BraveOrigin-1.99.57-universal.apk` sul dispositivo Android e aprilo per installarlo.
