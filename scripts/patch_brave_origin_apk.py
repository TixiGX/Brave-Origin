#!/usr/bin/env python3
"""
Brave Origin Official APK Patcher (v1.99.57+)

Takes an official Brave Android Chromium APK (BraveMonoarm64.apk, BraveMonoarm.apk,
BraveMonox64.apk, BraveMonox86.apk) and transforms it into Brave Origin:
  1. Applies official Brave Origin PC/Android monochrome branding (from brave-core
     app/theme/brave_origin/) across launcher icons, adaptive icons, monochrome
     themed icons, welcome/about product logos, and toolbar/settings Lion vectors.
  2. Sets the application & launcher label to "Brave Origin" directly in binary
     AndroidManifest.xml without recompiling or mutating resources.arsc.
  3. Injects BraveOriginInitProvider (compiled into an extra classes<N+1>.dex)
     and registers it in AndroidManifest.xml so Chromium's Local State and
     SharedPreferences are seeded with validated Brave Origin state synchronously
     before Application.onCreate() and before native libmonochrome loads.
  4. Patches BraveOriginSubscriptionPrefs in-place in DEX (supporting any DEX
     version 035..041 and R8 obfuscation) so Origin credential checks always
     resolve as active locally without Google Play Billing or SKU network calls,
     unlocking Settings -> Brave Origin with zero paywall.
  5. Preserves exact per-entry ZIP compression (ZIP_STORED for lib/*.so, *.pak,
     *.dat, *.bin, resources.arsc), aligns to 16 KiB pages with zipalign -P 16 4
     (Android 15+ 16KB page ready), and signs with v1 + v2 + v3 APK signatures.
"""

import argparse
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib
from pathlib import Path


# Android Binary XML constants
RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_RESOURCE_MAP_TYPE = 0x0180

UTF8_FLAG = 0x00000100

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12

ATTR_LABEL = 0x01010001
ATTR_NAME = 0x01010003
ATTR_EXPORTED = 0x01010010
ATTR_AUTHORITIES = 0x01010018
ATTR_INIT_ORDER = 0x0101001A

ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"
INIT_PROVIDER_CLASS = "org.brave.origin.BraveOriginInitProvider"


def run_cmd(cmd, **kwargs):
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, **kwargs)
    if res.returncode != 0:
        raise RuntimeError(
            f"Command failed ({res.returncode}): {' '.join(cmd)}\nSTDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}"
        )
    return res.stdout


# ==============================================================================
# 1. AAPT2 METADATA & SHORTENED RESOURCE PATH DISCOVERY
# ==============================================================================

def parse_badging(aapt2: str, apk_path: str) -> dict:
    out = run_cmd([aapt2, "dump", "badging", apk_path])
    m_pkg = re.search(r"package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'", out)
    if not m_pkg:
        raise RuntimeError("Could not parse package info from aapt2 dump badging")
    return {
        "package": m_pkg.group(1),
        "version_code": m_pkg.group(2),
        "version_name": m_pkg.group(3),
    }


def parse_resource_table(aapt2: str, apk_path: str):
    """
    Parses `aapt2 dump resources <apk>` to discover:
      - exact 0x7fXXXXXX resource IDs for string/app_name (and any launcher label)
      - exact ZIP entry paths (even when shortened to res/0A.png) for every icon/drawable
    """
    out = run_cmd([aapt2, "dump", "resources", apk_path])
    app_name_ids = set()
    file_map = {}  # (type_and_name, config_str) -> zip_entry_path

    current_res = None
    current_id = None
    res_header_re = re.compile(r"^\s*resource\s+(0x[0-9a-fA-F]+)\s+([\w.]+:)?(\w+/[\w.]+)")
    file_line_re = re.compile(r"^\s*\(([^)]*)\)\s+\(file\)\s+(\S+)")

    for line in out.splitlines():
        m = res_header_re.match(line)
        if m:
            current_id = int(m.group(1), 16)
            current_res = m.group(3)  # e.g. "mipmap/app_icon" or "string/app_name"
            if current_res in ("string/app_name", "string/bookmark_widget_title", "string/search_widget_title"):
                if current_res == "string/app_name":
                    app_name_ids.add(current_id)
            continue

        if current_res:
            mf = file_line_re.match(line)
            if mf:
                cfg = mf.group(1).strip()  # e.g. "mdpi", "xxxhdpi-v4", "night-v8", ""
                zpath = mf.group(2).strip()
                file_map.setdefault(current_res, []).append((cfg, zpath))

    return app_name_ids, file_map


def classify_dpi(cfg: str) -> str:
    for dpi in ("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "ldpi"):
        if dpi in cfg:
            return "mdpi" if dpi == "ldpi" else dpi
    return "xxhdpi"


# ==============================================================================
# 2. BINARY ANDROIDMANIFEST.XML (AXML) EDITOR
# ==============================================================================

def _encode_utf8_len(val: int) -> bytes:
    if val >= 0x80:
        return bytes([((val >> 8) & 0x7F) | 0x80, val & 0xFF])
    return bytes([val])


def _encode_utf16_len(val: int) -> bytes:
    if val >= 0x8000:
        hi = ((val >> 16) & 0x7FFF) | 0x8000
        lo = val & 0xFFFF
        return struct.pack("<HH", hi, lo)
    return struct.pack("<H", val)


def _decode_string_pool(chunk: bytes):
    _, header_size, chunk_size, str_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<HHIIIIII", chunk, 0
    )
    is_utf8 = bool(flags & UTF8_FLAG)
    offsets_pos = header_size
    str_offsets = list(struct.unpack_from(f"<{str_count}I", chunk, offsets_pos)) if str_count else []

    strings = []
    for off in str_offsets:
        p = strings_start + off
        if is_utf8:
            # u16 char len, then u8 byte len
            if chunk[p] & 0x80:
                p += 2
            else:
                p += 1
            if chunk[p] & 0x80:
                b_len = ((chunk[p] & 0x7F) << 8) | chunk[p + 1]
                p += 2
            else:
                b_len = chunk[p]
                p += 1
            strings.append(chunk[p : p + b_len].decode("utf-8", errors="surrogatepass"))
        else:
            u16_len = struct.unpack_from("<H", chunk, p)[0]
            p += 2
            if u16_len & 0x8000:
                lo = struct.unpack_from("<H", chunk, p)[0]
                p += 2
                u16_len = ((u16_len & 0x7FFF) << 16) | lo
            raw = chunk[p : p + u16_len * 2]
            strings.append(raw.decode("utf-16le", errors="surrogatepass"))

    style_offsets = []
    style_blob = b""
    if style_count > 0 and styles_start > 0:
        style_offsets = list(
            struct.unpack_from(f"<{style_count}I", chunk, offsets_pos + str_count * 4)
        )
        style_blob = chunk[styles_start:chunk_size]

    return strings, is_utf8, flags, style_offsets, style_blob


def _encode_string_pool(strings, is_utf8: bool, flags: int, style_offsets, style_blob: bytes) -> bytes:
    header_size = 28
    str_count = len(strings)
    style_count = len(style_offsets)

    encoded_items = []
    cur_off = 0
    str_offsets = []
    for s in strings:
        str_offsets.append(cur_off)
        if is_utf8:
            u16_units = len(s.encode("utf-16le", errors="surrogatepass")) // 2
            utf8_bytes = s.encode("utf-8", errors="surrogatepass")
            item = _encode_utf8_len(u16_units) + _encode_utf8_len(len(utf8_bytes)) + utf8_bytes + b"\x00"
        else:
            utf16_bytes = s.encode("utf-16le", errors="surrogatepass")
            u16_units = len(utf16_bytes) // 2
            item = _encode_utf16_len(u16_units) + utf16_bytes + b"\x00\x00"
        encoded_items.append(item)
        cur_off += len(item)

    str_data = b"".join(encoded_items)
    while len(str_data) % 4 != 0:
        str_data += b"\x00"

    strings_start = header_size + (str_count + style_count) * 4
    styles_start = (strings_start + len(str_data)) if style_count > 0 else 0

    body = bytearray()
    if str_count:
        body += struct.pack(f"<{str_count}I", *str_offsets)
    if style_count:
        body += struct.pack(f"<{style_count}I", *style_offsets)
    body += str_data
    if style_count:
        body += style_blob
        while len(body) % 4 != 0:
            body += b"\x00"

    total_size = header_size + len(body)
    header = struct.pack(
        "<HHIIIIII",
        RES_STRING_POOL_TYPE,
        header_size,
        total_size,
        str_count,
        style_count,
        flags,
        strings_start,
        styles_start,
    )
    return header + bytes(body)


def _shift_xml_chunk_str_idx(chunk: bytearray, threshold: int, delta: int):
    """Shifts string pool references >= threshold by +delta across an XML node chunk."""
    NO_IDX = 0xFFFFFFFF

    def bump(val: int) -> int:
        if val != NO_IDX and val >= threshold:
            return val + delta
        return val

    ctype, hdr_size, csize = struct.unpack_from("<HHI", chunk, 0)
    # ResXMLTree_node comment index at offset 12
    comment_idx = struct.unpack_from("<I", chunk, 12)[0]
    struct.pack_into("<I", chunk, 12, bump(comment_idx))

    if ctype in (RES_XML_START_NAMESPACE_TYPE, 0x0101):  # start/end namespace
        prefix, uri = struct.unpack_from("<II", chunk, 16)
        struct.pack_into("<II", chunk, 16, bump(prefix), bump(uri))
    elif ctype == RES_XML_START_ELEMENT_TYPE:
        ns, name, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", chunk, 16)
        struct.pack_into("<II", chunk, 16, bump(ns), bump(name))
        base = 16 + attr_start
        for i in range(attr_count):
            off = base + i * attr_size
            a_ns, a_name, a_raw, tv_size, res0, dtype, data = struct.unpack_from("<IIIHBBI", chunk, off)
            a_ns = bump(a_ns)
            a_name = bump(a_name)
            a_raw = bump(a_raw)
            if dtype == TYPE_STRING:
                data = bump(data)
            struct.pack_into("<IIIHBBI", chunk, off, a_ns, a_name, a_raw, tv_size, res0, dtype, data)
    elif ctype == RES_XML_END_ELEMENT_TYPE:
        ns, name = struct.unpack_from("<II", chunk, 16)
        struct.pack_into("<II", chunk, 16, bump(ns), bump(name))
    elif ctype == 0x0104:  # CDATA
        txt = struct.unpack_from("<I", chunk, 16)[0]
        struct.pack_into("<I", chunk, 16, bump(txt))


def patch_axml_manifest(
    manifest_bytes: bytes,
    app_name_res_ids: set,
    new_label: str,
    provider_class: str,
    provider_authority: str,
) -> bytes:
    magic, root_hdr_size, total_len = struct.unpack_from("<HHI", manifest_bytes, 0)
    if magic != RES_XML_TYPE:
        raise RuntimeError(f"Unexpected AndroidManifest AXML magic: 0x{magic:04x}")

    pos = root_hdr_size
    string_pool_chunk = None
    res_map_ids = []
    xml_chunks = []

    while pos < len(manifest_bytes):
        ctype, chdr, csize = struct.unpack_from("<HHI", manifest_bytes, pos)
        raw_chunk = bytearray(manifest_bytes[pos : pos + csize])
        if ctype == RES_STRING_POOL_TYPE and string_pool_chunk is None:
            string_pool_chunk = bytes(raw_chunk)
        elif ctype == RES_XML_RESOURCE_MAP_TYPE:
            count = (csize - chdr) // 4
            res_map_ids = list(struct.unpack_from(f"<{count}I", raw_chunk, chdr))
        else:
            xml_chunks.append(raw_chunk)
        pos += csize

    if string_pool_chunk is None:
        raise RuntimeError("No ResStringPool found in AndroidManifest.xml")

    strings, is_utf8, sp_flags, style_offsets, style_blob = _decode_string_pool(string_pool_chunk)

    # Ensure required framework attribute IDs exist in the resource-mapped prefix of strings
    required_attrs = [
        (ATTR_LABEL, "label"),
        (ATTR_NAME, "name"),
        (ATTR_EXPORTED, "exported"),
        (ATTR_AUTHORITIES, "authorities"),
        (ATTR_INIT_ORDER, "initOrder"),
    ]
    attr_idx_by_resid = {}
    for idx, rid in enumerate(res_map_ids):
        if rid not in attr_idx_by_resid:
            attr_idx_by_resid[rid] = idx

    for rid, attr_name in required_attrs:
        if rid not in attr_idx_by_resid:
            insert_at = len(res_map_ids)
            strings.insert(insert_at, attr_name)
            res_map_ids.append(rid)
            attr_idx_by_resid[rid] = insert_at
            for ch in xml_chunks:
                _shift_xml_chunk_str_idx(ch, insert_at, 1)

    def ensure_plain_string(val: str) -> int:
        # Look for an unmapped string (index >= len(res_map_ids)) first, or append at end
        for i in range(len(res_map_ids), len(strings)):
            if strings[i] == val:
                return i
        strings.append(val)
        return len(strings) - 1

    label_str_idx = ensure_plain_string(new_label)
    provider_tag_idx = ensure_plain_string("provider")
    provider_cls_idx = ensure_plain_string(provider_class)
    provider_auth_idx = ensure_plain_string(provider_authority)

    # Find android namespace URI index
    android_ns_idx = 0xFFFFFFFF
    for ch in xml_chunks:
        ctype = struct.unpack_from("<H", ch, 0)[0]
        if ctype == RES_XML_START_NAMESPACE_TYPE:
            _, uri_idx = struct.unpack_from("<II", ch, 16)
            if 0 <= uri_idx < len(strings) and strings[uri_idx] == ANDROID_NS_URI:
                android_ns_idx = uri_idx
                break
    if android_ns_idx == 0xFFFFFFFF:
        android_ns_idx = ensure_plain_string(ANDROID_NS_URI)

    # Walk XML elements:
    #  1) Replace android:label on <application> and launcher <activity>/<activity-alias>
    #  2) Check if provider already exists; insert <provider> right before </application>
    label_attr_idx = attr_idx_by_resid[ATTR_LABEL]
    patched_labels = 0
    provider_already_present = False
    app_end_chunk_index = None

    for idx_ch, ch in enumerate(xml_chunks):
        ctype = struct.unpack_from("<H", ch, 0)[0]
        if ctype == RES_XML_START_ELEMENT_TYPE:
            el_ns, el_name, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", ch, 16)
            tag_name = strings[el_name] if 0 <= el_name < len(strings) else ""
            base = 16 + attr_start

            if tag_name == "provider":
                for i in range(attr_count):
                    off = base + i * attr_size
                    _, a_name, _, _, _, dtype, data = struct.unpack_from("<IIIHBBI", ch, off)
                    if a_name < len(res_map_ids) and res_map_ids[a_name] == ATTR_NAME and dtype == TYPE_STRING:
                        if 0 <= data < len(strings) and strings[data] == provider_class:
                            provider_already_present = True

            if tag_name in ("application", "activity", "activity-alias"):
                for i in range(attr_count):
                    off = base + i * attr_size
                    a_ns, a_name, a_raw, tv_size, res0, dtype, data = struct.unpack_from("<IIIHBBI", ch, off)
                    is_label_attr = (a_name < len(res_map_ids) and res_map_ids[a_name] == ATTR_LABEL) or (
                        a_name == label_attr_idx
                    )
                    if not is_label_attr:
                        continue
                    should_replace = False
                    if tag_name == "application":
                        should_replace = True
                    elif dtype == TYPE_REFERENCE and data in app_name_res_ids:
                        should_replace = True
                    elif dtype == TYPE_STRING and 0 <= data < len(strings) and strings[data] == "Brave":
                        should_replace = True

                    if should_replace:
                        struct.pack_into(
                            "<IIIHBBI",
                            ch,
                            off,
                            a_ns,
                            a_name,
                            label_str_idx,
                            tv_size,
                            res0,
                            TYPE_STRING,
                            label_str_idx,
                        )
                        patched_labels += 1

        elif ctype == RES_XML_END_ELEMENT_TYPE:
            _, el_name = struct.unpack_from("<II", ch, 16)
            tag_name = strings[el_name] if 0 <= el_name < len(strings) else ""
            if tag_name == "application":
                app_end_chunk_index = idx_ch

    if app_end_chunk_index is None:
        raise RuntimeError("Could not find </application> in AndroidManifest.xml")

    if not provider_already_present:
        # Attributes MUST be sorted strictly by ascending 0x0101xxxx resource ID!
        provider_attrs = [
            # 0x01010003 android:name="org.brave.origin.BraveOriginInitProvider"
            (android_ns_idx, attr_idx_by_resid[ATTR_NAME], provider_cls_idx, TYPE_STRING, provider_cls_idx),
            # 0x01010010 android:exported="false"
            (android_ns_idx, attr_idx_by_resid[ATTR_EXPORTED], 0xFFFFFFFF, TYPE_INT_BOOLEAN, 0),
            # 0x01010018 android:authorities="<pkg>.braveorigin.init"
            (android_ns_idx, attr_idx_by_resid[ATTR_AUTHORITIES], provider_auth_idx, TYPE_STRING, provider_auth_idx),
            # 0x0101001a android:initOrder="2147483647"
            (android_ns_idx, attr_idx_by_resid[ATTR_INIT_ORDER], 0xFFFFFFFF, TYPE_INT_DEC, 0x7FFFFFFF),
        ]
        attr_bytes = bytearray()
        for a_ns, a_name, a_raw, dtype, data in provider_attrs:
            attr_bytes += struct.pack("<IIIHBBI", a_ns, a_name, a_raw, 8, 0, dtype, data)

        start_el_size = 36 + len(attr_bytes)
        start_el = bytearray()
        start_el += struct.pack("<HHI", RES_XML_START_ELEMENT_TYPE, 16, start_el_size)
        start_el += struct.pack("<II", 0, 0xFFFFFFFF)  # lineNumber, comment
        start_el += struct.pack(
            "<IIHHHHHH",
            0xFFFFFFFF,  # element ns
            provider_tag_idx,  # element name ("provider")
            20,  # attributeStart (from offset 16 -> 36)
            20,  # attributeSize
            len(provider_attrs),
            0,  # idIndex
            0,  # classIndex
            0,  # styleIndex
        )
        start_el += attr_bytes

        end_el = bytearray()
        end_el += struct.pack("<HHI", RES_XML_END_ELEMENT_TYPE, 16, 24)
        end_el += struct.pack("<II", 0, 0xFFFFFFFF)
        end_el += struct.pack("<II", 0xFFFFFFFF, provider_tag_idx)

        xml_chunks.insert(app_end_chunk_index, end_el)
        xml_chunks.insert(app_end_chunk_index, start_el)

    new_sp_chunk = _encode_string_pool(strings, is_utf8, sp_flags, style_offsets, style_blob)
    res_map_chunk = struct.pack(
        f"<HHI{len(res_map_ids)}I",
        RES_XML_RESOURCE_MAP_TYPE,
        8,
        8 + len(res_map_ids) * 4,
        *res_map_ids,
    )

    body = new_sp_chunk + res_map_chunk + b"".join(bytes(c) for c in xml_chunks)
    out = struct.pack("<HHI", RES_XML_TYPE, 8, 8 + len(body)) + body
    print(f"  [AXML] Patched {patched_labels} label(s) -> '{new_label}' and registered {provider_class}")
    return out


# ==============================================================================
# 3. SPEC-EXACT IN-PLACE DEX PATCHER FOR BRAVE ORIGIN SUBSCRIPTION PREFS
# ==============================================================================

def _read_uleb128(buf: bytes, pos: int):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
    return result, pos


def _read_sleb128(buf: bytes, pos: int):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if (b & 0x80) == 0:
            if shift < 32 and (b & 0x40):
                result |= -(1 << shift)
            break
    return result, pos


def _decode_mutf8_at(buf: bytes, data_off: int) -> str:
    _, pos = _read_uleb128(buf, data_off)
    end = buf.find(b"\x00", pos)
    if end < 0:
        end = len(buf)
    return buf[pos:end].decode("utf-8", errors="replace")


# Exact 16-bit Dalvik instruction sizes (in 16-bit code units) by opcode 0x00..0xFF
_DALVIK_INSN_WIDTH = [1] * 256
for _op in (
    0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F, 0x20, 0x22, 0x23,
    0x29, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
    0x39, 0x3A, 0x3B, 0x3C, 0x3D,
) + tuple(range(0x50, 0x6E)) + tuple(range(0xD0, 0xE0)):
    _DALVIK_INSN_WIDTH[_op] = 2
for _op in (
    0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C,
) + tuple(range(0x6E, 0x79)) + (0xFC, 0xFD):
    _DALVIK_INSN_WIDTH[_op] = 3
for _op in (0xFA, 0xFB):
    _DALVIK_INSN_WIDTH[_op] = 4
_DALVIK_INSN_WIDTH[0x18] = 5  # const-wide


def _scan_method_string_ids(buf: bytes, code_off: int) -> set:
    insns_size = struct.unpack_from("<I", buf, code_off + 12)[0]
    insns_start = code_off + 16
    found = set()
    pc = 0
    while pc < insns_size:
        u16 = struct.unpack_from("<H", buf, insns_start + pc * 2)[0]
        op = u16 & 0xFF
        if op == 0x00 and u16 != 0x0000:
            ident = u16 >> 8
            if ident == 0x01:  # packed-switch-payload
                sz = struct.unpack_from("<H", buf, insns_start + (pc + 1) * 2)[0]
                pc += sz * 2 + 4
                continue
            elif ident == 0x02:  # sparse-switch-payload
                sz = struct.unpack_from("<H", buf, insns_start + (pc + 1) * 2)[0]
                pc += sz * 4 + 2
                continue
            elif ident == 0x03:  # fill-array-data-payload
                elem_w = struct.unpack_from("<H", buf, insns_start + (pc + 1) * 2)[0]
                elem_n = struct.unpack_from("<I", buf, insns_start + (pc + 2) * 2)[0]
                pc += (elem_n * elem_w + 1) // 2 + 4
                continue
        if op == 0x1A and pc + 1 < insns_size:  # const-string vAA, string@BBBB
            sid = struct.unpack_from("<H", buf, insns_start + (pc + 1) * 2)[0]
            found.add(sid)
        elif op == 0x1B and pc + 2 < insns_size:  # const-string/jumbo vAA, string@BBBBBBBB
            sid = struct.unpack_from("<I", buf, insns_start + (pc + 1) * 2)[0]
            found.add(sid)
        pc += _DALVIK_INSN_WIDTH[op]
    return found


def _collect_catch_handler_addrs(buf: bytes, code_off: int) -> set:
    tries_size = struct.unpack_from("<H", buf, code_off + 6)[0]
    if tries_size == 0:
        return set()
    insns_size = struct.unpack_from("<I", buf, code_off + 12)[0]
    tries_off = code_off + 16 + insns_size * 2
    if insns_size % 2 == 1:
        tries_off += 2  # 4-byte padding before try_items
    handlers_base = tries_off + tries_size * 8

    handler_RelOffs = set()
    for i in range(tries_size):
        _, _, h_off = struct.unpack_from("<IHH", buf, tries_off + i * 8)
        handler_RelOffs.add(h_off)

    addrs = set()
    for h_off in handler_RelOffs:
        p = handlers_base + h_off
        size, p = _read_sleb128(buf, p)
        for _ in range(abs(size)):
            _, p = _read_uleb128(buf, p)  # type_idx
            addr, p = _read_uleb128(buf, p)
            addrs.add(addr)
        if size <= 0:
            addr, p = _read_uleb128(buf, p)  # catch_all_addr
            addrs.add(addr)
    return addrs


def _patch_method_return_in_place(buf: bytearray, code_off: int, ret_kind: str):
    """
    Rewrites a Dalvik method body in-place WITHOUT changing code_item size,
    tries_size, or catch_handler offsets, satisfying ART DexFileVerifier 100%:
      - ret_kind == 'true'  -> const/4 v0, 0x1 (0x1012); return v0 (0x000f)
      - ret_kind == 'false' -> const/4 v0, 0x0 (0x0012); return v0 (0x000f)
      - ret_kind == 'void'  -> return-void (0x000e)
    """
    insns_size = struct.unpack_from("<I", buf, code_off + 12)[0]
    insns_start = code_off + 16
    handler_addrs = _collect_catch_handler_addrs(buf, code_off)

    # Clear debug_info_off so stale local-variable debug opcodes never mismatch
    struct.pack_into("<I", buf, code_off + 8, 0)

    # Fill entire insns array with 1-unit `nop` (0x0000) first so every single
    # code-unit offset (including try start_addr / insn_count / handler_addr)
    # is guaranteed to be a valid 1-unit instruction boundary.
    for i in range(insns_size):
        struct.pack_into("<H", buf, insns_start + i * 2, 0x0000)

    if ret_kind == "void":
        if insns_size < 1:
            raise RuntimeError("Method insns_size < 1")
        struct.pack_into("<H", buf, insns_start, 0x000E)  # return-void at entry
        struct.pack_into("<H", buf, insns_start + (insns_size - 1) * 2, 0x000E)  # no fallthrough at end
        for ha in handler_addrs:
            if 0 <= ha < insns_size:
                struct.pack_into("<H", buf, insns_start + ha * 2, 0x000E)
    else:
        const_op = 0x1012 if ret_kind == "true" else 0x0012
        ret_op = 0x000F  # return v0
        if insns_size < 2:
            raise RuntimeError(f"Boolean method insns_size ({insns_size}) < 2")
        struct.pack_into("<HH", buf, insns_start, const_op, ret_op)
        if insns_size >= 4:
            struct.pack_into("<HH", buf, insns_start + (insns_size - 2) * 2, const_op, ret_op)
        for ha in sorted(handler_addrs):
            if 0 <= ha < insns_size - 1:
                struct.pack_into("<HH", buf, insns_start + ha * 2, const_op, ret_op)


def _patch_request_summary_immediate_true(
    buf: bytearray,
    code_off: int,
    bool_true_field_idx: int | None,
    bool_valueof_method_idx: int | None,
    is_callback_method,
):
    """
    Rewrites `requestCredentialSummary(Profile p0, Callback p1)` directly so if
    p1 != null it invokes `p1.onResult(Boolean.TRUE)` immediately and returns!
    """
    regs_size, ins_size, outs_size = struct.unpack_from("<HHH", buf, code_off)
    insns_size = struct.unpack_from("<I", buf, code_off + 12)[0]
    insns_start = code_off + 16
    if ins_size != 2 or regs_size < 3 or insns_size < 10:
        return False

    p1_reg = regs_size - 1  # second parameter (Callback)
    v0_reg = 0  # local scratch register (regs_size >= 3, ins_size == 2 -> v0 is local!)

    # Scan existing method body for `Callback.onResult(Ljava/lang/Object;)V`
    invoke_op = None
    invoke_method_idx = None
    pc = 0
    while pc < insns_size:
        u16 = struct.unpack_from("<H", buf, insns_start + pc * 2)[0]
        op = u16 & 0xFF
        if op in (0x6E, 0x72) and ((u16 >> 12) & 0xF) == 2 and pc + 2 < insns_size:
            midx = struct.unpack_from("<H", buf, insns_start + (pc + 1) * 2)[0]
            if is_callback_method(midx):
                invoke_op = op
                invoke_method_idx = midx
                break
        elif op in (0x74, 0x78) and ((u16 >> 8) & 0xFF) == 2 and pc + 2 < insns_size:
            midx = struct.unpack_from("<H", buf, insns_start + (pc + 1) * 2)[0]
            if is_callback_method(midx):
                invoke_op = 0x6E if op == 0x74 else 0x72
                invoke_method_idx = midx
                break
        pc += _DALVIK_INSN_WIDTH[op]

    if invoke_op is None or invoke_method_idx is None:
        return False

    seq = []
    cb_reg = p1_reg
    if p1_reg > 15:
        # regs_size >= 17 and ins_size == 2 -> both v0 and v1 are local registers!
        # move-object/from16 v1, v<p1_reg> (opcode 0x08, 2 units)
        seq.append(0x0108)
        seq.append(p1_reg)
        cb_reg = 1

    if bool_true_field_idx is not None and bool_true_field_idx < 65536:
        # if-eqz cb_reg, +7 -> jumps to return-void
        seq.append(0x0038 | (cb_reg << 8))
        seq.append(0x0007)
        # sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean; (2 units)
        seq.append(0x0062 | (v0_reg << 8))
        seq.append(bool_true_field_idx)
    elif bool_valueof_method_idx is not None and bool_valueof_method_idx < 65536:
        # if-eqz cb_reg, +10 (0x000a) -> jumps to return-void
        seq.append(0x0038 | (cb_reg << 8))
        seq.append(0x000A)
        # const/4 v0, 0x1 (1 unit)
        seq.append(0x1012)
        # invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean; (3 units)
        seq.append(0x1071)
        seq.append(bool_valueof_method_idx)
        seq.append(v0_reg)
        # move-result-object v0 (1 unit)
        seq.append(0x000C | (v0_reg << 8))
    else:
        return False

    # invoke-interface/virtual {cb_reg, v0}, Callback->onResult(Object)V
    seq.append(invoke_op | (2 << 12))
    seq.append(invoke_method_idx)
    seq.append(cb_reg | (v0_reg << 4))
    # return-void
    seq.append(0x000E)

    if len(seq) + 1 > insns_size:
        return False

    handler_addrs = _collect_catch_handler_addrs(buf, code_off)
    struct.pack_into("<I", buf, code_off + 8, 0)  # clear debug_info_off
    for i in range(insns_size):
        struct.pack_into("<H", buf, insns_start + i * 2, 0x0000)
    for idx_u, word in enumerate(seq):
        struct.pack_into("<H", buf, insns_start + idx_u * 2, word)
    struct.pack_into("<H", buf, insns_start + (insns_size - 1) * 2, 0x000E)
    for ha in handler_addrs:
        if len(seq) <= ha < insns_size:
            struct.pack_into("<H", buf, insns_start + ha * 2, 0x000E)
    return True


def patch_dex_if_needed(dex_name: str, dex_bytes: bytes) -> tuple[bytes, int]:
    """
    Inspects a DEX file for BraveOriginSubscriptionPrefs markers; if present,
    patches Origin checks in-place and updates DEX SHA-1 + Adler32 headers.
    Returns (possibly_patched_bytes, patched_method_count).
    """
    # Fast byte pre-filter (~2ms per 12 MB DEX!)
    if (
        b"remaining_credential_count" not in dex_bytes
        and b"brave_origin_credential_summary_cached" not in dex_bytes
        and b"SkusService is null, cannot request credential summary" not in dex_bytes
    ):
        return dex_bytes, 0

    magic = dex_bytes[:4]
    if magic != b"dex\n":
        return dex_bytes, 0

    (
        string_ids_size,
        string_ids_off,
        type_ids_size,
        type_ids_off,
        proto_ids_size,
        proto_ids_off,
        field_ids_size,
        field_ids_off,
        method_ids_size,
        method_ids_off,
        class_defs_size,
        class_defs_off,
    ) = struct.unpack_from("<12I", dex_bytes, 56)

    # Decode string table lazily with cache
    str_cache = {}

    def get_str(sid: int) -> str:
        s = str_cache.get(sid)
        if s is None:
            off = struct.unpack_from("<I", dex_bytes, string_ids_off + sid * 4)[0]
            s = _decode_mutf8_at(dex_bytes, off)
            str_cache[sid] = s
        return s

    def get_type_desc(tid: int) -> str:
        sid = struct.unpack_from("<I", dex_bytes, type_ids_off + tid * 4)[0]
        return get_str(sid)

    def get_method_sig(mid: int) -> tuple[str, str, str]:
        cls_tid, proto_id, name_sid = struct.unpack_from("<HHI", dex_bytes, method_ids_off + mid * 8)
        _, ret_tid, _ = struct.unpack_from("<III", dex_bytes, proto_ids_off + proto_id * 12)
        return get_type_desc(cls_tid), get_str(name_sid), get_type_desc(ret_tid)

    # Locate target string IDs via binary-like scan
    target_strings = {
        "remaining_credential_count",
        "Failed to parse credential summary JSON",
        "brave_origin_credential_summary_cached",
        "SkusService is null, cannot request credential summary",
        "requestCredentialSummary profile is null or destroyed",
        "Billing service is not available",
        "BraveOriginSubsPrefs",
    }
    sid_to_tag = {}
    for sid in range(string_ids_size):
        val = get_str(sid)
        if val in target_strings:
            sid_to_tag[sid] = val

    if not sid_to_tag:
        return dex_bytes, 0

    # Find Boolean.TRUE field_id and Boolean.valueOf(Z) method_id if needed
    bool_true_field_idx = None
    for fid in range(field_ids_size):
        c_tid, t_tid, n_sid = struct.unpack_from("<HHI", dex_bytes, field_ids_off + fid * 8)
        if get_str(n_sid) == "TRUE" and get_type_desc(c_tid) == "Ljava/lang/Boolean;":
            bool_true_field_idx = fid
            break

    bool_valueof_method_idx = None
    for mid in range(method_ids_size):
        c_tid, proto_id, n_sid = struct.unpack_from("<HHI", dex_bytes, method_ids_off + mid * 8)
        if get_str(n_sid) == "valueOf" and get_type_desc(c_tid) == "Ljava/lang/Boolean;":
            shorty_sid, ret_tid, params_off = struct.unpack_from("<III", dex_bytes, proto_ids_off + proto_id * 12)
            if get_str(shorty_sid) == "LZ":
                bool_valueof_method_idx = mid
                break

    buf = bytearray(dex_bytes)
    patched = 0
    parsed_summary_patched = False
    request_summary_method_info = None

    # Walk all class_defs -> class_data_item -> direct & virtual methods
    for cid in range(class_defs_size):
        cdef_off = class_defs_off + cid * 32
        cls_tid, _, _, _, _, _, class_data_off, _ = struct.unpack_from("<8I", buf, cdef_off)
        if class_data_off == 0:
            continue

        p = class_data_off
        static_fields_n, p = _read_uleb128(buf, p)
        instance_fields_n, p = _read_uleb128(buf, p)
        direct_methods_n, p = _read_uleb128(buf, p)
        virtual_methods_n, p = _read_uleb128(buf, p)

        for _ in range(static_fields_n + instance_fields_n):
            _, p = _read_uleb128(buf, p)
            _, p = _read_uleb128(buf, p)

        for group_n in (direct_methods_n, virtual_methods_n):
            mid = 0
            for _ in range(group_n):
                mid_diff, p = _read_uleb128(buf, p)
                access_flags, p = _read_uleb128(buf, p)
                code_off, p = _read_uleb128(buf, p)
                mid += mid_diff
                if code_off == 0:
                    continue

                ref_sids = _scan_method_string_ids(buf, code_off)
                matched_tags = {sid_to_tag[s] for s in ref_sids if s in sid_to_tag}
                if not matched_tags:
                    continue

                cls_desc, m_name, ret_desc = get_method_sig(mid)
                ins_size = struct.unpack_from("<H", buf, code_off + 2)[0]
                is_static = bool(access_flags & 0x0008)

                # 1. parseCredentialSummary(SkusResult) -> boolean true
                if (
                    ("remaining_credential_count" in matched_tags or "Failed to parse credential summary JSON" in matched_tags)
                    and ret_desc == "Z"
                ):
                    _patch_method_return_in_place(buf, code_off, "true")
                    patched += 1
                    parsed_summary_patched = True
                    print(f"  [DEX:{dex_name}] Patched parseCredentialSummary ({cls_desc}->{m_name}{ret_desc}) -> true")

                # 2. getIsCredentialSummaryActiveCached() -> boolean true (when not inlined; 0 args, returns Z)
                elif (
                    "brave_origin_credential_summary_cached" in matched_tags
                    and is_static
                    and ins_size == 0
                    and ret_desc == "Z"
                ):
                    _patch_method_return_in_place(buf, code_off, "true")
                    patched += 1
                    print(
                        f"  [DEX:{dex_name}] Patched getIsCredentialSummaryActiveCached ({cls_desc}->{m_name}()Z) -> true"
                    )

                # 3. Record requestCredentialSummary(Profile, Callback)V
                if (
                    "SkusService is null, cannot request credential summary" in matched_tags
                    or "requestCredentialSummary profile is null or destroyed" in matched_tags
                ) and ret_desc == "V":
                    request_summary_method_info = (code_off, cls_desc, m_name)

    # 4. Also short-circuit requestCredentialSummary directly to Callback.onResult(true)
    #    so Settings -> Brave Origin & brave://origin deep links resolve in 0ms with zero SKU calls
    if request_summary_method_info is not None:
        code_off, cls_desc, m_name = request_summary_method_info

        def _is_cb_void_obj(mid: int) -> bool:
            cls_tid, proto_id, _ = struct.unpack_from("<HHI", buf, method_ids_off + mid * 8)
            cls_d = get_type_desc(cls_tid)
            if cls_d.startswith(("Ljava/", "Landroid/")):
                return False
            shorty_sid, ret_tid, params_off = struct.unpack_from("<III", buf, proto_ids_off + proto_id * 12)
            if get_str(shorty_sid) != "VL" or get_type_desc(ret_tid) != "V" or params_off == 0:
                return False
            p_cnt = struct.unpack_from("<I", buf, params_off)[0]
            if p_cnt != 1:
                return False
            p0_tid = struct.unpack_from("<H", buf, params_off + 4)[0]
            return get_type_desc(p0_tid) == "Ljava/lang/Object;"

        if _patch_request_summary_immediate_true(
            buf, code_off, bool_true_field_idx, bool_valueof_method_idx, _is_cb_void_obj
        ):
            patched += 1
            print(
                f"  [DEX:{dex_name}] Patched requestCredentialSummary ({cls_desc}->{m_name}) -> immediate Callback.onResult(true)"
            )

    if patched > 0:
        # Recompute DEX SHA-1 signature (bytes 12..32) over bytes 32..end
        sha1_digest = hashlib.sha1(buf[32:]).digest()
        buf[12:32] = sha1_digest
        # Recompute DEX Adler32 checksum (bytes 8..12) over bytes 12..end
        adler = zlib.adler32(buf[12:]) & 0xFFFFFFFF
        struct.pack_into("<I", buf, 8, adler)

    return bytes(buf), patched


# ==============================================================================
# 4. COMPILE OVERLAY VECTOR AXMLS & INIT PROVIDER DEX
# ==============================================================================

def compile_branding_vectors(aapt2: str, android_jar: str, branding_dir: Path, work_dir: Path) -> dict[str, bytes]:
    """
    Compiles our monochrome Brave Origin <vector> XMLs into binary AXML via aapt2
    so they can directly replace the shortened resource entries inside BraveMono*.apk.
    """
    res_dir = work_dir / "vec_res"
    (res_dir / "drawable").mkdir(parents=True, exist_ok=True)
    (res_dir / "drawable-night").mkdir(parents=True, exist_ok=True)

    for vec_xml in (branding_dir / "vectors").glob("*.xml"):
        shutil.copy2(vec_xml, res_dir / "drawable" / vec_xml.name)

    shutil.copy2(
        branding_dir / "android/res_brave_base/drawable/themed_app_icon.xml",
        res_dir / "drawable/themed_app_icon.xml",
    )
    shutil.copy2(
        branding_dir / "android/res_brave_base/drawable-night/themed_app_icon.xml",
        res_dir / "drawable-night/themed_app_icon.xml",
    )

    dummy_manifest = work_dir / "DummyManifest.xml"
    dummy_manifest.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.brave.origin.vec"/>\n',
        encoding="utf-8",
    )

    compiled_zip = work_dir / "vec_compiled.zip"
    linked_apk = work_dir / "vec_linked.apk"
    run_cmd([aapt2, "compile", "--dir", str(res_dir), "-o", str(compiled_zip)])
    run_cmd([
        aapt2, "link",
        "-I", android_jar,
        "--manifest", str(dummy_manifest),
        "--min-sdk-version", "24",
        "--target-sdk-version", "34",
        "-o", str(linked_apk),
        str(compiled_zip),
    ])

    compiled_vectors = {}
    with zipfile.ZipFile(linked_apk, "r") as zf:
        for name in zf.namelist():
            if name.startswith("res/drawable-night") and name.endswith("themed_app_icon.xml"):
                compiled_vectors["drawable/themed_app_icon:night"] = zf.read(name)
            elif name.startswith("res/drawable") and name.endswith(".xml"):
                stem = Path(name).stem
                compiled_vectors[f"drawable/{stem}"] = zf.read(name)

    return compiled_vectors


def compile_init_provider_dex(javac: str, d8: str, android_jar: str, java_src: Path, work_dir: Path) -> bytes:
    cls_dir = work_dir / "init_classes"
    dex_dir = work_dir / "init_dex"
    cls_dir.mkdir(parents=True, exist_ok=True)
    dex_dir.mkdir(parents=True, exist_ok=True)

    run_cmd([
        javac,
        "-encoding", "UTF-8",
        "-source", "8",
        "-target", "8",
        "-bootclasspath", android_jar,
        "-classpath", android_jar,
        "-d", str(cls_dir),
        str(java_src),
    ])
    class_files = sorted(str(p) for p in cls_dir.rglob("*.class"))
    run_cmd([d8, "--lib", android_jar, "--min-api", "24", "--output", str(dex_dir)] + class_files)
    return (dex_dir / "classes.dex").read_bytes()


# ==============================================================================
# 5. END-TO-END APK REPACK, 16KB ZIPALIGN & V1+V2+V3 SIGNING
# ==============================================================================

def ensure_keystore(keytool: str, ks_path: Path, ks_pass: str, key_alias: str):
    if ks_path.is_file():
        return
    ks_path.parent.mkdir(parents=True, exist_ok=True)
    run_cmd([
        keytool,
        "-genkeypair",
        "-keystore", str(ks_path),
        "-alias", key_alias,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10950",
        "-storepass", ks_pass,
        "-keypass", ks_pass,
        "-dname", "CN=Brave Origin, OU=Origin, O=Brave Origin Project, C=US",
    ])


def patch_apk(
    input_apk: Path,
    output_apk: Path,
    branding_dir: Path,
    init_java_src: Path,
    sdk_build_tools: Path,
    android_jar: Path,
    jdk_bin: Path,
    keystore_path: Path,
    ks_pass: str = "braveorigin",
    key_alias: str = "braveorigin",
):
    aapt2 = str(sdk_build_tools / "aapt2")
    d8 = str(sdk_build_tools / "d8")
    zipalign = str(sdk_build_tools / "zipalign")
    apksigner = str(sdk_build_tools / "apksigner")
    javac = str(jdk_bin / "javac")
    keytool = str(jdk_bin / "keytool")

    print(f"==> Analyzing official APK: {input_apk.name}")
    meta = parse_badging(aapt2, str(input_apk))
    pkg = meta["package"]
    print(f"  Package: {pkg} | Version: {meta['version_name']} ({meta['version_code']})")

    app_name_ids, file_map = parse_resource_table(aapt2, str(input_apk))
    print(f"  Discovered string/app_name IDs: {[hex(x) for x in sorted(app_name_ids)]}")

    with tempfile.TemporaryDirectory(prefix="brave_origin_patch_") as tmp:
        work = Path(tmp)

        # 1. Compile vector AXML overlays & BraveOriginInitProvider DEX
        compiled_vectors = compile_branding_vectors(aapt2, str(android_jar), branding_dir, work)
        init_provider_dex = compile_init_provider_dex(javac, d8, str(android_jar), init_java_src, work)

        # 2. Build exact zip_entry_path -> replacement_bytes map from official branding
        replacements: dict[str, bytes] = {}

        # Launcher icons (mipmap/app_icon, mipmap/ic_launcher, mipmap/ic_launcher_round)
        for res_key in ("mipmap/app_icon", "mipmap/ic_launcher", "mipmap/ic_launcher_round"):
            for cfg, zpath in file_map.get(res_key, []):
                if zpath.endswith(".xml"):
                    continue
                dpi = classify_dpi(cfg)
                src = branding_dir / f"android/res_brave_base/mipmap-{dpi}/app_icon.png"
                if not src.is_file():
                    src = branding_dir / f"android/mipmap-{dpi}/app_icon.png"
                replacements[zpath] = src.read_bytes()

        # Adaptive icon foreground & background
        for cfg, zpath in file_map.get("mipmap/layered_app_icon", []):
            if not zpath.endswith(".xml"):
                dpi = classify_dpi(cfg)
                replacements[zpath] = (
                    branding_dir / f"android/res_brave_base/mipmap-{dpi}/layered_app_icon.png"
                ).read_bytes()

        for cfg, zpath in file_map.get("mipmap/layered_app_icon_background", []):
            if not zpath.endswith(".xml"):
                dpi = classify_dpi(cfg)
                replacements[zpath] = (
                    branding_dir / f"android/res_brave_base/mipmap-{dpi}/layered_app_icon_background.png"
                ).read_bytes()

        # First-run / Welcome / About product logo
        for cfg, zpath in file_map.get("drawable/fre_product_logo", []):
            if not zpath.endswith(".xml"):
                dpi = classify_dpi(cfg)
                replacements[zpath] = (
                    branding_dir / f"android/res_brave/drawable-{dpi}/fre_product_logo.png"
                ).read_bytes()

        # Internal browser/notification/custom-tab logos (chromelogo16 & ic_chrome)
        for logo_res, fname in (("drawable/chromelogo16", "chromelogo16.png"), ("drawable/ic_chrome", "ic_chrome.png")):
            for cfg, zpath in file_map.get(logo_res, []):
                if not zpath.endswith(".xml"):
                    dpi = classify_dpi(cfg)
                    src_logo = branding_dir / f"android/res_chrome_logos/drawable-{dpi}/{fname}"
                    if src_logo.is_file():
                        replacements[zpath] = src_logo.read_bytes()

        # Monochrome Material You themed icon
        for cfg, zpath in file_map.get("drawable/themed_app_icon", []):
            if zpath.endswith(".xml"):
                key = "drawable/themed_app_icon:night" if "night" in cfg else "drawable/themed_app_icon"
                replacements[zpath] = compiled_vectors[key]

        # In-app Brave Lion vectors (Toolbar Shields Lion, Settings Account Lion, About/Widget Lion)
        for vec_name in (
            "drawable/ic_social_brave_release_favicon_fullheight_color",
            "drawable/ic_brave",
            "drawable/ic_brave_36",
            "drawable/ic_brave_logo",
        ):
            if vec_name in compiled_vectors:
                for cfg, zpath in file_map.get(vec_name, []):
                    if zpath.endswith(".xml"):
                        replacements[zpath] = compiled_vectors[vec_name]

        print(f"  Mapped {len(replacements)} official monochrome Brave Origin branding resource files")

        # 3. Stream-copy APK entries preserving exact per-entry compression method
        unaligned_apk = work / "unaligned.apk"
        dex_indices = []
        total_dex_patches = 0

        with zipfile.ZipFile(input_apk, "r") as zin, zipfile.ZipFile(unaligned_apk, "w") as zout:
            for info in zin.infolist():
                fn = info.filename
                # Strip old JAR signature block; apksigner will generate clean v1+v2+v3
                if fn.startswith("META-INF/") and (
                    fn == "META-INF/MANIFEST.MF"
                    or fn.endswith((".SF", ".RSA", ".DSA", ".EC"))
                    or fn.startswith("META-INF/com/android/build/gradle/")
                ):
                    continue

                m_dex = re.fullmatch(r"classes(\d*)\.dex", fn)
                if m_dex:
                    idx = 1 if m_dex.group(1) == "" else int(m_dex.group(1))
                    dex_indices.append(idx)

                raw_data = zin.read(fn)

                if fn == "AndroidManifest.xml":
                    raw_data = patch_axml_manifest(
                        raw_data,
                        app_name_ids,
                        new_label="Brave Origin",
                        provider_class=INIT_PROVIDER_CLASS,
                        provider_authority=f"{pkg}.braveorigin.init",
                    )
                elif fn in replacements:
                    raw_data = replacements[fn]
                elif m_dex:
                    raw_data, p_cnt = patch_dex_if_needed(fn, raw_data)
                    total_dex_patches += p_cnt

                new_info = zipfile.ZipInfo(filename=fn, date_time=(1981, 1, 1, 1, 1, 0))
                new_info.compress_type = info.compress_type
                new_info.external_attr = info.external_attr
                new_info.create_system = info.create_system

                # Critical Chromium invariants: native libs and resources.arsc MUST be ZIP_STORED
                if fn == "resources.arsc" or (fn.startswith("lib/") and fn.endswith(".so")):
                    new_info.compress_type = zipfile.ZIP_STORED

                if new_info.compress_type == zipfile.ZIP_STORED:
                    zout.writestr(new_info, raw_data, compress_type=zipfile.ZIP_STORED)
                else:
                    zout.writestr(new_info, raw_data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)

            # Append our standalone BraveOriginInitProvider as classes<N+1>.dex
            next_dex_num = (max(dex_indices) + 1) if dex_indices else 2
            extra_dex_name = f"classes{next_dex_num}.dex"
            dex_info = zipfile.ZipInfo(filename=extra_dex_name, date_time=(1981, 1, 1, 1, 1, 0))
            dex_info.compress_type = zipfile.ZIP_DEFLATED
            dex_info.external_attr = 0o644 << 16
            zout.writestr(dex_info, init_provider_dex, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
            print(f"  Added {extra_dex_name} ({len(init_provider_dex)} bytes) | Patched {total_dex_patches} Origin method(s)")

        # 4. Page-align uncompressed .so (16 KiB on build-tools 35+, 4 KiB -p on 34) and 4-byte stored entries
        aligned_apk = work / "aligned.apk"
        help_out = subprocess.run([zipalign], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout
        page_flags = ["-P", "16"] if "-P <pagesize_kb>" in help_out or "[-P " in help_out else ["-p"]
        print(f"==> Aligning APK (zipalign {' '.join(page_flags)} -f 4)")
        run_cmd([zipalign] + page_flags + ["-f", "4", str(unaligned_apk), str(aligned_apk)])
        unaligned_apk.unlink(missing_ok=True)

        # 5. Sign with v1 + v2 + v3 APK Signature Schemes
        ensure_keystore(keytool, keystore_path, ks_pass, key_alias)
        output_apk.parent.mkdir(parents=True, exist_ok=True)
        print(f"==> Signing {output_apk.name} (v1 + v2 + v3)")
        run_cmd([
            apksigner, "sign",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--ks", str(keystore_path),
            "--ks-key-alias", key_alias,
            "--ks-pass", f"pass:{ks_pass}",
            "--key-pass", f"pass:{ks_pass}",
            "--out", str(output_apk),
            str(aligned_apk),
        ])

    size_mb = output_apk.stat().st_size / (1024 * 1024)
    print(f"==> Done: {output_apk} ({size_mb:.2f} MB)")


def main():
    parser = argparse.ArgumentParser(description="Patch official BraveMono APK into Brave Origin")
    parser.add_argument("--input", required=True, type=Path, help="Input official BraveMono*.apk")
    parser.add_argument("--output", required=True, type=Path, help="Output BraveOrigin-*.apk")
    parser.add_argument(
        "--branding-dir",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "branding/brave_origin",
    )
    parser.add_argument(
        "--init-java",
        type=Path,
        default=Path(__file__).resolve().parent / "BraveOriginInitProvider.java",
    )
    parser.add_argument("--android-home", type=Path, default=Path(os.environ.get("ANDROID_HOME", "")))
    parser.add_argument("--build-tools-ver", default=os.environ.get("BUILD_TOOLS_VER", "34.0.0"))
    parser.add_argument("--platform-ver", default=os.environ.get("PLATFORM_VER", "android-34"))
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "")))
    parser.add_argument(
        "--keystore",
        type=Path,
        default=Path(__file__).resolve().parent.parent / ".keystore/brave-origin.jks",
    )
    args = parser.parse_args()

    sdk_bt = args.android_home / "build-tools" / args.build_tools_ver
    android_jar = args.android_home / "platforms" / args.platform_ver / "android.jar"
    jdk_bin = (args.java_home / "bin") if str(args.java_home) else Path(shutil.which("javac")).parent
    os.environ["PATH"] = f"{jdk_bin}:{os.environ.get('PATH', '')}"

    patch_apk(
        input_apk=args.input,
        output_apk=args.output,
        branding_dir=args.branding_dir,
        init_java_src=args.init_java,
        sdk_build_tools=sdk_bt,
        android_jar=android_jar,
        jdk_bin=jdk_bin,
        keystore_path=args.keystore,
    )


if __name__ == "__main__":
    main()
