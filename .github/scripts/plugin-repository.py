"""Writes the custom IntelliJ plugin repository that GitHub Pages serves, from the GitHub releases.

Usage: gh api repos/OWNER/REPO/releases | python3 plugin-repository.py intellij-plugin/build.gradle OUTPUT_DIR

  updatePlugins.xml  the newest release
  index.html         how to add it to IntelliJ IDEA

IntelliJ IDEA checks a repository added in Settings → Plugins → ⚙ → Manage Plugin Repositories for updates;
the plugin adds this one by itself (see PluginRepositoryRegistrar).
"""
import html
import json
import os
import re
import sys

PLUGIN_ID = "cc.gahaha.fastmultiversion"
ASSET = re.compile(r"fastmultiversion-intellij-plugin-.+\.zip")


def newest(releases):
    """The newest published release (not a pre-release) with the IntelliJ plugin."""
    for release in sorted(releases, key=lambda r: r["published_at"] or "", reverse=True):
        if release["draft"] or release["prerelease"]:
            continue
        asset = next((a for a in release["assets"] if ASSET.fullmatch(a["name"])), None)
        if asset:
            return release, asset
    return None


def repository(found, since_build):
    plugins = ""
    if found:
        release, asset = found
        version = release["tag_name"].removeprefix("v")
        plugins = f"""
    <plugin id="{PLUGIN_ID}" url="{html.escape(asset["browser_download_url"])}" version="{html.escape(version)}">
        <idea-version since-build="{since_build}"/>
        <name>FastMultiversion</name>
        <vendor>gahaha</vendor>
        <description><![CDATA[Minecraft mods for several Minecraft versions and loaders from one source tree.]]></description>
        <change-notes><![CDATA[<a href="{release["html_url"]}">Release notes of {html.escape(version)}</a>]]></change-notes>
    </plugin>"""
    return f'<?xml version="1.0" encoding="UTF-8"?>\n<plugins>{plugins}\n</plugins>\n'


def index(base_url):
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>FastMultiversion plugin repository</title>
<style>
  body {{ font-family: system-ui, sans-serif; max-width: 42rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; }}
  code {{ background: #f2f2f2; padding: 0.1rem 0.3rem; border-radius: 3px; }}
</style>
</head>
<body>
<h1>FastMultiversion for IntelliJ IDEA</h1>
<p>The plugin keeps itself up to date: once installed from a
<a href="https://github.com/GahahaWang/FastMultiversion/releases">release</a>, it adds this repository to
IntelliJ IDEA, which then offers updates.</p>
<p>To add it by hand: <b>Settings → Plugins → ⚙ → Manage Plugin Repositories…</b>, then
<code>{base_url}updatePlugins.xml</code></p>
</body>
</html>
"""


def main():
    build_gradle, output = sys.argv[1], sys.argv[2]
    releases = json.load(sys.stdin)
    with open(build_gradle, encoding="utf-8") as f:
        since_build = re.search(r"sinceBuild\s*=\s*'([^']+)'", f.read()).group(1)
    base_url = os.environ.get("PAGES_URL", "https://gahahawang.github.io/FastMultiversion/")
    os.makedirs(output, exist_ok=True)
    files = {
        "updatePlugins.xml": repository(newest(releases), since_build),
        "index.html": index(base_url),
        ".nojekyll": "",
    }
    for name, text in files.items():
        with open(os.path.join(output, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)


if __name__ == "__main__":
    main()
