#!/usr/bin/env python3
"""Compila le liste ufficiali Brave Shields + EasyList + EasyPrivacy + Cookie Notices
in asset compatti e ultra-veloci per Brave Origin Android (`app/assets/shields/`).

Uso:
  python3 tools/build_shields_lists.py /tmp/filter-sources
"""
import json
import os
import re
import sys

SRC_ROOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/filter-sources"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "assets", "shields")

DOMAIN_RULE_RE = re.compile(r"^\|\|([a-z0-9.-]+\.[a-z]{2,})\^(\$(.*))?$")
VALID_CSS_SEL_RE = re.compile(r"^[a-zA-Z0-9_#.\[\]='\"^$*~|:()>\s+-]+$")

# Domini essenziali da non bloccare mai a livello di intero dominio (CDN/siti principali)
NEVER_WHOLE_DOMAIN_BLOCK = {
    "google.com", "www.google.com", "gstatic.com", "googleapis.com",
    "youtube.com", "www.youtube.com", "m.youtube.com", "ytimg.com", "googlevideo.com",
    "github.com", "githubusercontent.com", "cloudflare.com", "jsdelivr.net",
    "unpkg.com", "cdnjs.cloudflare.com", "wikipedia.org", "wikimedia.org",
    "reddit.com", "redd.it", "redditstatic.com", "brave.com", "search.brave.com",
    "apple.com", "microsoft.com", "live.com", "office.com", "amazon.com", "amazon.it",
    "facebook.com", "instagram.com", "twitter.com", "x.com", "twimg.com", "linkedin.com",
    "whatsapp.com", "telegram.org", "t.me", "discord.com", "twitch.tv", "netflix.com",
    "spotify.com", "archive.org", "duckduckgo.com", "startpage.com", "qwant.com",
}


def extract_domains(files, allow_domain_scoped=False):
    domains = set()
    for path in files:
        if not os.path.exists(path):
            continue
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for raw in f:
                line = raw.strip()
                if not line or line.startswith("!") or line.startswith("[") or line.startswith("@@"):
                    continue
                m = DOMAIN_RULE_RE.match(line)
                if not m:
                    continue
                dom = m.group(1).lower().strip(".")
                opts = (m.group(3) or "").lower()
                if not dom or ".." in dom:
                    continue
                # Salta regole limitate a singoli domini sorgente o di rewrite complesso
                if opts:
                    opt_parts = [o.strip() for o in opts.split(",") if o.strip()]
                    skip = False
                    for o in opt_parts:
                        if o.startswith(("domain=", "denyallow=", "redirect=", "Redirect", "removeparam", "csp=", "rewrite=")):
                            if not allow_domain_scoped:
                                skip = True
                                break
                        if o in ("popup", "document") and dom in NEVER_WHOLE_DOMAIN_BLOCK:
                            skip = True
                            break
                    if skip:
                        continue
                if dom in NEVER_WHOLE_DOMAIN_BLOCK:
                    continue
                domains.add(dom)
    return prune_subdomains(domains)


def prune_subdomains(domains):
    """Rimuove sottodomini ridondanti se il dominio padre è già presente."""
    kept = set()
    for d in sorted(domains, key=lambda x: x.count(".")):
        parts = d.split(".")
        redundant = False
        for i in range(1, len(parts) - 1):
            parent = ".".join(parts[i:])
            if parent in kept:
                redundant = True
                break
        if not redundant:
            kept.add(d)
    return sorted(kept)


def extract_cosmetic_selectors(files, limit=3000):
    selectors = []
    seen = set()
    bad_tokens = (":-abp-", ":has-text(", ":xpath(", ":upward(", ":remove(", ":style(", ":matches-css(", ":min-text-length")
    for path in files:
        if not os.path.exists(path):
            continue
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for raw in f:
                line = raw.strip()
                if not line or line.startswith("!") or line.startswith("@@"):
                    continue
                if line.startswith("##"):
                    sel = line[2:].strip()
                else:
                    continue
                if not sel or len(sel) > 140 or sel in seen:
                    continue
                if any(tok in sel for tok in bad_tokens):
                    continue
                if ":has(" in sel or "\\" in sel or "{" in sel or "}" in sel:
                    continue
                if not VALID_CSS_SEL_RE.match(sel):
                    continue
                seen.add(sel)
                selectors.append(sel)
                if len(selectors) >= limit:
                    return selectors
    return selectors


def build_css_chunks(selectors, chunk_size=50):
    lines = []
    for i in range(0, len(selectors), chunk_size):
        group = ", ".join(selectors[i:i + chunk_size])
        lines.append(f"{group} {{ display: none !important; visibility: hidden !important; }}")
    return "\n".join(lines) + ("\n" if lines else "")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    el = os.path.join(SRC_ROOT, "easylist", "easylist")
    ep = os.path.join(SRC_ROOT, "easylist", "easyprivacy")
    ec = os.path.join(SRC_ROOT, "easylist", "easylist_cookie")
    bl = os.path.join(SRC_ROOT, "adblock-lists", "brave-lists")

    ad_domains = extract_domains([
        os.path.join(el, "easylist_adservers.txt"),
        os.path.join(el, "easylist_adservers_popup.txt"),
        os.path.join(el, "easylist_thirdparty.txt"),
        os.path.join(ep, "easyprivacy_trackingservers.txt"),
        os.path.join(ep, "easyprivacy_trackingservers_general.txt"),
        os.path.join(ep, "easyprivacy_trackingservers_international.txt"),
        os.path.join(ep, "easyprivacy_trackingservers_thirdparty.txt"),
        os.path.join(ep, "easyprivacy_trackingservers_admiral.txt"),
        os.path.join(ep, "easyprivacy_trackingservers_mining.txt"),
        os.path.join(ep, "easyprivacy_trackingservers_notifications.txt"),
        os.path.join(ep, "easyprivacy_thirdparty.txt"),
        os.path.join(ep, "easyprivacy_thirdparty_international.txt"),
        os.path.join(bl, "brave-specific.txt"),
        os.path.join(bl, "brave-android-specific.txt"),
    ])

    cookie_domains = extract_domains([
        os.path.join(ec, "easylist_cookie_thirdparty.txt"),
        os.path.join(ec, "easylist_cookie_general_block.txt"),
        os.path.join(bl, "brave-cookie-specific.txt"),
    ])
    # Aggiungiamo CMP noti di terze parti
    extra_cmp = [
        "cookielaw.org", "cookiebot.com", "consent.cookiebot.com", "consentmanager.net",
        "didomi.io", "sdk.privacy-center.org", "iubenda.com", "cdn.iubenda.com",
        "quantcast.mgr.consensu.org", "consensu.org", "trustarc.com", "truste.com",
        "evidon.com", "usercentrics.eu", "app.usercentrics.eu", "osano.com",
        "termly.io", "cookieyes.com", "cdn-cookieyes.com", "complianz.io",
    ]
    cookie_domains = prune_subdomains(set(cookie_domains) | set(extra_cmp))

    social_domains = extract_domains([
        os.path.join(bl, "brave-social.txt"),
    ])
    extra_social = [
        "connect.facebook.net", "platform.twitter.com", "Syndication.twitter.com",
        "platform.linkedin.com", "px.ads.linkedin.com", "analytics.tiktok.com",
        "widgets.pinterest.com", "ct.pinterest.com", "sc-static.net", "tr.snapchat.com",
        "addthis.com", "sharethis.com", "disqus.com",
    ]
    social_domains = prune_subdomains(set(social_domains) | {d.lower() for d in extra_social})

    url_patterns = [
        "/pagead/js/", "/pagead/lvz", "/pagead/gen_204", "/adsbygoogle.js",
        "/google-analytics.com/analytics.js", "/gtag/js?", "/gtm.js?",
        "/fbevents.js", "/bat.bing.com/bat.js", "/adsct?", "/collect?v=1&_v=",
        "/adserver/", "/adservice.", "/doubleclick.", "/prebid", "/gpt.js",
        "/taboola.", "/outbrain.", "/criteo.", "/popunder", "/popads",
        "/adframe.", "/banner_ads/", "/sponsored_links", "/track/imp",
    ]

    # Query params da Brave query-filter.json + standard tracking params
    query_params = set([
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_name", "utm_reader", "utm_viz_id", "utm_pubreferrer",
        "utm_swu", "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid",
        "twclid", "ttclid", "li_fat_id", "mc_cid", "mc_eid", "igshid", "yclid",
        "_gl", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id",
        "vero_id", "vero_conv", "wickedid", "rb_clickid", "s_kwcid", "spm",
    ])
    qf_path = os.path.join(bl, "query-filter.json")
    if os.path.exists(qf_path):
        with open(qf_path, "r", encoding="utf-8") as f:
            data = json.load(f)
            for entry in data:
                if entry.get("include") == ["*://*/*"] and not entry.get("exclude"):
                    for p in entry.get("params", []):
                        query_params.add(p)

    ad_selectors = extract_cosmetic_selectors([
        os.path.join(el, "easylist_general_hide.txt"),
    ], limit=2200)

    cookie_selectors = extract_cosmetic_selectors([
        os.path.join(ec, "easylist_cookie_general_hide.txt"),
        os.path.join(bl, "brave-cookie-specific.txt"),
    ], limit=1800)

    yt_shorts_css = """
ytd-rich-section-renderer:has(a[href^="/shorts/"]),
ytd-reel-shelf-renderer,
ytm-reel-shelf-renderer,
ytm-shorts-lockup-view-model,
ytm-shorts-lockup-view-model-v2,
.ytGridShelfViewModelHost,
.big-shorts-singleton,
ytm-pivot-bar-item-renderer:has(.pivot-shorts),
.pivot-shorts.pivot-bar-item-tab,
a.yt-simple-endpoint[title="Shorts"],
ytd-mini-guide-entry-renderer[aria-label="Shorts"],
ytd-guide-entry-renderer:has(a[title="Shorts"]),
ytd-video-renderer:has(a[href^="/shorts/"]),
ytm-video-with-context-renderer:has(a[href^="/shorts/"]),
ytm-media-item:has(a[href^="/shorts/"]) {
  display: none !important;
}
""".strip() + "\n"

    yt_distracting_css = """
#related #items ytd-compact-video-renderer,
ytd-watch-next-secondary-results-renderer #related,
.ytp-ce-element,
.ytp-cards-teaser,
.ytp-endscreen-content,
ytd-merch-shelf-renderer,
ytd-Universal-watch-card-renderer {
  display: none !important;
}
""".strip() + "\n"

    with open(os.path.join(OUT_DIR, "ad_domains.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(ad_domains) + "\n")
    with open(os.path.join(OUT_DIR, "cookie_domains.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(cookie_domains) + "\n")
    with open(os.path.join(OUT_DIR, "social_domains.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(social_domains) + "\n")
    with open(os.path.join(OUT_DIR, "url_patterns.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(url_patterns) + "\n")
    with open(os.path.join(OUT_DIR, "query_params.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(query_params)) + "\n")
    with open(os.path.join(OUT_DIR, "cosmetic_ads.css"), "w", encoding="utf-8") as f:
        f.write(build_css_chunks(ad_selectors))
    with open(os.path.join(OUT_DIR, "cosmetic_cookies.css"), "w", encoding="utf-8") as f:
        f.write(build_css_chunks(cookie_selectors))
    with open(os.path.join(OUT_DIR, "yt_shorts.css"), "w", encoding="utf-8") as f:
        f.write(yt_shorts_css)
    with open(os.path.join(OUT_DIR, "yt_distracting.css"), "w", encoding="utf-8") as f:
        f.write(yt_distracting_css)

    print(f"ad_domains:       {len(ad_domains)}")
    print(f"cookie_domains:   {len(cookie_domains)}")
    print(f"social_domains:   {len(social_domains)}")
    print(f"query_params:     {len(query_params)}")
    print(f"ad_selectors:     {len(ad_selectors)}")
    print(f"cookie_selectors: {len(cookie_selectors)}")


if __name__ == "__main__":
    main()
