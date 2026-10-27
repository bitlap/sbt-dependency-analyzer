#!/usr/bin/env python3
"""Offline tests for the Marketplace upload tool. No request reaches Marketplace."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

spec = importlib.util.spec_from_file_location("upload_plugin", Path(__file__).with_name("upload-plugin.py"))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
VERSION = "0.12.0"
TOKEN = "perm:unit-test-only"


def jar_bytes(plugin_id=tool.PLUGIN_ID, version=VERSION, note_version=VERSION, test_classes=False):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w") as jar:
        jar.writestr("META-INF/plugin.xml", f"""<idea-plugin><id>{plugin_id}</id><version>{version}</version>
<change-notes><![CDATA[<h1>{note_version}</h1><ul><li>Release</li></ul>]]></change-notes></idea-plugin>""")
        if test_classes:
            jar.writestr("bitlap/sbt/analyzer/AnalyzerSmokeStarter.class", b"test")
    return result.getvalue()


class MarketplaceUploadTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.archive = self.root / 'plugin, with "quotes".zip'
        self.make_zip()
        (self.root / "build.sbt").write_text(f'lazy val pluginVersion = "{VERSION}"')
        self.source_xml = self.root / "src/main/resources/META-INF/plugin.xml"
        self.source_xml.parent.mkdir(parents=True)
        with zipfile.ZipFile(io.BytesIO(jar_bytes())) as jar:
            self.source_xml.write_bytes(jar.read("META-INF/plugin.xml"))
        patcher = mock.patch.object(tool, "ROOT", self.root)
        patcher.start()
        self.addCleanup(patcher.stop)

    def tearDown(self):
        self.directory.cleanup()

    def make_zip(self, **kwargs):
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr("SbtDependencyAnalyzer/lib/" + tool.MAIN_JAR, jar_bytes(**kwargs))

    def test_valid_archive_and_unchanged_zip_input(self):
        before = self.archive.read_bytes()
        self.assertEqual(tool.validate_archive(self.archive, VERSION), (tool.PLUGIN_ID, VERSION))
        self.assertEqual(tool.prepare_artifact(self.archive, VERSION, self.root / "out"), self.archive.resolve())
        self.assertEqual(self.archive.read_bytes(), before)

    def test_plugin_identity_version_and_change_notes_must_match(self):
        for arguments, expected in [({"plugin_id": "different.plugin"}, "identity"),
                                    ({"version": "0.11.0", "note_version": "0.11.0"}, "does not match"),
                                    ({"note_version": "old"}, "change-notes"),
                                    ({"test_classes": True}, "test-only")]:
            with self.subTest(arguments=arguments):
                self.make_zip(**arguments)
                with self.assertRaisesRegex(ValueError, expected):
                    tool.validate_archive(self.archive, VERSION)

    def test_invalid_distribution_and_multiple_plugin_roots_are_rejected(self):
        self.archive.write_bytes(b"not a zip")
        with self.assertRaises(zipfile.BadZipFile):
            tool.validate_archive(self.archive, VERSION)
        with zipfile.ZipFile(self.archive, "w") as archive:
            for root in ("one", "two"):
                archive.writestr(root + "/lib/" + tool.MAIN_JAR, jar_bytes())
        with self.assertRaisesRegex(ValueError, "one plugin root"):
            tool.validate_archive(self.archive, VERSION)

    def test_packaged_directory_includes_dependencies_and_optional_descriptors(self):
        plugin = self.root / "SbtDependencyAnalyzer"
        (plugin / "lib").mkdir(parents=True)
        (plugin / "lib" / tool.MAIN_JAR).write_bytes(jar_bytes())
        (plugin / "lib/dependency.jar").write_bytes(b"dependency")
        (plugin / "META-INF").mkdir()
        (plugin / "META-INF/optional.xml").write_text("<idea-plugin/>")
        output = tool.prepare_artifact(plugin, VERSION, self.root / "release")
        self.assertEqual(tool.validate_archive(output, VERSION), (tool.PLUGIN_ID, VERSION))
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(archive.read("SbtDependencyAnalyzer/lib/dependency.jar"), b"dependency")
            self.assertIn("SbtDependencyAnalyzer/META-INF/optional.xml", archive.namelist())
        with self.assertRaisesRegex(ValueError, "rebuild first"):
            tool.prepare_artifact(plugin, "next", self.root / "release")

    def test_dry_run_and_default_mode_need_no_token_and_never_upload(self):
        for mode in ([], ["--dry-run"]):
            with mock.patch.object(tool, "project_version", return_value=VERSION), \
                 mock.patch.object(tool, "upload") as upload, \
                 mock.patch.dict(os.environ, {}, clear=True), contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(tool.main([str(self.archive), *mode]), 0)
                upload.assert_not_called()
                self.assertIn("No upload was performed", output.getvalue())
                self.assertIn("SHA-256:", output.getvalue())

    def test_source_versions_must_match_before_build_or_upload(self):
        self.source_xml.write_text(self.source_xml.read_text().replace(
            f"<version>{VERSION}</version>", "<version>0.11.0</version>").replace(
                f"<h1>{VERSION}</h1>", "<h1>0.11.0</h1>"))
        with mock.patch.object(tool.subprocess, "run") as build, mock.patch.object(tool, "upload") as upload, \
             contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(tool.main([str(self.archive), "--build", "--upload"]), 1)
        self.assertIn("update both", errors.getvalue())
        build.assert_not_called()
        upload.assert_not_called()

    def test_missing_empty_stale_and_non_incrementing_change_notes_are_rejected(self):
        for notes in ("", "<h1>0.11.0</h1><p>Old</p>",
                      f"<h1>{VERSION}</h1><ul><li> &nbsp; </li></ul><!-- TODO -->",
                      f"<h1>{VERSION}</h1><h1>0.11.0</h1><p>Only old details</p>",
                      f"<h1>{VERSION}</h1><p>New</p><h1>{VERSION}</h1><p>Old</p>",
                      f"<h1>{VERSION}</h1><p>New</p><h1>0.13.0</h1><p>Old</p>"):
            with self.subTest(notes=notes):
                xml = tool.ET.parse(self.source_xml).getroot()
                xml.find("change-notes").text = notes
                tool.ET.ElementTree(xml).write(self.source_xml)
                with mock.patch.object(tool, "upload") as upload, contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(tool.main([str(self.archive), "--upload"]), 1)
                upload.assert_not_called()

    def test_new_entry_preserves_history_and_release_order_handles_rc_versions(self):
        xml = tool.ET.parse(self.source_xml).getroot()
        for current, previous in [("0.12.0", "0.11.0"), ("0.12.0", "0.12.0-RC2"),
                                  ("0.12.0-RC10", "0.12.0-RC2")]:
            xml.find("version").text = current
            xml.find("change-notes").text = f"<h1>{current}</h1><p>New</p><h1>{previous}</h1><p>History</p>"
            before = tool.ET.tostring(xml)
            self.assertEqual(tool.release_metadata(xml)[1], current)
            self.assertEqual(tool.ET.tostring(xml), before)

    def test_same_version_with_stale_packaged_notes_requires_rebuild(self):
        xml = tool.ET.parse(self.source_xml).getroot()
        xml.find("change-notes").text = f"<h1>{VERSION}</h1><p>Updated release details</p>"
        tool.ET.ElementTree(xml).write(self.source_xml)
        for mode in ("--dry-run", "--upload"):
            with mock.patch.object(tool, "upload") as upload, contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(tool.main([str(self.archive), mode]), 1)
            self.assertIn("rebuild first", errors.getvalue())
            upload.assert_not_called()

    def test_request_uses_official_endpoint_and_keeps_token_out_of_args_and_child_environment(self):
        requests = []
        def runner(args, **kwargs):
            requests.append((args, kwargs))
            return subprocess.CompletedProcess(args, 0, '{"id":123}\n200', "")
        with mock.patch.object(tool.shutil, "which", return_value="/usr/bin/curl"), \
             mock.patch.dict(os.environ, {tool.TOKEN_ENV: TOKEN}):
            result = tool.upload(self.archive, TOKEN, "eap", True, runner)
        args, kwargs = requests[0]
        self.assertEqual(result, '{"id":123}')
        self.assertEqual(args[-1], "https://plugins.jetbrains.com/api/updates/upload")
        self.assertNotIn(TOKEN, " ".join(args))
        self.assertNotIn(tool.TOKEN_ENV, kwargs["env"])
        self.assertIn("Authorization: Bearer " + TOKEN, kwargs["input"])
        self.assertIn("xmlId=" + tool.PLUGIN_ID, args)
        self.assertIn("channel=eap", args)
        self.assertIn("isHidden=true", args)
        self.assertTrue(next(arg for arg in args if arg.startswith("file=@")).startswith('file=@"'))
        self.assertNotIn("--location", args)
        self.assertNotIn("--retry", args)

    def test_stable_uses_default_channel_only_when_explicit_upload_is_requested(self):
        with mock.patch.object(tool, "project_version", return_value=VERSION), \
             mock.patch.object(tool, "upload", return_value='{"id":123}') as upload, \
             mock.patch.dict(os.environ, {tool.TOKEN_ENV: TOKEN}), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(tool.main([str(self.archive), "--upload", "--channel", "Stable"]), 0)
        self.assertEqual(upload.call_args.args, (self.archive.resolve(), TOKEN, "", False))

    def test_empty_or_multiline_tokens_are_rejected_before_starting_curl(self):
        for token in ("", "bad\nheader", "bad token"):
            runner = mock.Mock()
            with self.assertRaisesRegex(ValueError, tool.TOKEN_ENV):
                tool.upload(self.archive, token, runner=runner)
            runner.assert_not_called()

    def test_http_failures_redact_token_and_do_not_retry(self):
        runner = mock.Mock(return_value=subprocess.CompletedProcess([], 0, f"Rejected {TOKEN}\n401", TOKEN))
        with mock.patch.object(tool.shutil, "which", return_value="curl"), self.assertRaises(ValueError) as caught:
            tool.upload(self.archive, TOKEN, runner=runner)
        self.assertIn("HTTP 401", str(caught.exception))
        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertIn("[REDACTED]", str(caught.exception))
        self.assertEqual(runner.call_count, 1)

    def test_network_failures_and_redirects_are_errors(self):
        for returncode, response in [(7, "\n000"), (0, "redirect\n302")]:
            with mock.patch.object(tool.shutil, "which", return_value="curl"), self.assertRaises(ValueError):
                tool.upload(self.archive, TOKEN,
                            runner=lambda *args, **kwargs: subprocess.CompletedProcess([], returncode, response, "error"))
        with mock.patch.object(tool.shutil, "which", return_value="curl"), self.assertRaisesRegex(ValueError, "before retrying"):
            tool.upload(self.archive, TOKEN, runner=mock.Mock(side_effect=subprocess.TimeoutExpired("curl", 330)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
