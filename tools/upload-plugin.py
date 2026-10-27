#!/usr/bin/env python3
"""Validate/package this plugin and optionally upload an update through JetBrains Marketplace.

No upload occurs unless --upload is supplied. Authentication is read only from
JETBRAINS_MARKETPLACE_TOKEN. Uses Python's standard library and curl (sbt for --build).
Official API: https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ENDPOINT = "https://plugins.jetbrains.com/api/updates/upload"
PLUGIN_ID = "org.bitlap.sbtDependencyAnalyzer"
TOKEN_ENV = "JETBRAINS_MARKETPLACE_TOKEN"
MAX_SIZE = 400_000_000
MAIN_JAR = "sbt-dependency-analyzer.jar"


def project_version(root=ROOT):
    match = re.search(r'lazy val pluginVersion\s*=\s*"([^\"]+)"', (root / "build.sbt").read_text())
    if not match:
        raise ValueError("Cannot read pluginVersion from build.sbt")
    return match.group(1)


def descriptor(jar_bytes):
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        info = jar.getinfo("META-INF/plugin.xml")
        if info.file_size > 1_000_000:
            raise ValueError("Plugin descriptor is unexpectedly large")
        xml = ET.fromstring(jar.read(info))
        if any("AnalyzerSmokeStarter" in n or "GradleAnalyzerCompatibilityStarter" in n for n in jar.namelist()):
            raise ValueError("Distribution includes test-only IDE starters")
    plugin_id = (xml.findtext("id") or "").strip()
    version = (xml.findtext("version") or "").strip()
    if plugin_id != PLUGIN_ID or not version:
        raise ValueError(f"Unexpected plugin identity/version: {plugin_id!r}, {version!r}")
    heading = re.search(r"(?is)<h1\b[^>]*>(.*?)</h1\s*>", xml.findtext("change-notes") or "")
    if heading and heading.group(1).strip() != version:
        raise ValueError("Latest change-notes version differs from the packaged plugin version")
    return plugin_id, version


def validate_archive(path, expected_version):
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
        plugin_id, version = descriptor(archive.read(jars[0]))
        bad_entry = archive.testzip()
        if bad_entry:
            raise ValueError(f"Corrupt ZIP entry: {bad_entry}")
    if version != expected_version:
        raise ValueError(f"Artifact version {version!r} does not match build.sbt {expected_version!r}")
    return plugin_id, version


def prepare_artifact(source, expected_version, output_dir):
    source = Path(source).resolve()
    if not source.is_dir():
        validate_archive(source, expected_version)
        return source
    jar = source / "lib" / MAIN_JAR
    if not jar.is_file():
        raise ValueError(f"No packaged plugin at {source}; run sbt packageArtifact or use --build")
    _, version = descriptor(jar.read_bytes())
    if version != expected_version:
        raise ValueError(f"Packaged version {version!r} differs from build.sbt {expected_version!r}; rebuild first")
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    result = output_dir / f"SbtDependencyAnalyzer-{version}.zip"
    temporary = result.with_suffix(".zip.tmp")
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(source.rglob("*")):
                if file.is_file():
                    archive.write(file, file.relative_to(source.parent))
        temporary.replace(result)
        validate_archive(result, expected_version)
    finally:
        temporary.unlink(missing_ok=True)
    return result.resolve()


def upload(path, token, channel="", hidden=False, runner=None):
    if not token or any(ord(c) < 33 or ord(c) > 126 for c in token):
        raise ValueError(f"Set {TOKEN_ENV} to a non-empty Marketplace permanent token without whitespace")
    path = str(Path(path).resolve())
    if any(c in path for c in "\r\n\x00") or any(ord(c) < 32 for c in channel):
        raise ValueError("Artifact path and channel must not contain control characters")
    curl = shutil.which("curl")
    if not curl:
        raise ValueError("curl is required for --upload")
    quoted_path = path.replace("\\", "\\\\").replace('"', '\\"')
    args = [curl, "--silent", "--show-error", "--connect-timeout", "20", "--max-time", "300",
            "--proto", "=https", "--request", "POST", "--config", "-",
            "--form-string", f"xmlId={PLUGIN_ID}", "--form", f'file=@"{quoted_path}"']
    if channel:
        args += ["--form-string", f"channel={channel}"]
    if hidden:
        args += ["--form-string", "isHidden=true"]
    args += ["--write-out", "\n%{http_code}", ENDPOINT]
    # Keep credentials out of process arguments and the curl child environment.
    escaped = token.replace("\\", "\\\\").replace('"', '\\"')
    config = f'header = "Authorization: Bearer {escaped}"\n'
    environment = {key: value for key, value in os.environ.items() if key != TOKEN_ENV}
    try:
        result = (runner or subprocess.run)(args, input=config, capture_output=True, text=True,
                                            encoding="utf-8", errors="replace", env=environment, timeout=330)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError("Upload transport failed; check Marketplace before retrying to avoid a duplicate update") from error
    body, separator, status = result.stdout.rpartition("\n")
    redact = lambda value: value.replace(token, "[REDACTED]")[:4000]
    if result.returncode or not separator or not status.isdigit() or not 200 <= int(status) < 300:
        details = redact((body or result.stdout) + "\n" + result.stderr).strip()
        raise ValueError(f"Marketplace upload failed (HTTP {status if status.isdigit() else 'unknown'}): {details}")
    return redact(body).strip()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", nargs="?", type=Path, default=ROOT / "target/plugin/SbtDependencyAnalyzer",
                        help="Distribution ZIP, or packaged plugin directory (default: target/plugin/SbtDependencyAnalyzer)")
    parser.add_argument("--build", action="store_true", help="Run sbt packageArtifact before validation")
    parser.add_argument("--sbt", default="sbt", help="sbt executable to use with --build")
    parser.add_argument("--channel", default="", help="Custom channel such as eap; empty/Stable uses the default Stable channel")
    parser.add_argument("--hidden", action="store_true", help="Upload a hidden update (isHidden=true)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--upload", action="store_true", help=f"Upload using {TOKEN_ENV}; otherwise only validate/package")
    mode.add_argument("--dry-run", action="store_true", help="Explicitly validate/package only, without a token or network upload")
    args = parser.parse_args(argv)
    try:
        if args.build:
            if not shutil.which(args.sbt):
                raise ValueError("sbt executable not found; add it to PATH or specify --sbt /path/to/sbt")
            subprocess.run([args.sbt, "packageArtifact"], cwd=ROOT, check=True)
        expected = project_version()
        archive = prepare_artifact(args.artifact, expected, ROOT / "target/releases")
        plugin_id, version = validate_archive(archive, expected)
        checksum = hashlib.sha256()
        with archive.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                checksum.update(chunk)
        digest = checksum.hexdigest()
        channel = "" if args.channel.lower() == "stable" else args.channel
        print(f"Plugin: {plugin_id}\nVersion: {version}\nArchive: {archive}\nSHA-256: {digest}")
        print(f"Channel: {channel or 'Stable'}; hidden: {args.hidden}")
        if not args.upload:
            print("Validation complete. No upload was performed. Use --upload to submit this artifact.")
            return 0
        response = upload(archive, os.environ.get(TOKEN_ENV, ""), channel, args.hidden)
        print("Marketplace accepted the upload request. Check the vendor dashboard for review/release status.")
        if response:
            print(response)
        return 0
    except (OSError, ValueError, zipfile.BadZipFile, KeyError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
