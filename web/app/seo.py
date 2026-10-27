"""Pages for search engines: the home page and one downloader page per popular site, all built
from static/index.html with their own title, intro and help text, plus sitemap.xml and
robots.txt. Every page is the working downloader, not a separate landing page."""
from __future__ import annotations

import html
import json
import re
from functools import lru_cache
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, PlainTextResponse, Response

from . import config

STATIC = Path(__file__).resolve().parent.parent / "static"

# slug -> page. "name" is the site as people write it; "kind" picks the tab that opens first.
PAGES: dict[str, dict] = {
    "youtube-downloader": {
        "name": "YouTube", "kind": "video",
        "title": "YouTube video downloader: save in 4K, 1080p or MP3",
        "desc": "Download YouTube videos, Shorts and playlists in up to 4K or as MP3. Pick the audio language, subtitles or a clip. Free, no sign-up.",
        "h1": "Download YouTube videos.",
        "lead": "Paste a YouTube link to save the video in up to 4K, or just the audio as MP3. Works with Shorts and whole playlists, and lets you pick the audio language, subtitles or a clip.",
        "what": "YouTube videos, Shorts, live recordings and playlists",
        "faq": [
            ("Can I download a whole YouTube playlist?", "Yes. Paste the playlist link, pick the videos you want and download them together as one zip."),
            ("Which qualities are there?", "Whatever the video has, up to 4K or 8K, plus MP3, M4A and Opus audio."),
            ("Can I download only part of a video?", "Yes. Open More options and set a start and end time, or pick a chapter."),
            ("Why won't a video download?", "Private, members-only and some age-restricted videos can't be downloaded. If YouTube is busy, try again a little later."),
        ],
    },
    "youtube-to-mp3": {
        "name": "YouTube to MP3", "kind": "audio", "site": "YouTube", "howto": "How to convert YouTube to MP3",
        "title": "YouTube to MP3 converter: up to 320 kbps with cover art",
        "desc": "Turn YouTube videos and playlists into MP3 at up to 320 kbps, with the title and cover art added. Also M4A and Opus. Free, no sign-up.",
        "h1": "YouTube to MP3.",
        "lead": "Paste a YouTube link and save the audio as MP3 at up to 320 kbps, with the cover art and title filled in. Convert a whole playlist at once, or choose M4A or Opus.",
        "what": "the audio of YouTube videos, music and playlists",
        "faq": [
            ("What MP3 quality can I pick?", "128, 192, 256 or 320 kbps. You can also keep the original audio as M4A or Opus, which skips re-encoding."),
            ("Is the cover art added?", "Yes, when the video has a thumbnail it's added as the cover, with the title."),
            ("Can I convert a playlist to MP3?", "Yes. Paste the playlist link, choose Audio, and download all of them as one zip."),
        ],
    },
    "instagram-downloader": {
        "name": "Instagram", "kind": "video",
        "title": "Instagram video and Reels downloader",
        "desc": "Save Instagram Reels and videos in full quality, or as MP3. Paste the link and download. Free, no sign-up.",
        "h1": "Download Instagram Reels and videos.",
        "lead": "Paste a link to a public Instagram Reel or video post to save it in full quality, or keep just the sound as MP3.",
        "what": "public Instagram Reels and video posts",
        "faq": [
            ("Where do I find the link?", "Tap the share button (or the three dots) on the Reel and choose Copy link."),
            ("Can I download private accounts?", "No. Only public posts can be downloaded."),
            ("Can I save only the audio?", "Yes. Choose the Audio tab to get MP3, M4A or Opus."),
        ],
    },
    "tiktok-downloader": {
        "name": "TikTok", "kind": "video",
        "title": "TikTok video downloader: save TikToks as MP4 or MP3",
        "desc": "Download TikTok videos as MP4 in full quality, or save the sound as MP3. Paste the link and go. Free, no sign-up.",
        "h1": "Download TikTok videos.",
        "lead": "Paste a TikTok link to save the video as MP4 in the best quality available, or save the sound as MP3.",
        "what": "public TikTok videos",
        "faq": [
            ("Where do I find the link?", "Tap Share on the video, then Copy link."),
            ("Can I save a TikTok sound?", "Yes. Choose the Audio tab to save it as MP3."),
        ],
    },
    "facebook-video-downloader": {
        "name": "Facebook", "kind": "video",
        "title": "Facebook video downloader: save videos and Reels in HD",
        "desc": "Download public Facebook videos and Reels in HD, or as MP3. Paste the link and download. Free, no sign-up.",
        "h1": "Download Facebook videos.",
        "lead": "Paste a link to a public Facebook video or Reel to save it in HD, or keep just the audio.",
        "what": "public Facebook videos, Reels and Watch videos",
        "faq": [
            ("Where do I find the link?", "Open the video, tap Share, then Copy link."),
            ("Can I download from private groups?", "No. Only videos anyone can see can be downloaded."),
        ],
    },
    "twitter-video-downloader": {
        "name": "X (Twitter)", "kind": "video",
        "title": "X (Twitter) video downloader: save videos and GIFs",
        "desc": "Download videos and GIFs from X (Twitter) posts in the best quality, or as MP3. Free, no sign-up.",
        "h1": "Download X (Twitter) videos.",
        "lead": "Paste the link to a post on X (Twitter) to save its video or GIF in the best quality, or as MP3.",
        "what": "videos and GIFs in public posts on X (Twitter)",
        "faq": [
            ("Where do I find the link?", "Tap Share on the post, then Copy link."),
            ("Are GIFs saved as video?", "Yes. X stores GIFs as short videos, so they're saved as MP4."),
        ],
    },
}

HOME_FAQ = [
    ("Is Haul free?", "Yes. There's no account, no sign-up and no ads."),
    ("Which sites work?", "YouTube, Instagram, TikTok, Facebook, X (Twitter), Vimeo, SoundCloud and more than 1,000 others."),
    ("Where do downloads happen?", "With the Haul extension and PC helper installed, on your own computer. Otherwise on our server, which deletes each file a few minutes after you download it."),
    ("Can I download several videos at once?", "Yes. Paste a playlist link, or several links on separate lines."),
]


def base_url(request: Request) -> str:
    return (config.PUBLIC_URL or str(request.base_url)).rstrip("/")


def _e(s: str) -> str:
    return html.escape(s, quote=True)


@lru_cache(maxsize=1)
def _template(mtime: float) -> str:
    return (STATIC / "index.html").read_text(encoding="utf-8")


def _help(slug: str | None, page: dict | None) -> str:
    name = page["name"] if page else None
    others = "".join(f'<a href="{s}">{_e(p["name"])}</a>' for s, p in PAGES.items() if s != slug)
    if page:
        steps = (f"<li>Copy the link to the {_e(page.get('site', name))} video.</li><li>Paste it in the box above and press <b>Get</b>.</li>"
                 "<li>Pick the quality or format, then press <b>Download</b>.</li>")
        intro = f"Haul saves {_e(page['what'])}. It's free, works in any browser and needs no account."
        head = _e(page.get("howto") or f"How to download from {name}")
    else:
        steps = ("<li>Copy the link to a video or song.</li><li>Paste it in the box above and press <b>Get</b>.</li>"
                 "<li>Pick the quality, audio language, subtitles or a clip, then press <b>Download</b>.</li>")
        intro = "Haul saves video and audio from YouTube and <a href=\"sites\">more than 1,000 other sites</a>. It's free, works in any browser and needs no account."
        head = "How it works"
    faq = page["faq"] if page else HOME_FAQ
    faq_html = "".join(f"<h3>{_e(q)}</h3><p>{_e(a)}</p>" for q, a in faq).replace(
        "more than 1,000 others", '<a href="sites">more than 1,000 others</a>')
    return f'''<section class="seo doc">
    <h2>{head}</h2>
    <p>{intro}</p>
    <ol>{steps}</ol>
    <h2>Questions</h2>
    {faq_html}
    <h2>Also works with</h2>
    <p class="sitelinks">{'<a href="./">All sites</a>' if page else ''}{others}</p>
  </section>

  '''


@lru_cache(maxsize=64)
def _render(slug: str | None, base: str, mtime: float) -> str:
    page = PAGES.get(slug) if slug else None
    out = _template(mtime)
    url = f"{base}/{slug}" if slug else f"{base}/"
    title = page["title"] + " · Haul" if page else "Haul: free video and audio downloader"
    desc = page["desc"] if page else ("Save video and audio from YouTube, Instagram, TikTok, Facebook and 1,000+ sites. "
                                      "Pick quality, audio language, subtitles or a clip. Free, no sign-up.")
    faq = page["faq"] if page else HOME_FAQ
    ld = [
        {"@context": "https://schema.org", "@type": "WebApplication", "name": "Haul", "url": url,
         "applicationCategory": "MultimediaApplication", "operatingSystem": "Any",
         "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"}, "description": desc},
        {"@context": "https://schema.org", "@type": "FAQPage", "mainEntity": [
            {"@type": "Question", "name": q, "acceptedAnswer": {"@type": "Answer", "text": a}} for q, a in faq]},
    ]
    ld_json = json.dumps(ld).replace("</", "<\\/")
    meta = (f'<link rel="canonical" href="{_e(url)}">\n'
            f'<meta property="og:type" content="website">\n<meta property="og:site_name" content="Haul">\n'
            f'<meta property="og:title" content="{_e(title)}">\n<meta property="og:description" content="{_e(desc)}">\n'
            f'<meta property="og:url" content="{_e(url)}">\n<meta property="og:image" content="{_e(base)}/icon-512.png">\n'
            f'<meta name="twitter:card" content="summary">\n'
            f'<script type="application/ld+json">{ld_json}</script>\n')
    out = out.replace("<title>Haul downloader</title>", f"<title>{_e(title)}</title>", 1)
    out = out.replace('<meta name="description" content="Paste a link, pick the quality, and save video or audio. Powered by yt-dlp.">',
                      f'<meta name="description" content="{_e(desc)}">\n' + meta, 1)
    if page:
        start = out.index("<h1>")
        end = out.index("</p>", out.index('<p class="lead">')) + len("</p>")
        out = out[:start] + f'<h1>{_e(page["h1"])}</h1>\n  <p class="lead">{_e(page["lead"])}</p>' + out[end:]
        out = out.replace("<body>", f'<body data-kind="{page["kind"]}">', 1)
    return out.replace("  <footer>", _help(slug, page) + "<footer>", 1)


def render(slug: str | None, request: Request) -> HTMLResponse:
    mtime = (STATIC / "index.html").stat().st_mtime
    return HTMLResponse(_render(slug, base_url(request), mtime))


router = APIRouter(include_in_schema=False)


@router.get("/")
async def home(request: Request):
    return render(None, request)


@router.get("/index.html")
async def home_file(request: Request):
    return render(None, request)


# Supported sites ---------------------------------------------------------------------------

_SUFFIX = re.compile(r"(?<=[a-z0-9])(Playlists?|Channels?|Users?|Live|Series|Seasons?|Shows?|Collections?|Clips?|"
                     r"Episodes?|Embeds?|Albums?|Titles?|Search|Tabs?|Feeds?|Lists?|Programs?|Categor(?:y|ies)|"
                     r"Tracks?|Artists?|Movies?|Videos?|Podcasts?|Audio|Radio|Stories|Story|Posts?|Pages?|IE)$")


@lru_cache(maxsize=1)
def supported_sites(version: str) -> list[dict]:
    """Sites the installed yt-dlp can download from, one row per site, A to Z."""
    from yt_dlp.extractor import gen_extractor_classes
    sites: dict[str, dict] = {}
    for ie in gen_extractor_classes():
        if ie.IE_DESC is False or ie.IE_NAME in ("generic",) or not ie.working():
            continue
        name = ie.IE_NAME.split(":")[0]
        for _ in range(3):
            shorter = _SUFFIX.sub("", name)
            if shorter == name or len(shorter) < 2:
                break
            name = shorter
        key = name.lower().replace(" ", "")
        row = sites.setdefault(key, {"name": name, "login": False})
        if getattr(ie, "_NETRC_MACHINE", None):
            row["login"] = True
    return sorted(sites.values(), key=lambda r: r["name"].lower())


def _info_page(title: str, desc: str, body: str, base: str, path: str) -> str:
    """An info page in the same frame as /privacy and the others."""
    t = (STATIC / "privacy.html").read_text(encoding="utf-8")
    head, rest = t.split('<article class="doc">', 1)
    tail = rest.split("</article>", 1)[1]
    head = re.sub(r"<title>.*?</title>", f"<title>{_e(title)} · Haul</title>", head, count=1)
    head = re.sub(r'<meta name="description" content="[^"]*">',
                  f'<meta name="description" content="{_e(desc)}">\n<link rel="canonical" href="{_e(base)}/{path}">', head, count=1)
    return head + '<article class="doc">\n' + body + "\n  </article>" + tail


@router.get("/sites")
async def sites_page(request: Request):
    import yt_dlp
    rows = supported_sites(yt_dlp.version.__version__)
    items = "".join(f'<li data-n="{_e(r["name"].lower())}">{_e(r["name"])}'
                    + ('<span class="login" title="Some videos here need an account">login</span>' if r["login"] else "")
                    + "</li>" for r in rows)
    body = f'''  <h1>Supported sites</h1>
  <p class="updated">{len(rows):,} sites · from yt-dlp {_e(yt_dlp.version.__version__)}</p>
  <p>Haul can save video and audio from all of these. Sites marked <span class="login">login</span> have some videos that need an account, which Haul can't open. Private and paid videos can't be downloaded anywhere. Not listed? Try the link anyway, because many sites with ordinary video players work too.</p>
  <input id="sitesearch" class="sitesearch" type="search" placeholder="Search {len(rows):,} sites" aria-label="Search sites" autocomplete="off">
  <p class="sub hidden" id="nosite">No site by that name. Try pasting the link on the <a href="./">home page</a> anyway.</p>
  <ul class="sitelist" id="sitelist">{items}</ul>
  <script>
  (function () {{
    var q = document.getElementById('sitesearch'), li = document.getElementById('sitelist').children;
    q.addEventListener('input', function () {{
      var v = q.value.trim().toLowerCase().replace(/\\s+/g, ''), shown = 0;
      for (var i = 0; i < li.length; i++) {{ var on = !v || li[i].getAttribute('data-n').replace(/\\s+/g, '').indexOf(v) >= 0; li[i].style.display = on ? '' : 'none'; if (on) shown++; }}
      document.getElementById('nosite').classList.toggle('hidden', shown > 0);
    }});
  }})();
  </script>'''
    return HTMLResponse(_info_page("Supported sites", f"All {len(rows):,} sites Haul can download video and audio from.",
                                   body, base_url(request), "sites"))


@router.get("/robots.txt")
async def robots(request: Request):
    return PlainTextResponse(f"User-agent: *\nDisallow: /api/\nDisallow: /admin\nDisallow: /get/\nDisallow: /files/\n"
                             f"Sitemap: {base_url(request)}/sitemap.xml\n")


@router.get("/sitemap.xml")
async def sitemap(request: Request):
    base = base_url(request)
    paths = ["", *PAGES, "sites", "helper", "about", "privacy", "terms", "copyright", "contact"]
    urls = "".join(f"<url><loc>{_e(base)}/{p}</loc></url>" for p in paths)
    return Response(f'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">{urls}</urlset>',
                    media_type="application/xml")


def _site_page(slug: str):
    async def serve(request: Request):
        return render(slug, request)
    return serve


for _slug in PAGES:
    router.add_api_route(f"/{_slug}", _site_page(_slug), methods=["GET"])
