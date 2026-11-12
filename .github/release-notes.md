# Brave Origin v1.99.57 — Windows, macOS e Android

Pacchetti di installazione di **Brave Origin v1.99.57**, presi dalla release ufficiale
[`brave/brave-browser`](https://github.com/brave/brave-browser/releases/tag/v1.99.57) e
verificati uno per uno con i checksum SHA-256 pubblicati da Brave: la release della action
esegue `sha256sum -c` contro [`installers/v1.99.57/SHA256SUMS.txt`](../installers/v1.99.57/SHA256SUMS.txt)
prima di caricare i file, e lo stesso file dei checksum è allegato qui come `SHA256SUMS.txt`.

## Windows

| Architettura | File | Note |
|---|---|---|
| amd64 (x86-64) | `BraveOriginStandaloneSetup.exe` | installer offline completo |
| amd64 (x86-64) | `BraveOriginSetup.exe` | installer online (scarica i componenti) |
| arm64 | `BraveOriginStandaloneSetupArm64.exe` | installer offline completo |

## macOS

| Architettura | File |
|---|---|
| Intel (amd64) | `Brave-Origin-x64.dmg` |
| Apple Silicon (arm64) | `Brave-Origin-arm64.dmg` |

## Android

| Architettura | File |
|---|---|
| arm64-v8a | `BraveMonoarm64.apk` |
| armeabi-v7a | `BraveMonoarm.apk` |
| x86_64 | `BraveMonox64.apk` |
| x86 | `BraveMonox86.apk` |
| universale (tutte le ABI) | `BraveOrigin-1.99.57-universal.apk` |

Le prime quattro sono le APK ufficiali Brave (Brave Origin non ha una build Android
distribuita da Brave). `BraveOrigin-1.99.57-universal.apk` è invece l'app completa **Brave Origin per
Android** inclusa in questo repository: interfaccia e funzionalità equivalenti a Brave Origin Desktop
(schede in stile Desktop, Brave Shields con blocco annunci/tracker/cookie/impronta digitale,
pannello `brave://settings/origin` con tutte le 16 policy ufficiali, `brave://flags`, `brave://policy`,
Preferiti, Cronologia, Download, DevTools integrato e icona ufficiale monocromatica). Sorgenti in [`android/`](../android/).

## Verifica dei download

```bash
sha256sum -c SHA256SUMS.txt          # Linux
shasum -a 256 -c SHA256SUMS.txt      # macOS
certutil -hashfile <file> SHA256     # Windows
```

Gli hash corrispondono ai file `.sha256` ufficiali della release Brave v1.99.57.

## Installazione dell'app Android del repository

```bash
adb install -r BraveOrigin-1.99.57-universal.apk
```

oppure copia l'APK sul telefono e consenti l'installazione da origini sconosciute.
