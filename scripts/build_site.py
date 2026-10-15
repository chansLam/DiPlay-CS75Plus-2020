#!/usr/bin/env python3
"""Generate the static GitHub Pages editions from site/content.json + CHANGELOG.md."""
from __future__ import annotations

import json
import os
import re
import subprocess
from html import escape as e
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'site'
CHANGELOG = ROOT / 'CHANGELOG.md'

# Screenshots under site/assets/ (copied from docs/images/).
GALLERY_FILES = [
    'carplay-immersive.jpg',
    'carplay-app-launcher.png',
    'edit-app-list.png',
    'settings-vehicle-apps.png',
]


def detect_repository() -> str:
    env = os.environ.get('GITHUB_REPOSITORY', '').strip()
    if env:
        return env
    config_path = SITE / 'config.json'
    if config_path.is_file():
        configured = json.loads(config_path.read_text(encoding='utf-8')).get('github_repository', '').strip()
        if configured:
            return configured
    try:
        url = subprocess.check_output(
            ['git', 'remote', 'get-url', 'origin'],
            cwd=ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        url = ''
    match = re.search(r'github\.com[:/]([^/]+)/([^/.]+?)(?:\.git)?$', url)
    if match:
        return f'{match.group(1)}/{match.group(2)}'
    raise SystemExit(
        'Cannot detect GitHub repository. Set GITHUB_REPOSITORY, site/config.json '
        'github_repository, or configure git remote origin.'
    )


def parse_changelog(path: Path) -> list[tuple[str, list[str]]]:
    """Return [(version, bullets)] from CHANGELOG.md `## X.Y.Z` sections."""
    if not path.is_file():
        return []
    versions: list[tuple[str, list[str]]] = []
    current: str | None = None
    bullets: list[str] = []
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.startswith('## '):
            if current is not None:
                versions.append((current, bullets))
            current = line[3:].strip()
            bullets = []
        elif current is not None and line.startswith('- '):
            bullets.append(line[2:].strip())
    if current is not None:
        versions.append((current, bullets))
    return versions


def strip_md_links(text: str) -> str:
    return re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text)


def md_inline(text: str) -> str:
    """Render a small safe subset of inline Markdown: escape first, then format."""
    text = e(text)
    text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)                      # inline code
    text = re.sub(r'~~([^~]+)~~', r'<s>\1</s>', text)                          # strikethrough
    text = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', text)           # bold
    text = re.sub(r'\*([^*]+)\*', r'<em>\1</em>', text)                        # italic
    text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', text)    # link
    return text


def render_changelog(entries: list[tuple[str, list[str]]], title: str, more: str, changelog_url: str) -> str:
    if not entries:
        return ''
    blocks = []
    for version, bullets in entries:
        label = version if version.lower().startswith('v') else f'v{version}'
        items = ''.join(f'<li>{md_inline(b)}</li>' for b in bullets) or '<li>—</li>'
        blocks.append(
            f'<article class="release"><h3><bdi>{e(label)}</bdi></h3><ul>{items}</ul></article>'
        )
    return (
        f'<section class="changelog" id="changelog"><h2>{e(title)}</h2>'
        f'{"".join(blocks)}'
        f'<p class="note"><a href="{e(changelog_url)}">{e(more)} ↗</a></p></section>'
    )


config = json.loads((SITE / 'config.json').read_text(encoding='utf-8-sig'))
data = json.loads((SITE / 'content.json').read_text(encoding='utf-8-sig'))
REPO_SLUG = detect_repository()
OWNER, REPO_NAME = REPO_SLUG.split('/', 1)
BASE = f'https://{OWNER}.github.io/{REPO_NAME}/'
REPO = f'https://github.com/{REPO_SLUG}'
VERSION = config['version']
TAG = config.get('tag') or f'v{VERSION}'
APK = config.get('apk_asset') or f'DiPlay-v{VERSION}-release.apk'
RELEASE = f'{REPO}/releases/tag/{TAG}'
DOWNLOAD = f'{REPO}/releases/download/{TAG}/{APK}'
CHANGELOG_URL = f'{REPO}/blob/main/CHANGELOG.md'
MIRRORS = [
    'https://ghproxy.net/' + DOWNLOAD,
    'https://gh-proxy.com/' + DOWNLOAD,
]
CHANGELOG_ENTRIES = parse_changelog(CHANGELOG)

for lang, d in data.items():
    folder = SITE if lang == 'zh-Hans' else SITE / lang
    folder.mkdir(exist_ok=True)
    prefix = './' if lang == 'zh-Hans' else '../'
    url = BASE + ('' if lang == 'zh-Hans' else lang + '/')
    nav = ''.join(
        f'<a href="{prefix}{"" if code == "zh-Hans" else code + "/"}" lang="{code}" hreflang="{code}" dir="auto"'
        + (' aria-current="page"' if code == lang else '')
        + f'>{e(v["name"])}</a>'
        for code, v in data.items()
    )
    alternates = ''.join(
        f'<link rel="alternate" hreflang="{code}" href="{BASE}{"" if code == "zh-Hans" else code + "/"}">'
        for code in data
    )
    captions = d.get('captions') or []
    pics = ''.join(
        f'<figure><a href="{prefix}assets/{pic}"><img src="{prefix}assets/{pic}" loading="lazy" '
        f'alt="{e(captions[i] if i < len(captions) else pic)}"></a>'
        f'<figcaption>{e(captions[i] if i < len(captions) else "")}</figcaption></figure>'
        for i, pic in enumerate(GALLERY_FILES)
    )
    feature_items = ''.join(f'<li>{e(item)}</li>' for item in d.get('features') or [])
    step_items = ''.join(f'<li>{e(item)}</li>' for item in d.get('steps') or [])
    mirror_links = ' · '.join(
        f'<a href="{e(mirror)}">{e(d["mirrors"])} {i + 1} ↗</a>' for i, mirror in enumerate(MIRRORS)
    )
    badge = TAG  # single source of truth from config.json
    history_btn = (
        f'<a class="button secondary" href="#changelog">{e(d.get("history", "Changelog"))}</a>'
        if CHANGELOG_ENTRIES else ''
    )
    changelog_html = render_changelog(
        CHANGELOG_ENTRIES,
        d.get('changelogTitle', 'Version history'),
        d.get('changelogMore', 'Full changelog'),
        CHANGELOG_URL,
    )
    (folder / 'index.html').write_text(
        f'''<!doctype html>
<html lang="{lang}" dir="{d['dir']}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DiPlay · {e(d['download'])}</title><meta name="description" content="{e(d['intro'])}"><meta name="theme-color" content="#0c121c">
<link rel="icon" href="{prefix}assets/icon.png"><link rel="stylesheet" href="{prefix}assets/site.css"><link rel="canonical" href="{url}">{alternates}
<meta property="og:title" content="DiPlay — CarPlay for Changan CS75 Plus"><meta property="og:description" content="{e(d['promise'])}"><meta property="og:image" content="{BASE}assets/carplay-immersive.jpg"><meta property="og:url" content="{url}"><meta property="og:type" content="website">
</head><body><main>
<header><a class="brand" href="{prefix}"><img src="{prefix}assets/icon.png" width="56" height="56" alt=""><span><strong>DiPlay</strong><small>{e(d['tag'])}</small></span></a><nav class="languages" aria-label="Language">{nav}</nav></header>
<section class="hero"><span class="badge"><bdi>{e(badge)}</bdi></span><h1>{e(d['title']).replace(chr(10), '<br>')}</h1><p class="intro">{e(d['intro'])}</p><div class="actions"><a class="button" href="{DOWNLOAD}">{e(d['download'])} <span aria-hidden="true">↓</span></a><a class="button secondary" href="#install">{e(d['install'])}</a>{history_btn}</div><p class="note">{mirror_links}</p><p class="promise">{e(d['promise'])}</p><p class="note">{e(d['requires'])}</p><p class="note support-scope"><strong>{e(d['supportScope'])}</strong></p></section>
<section class="gallery"><span class="eyebrow">{e(d['gallery'])}</span><div class="screens">{pics}</div></section>
<section class="grid"><div class="card" id="features"><span class="eyebrow">{e(d['whats'])}</span><h2>{e(d['whats'])}</h2><ul>{feature_items}</ul></div><div class="card" id="install"><span class="eyebrow">{e(d['setup'])}</span><h2>{e(d['setup'])}</h2><ol>{step_items}</ol><p>{e(d['bssid'])}</p></div></section>
{changelog_html}
<section class="signing"><h2>{e(d['update'])}</h2><p>{e(d['updateText'])}</p></section>
<section class="card"><h2>{e(d['diagnosticsTitle'])}</h2><p>{e(d['diagnosticsText'])}</p><a href="{REPO}/issues">{e(d['feedback'])} ↗</a> · <a href="{REPO}/issues/new">{e(d['newIssue'])} ↗</a></section>
<footer><nav><a href="{REPO}/blob/main/SECURITY.md">{e(d["privacy"])}</a><a href="{REPO}">{e(d['source'])}</a><a href="{CHANGELOG_URL}">{e(d.get("changelogTitle", d["notes"]))}</a><a href="{RELEASE}">{e(d['notes'])}</a><a href="{REPO}/issues">{e(d['feedback'])}</a></nav><p>{e(d['footer'])}</p></footer>
</main></body></html>''',
        encoding='utf-8',
    )
print(f'Generated {len(data)} language pages for {REPO_SLUG} ({TAG})')
