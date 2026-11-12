# Brave Origin per Android

App Android minimale con il branding **Brave Origin**: barra degli indirizzi, ricerca
Brave, navigazione avanti/indietro/ricarica e icona ufficiale.

- Package: `com.tixigx.braveorigin`
- Versione: `1.0.0` (versionCode 1)
- Min SDK 24 (Android 7.0), target SDK 34
- APK universale firmato: [`BraveOrigin-1.0.0-universal.apk`](BraveOrigin-1.0.0-universal.apk) — nessuna libreria nativa, gira su arm64-v8a, armeabi-v7a, x86 e x86_64

## Cosa è (e cosa non è)

È un **browser leggero basato sulla WebView di sistema** con l'interfaccia e l'icona di
Brave Origin: non contiene il motore Chromium di Brave né Shields/adblock, perché un fork
completo di Brave per Android richiede l'infrastruttura di build Chromium (depot_tools,
~100 GB e ore di compilazione). L'APK ufficiale Brave per Android resta in
[`../installers/v1.99.57/android/`](../installers/v1.99.57/android/).

## Icona

L'icona è il logo **ufficiale** di Brave Origin, cioè `product_logo_256.png` contenuto nel
pacchetto `brave-origin-1.99.57-linux-amd64.zip` della release Brave: l'originale è copiato
in [`tools/product_logo_256.png`](tools/product_logo_256.png). Da lì sono state generate:

- icone legacy `mipmap-{mdpi..xxxhdpi}/ic_launcher.png` e `ic_launcher_round.png`
- icona adattiva (Android 8+) con livello `monochrome` per le icone a tema (Android 13+)
- anteprima delle varianti in [`tools/icon-preview.png`](tools/icon-preview.png)

Per rigenerarle: `python3 tools/make_icons.py tools/product_logo_256.png`

## Build

Serve JDK 17 e Android SDK con `build-tools;34.0.0` e `platforms;android-34`:

```bash
ANDROID_HOME=$HOME/android-sdk JAVA_HOME=$HOME/tools/jdk-17.0.21+9 ./build.sh
```

Lo script usa `aapt2` → `javac` → `d8` → `zipalign` → `apksigner` (nessun Gradle).
La prima build crea il keystore `keystore/brave-origin.jks` (alias `braveorigin`,
password `braveorigin`): va conservato per poter firmare gli aggiornamenti futuri.

## Installazione

1. Copia l'APK sul telefono (o `adb install -r BraveOrigin-1.0.0-universal.apk`).
2. Consenti l'installazione da origini sconosciute quando richiesto.
3. L'app compare nel launcher come **Brave Origin** con il leone bianco su fondo scuro.
