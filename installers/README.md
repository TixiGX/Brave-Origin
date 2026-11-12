# Installer — Brave Origin v1.99.57

Pacchetti ufficiali di **Brave Origin**, scaricati dalla release GitHub
[`brave/brave-browser v1.99.57`](https://github.com/brave/brave-browser/releases/tag/v1.99.57)
e verificati uno per uno con i checksum SHA-256 pubblicati da Brave.

## Contenuto

| Sistema | Architettura | File | Dimensione |
|---|---|---|---|
| Windows | amd64 (x86-64) | `windows/amd64/BraveOriginStandaloneSetup.exe` — installer offline completo | 140 MB |
| Windows | amd64 (x86-64) | `windows/amd64/BraveOriginSetup.exe` — installer online (scarica i componenti) | 1,2 MB |
| Windows | arm64 | `windows/arm64/BraveOriginStandaloneSetupArm64.exe` — installer offline completo | 145 MB |
| macOS | amd64 (Intel) | `macos/amd64/Brave-Origin-x64.dmg` | 182 MB |
| macOS | arm64 (Apple Silicon) | `macos/arm64/Brave-Origin-arm64.dmg` | 163 MB |
| Android | arm64-v8a | `android/arm64-v8a/BraveMonoarm64.apk` | 349 MB |
| Android | armeabi-v7a (32-bit ARM) | `android/armeabi-v7a/BraveMonoarm.apk` | 221 MB |
| Android | x86_64 | `android/x86_64/BraveMonox64.apk` | 385 MB |
| Android | x86 (32-bit) | `android/x86/BraveMonox86.apk` | 296 MB |

Fonte e data: release `v1.99.57` di Brave (novembre 2026). Gli installer Windows sono i
pacchetti "standalone", cioè completi e installabili senza connessione; `BraveOriginSetup.exe`
è invece lo stub online che scarica i componenti al primo avvio (solo amd64).

## Note

- Brave Origin è distribuito da Brave solo per desktop: le APK Android sono le build
  ufficiali Brave (`BraveMono*`), una per ogni ABI.
- Verifica di integrità, dalla cartella `v1.99.57`:

  ```bash
  sha256sum -c SHA256SUMS.txt        # Linux
  shasum -a 256 -c SHA256SUMS.txt    # macOS
  certutil -hashfile <file> SHA256   # Windows
  ```

  Gli hash corrispondono ai file `.sha256` ufficiali della release Brave.

## Distribuzione

I file superano il limite di GitHub di 100 MB per file, quindi non possono essere
versionati nel tree git: vanno pubblicati come **asset di una GitHub Release**
(limite 2 GB per asset) o su un archivio esterno.
