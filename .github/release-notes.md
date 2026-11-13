# Brave Origin v1.99.57 — Windows, macOS e Android

Pacchetti di installazione di **Brave Origin v1.99.57** per **Android**, **Windows** e **macOS**.
Tutti i binari di partenza provengono dalla release ufficiale [`brave/brave-browser v1.99.57`](https://github.com/brave/brave-browser/releases/tag/v1.99.57) e vengono verificati con `sha256sum -c` contro [`installers/v1.99.57/SHA256SUMS.txt`](../installers/v1.99.57/SHA256SUMS.txt) prima della generazione e pubblicazione.

## Android — Brave Origin Ufficiale (basato su `BraveMono` v1.99.57)

| Architettura | File | Dispositivi |
|---|---|---|
| **arm64-v8a** *(consigliato)* | `BraveOrigin-arm64.apk` | Smartphone e tablet Android moderni (64-bit) |
| **armeabi-v7a** | `BraveOrigin-arm.apk` | Dispositivi ARM a 32-bit |
| **x86_64** | `BraveOrigin-x64.apk` | Chromebook, tablet Intel/AMD ed emulatori 64-bit |
| **x86** | `BraveOrigin-x86.apk` | Dispositivi ed emulatori x86 a 32-bit |

Le APK `BraveOrigin-*.apk` sono costruite direttamente sull'app Chromium ufficiale di Brave per Android (`BraveMono*.apk` `v1.99.57`), mantenendone l'interfaccia touch originale e il motore nativo completo, con:
- **Brave Origin attivo e sbloccato nativamente** fin dal primo avvio (`Local State` + `BraveOriginSubscriptionPrefs`), senza paywall né richieste di abbonamento: tutte le **16 policy ufficiali di Brave Origin** (Rewards, Wallet, Leo AI, Brave News, VPN, Talk, Tor, P3A, Stats Ping, Web Discovery, Speedreader, Playlist, Wayback Machine, Email Aliases, PSST) sono attive di default e personalizzabili in **`Impostazioni -> Brave Origin`**.
- **Aspetto monocromatico ufficiale della versione PC di Brave Origin**: icone launcher, adaptive e Material You monocromatiche nere/bianche ufficiali (`app/theme/brave_origin/`), logo di benvenuto monocromatico, icona Leone Brave monocromatica nella barra degli indirizzi e nome app **`Brave Origin`**.

> **Installazione su Android e Obtainium:** le APK `BraveOrigin-*.apk` usano il package ID indipendente **`com.tixigx.braveorigin`**, quindi **coesistono senza conflitti accanto a Brave standard (`com.brave.browser`)** e funzionano direttamente con **Obtainium** (`https://github.com/TixiGX/Brave-Origin`). Se sul telefono era ancora installata la vecchia build WebView, disinstallala una sola volta prima di installare `BraveOrigin-arm64.apk`.

## Windows

| Architettura | File | Note |
|---|---|---|
| amd64 (x86-64) | `BraveOriginStandaloneSetup.exe` | installer offline completo |
| amd64 (x86-64) | `BraveOriginSetup.exe` | installer online (scarica i componenti) |
| arm64 | `BraveOriginStandaloneSetupArm64.exe` | installer offline completo |

## macOS

| Architettura | File |
|---|---|
| Apple Silicon (arm64) | `Brave-Origin-arm64.dmg` |
| Intel (amd64) | `Brave-Origin-x64.dmg` |

## Verifica dei download

```bash
sha256sum -c BraveOrigin-Android-SHA256SUMS.txt   # APK BraveOrigin per Android
sha256sum -c SHA256SUMS.txt                       # Installer upstream ufficiali Brave
```
