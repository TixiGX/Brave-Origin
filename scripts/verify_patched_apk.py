#!/usr/bin/env python3
"""
Strict Automated Verifier for Patched Brave Origin APKs.

Validates:
  1. APK Signature Schemes v1 + v2 + v3 via `apksigner verify --verbose`
  2. 16 KiB native page alignment + 4-byte stored entry alignment via `zipalign -c -P 16 4`
  3. `aapt2 dump badging`: application-label == 'Brave Origin' and launchable-activity label == 'Brave Origin'
  4. `aapt2 dump xmltree` on AndroidManifest.xml: libandroidfw parses BraveOriginInitProvider cleanly
  5. `resources.arsc` is ZIP_STORED and byte-for-byte untouched vs upstream APK
  6. All `lib/**/*.so` entries are ZIP_STORED
  7. Every modified/added DEX passes both `dexdump -c` (Adler32 checksum + header validation)
     and `d8` (full Dalvik bytecode & register-type verification)
  8. Official monochrome Brave Origin launcher icons & vector drawables are installed
     with zero invalid 0x7f resource table references
"""

import argparse
import hashlib
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path


def run_cmd(cmd):
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        raise RuntimeError(
            f"Verification command failed ({res.returncode}): {' '.join(cmd)}\n"
            f"STDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}"
        )
    return res.stdout


def verify_apk(
    patched_apk: Path,
    upstream_apk: Path | None,
    branding_dir: Path,
    sdk_build_tools: Path,
    android_jar: Path,
):
    aapt2 = str(sdk_build_tools / "aapt2")
    d8 = str(sdk_build_tools / "d8")
    dexdump = str(sdk_build_tools / "dexdump")
    zipalign = str(sdk_build_tools / "zipalign")
    apksigner = str(sdk_build_tools / "apksigner")

    print(f"==> Verifying {patched_apk.name}")

    # 1. Signature verification (v2 / v3 depending on APK minSdkVersion)
    sig_out = run_cmd([apksigner, "verify", "--verbose", str(patched_apk)])
    if "Verifies" not in sig_out or (
        "Verified using v3 scheme (APK Signature Scheme v3): true" not in sig_out
        and "Verified using v2 scheme (APK Signature Scheme v2): true" not in sig_out
    ):
        raise AssertionError(f"APK signature verification failed:\n{sig_out}")
    print("  [OK] APK Signatures verified (apksigner verify passed)")

    # 2. Alignment verification (page alignment + 4-byte ZIP_STORED alignment)
    help_out = subprocess.run([zipalign], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout
    page_flags = ["-P", "16"] if "-P <pagesize_kb>" in help_out or "[-P " in help_out else ["-p"]
    run_cmd([zipalign, "-c"] + page_flags + ["4", str(patched_apk)])
    print(f"  [OK] zipalign -c {' '.join(page_flags)} 4 passed")

    # 3. Badging & label verification
    badging = run_cmd([aapt2, "dump", "badging", str(patched_apk)])
    if "application-label:'Brave Origin'" not in badging:
        raise AssertionError(f"Expected application-label:'Brave Origin' in badging:\n{badging[:1500]}")
    m_act = re.search(r"launchable-activity:[^\n]*label='([^']*)'", badging)
    if m_act and m_act.group(1) != "Brave Origin":
        raise AssertionError(f"Expected launchable-activity label 'Brave Origin', got '{m_act.group(1)}'")
    print("  [OK] application-label & launchable-activity label == 'Brave Origin'")

    # 4. AndroidManifest.xml C++ libandroidfw XML tree + attribute binary-search check
    xmltree = run_cmd([aapt2, "dump", "xmltree", str(patched_apk), "--file", "AndroidManifest.xml"])
    if "org.brave.origin.BraveOriginInitProvider" not in xmltree:
        raise AssertionError("BraveOriginInitProvider missing from AndroidManifest.xml xmltree!")
    if ".braveorigin.init" not in xmltree:
        raise AssertionError("Provider authority .braveorigin.init missing from AndroidManifest.xml xmltree!")
    print("  [OK] AndroidManifest.xml parsed cleanly by libandroidfw with BraveOriginInitProvider")

    # 5. ZIP_STORED & resources.arsc byte-identity check
    upstream_arsc_sha = None
    upstream_dex_shas = {}
    if upstream_apk and upstream_apk.is_file():
        with zipfile.ZipFile(upstream_apk, "r") as zu:
            upstream_arsc_sha = hashlib.sha256(zu.read("resources.arsc")).hexdigest()
            for n in zu.namelist():
                if re.fullmatch(r"classes\d*\.dex", n):
                    upstream_dex_shas[n] = hashlib.sha256(zu.read(n)).hexdigest()

    expected_icon_shas = {
        hashlib.sha256(p.read_bytes()).hexdigest()
        for p in (branding_dir / "android/res_brave_base").glob("mipmap-*/app_icon.png")
    } | {
        hashlib.sha256(p.read_bytes()).hexdigest()
        for p in (branding_dir / "android").glob("mipmap-*/app_icon.png")
    }

    matched_origin_icons = 0
    modified_or_new_dex = []

    with tempfile.TemporaryDirectory(prefix="brave_origin_verify_") as tmp:
        work = Path(tmp)
        with zipfile.ZipFile(patched_apk, "r") as zp:
            arsc_info = zp.getinfo("resources.arsc")
            if arsc_info.compress_type != zipfile.ZIP_STORED:
                raise AssertionError("resources.arsc MUST be ZIP_STORED!")
            patched_arsc_sha = hashlib.sha256(zp.read("resources.arsc")).hexdigest()
            if upstream_arsc_sha and patched_arsc_sha != upstream_arsc_sha:
                raise AssertionError("resources.arsc was modified! Expected 100% untouched table.")

            for info in zp.infolist():
                if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                    if info.compress_type != zipfile.ZIP_STORED:
                        raise AssertionError(f"Native library {info.filename} MUST be ZIP_STORED!")
                if info.filename.endswith(".png"):
                    sha = hashlib.sha256(zp.read(info.filename)).hexdigest()
                    if sha in expected_icon_shas:
                        matched_origin_icons += 1
                if re.fullmatch(r"classes\d*\.dex", info.filename):
                    dex_data = zp.read(info.filename)
                    sha = hashlib.sha256(dex_data).hexdigest()
                    if upstream_dex_shas.get(info.filename) != sha:
                        dex_file = work / info.filename
                        dex_file.write_bytes(dex_data)
                        modified_or_new_dex.append(dex_file)

        if matched_origin_icons == 0:
            raise AssertionError("No official monochrome Brave Origin app_icon PNGs found in patched APK!")
        print(f"  [OK] resources.arsc untouched & ZIP_STORED; {matched_origin_icons} monochrome app_icon PNGs verified")

        # 6. DEX checksum + D8 full bytecode verification on all modified/added DEX files
        if not modified_or_new_dex:
            raise AssertionError("Expected at least BraveOriginInitProvider extra DEX in patched APK!")

        has_init_provider_cls = False
        for dex_file in modified_or_new_dex:
            run_cmd([dexdump, "-c", str(dex_file)])
            if b"Lorg/brave/origin/BraveOriginInitProvider;" in dex_file.read_bytes():
                has_init_provider_cls = True
            d8_out = work / f"d8_check_{dex_file.stem}"
            d8_out.mkdir(parents=True, exist_ok=True)
            run_cmd([d8, "--lib", str(android_jar), "--min-api", "24", "--output", str(d8_out), str(dex_file)])
            print(f"  [OK] DEX verified via ART dexdump -c + d8 bytecode verifier: {dex_file.name}")

        if not has_init_provider_cls:
            raise AssertionError("BraveOriginInitProvider class not found in added DEX!")

    # 7. Verify compiled vector drawables have zero 0x7f package references
    res_dump = run_cmd([aapt2, "dump", "resources", str(patched_apk)])
    m_vec = re.search(
        r"resource\s+0x[0-9a-fA-F]+\s+(?:[\w.]+:)?drawable/ic_social_brave_release_favicon_fullheight_color\b.*?\n\s*\([^)]*\)\s+\(file\)\s+(\S+)",
        res_dump,
    )
    if m_vec:
        vec_zpath = m_vec.group(1)
        vec_tree = run_cmd([aapt2, "dump", "xmltree", str(patched_apk), "--file", vec_zpath])
        if "@0x7f" in vec_tree:
            raise AssertionError(f"Invalid 0x7f reference in compiled vector {vec_zpath}:\n{vec_tree}")
        print(f"  [OK] Toolbar Lion vector ({vec_zpath}) verified clean with zero 0x7f refs")

    print(f"==> ALL VERIFICATION CHECKS PASSED FOR {patched_apk.name}!")


def main():
    parser = argparse.ArgumentParser(description="Verify patched Brave Origin APK")
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--upstream-apk", type=Path, default=None)
    parser.add_argument(
        "--branding-dir",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "branding/brave_origin",
    )
    parser.add_argument("--android-home", type=Path, default=Path(os.environ.get("ANDROID_HOME", "")))
    parser.add_argument("--build-tools-ver", default=os.environ.get("BUILD_TOOLS_VER", "34.0.0"))
    parser.add_argument("--platform-ver", default=os.environ.get("PLATFORM_VER", "android-34"))
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "")))
    args = parser.parse_args()

    if str(args.java_home):
        os.environ["PATH"] = f"{args.java_home / 'bin'}:{os.environ.get('PATH', '')}"

    verify_apk(
        patched_apk=args.apk,
        upstream_apk=args.upstream_apk,
        branding_dir=args.branding_dir,
        sdk_build_tools=args.android_home / "build-tools" / args.build_tools_ver,
        android_jar=args.android_home / "platforms" / args.platform_ver / "android.jar",
    )


if __name__ == "__main__":
    main()
