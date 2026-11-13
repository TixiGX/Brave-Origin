# Brave Origin (`v1.99.57`) — Desktop & Android Ufficiale

Questo repository raccoglie gli installer ufficiali **Brave Origin** per **Windows** e **macOS** e genera le build **Brave Origin per Android** direttamente a partire dall'app Chromium ufficiale di Brave (`BraveMono*.apk` `v1.99.57`), integrando:

1. **Motore e interfaccia ufficiale Brave Android (`BraveMono`)**: il vero browser Chromium di Brave con interfaccia touch nativa, Brave Shields completo in C++, sincronizzazione, estensioni/componenti nativi e prestazioni originali.
2. **Sblocco e attivazione nativa di Brave Origin (zero paywall)**:
   - Inizializzazione automatica tramite `BraveOriginInitProvider` prima del caricamento di `libmonochrome` (`Local State`: `brave.origin.purchase_validated = true`, `brave.origin.policies_were_enforced = true`, `p3a.enabled = false`).
   - Attivazione immediata di tutte le **16 policy ufficiali Brave Origin** gestite da `BraveBrowserPolicyProvider` e `BraveProfilePolicyProvider` (disattivazione di Brave Rewards, Brave Wallet, Leo AI, Brave News, Brave VPN, Brave Talk, Tor, P3A, Stats Ping, Web Discovery, Speedreader, Playlist, Wayback Machine, Email Aliases e PSST).
   - Accesso diretto a **`Impostazioni -> Brave Origin`** (`BraveOriginPreferences`) senza schermate di abbonamento Google Play Billing o chiamate di rete SKU, con possibilità di riattivare singolarmente qualsiasi funzione desiderata.
3. **Estetica monocromatica ufficiale della versione PC di Brave Origin**:
   - Icone launcher standard, round, adaptive (`layered_app_icon`) e Material You monocromatiche tratte direttamente da `app/theme/brave_origin/` di `brave/brave-core` (`v1.99.57`).
   - Logo monocromatico nero/bianco nella schermata di benvenuto/informazioni (`fre_product_logo`), nella barra degli indirizzi (pulsante Leone Brave Shields) e nei menu impostazioni.
   - Nome applicazione **`Brave Origin`** applicato direttamente al manifesto binario `AndroidManifest.xml` lasciando intatta al 100% la tabella `resources.arsc` compatta di Chromium.

---

## Download rapido

Tutti i pacchetti pronti all'uso sono pubblicati nella pagina [**Releases (`v1.99.57`)**](https://github.com/TixiGX/Brave-Origin/releases/tag/v1.99.57):

| Piattaforma | Architettura | File | Descrizione |
|---|---|---|---|
| **Android** | `arm64-v8a` (consigliato, smartphone moderni) | `BraveOrigin-arm64.apk` | App ufficiale Brave Android patchata in **Brave Origin** |
| **Android** | `armeabi-v7a` (ARM 32-bit) | `BraveOrigin-arm.apk` | App ufficiale Brave Android patchata in **Brave Origin** |
| **Android** | `x86_64` (emulatori / Chromebook / tablet Intel) | `BraveOrigin-x64.apk` | App ufficiale Brave Android patchata in **Brave Origin** |
| **Android** | `x86` (32-bit) | `BraveOrigin-x86.apk` | App ufficiale Brave Android patchata in **Brave Origin** |
| **Windows** | `amd64` (x86-64) | `BraveOriginStandaloneSetup.exe` | Installer offline ufficiale PC |
| **Windows** | `amd64` (x86-64) | `BraveOriginSetup.exe` | Installer online ufficiale PC |
| **Windows** | `arm64` | `BraveOriginStandaloneSetupArm64.exe` | Installer offline ufficiale Windows ARM64 |
| **macOS** | Apple Silicon (`arm64`) | `Brave-Origin-arm64.dmg` | Immagine disco ufficiale macOS |
| **macOS** | Intel (`x86_64`) | `Brave-Origin-x64.dmg` | Immagine disco ufficiale macOS |

> **Nota di installazione su Android:** poiché l'APK mantiene il package originale `com.brave.browser` ma è rifirmata con chiave dedicata Brave Origin ( APK Signature Scheme v2/v3), se sul telefono è già installato il Brave standard del Play Store disinstallalo prima di installare `BraveOrigin-arm64.apk`.

---

## Struttura del progetto

- [`branding/brave_origin/`](branding/brave_origin/) — Asset monocromatici ufficiali estratti da `brave/brave-core` (`app/theme/brave_origin/`, tag `v1.99.57`) più i drawable vettoriali monocromatici del Leone Brave.
- [`scripts/BraveOriginInitProvider.java`](scripts/BraveOriginInitProvider.java) — `ContentProvider` ad alta priorità compilato in un DEX dedicato (`classes<N+1>.dex`) che preconfigura `Local State` e `SharedPreferences` nel processo principale del browser prima di `Application.onCreate()`.
- [`scripts/patch_brave_origin_apk.py`](scripts/patch_brave_origin_apk.py) — Patcher deterministico in puro Python + Android SDK (`aapt2`, `d8`, `zipalign`, `apksigner`) compatibile con percorsi risorse abbreviati (`--shorten-resource-paths`), voci compatte (`--enable-compact-entries`), offuscamento R8 e allineamento pagine a 16 KiB.
- [`scripts/verify_patched_apk.py`](scripts/verify_patched_apk.py) — Suite di verifica automatica (firme v2/v3, `zipalign`, integrità `resources.arsc`, `libandroidfw` `xmltree`, `dexdump -c` di ART e `d8` bytecode verifier).
- [`scripts/origin_policies.json`](scripts/origin_policies.json) — Manifesto completo delle 16 policy ufficiali di Brave Origin `v1.99.57`.
- [`.github/workflows/release.yml`](.github/workflows/release.yml) — Pipeline CI/CD GitHub Actions che verifica gli SHA-256 ufficiali, genera e valida le 4 APK `BraveOrigin-*.apk` e le pubblica nella GitHub Release.

---

## Generazione locale di un'APK Brave Origin

Prerequisiti: Python 3.10+, JDK 17 e Android SDK (`build-tools` >= 34.0.0 e `platforms/android-34`).

```bash
python3 scripts/patch_brave_origin_apk.py \
  --input BraveMonoarm64.apk \
  --output BraveOrigin-arm64.apk \
  --android-home "$ANDROID_HOME"

python3 scripts/verify_patched_apk.py \
  --apk BraveOrigin-arm64.apk \
  --upstream-apk BraveMonoarm64.apk \
  --android-home "$ANDROID_HOME"
```
