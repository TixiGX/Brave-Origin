# Installer — Brave Origin v1.99.57

Pacchetti ufficiali di **Brave Origin** per Desktop e build **Brave Origin per Android** basate sull'APK Chromium ufficiale di Brave (`BraveMono*.apk` `v1.99.57`), verificati con i checksum SHA-256 pubblicati da Brave.

## Contenuto della Release

### Android — Brave Origin (sbloccato + grafica monocromatica PC)

| Architettura | File | Base upstream verificata |
|---|---|---|
| `arm64-v8a` (64-bit ARM, consigliato) | `BraveOrigin-arm64.apk` | `BraveMonoarm64.apk` |
| `armeabi-v7a` (32-bit ARM) | `BraveOrigin-arm.apk` | `BraveMonoarm.apk` |
| `x86_64` (64-bit Intel/AMD) | `BraveOrigin-x64.apk` | `BraveMonox64.apk` |
| `x86` (32-bit Intel) | `BraveOrigin-x86.apk` | `BraveMonox86.apk` |

Ogni `BraveOrigin-*.apk` è l'applicazione ufficiale Brave Android (`v1.99.57`) con:
- **Brave Origin attivo nativamente** al primo avvio (tutte le 16 policy anti-bloat/privacy attive e pannello `Impostazioni -> Brave Origin` sbloccato senza paywall).
- **Branding monocromatico ufficiale PC** (`app/theme/brave_origin/` di `brave-core`: icone launcher/adaptive/monochrome, loghi di benvenuto e Leone Brave monocromatico nella barra indirizzi) e nome **`Brave Origin`**.

### Desktop — Installer ufficiali Brave Origin

| Sistema | Architettura | File | Dimensione |
|---|---|---|---|
| Windows | amd64 (x86-64) | `BraveOriginStandaloneSetup.exe` — installer offline completo | 140 MB |
| Windows | amd64 (x86-64) | `BraveOriginSetup.exe` — installer online | 1,2 MB |
| Windows | arm64 | `BraveOriginStandaloneSetupArm64.exe` — installer offline completo | 145 MB |
| macOS | amd64 (Intel) | `Brave-Origin-x64.dmg` | 182 MB |
| macOS | arm64 (Apple Silicon) | `Brave-Origin-arm64.dmg` | 163 MB |

## Verifica di integrità

```bash
sha256sum -c SHA256SUMS.txt                      # File upstream ufficiali Brave
sha256sum -c BraveOrigin-Android-SHA256SUMS.txt  # APK BraveOrigin-* generate e verificate
```
