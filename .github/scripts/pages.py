"""Writes the site that GitHub Pages serves, from the GitHub releases.

Usage: gh api repos/OWNER/REPO/releases | python3 pages.py intellij-plugin/build.gradle OUTPUT_DIR

  maven/             a Maven repository with the Gradle plugin of every release: the releases'
                     fastmultiversion-<version>-maven-repository.zip, merged, with maven-metadata.xml listing
                     all versions
  updatePlugins.xml  an IntelliJ plugin repository with the newest release of the IntelliJ plugin
  index.html         how to use both

Mods put maven/ in the plugin repositories of settings.gradle. IntelliJ IDEA checks a repository added in
Settings → Plugins → ⚙ → Manage Plugin Repositories for updates; the plugin adds this one by itself (see
PluginRepositoryRegistrar). GitHub Pages serves a whole branch, so each run writes the site from all releases.
"""
import hashlib
import html
import io
import json
import os
import re
import sys
import urllib.request
import zipfile
from datetime import datetime

PLUGIN_ID = "cc.gahaha.fastmultiversion"
INTELLIJ_ASSET = re.compile(r"fastmultiversion-intellij-plugin-.+\.zip")
MAVEN_ASSET = re.compile(r"fastmultiversion-.+-maven-repository\.zip")


def version_key(version):
    return tuple(int(part) for part in re.findall(r"\d+", version))


def releases_newest_first(releases):
    """The published releases, without drafts and pre-releases, the newest version first."""
    published = [r for r in releases if not r["draft"] and not r["prerelease"]]
    return sorted(published, key=lambda r: version_key(r["tag_name"]), reverse=True)


def asset(release, pattern):
    return next((a for a in release["assets"] if pattern.fullmatch(a["name"])), None)


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "FastMultiversion pages"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def maven(releases, root, fetch=download):
    """Unpacks every release's Maven repository zip into root and writes maven-metadata.xml for each artifact.

    Returns the versions in the repository, the newest first."""
    root = os.path.abspath(root)
    released = {}
    for release in releases:
        found = asset(release, MAVEN_ASSET)
        if not found:
            continue
        version = release["tag_name"].removeprefix("v")
        released[version] = release["published_at"]
        with zipfile.ZipFile(io.BytesIO(fetch(found["browser_download_url"]))) as zip:
            for entry in zip.infolist():
                name = entry.filename
                # Each zip's maven-metadata.xml only knows its own version; it is written again below.
                if entry.is_dir() or os.path.basename(name).startswith("maven-metadata.xml"):
                    continue
                target = os.path.abspath(os.path.join(root, name))
                if os.path.commonpath([root, target]) != root:
                    raise ValueError(f"{found['name']}: unexpected entry {name}")
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as f:
                    f.write(zip.read(entry))

    # <group path>/<artifact>/<version>/<artifact>-<version>.pom
    artifacts = {}
    for directory, _, files in os.walk(root):
        version_dir = os.path.relpath(directory, root).replace(os.sep, "/")
        parts = version_dir.split("/")
        if len(parts) < 3:
            continue
        artifact, version = parts[-2], parts[-1]
        if f"{artifact}-{version}.pom" in files:
            artifacts.setdefault("/".join(parts[:-1]), []).append(version)
    for path, versions in artifacts.items():
        versions.sort(key=version_key)
        newest = versions[-1]
        updated = released.get(newest)
        updated = datetime.fromisoformat(updated.replace("Z", "+00:00")) if updated else datetime.now()
        write_metadata(os.path.join(root, *path.split("/")), path, versions, updated.strftime("%Y%m%d%H%M%S"))
    return sorted(released, key=version_key, reverse=True)


def write_metadata(directory, path, versions, updated):
    *group, artifact = path.split("/")
    listed = "".join(f"\n      <version>{v}</version>" for v in versions)
    text = f"""<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>{".".join(group)}</groupId>
  <artifactId>{artifact}</artifactId>
  <versioning>
    <latest>{versions[-1]}</latest>
    <release>{versions[-1]}</release>
    <versions>{listed}
    </versions>
    <lastUpdated>{updated}</lastUpdated>
  </versioning>
</metadata>
"""
    data = text.encode("utf-8")
    file = os.path.join(directory, "maven-metadata.xml")
    with open(file, "wb") as f:
        f.write(data)
    for algorithm in ("md5", "sha1", "sha256", "sha512"):
        with open(f"{file}.{algorithm}", "w", encoding="ascii", newline="\n") as f:
            f.write(hashlib.new(algorithm, data).hexdigest())


def plugin_repository(releases, since_build):
    plugins = ""
    release = next((r for r in releases if asset(r, INTELLIJ_ASSET)), None)
    if release:
        version = release["tag_name"].removeprefix("v")
        url = asset(release, INTELLIJ_ASSET)["browser_download_url"]
        plugins = f"""
    <plugin id="{PLUGIN_ID}" url="{html.escape(url)}" version="{html.escape(version)}">
        <idea-version since-build="{since_build}"/>
        <name>FastMultiversion</name>
        <vendor>gahaha</vendor>
        <description><![CDATA[Minecraft mods for several Minecraft versions and loaders from one source tree.]]></description>
        <change-notes><![CDATA[<a href="{release["html_url"]}">Release notes of {html.escape(version)}</a>]]></change-notes>
    </plugin>"""
    return f'<?xml version="1.0" encoding="UTF-8"?>\n<plugins>{plugins}\n</plugins>\n'


def index(base_url, versions):
    newest = html.escape(versions[0]) if versions else "&lt;version&gt;"
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>FastMultiversion repositories</title>
<style>
  body {{ font-family: system-ui, sans-serif; max-width: 42rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; }}
  code, pre {{ background: #f2f2f2; border-radius: 3px; }}
  code {{ padding: 0.1rem 0.3rem; }}
  pre {{ padding: 0.75rem; overflow-x: auto; }}
</style>
</head>
<body>
<h1>FastMultiversion</h1>
<p>Build one Minecraft mod for several Minecraft versions and loaders.
See <a href="https://github.com/GahahaWang/FastMultiversion">GitHub</a> for how to use it.</p>

<h2>Gradle plugin</h2>
<p>In <code>settings.gradle</code>:</p>
<pre>pluginManagement {{
    repositories {{
        maven {{ url = '{base_url}maven' }}
        gradlePluginPortal()
        mavenCentral()
    }}
}}

plugins {{
    id '{PLUGIN_ID}' version '{newest}'
}}</pre>

<h2>IntelliJ IDEA plugin</h2>
<p>Install it from a <a href="https://github.com/GahahaWang/FastMultiversion/releases">release</a>. It then
adds this repository to IntelliJ IDEA, which offers its updates. To add it by hand:
<b>Settings → Plugins → ⚙ → Manage Plugin Repositories…</b>, then <code>{base_url}updatePlugins.xml</code></p>
</body>
</html>
"""


def main():
    build_gradle, output = sys.argv[1], sys.argv[2]
    releases = releases_newest_first(json.load(sys.stdin))
    with open(build_gradle, encoding="utf-8") as f:
        since_build = re.search(r"sinceBuild\s*=\s*'([^']+)'", f.read()).group(1)
    base_url = os.environ.get("PAGES_URL", "https://gahahawang.github.io/FastMultiversion/")
    os.makedirs(output, exist_ok=True)
    versions = maven(releases, os.path.join(output, "maven"))
    files = {
        "updatePlugins.xml": plugin_repository(releases, since_build),
        "index.html": index(base_url, versions),
        ".nojekyll": "",
    }
    for name, text in files.items():
        with open(os.path.join(output, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)


if __name__ == "__main__":
    main()
