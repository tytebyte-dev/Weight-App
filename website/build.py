#!/usr/bin/env python3
"""Builds newtube.org. Standard library only, so it runs anywhere Python 3.9+ does.

    python3 website/build.py              # writes website/_site/
    python3 website/build.py --out DIR    # writes DIR instead
    python3 website/build.py --serve      # builds, then serves _site/ on http://localhost:8000/
    python3 website/build.py --offline    # no GitHub API call: release links fall back to releases/latest

The latest release (version, date, a direct link and size for each APK) and the star count are read
from the GitHub API at build time and written into the pages, so the download buttons fetch the APK
itself and the site makes no third-party requests. Set GITHUB_TOKEN to avoid the API's rate limit.
CI passes --require-release, so a failed API call stops the deploy instead of shipping fallback links.

Sources (all under website/src/):
  pages/<lang>/<name>.html  page bodies. Each starts with a <!--meta {...}--> JSON block
                            (title, description; optional og_title, and the flags "jsonld" for the
                            app's SoftwareApplication data and "faqld" for FAQPage data built from
                            the page's <details> questions) followed by the <main> content.
  layout.html               the shared document: head, header and footer, with {{placeholders}}.
  strings.json              per-language strings the layout and the build use.
  static/                   copied as-is: CSS, JS, assets, CNAME, robots.txt, the Search Console file.

English lives at /, Spanish at /es/. Every page exists in both languages; the build
fails if one is missing, so the language switch and the hreflang links never point at a 404.
"""
import argparse
import html
import json
import os
import re
import shutil
import sys
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
SITE = "https://newtube.org"
LANGS = ("en", "es")
PAGES = ("index", "download", "trust", "faq")
REPO = "aleixrodriala/newtube"
RELEASES_URL = "https://github.com/%s/releases/latest" % REPO
ABIS = ("arm64-v8a", "armeabi-v7a", "universal", "x86")
MONTHS = {
    "en": ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
    "es": ("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic"),
}
META_RE = re.compile(r"\A\s*<!--meta\s+(\{.*?\})\s*-->\s*", re.S)
SLOT_RE = re.compile(r"\{\{\s*([a-zA-Z0-9_.]+)\s*\}\}")


def github(path):
    req = urllib.request.Request("https://api.github.com/repos/%s%s" % (REPO, path),
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "newtube-site-build"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=20) as res:
        return json.load(res)


def fetch_release():
    """The newest published release (GitHub's "latest": not a draft, not a prerelease) and the stars."""
    rel = next((r for r in github("/releases?per_page=10") if not r["draft"] and not r["prerelease"]), None)
    if rel is None:
        raise RuntimeError("no published release")
    apks = {}
    for a in rel["assets"]:
        m = re.search(r"_(%s)\.apk$" % "|".join(map(re.escape, ABIS)), a["name"])
        if m and a["browser_download_url"].startswith("https://github.com/%s/releases/download/" % REPO):
            apks[m.group(1)] = {"url": a["browser_download_url"], "name": a["name"], "size": a["size"]}
    if "arm64-v8a" not in apks:
        raise RuntimeError("release %s has no arm64-v8a APK" % rel["tag_name"])
    return {"tag": rel["tag_name"], "date": rel["published_at"], "apks": apks,
            "stars": github("")["stargazers_count"]}


def release_ctx(lang, rel):
    """Placeholders for the download buttons. Without release data every link is releases/latest
    and the version, size and star lines are left empty (the CSS hides empty ones)."""
    ctx = {"release_version": "", "release_date": "", "release_line": "", "stars": "",
           "apk_url": RELEASES_URL, "apk_size": ""}
    for abi in ABIS:
        key = abi.replace("-", "_")
        ctx["apk_url_" + key], ctx["apk_size_" + key] = RELEASES_URL, ""
        ctx["apk_name_" + key] = "NewTube_&lt;version&gt;_%s.apk" % abi
    if not rel:
        return ctx
    d = datetime.strptime(rel["date"][:10], "%Y-%m-%d")
    ctx["release_version"] = rel["tag"] if rel["tag"].startswith("v") else "v" + rel["tag"]
    ctx["release_date"] = "%d %s %d" % (d.day, MONTHS[lang][d.month - 1], d.year)
    ctx["release_line"] = "%s · %s" % (ctx["release_version"], ctx["release_date"])
    ctx["stars"] = "{:,}".format(rel["stars"]).replace(",", "." if lang == "es" else ",")
    for abi, apk in rel["apks"].items():
        key = abi.replace("-", "_")
        ctx["apk_url_" + key] = html.escape(apk["url"])
        ctx["apk_size_" + key] = "%d MB" % round(apk["size"] / 1e6)
        ctx["apk_name_" + key] = html.escape(apk["name"])
    ctx["apk_url"], ctx["apk_size"] = ctx["apk_url_arm64_v8a"], ctx["apk_size_arm64_v8a"]
    return ctx


def page_path(lang, page):
    prefix = "" if lang == "en" else "/" + lang
    if page == "index":
        return prefix + "/"
    return "%s/%s/" % (prefix, page)


def fill(template, ctx, where):
    def sub(m):
        key = m.group(1)
        if key not in ctx:
            sys.exit("build.py: %s uses {{%s}}, which nothing defines" % (where, key))
        return str(ctx[key])
    return SLOT_RE.sub(sub, template)


def read_page(lang, name):
    src = SRC / "pages" / lang / (name + ".html")
    if not src.exists():
        sys.exit("build.py: missing %s (every page needs every language)" % src.relative_to(ROOT))
    text = src.read_text(encoding="utf-8")
    m = META_RE.match(text)
    if not m:
        sys.exit("build.py: %s must start with a <!--meta {...}--> block" % src.relative_to(ROOT))
    return json.loads(m.group(1)), text[m.end():]


def json_ld(lang, strings):
    data = {
        "@context": "https://schema.org",
        "@type": "SoftwareApplication",
        "name": "NewTube",
        "alternateName": "NewTube for Android",
        "description": strings["ld_description"],
        "inLanguage": lang,
        "applicationCategory": "MultimediaApplication",
        "applicationSubCategory": "YouTube client",
        "operatingSystem": "Android 7.0 or newer",
        "softwareRequirements": "Android 7.0 or newer; no Google Play Services needed",
        "url": SITE + page_path(lang, "index"),
        "downloadUrl": "https://github.com/aleixrodriala/newtube/releases/latest",
        "codeRepository": "https://github.com/aleixrodriala/newtube",
        "image": SITE + "/assets/icon-512.png",
        "screenshot": [SITE + "/assets/screens/watch-720.webp", SITE + "/assets/screens/downloads-720.webp"],
        "license": "https://opensource.org/licenses/MIT",
        "isAccessibleForFree": True,
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
        "author": {"@type": "Person", "name": "aleixrodriala", "url": "https://github.com/aleixrodriala"},
        "isBasedOn": {
            "@type": "SoftwareApplication",
            "name": "SmartTube",
            "url": "https://github.com/yuliskov/SmartTube",
            "author": {"@type": "Person", "name": "yuliskov", "url": "https://github.com/yuliskov"},
        },
        "featureList": strings["ld_features"],
    }
    body = json.dumps(data, ensure_ascii=False, indent=2).replace("</", "<\\/")
    return '<script type="application/ld+json">\n%s\n  </script>' % body


FAQ_RE = re.compile(r"<details[^>]*>\s*<summary>(.*?)</summary>\s*<div>(.*?)</div>\s*</details>", re.S)
BLOCK_TAG_RE = re.compile(r"</?(?:p|div|ol|ul|li|br)\b[^>]*>", re.I)


def plain(fragment):
    text = re.sub(r"<[^>]+>", "", BLOCK_TAG_RE.sub(" ", fragment))
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def faq_ld(lang, body, where):
    """FAQPage data made from the page's own <details> questions, so it can't drift from them.
    Google stopped showing FAQ rich results in May 2026; other readers of schema.org still use it."""
    items = [
        {"@type": "Question", "name": plain(q), "acceptedAnswer": {"@type": "Answer", "text": plain(a)}}
        for q, a in FAQ_RE.findall(body)
    ]
    if not items:
        sys.exit("build.py: %s asks for faqld but has no <details><summary>…</summary><div>…</div></details>" % where)
    data = {"@context": "https://schema.org", "@type": "FAQPage", "inLanguage": lang, "mainEntity": items}
    body = json.dumps(data, ensure_ascii=False, indent=2).replace("</", "<\\/")
    return '<script type="application/ld+json">\n%s\n  </script>' % body


def build(out, rel):
    layout = (SRC / "layout.html").read_text(encoding="utf-8")
    all_strings = json.loads((SRC / "strings.json").read_text(encoding="utf-8"))

    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(SRC / "static", out)

    sitemap = []
    for lang in LANGS:
        t = all_strings[lang]
        other = "es" if lang == "en" else "en"
        for name in PAGES + ("404",) if lang == "en" else PAGES:
            if name == "404":
                meta, body = read_page("en", "404")
            else:
                meta, body = read_page(lang, name)
            path = "/404.html" if name == "404" else page_path(lang, name)
            ctx = {"t." + k: v for k, v in t.items() if isinstance(v, str)}
            ctx.update({
                "lang": lang,
                "title": html.escape(meta["title"]),
                "description": html.escape(meta["description"]),
                "canonical": SITE + path,
                "robots": "noindex" if name == "404" else "index, follow, max-image-preview:large",
                "alt_en": SITE + page_path("en", "index" if name == "404" else name),
                "alt_es": SITE + page_path("es", "index" if name == "404" else name),
                "og_locale": t["og_locale"],
                "og_locale_alt": all_strings[other]["og_locale"],
                "og_title": html.escape(meta.get("og_title", meta["title"])),
                "og_image_alt": html.escape(t["og_image_alt"]),
                "home_href": page_path(lang, "index"),
                "download_href": page_path(lang, "download"),
                "trust_href": page_path(lang, "trust"),
                "faq_href": page_path(lang, "faq"),
                "switch_href": page_path(other, "index" if name == "404" else name),
                "switch_lang": other,
                "switch_label": t["switch_label"],
                "switch_title": html.escape(t["switch_title"]),
                "page": name,
            })
            ctx.update(release_ctx(lang, rel))
            for nav in ("download", "trust", "faq"):
                ctx["cur_" + nav] = ' aria-current="page"' if nav == name else ""
            where = "pages/%s/%s.html" % (lang, name)
            ctx["body"] = fill(body, ctx, where)
            ld = [json_ld(lang, t)] if meta.get("jsonld") else []
            if meta.get("faqld"):
                ld.append(faq_ld(lang, ctx["body"], where))
            ctx["jsonld"] = "\n  ".join(ld)
            doc = fill(layout, ctx, "layout.html")
            target = out / ("404.html" if name == "404" else path.strip("/") + "/index.html" if path != "/" else "index.html")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(doc, encoding="utf-8")
            if name != "404":
                sitemap.append(name)

    urls = []
    for name in PAGES:
        alts = "".join(
            '\n    <xhtml:link rel="alternate" hreflang="%s" href="%s"/>' % (l, SITE + page_path(l, name)) for l in LANGS
        ) + '\n    <xhtml:link rel="alternate" hreflang="x-default" href="%s"/>' % (SITE + page_path("en", name))
        for lang in LANGS:
            urls.append("  <url>\n    <loc>%s</loc>%s\n  </url>" % (SITE + page_path(lang, name), alts))
    (out / "sitemap.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'
        + "\n".join(urls) + "\n</urlset>\n",
        encoding="utf-8",
    )
    print("built %d pages into %s" % (len(sitemap) + 1, out))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, default=ROOT / "_site")
    ap.add_argument("--serve", action="store_true", help="serve the result on http://localhost:8000/")
    ap.add_argument("--offline", action="store_true", help="skip the GitHub API; link releases/latest")
    ap.add_argument("--require-release", action="store_true", help="fail if the GitHub API call fails (CI)")
    args = ap.parse_args()
    rel = None
    if not args.offline:
        try:
            rel = fetch_release()
            print("release %s, %d APKs, %s stars" % (rel["tag"], len(rel["apks"]), rel["stars"]))
        except Exception as e:  # network, rate limit, no release yet
            if args.require_release:
                sys.exit("build.py: could not read the latest release from GitHub: %s" % e)
            print("build.py: no release data (%s); links fall back to releases/latest" % e, file=sys.stderr)
    build(args.out.resolve(), rel)
    if args.serve:
        import functools
        import http.server
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(args.out.resolve()))
        print("serving on http://localhost:8000/ (Ctrl+C to stop)")
        http.server.ThreadingHTTPServer(("127.0.0.1", 8000), handler).serve_forever()


if __name__ == "__main__":
    main()
