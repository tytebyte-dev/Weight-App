# NewTube website

The site for NewTube, served at <https://newtube.org/> (custom domain on GitHub Pages).
`.github/workflows/pages.yml` builds this folder and copies the result to the
[newtube-app/newtube-app.github.io](https://github.com/newtube-app/newtube-app.github.io) repo,
which GitHub Pages serves, and turns the old address (aleixrodriala.github.io/newtube) into a
redirect. Edit the site here, never in that repo.

## Pages

Four pages, each in English at `/` and in Spanish (Spain) at `/es/`:

| Page | English | Spanish |
|:--|:--|:--|
| Home: what NewTube is, the chapter demo, sign-in, the SmartTube credit, a trust summary | `/` | `/es/` |
| Download: which APK, install, updates, checking the file | `/download/` | `/es/download/` |
| Trust: who makes it, build and signing, VirusTotal, data flows, updater, source, speed | `/trust/` | `/es/trust/` |
| FAQ | `/faq/` | `/es/faq/` |

Plus a bilingual `404.html`. Links to the old one-page site's anchors (`/#download`,
`/#trust`, `/#faq`, `/#sign-in`, `/#how-fast`, `/#features`, `/#smarttube`) are forwarded to
the page that replaced them by `main.js`.

## Source and build

```
src/layout.html        the shared document: head, header, footer, with {{placeholders}}
src/strings.json       per-language strings for the layout (nav, footer, disclaimers, JSON-LD)
src/pages/<lang>/*.html page bodies, each starting with a <!--meta {...}--> JSON block
src/static/            copied as-is: styles.css, main.js, assets/, CNAME, robots.txt,
                       the Search Console file
build.py               renders everything into _site/ (git-ignored), plus sitemap.xml
```

`build.py` uses the Python standard library only. It fails on a page missing in one language,
on an unknown `{{placeholder}}` and on a page without its meta block, so the language switch and
the hreflang links never point at a 404. Page meta takes `title`, `description`, an optional
`og_title`, and two flags: `"jsonld": true` adds the app's `SoftwareApplication` data (home
pages) and `"faqld": true` adds `FAQPage` data built from the page's own `<details>` questions,
so the two can't drift apart.

```sh
python3 website/build.py            # writes website/_site/
python3 website/build.py --serve    # builds, then serves it on http://localhost:8000/
```

Paths are root-absolute (`/assets/…`, `/download/`), so preview through a server at the root,
not by opening the files.

`website/CNAME` lives in `src/static/` and must stay, or the deploy drops the domain.

## What the pages load

Everything is self-hosted: no framework, no analytics, no third-party scripts or fonts, and no
requests to other sites at all (the Content-Security-Policy meta tag in `layout.html` enforces it,
`connect-src 'none'`). The latest release's version, date, direct APK links and sizes, and the
GitHub star count, are read from the GitHub API **at build time** and written into the pages, so
the download buttons fetch the APK itself. `pages.yml` therefore rebuilds the site when a release
is published and once a day (for the stars), and builds with `--require-release`: if the API call
fails, the deploy stops and the previous site stays up. Locally, `--offline` skips the call and
links `releases/latest` instead.

- Headings use a subset of [Roboto Flex](https://github.com/googlefonts/roboto-flex) (Latin and
  Spanish characters, weights 300–600, optical size axis kept), 62 KiB as WOFF2. Its license is
  `assets/fonts/OFL-RobotoFlex.txt`. Body text uses the system font (Roboto on Android).
- The chapter demo (`assets/video/demo.mp4`) loads and plays only when it scrolls into view, and
  never plays by itself with reduced motion or Save-Data on.
- Light and dark follow the system setting, including the phone screenshot in the hero.
- Motion is CSS except the stopwatch: the first screen rises in on load, later sections reveal as
  you scroll (scroll-driven animations, so browsers without them just show the page), pages
  crossfade with the header held still (cross-document view transitions), FAQ answers open
  smoothly, and the speed numbers count up in real time (`main.js`). The system's "reduce
  motion" setting turns all of it off.

## Copy rules

The copy follows the repository's `README.md`, which is the source of truth for features,
numbers and wording; change the README first, then mirror it here, in both languages.

- "Unofficial" (*no oficial*) stays next to "SmartTube for phones" (*SmartTube para móviles*),
  and SmartTube and @yuliskov are credited.
- No ad-free or Premium claims in headlines, no "the only" or "the first", and every number
  points to its measurement.
- The speed numbers are the README's September 2026 table, timed on the builds that became
  1.10.1. They keep that label until a current release is re-timed, with the script committed
  this time (the round-3 scripts were never saved). Network work is "NewTube's changes to
  SmartTube's engine", never "its own engine", and nothing is compared with SmartTube or YouTube.
- Saving is "save for offline" (*guardar para ver sin conexión*), never "download YouTube videos".
- No YouTube logo or red, and the only videos shown are Blender Foundation open movies
  (Creative Commons), credited where they appear.
- Spanish uses the app's own words for its screens (*Tú*, *Iniciar sesión*, *Continuar con
  Google*, *la TV*).

## Search

The pages target the searches "SmartTube for phone(s)", "SmartTube mobile" and "SmartTube android
phone" (and their Spanish forms): the home title and H1 and the FAQ say it, always with
"unofficial" and "built on SmartTube by @yuliskov" next to it. Google stopped showing FAQ rich
results in May 2026; the `FAQPage` data stays because it is generated and costs nothing.

### Google Search Console

The verification file is `src/static/google49bffcec5db4ccd0.html`; keep it unchanged for as long
as the property should stay verified. After a deploy that changes pages, submit `sitemap.xml`
again under **Sitemaps** (it lists both languages with hreflang alternates).

## Assets

- `assets/icon.svg`: the app icon, also the favicon. `assets/apple-touch-icon.png`: 180 px.
  `assets/icon-512.png`: structured-data image. `assets/og-image.jpg` and `og-image-es.jpg`: the
  1280x640 social previews (the hero's headline and phone; template in the launch folder,
  `site-redesign/og/og-template.html`); only Blender films may appear in them.
- `assets/screens/*.webp`: phone screenshots at 360 and 720 px wide.
- `assets/video/demo.mp4` and `demo-poster.webp`: the chapter demo, recorded on an emulator with
  Blender Studio's channel and open movies.
