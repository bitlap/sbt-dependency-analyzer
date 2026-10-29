#!/usr/bin/env python3
"""Offline XML/ZIP checks for upload-plugin.sh; never builds or uploads a plugin."""
import argparse
import hashlib
import html
import io
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_ID = "org.bitlap.sbtDependencyAnalyzer"
MAX_SIZE = 400_000_000
MAIN_JAR = "sbt-dependency-analyzer.jar"


def project_version(root=None):
    root = Path(root or ROOT)
    match = re.search(r'lazy val pluginVersion\s*=\s*"([^\"]+)"', (root / "build.sbt").read_text())
    if not match:
        raise ValueError("Cannot read pluginVersion from build.sbt")
    return match.group(1)


def normalize_notes(notes):
    return re.sub(r"\s+", " ", notes).strip()


def release_metadata(xml):
    plugin_id = (xml.findtext("id") or "").strip()
    version = (xml.findtext("version") or "").strip()
    if plugin_id != PLUGIN_ID or not version:
        raise ValueError(f"Unexpected plugin identity/version: {plugin_id!r}, {version!r}")
    notes = xml.findtext("change-notes") or ""
    clean_notes = re.sub(r"(?s)<!--.*?-->", "", notes)
    headings = list(re.finditer(r"(?is)<h1\b[^>]*>(.*?)</h1\s*>", clean_notes))
    if not headings:
        raise ValueError("change-notes must contain a latest release <h1> heading")
    if headings[0].group(1).strip() != version:
        raise ValueError("Latest change-notes version differs from the plugin version")
    section = clean_notes[headings[0].end():headings[1].start() if len(headings) > 1 else len(clean_notes)]
    if not html.unescape(re.sub(r"<[^>]*>", "", section)).strip():
        raise ValueError("Latest change-notes entry must contain release details")
    # Numeric release order, with a final release ordered after its RC/build qualifiers.
    def number(value):
        match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z0-9][A-Za-z0-9.-]*))?", value)
        if not match:
            raise ValueError(f"Invalid release version in change-notes: {value!r}")
        qualifier = match.group(4)
        parts = tuple((0, int(p)) if p.isdigit() else (1, p.lower())
                      for p in re.findall(r"\d+|[^\d]+", qualifier or ""))
        return tuple(int(match.group(i)) for i in (1, 2, 3)), qualifier is None, parts
    current = number(version)
    if len(headings) > 1 and current <= number(headings[1].group(1).strip()):
        raise ValueError("Latest version must increase over the previous change-notes release")
    return plugin_id, version, notes


def project_release():
    expected = project_version()
    source = ROOT / "src/main/resources/META-INF/plugin.xml"
    _, version, notes = release_metadata(ET.parse(source).getroot())
    if version != expected:
        raise ValueError(f"Source plugin.xml version {version!r} differs from build.sbt {expected!r}; update both")
    return expected, notes


def descriptor(jar_bytes, expected_notes=None):
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        info = jar.getinfo("META-INF/plugin.xml")
        if info.file_size > 1_000_000:
            raise ValueError("Plugin descriptor is unexpectedly large")
        xml = ET.fromstring(jar.read(info))
        if any("AnalyzerSmokeStarter" in n or "GradleAnalyzerCompatibilityStarter" in n for n in jar.namelist()):
            raise ValueError("Distribution includes test-only IDE starters")
    plugin_id, version, notes = release_metadata(xml)
    if expected_notes is not None and normalize_notes(notes) != normalize_notes(expected_notes):
        raise ValueError("Packaged change-notes differ from source plugin.xml; rebuild first")
    return plugin_id, version


def validate_archive(path, expected_version, expected_notes=None):
    path = Path(path).resolve()
    if path.suffix.lower() != ".zip" or not path.is_file():
        raise ValueError(f"Expected a plugin distribution ZIP: {path}")
    if path.stat().st_size > MAX_SIZE:
        raise ValueError("Marketplace accepts plugins up to 400 MB")
    with zipfile.ZipFile(path) as archive:
        jars = [entry for entry in archive.infolist()
                if len(Path(entry.filename).parts) == 3
                and Path(entry.filename).parts[1:] == ("lib", MAIN_JAR)
                and Path(entry.filename).parts[0] not in (".", "..")]
        if len(jars) != 1:
            raise ValueError("ZIP must contain one plugin root with lib/sbt-dependency-analyzer.jar")
        if jars[0].file_size > MAX_SIZE:
            raise ValueError("Plugin JAR exceeds the upload limit")
        plugin_id, version = descriptor(archive.read(jars[0]), expected_notes)
        bad_entry = archive.testzip()
        if bad_entry:
            raise ValueError(f"Corrupt ZIP entry: {bad_entry}")
    if version != expected_version:
        raise ValueError(f"Artifact version {version!r} does not match build.sbt {expected_version!r}")
    return plugin_id, version



def redact_output():
    secrets = [os.environ.get(key, "") for key in
               ("IJ_PLUGIN_REPO_TOKEN", "JETBRAINS_MARKETPLACE_TOKEN", "PLUGIN_SIGN_KEY_PWD")]
    for line in sys.stdin:
        for secret in sorted(filter(None, secrets), key=len, reverse=True):
            line = line.replace(secret, "[REDACTED]")
        print(line, end="", flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", nargs="?", type=Path)
    parser.add_argument("--version-only", action="store_true")
    parser.add_argument("--redact-stdin", action="store_true")
    args = parser.parse_args(argv)
    if args.redact_stdin:
        redact_output()
        return 0
    try:
        version, notes = project_release()
        if args.version_only:
            print(version)
            return 0
        print(f"Source metadata valid: {PLUGIN_ID} {version}")
        if args.artifact is not None:
            validate_archive(args.artifact, version, notes)
            digest = hashlib.sha256(args.artifact.read_bytes()).hexdigest()
            print(f"Validated archive: {args.artifact.resolve()}\nSHA-256: {digest}")
        return 0
    except (OSError, ValueError, zipfile.BadZipFile, KeyError, ET.ParseError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
