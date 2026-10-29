#!/usr/bin/env python3
"""Offline release validation and shell orchestration tests; fake sbt never contacts Marketplace."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('release_validator', TOOLS / 'validate-plugin-release.py')
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
VERSION = '0.12.0'
TOKEN = 'perm:offline-test-only'


def jar_bytes(plugin_id=tool.PLUGIN_ID, version=VERSION, note_version=VERSION, test_classes=False):
    result = io.BytesIO()
    with zipfile.ZipFile(result, 'w') as jar:
        jar.writestr('META-INF/plugin.xml', f'''<idea-plugin><id>{plugin_id}</id><version>{version}</version>
<change-notes><![CDATA[<h1>{note_version}</h1><ul><li>Release</li></ul>]]></change-notes></idea-plugin>''')
        if test_classes:
            jar.writestr('bitlap/sbt/analyzer/AnalyzerSmokeStarter.class', b'test')
    return result.getvalue()


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'project "quoted" $dollar'
        self.root.mkdir()
        (self.root / 'build.sbt').write_text(f'lazy val pluginVersion = "{VERSION}"')
        self.xml = self.root / 'src/main/resources/META-INF/plugin.xml'
        self.xml.parent.mkdir(parents=True)
        with zipfile.ZipFile(io.BytesIO(jar_bytes())) as jar:
            self.xml.write_bytes(jar.read('META-INF/plugin.xml'))
        self.archive = self.root / 'fixture.zip'
        self.make_zip()
        (self.root / 'tools').mkdir()
        for name in ['upload-plugin.sh', 'validate-plugin-release.py']:
            shutil.copyfile(TOOLS / name, self.root / 'tools' / name)
        self.home = Path(self.tmp.name) / 'home'
        self.home.mkdir()
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(('PLUGIN_SIGN_', 'IJ_PLUGIN_', 'JETBRAINS_'))}
        self.env.update(HOME=str(self.home), XDG_CONFIG_HOME=str(self.home / '.config'),
                        SBT_CALL_LOG=str(self.root / 'calls.jsonl'), EXPECTED_TOKEN=TOKEN)
        # Deliberately not a real certificate/key: signing is performed by the fake sbt only.
        for name in ['chain.crt', 'private.pem']:
            (self.root / name).write_text('offline fixture')
        self.fake = self.root / 'fake sbt'
        self.fake.write_text('''#!/usr/bin/env python3
import json,os,pathlib,shutil,sys
root=pathlib.Path.cwd()
args=sys.argv[1:]
jdk=None
if args[:1]==['-java-home']:
 jdk=args[1];args=args[2:]
with open(os.environ['SBT_CALL_LOG'],'a') as f:
 f.write(json.dumps({'args':args,'jdk':jdk,'signing':os.environ.get('SIGN_PLUGIN'),
                     'token_matches':os.environ.get('IJ_PLUGIN_REPO_TOKEN')==os.environ.get('EXPECTED_TOKEN')})+'\\n')
if args==['signPlugin']:
 if os.environ.get('FAIL_SIGN'):sys.exit(17)
 (root/'target').mkdir(exist_ok=True)
 shutil.copyfile(root/'fixture.zip',root/'target/signed-SbtDependencyAnalyzer-0.12.0.zip')
elif args[-1].startswith('publishPlugin'):
 assert len(args)==2 and args[0].startswith('set signPlugin := file(')
 if os.environ.get('FAIL_UPLOAD'):
  print('server rejected '+os.environ.get('IJ_PLUGIN_REPO_TOKEN','')+' '+os.environ.get('PLUGIN_SIGN_KEY_PWD',''))
  sys.exit(23)
 print('Successfully uploaded offline fixture')
else:sys.exit(99)
''')
        self.fake.chmod(0o700)
        patcher = mock.patch.object(tool, 'ROOT', self.root)
        patcher.start()
        self.addCleanup(patcher.stop)

    def make_zip(self, **kwargs):
        with zipfile.ZipFile(self.archive, 'w') as z:
            z.writestr('SbtDependencyAnalyzer/lib/' + tool.MAIN_JAR, jar_bytes(**kwargs))

    def signing(self):
        self.env.update(PLUGIN_SIGN_CERT_FILE=str(self.root / 'chain.crt'),
                        PLUGIN_SIGN_PRIVATE_KEY_FILE=str(self.root / 'private.pem'),
                        PLUGIN_SIGN_KEY_PWD='offline-password')

    def run_shell(self, *args):
        return subprocess.run(['bash', str(self.root / 'tools/upload-plugin.sh'), '--sbt', str(self.fake), *args],
                              cwd=self.home, env=self.env, capture_output=True, text=True)

    def calls(self):
        p = self.root / 'calls.jsonl'
        return [json.loads(s) for s in p.read_text().splitlines()] if p.exists() else []

    def test_valid_archive_is_not_modified(self):
        before = self.archive.read_bytes()
        self.assertEqual(tool.validate_archive(self.archive, VERSION), (tool.PLUGIN_ID, VERSION))
        self.assertEqual(self.archive.read_bytes(), before)

    def test_archive_identity_version_notes_and_test_starters_are_checked(self):
        for kwargs in [{'plugin_id': 'wrong'}, {'version': '0.11.0', 'note_version': '0.11.0'},
                       {'note_version': 'old'}, {'test_classes': True}]:
            with self.subTest(kwargs=kwargs):
                self.make_zip(**kwargs)
                with self.assertRaises(ValueError): tool.validate_archive(self.archive, VERSION)

    def test_corrupt_zip_and_multiple_plugin_roots_are_rejected(self):
        self.archive.write_bytes(b'not zip')
        with self.assertRaises(zipfile.BadZipFile): tool.validate_archive(self.archive, VERSION)
        with zipfile.ZipFile(self.archive, 'w') as z:
            for root in ['one', 'two']: z.writestr(root + '/lib/' + tool.MAIN_JAR, jar_bytes())
        with self.assertRaisesRegex(ValueError, 'one plugin root'): tool.validate_archive(self.archive, VERSION)

    def test_missing_empty_stale_and_non_incrementing_notes_are_rejected(self):
        for notes in ['', '<h1>0.11.0</h1><p>Old</p>', f'<h1>{VERSION}</h1><p>&nbsp;</p>',
                      f'<h1>{VERSION}</h1><h1>0.11.0</h1><p>Old only</p>',
                      f'<h1>{VERSION}</h1><p>New</p><h1>{VERSION}</h1><p>Old</p>',
                      f'<h1>{VERSION}</h1><p>New</p><h1>0.13.0</h1><p>Old</p>']:
            with self.subTest(notes=notes):
                xml = tool.ET.parse(self.xml).getroot(); xml.find('change-notes').text = notes
                tool.ET.ElementTree(xml).write(self.xml)
                with self.assertRaises(ValueError): tool.project_release()

    def test_release_order_supports_rc_and_preserves_history(self):
        xml = tool.ET.parse(self.xml).getroot()
        for current, previous in [('0.12.0', '0.11.0'), ('0.12.0', '0.12.0-RC2'), ('0.12.0-RC10', '0.12.0-RC2')]:
            xml.find('version').text = current
            xml.find('change-notes').text = f'<h1>{current}</h1><p>New</p><h1>{previous}</h1><p>History</p>'
            before = tool.ET.tostring(xml)
            self.assertEqual(tool.release_metadata(xml)[1], current)
            self.assertEqual(tool.ET.tostring(xml), before)

    def test_stale_packaged_notes_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'rebuild first'):
            tool.validate_archive(self.archive, VERSION, '<h1>0.12.0</h1><p>Different</p>')

    def test_default_and_dry_run_do_not_build_upload_or_load_credentials(self):
        self.env['SBT'] = '/missing/sbt'
        for args in [(), ('--dry-run',)]:
            result = self.run_shell(*args, '--env-file', '/missing/credentials')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('No build or upload', result.stdout)
        self.assertEqual(self.calls(), [])

    def test_source_version_mismatch_stops_before_sbt(self):
        self.xml.write_text(self.xml.read_text().replace('<version>0.12.0</version>', '<version>0.11.0</version>')
                            .replace('<h1>0.12.0</h1>', '<h1>0.11.0</h1>'))
        result = self.run_shell('--upload')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('update both', result.stderr)
        self.assertEqual(self.calls(), [])

    def test_build_signs_and_validates_without_token_or_upload(self):
        self.signing()
        result = self.run_shell('--build')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c['args'] for c in self.calls()], [['signPlugin']])
        self.assertEqual(self.calls()[0]['signing'], 'true')
        self.assertIn('SHA-256:', result.stdout)

    def test_upload_pins_the_validated_signed_zip_and_uses_native_publish(self):
        self.signing(); self.env['JETBRAINS_MARKETPLACE_TOKEN'] = TOKEN
        result = self.run_shell('--upload', '--channel', 'eap')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls()
        self.assertEqual(calls[0]['args'], ['signPlugin'])
        self.assertEqual(calls[1]['args'][1], 'publishPlugin eap')
        quoted = calls[1]['args'][0].removeprefix('set signPlugin := file(').removesuffix(')')
        self.assertEqual(json.loads(quoted), str(self.root / f'target/signed-SbtDependencyAnalyzer-{VERSION}.zip'))
        self.assertTrue(calls[1]['token_matches'])

    def test_existing_sbt_home_variable_is_not_used_as_executable(self):
        self.signing()
        (self.root / 'sbt').symlink_to(self.fake)
        self.env['PATH'] = str(self.root) + os.pathsep + self.env['PATH']
        self.env['SBT'] = str(self.root)
        result = subprocess.run(['bash', str(self.root / 'tools/upload-plugin.sh'), '--build'],
                                cwd=self.home, env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_configured_jdk_is_explicitly_passed_to_sbt(self):
        self.signing(); self.env['JAVA_HOME'] = str(self.root / 'custom JDK')
        result = self.run_shell('--build')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls()[0]['jdk'], self.env['JAVA_HOME'])

    def test_invalid_tokens_are_rejected_before_sbt(self):
        self.signing()
        for token in ['bad token', 'bad\nheader', '']:
            self.env['IJ_PLUGIN_REPO_TOKEN'] = token
            self.assertNotEqual(self.run_shell('--upload').returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_stable_maps_to_default_channel(self):
        self.signing(); self.env['IJ_PLUGIN_REPO_TOKEN'] = TOKEN
        result = self.run_shell('--upload', '--channel', 'Stable')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls()[-1]['args'][-1], 'publishPlugin')

    def test_native_token_file_takes_precedence_and_is_not_changed(self):
        self.signing(); self.env['IJ_PLUGIN_REPO_TOKEN'] = 'different'
        token = self.home / '.ij-plugin-repo-token'; token.write_text(TOKEN+'\n')
        result = self.run_shell('--upload')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.calls()[-1]['token_matches'])
        self.assertEqual(token.read_text(), TOKEN+'\n')

    def test_missing_signing_files_and_token_stop_before_sbt(self):
        self.assertNotEqual(self.run_shell('--build').returncode, 0)
        self.signing()
        self.assertNotEqual(self.run_shell('--upload').returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_config_file_is_loaded_and_missing_explicit_config_fails(self):
        config = self.root / 'private config.env'
        config.write_text(f'PLUGIN_SIGN_CERT_FILE={str(self.root / "chain.crt")!r}\n'
                          f'PLUGIN_SIGN_PRIVATE_KEY_FILE={str(self.root / "private.pem")!r}\n'
                          f'IJ_PLUGIN_REPO_TOKEN={TOKEN!r}\n')
        self.assertEqual(self.run_shell('--upload', '--env-file', str(config)).returncode, 0)
        self.assertNotEqual(self.run_shell('--build', '--env-file', '/missing').returncode, 0)

    def test_sign_failure_and_invalid_signed_metadata_prevent_upload(self):
        self.signing(); self.env['IJ_PLUGIN_REPO_TOKEN'] = TOKEN; self.env['FAIL_SIGN'] = '1'
        self.assertEqual(self.run_shell('--upload').returncode, 17)
        self.assertEqual(len(self.calls()), 1)
        del self.env['FAIL_SIGN']; self.make_zip(version='0.11.0', note_version='0.11.0')
        self.assertNotEqual(self.run_shell('--upload').returncode, 0)
        self.assertEqual(len(self.calls()), 2)

    def test_upload_failure_redacts_secrets_preserves_status_and_does_not_retry(self):
        self.signing(); self.env.update(IJ_PLUGIN_REPO_TOKEN=TOKEN, FAIL_UPLOAD='1')
        result = self.run_shell('--upload')
        self.assertEqual(result.returncode, 23)
        self.assertEqual(len(self.calls()), 2)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        self.assertNotIn('offline-password', result.stdout + result.stderr)
        self.assertIn('[REDACTED]', result.stdout)
        self.assertIn('no automatic retry', result.stderr)
        self.assertNotIn(TOKEN, repr(self.calls()))

    def test_channel_injection_unknown_flags_and_conflicting_modes_fail_before_sbt(self):
        for args in [('--channel', 'eap;publishPlugin'), ('--channel', 'eap\nexit'), ('--hidden',),
                     ('--upload', '--build'), ('--build', '--dry-run'), ('--dry-run', '--upload'), ('--channel',)]:
            with self.subTest(args=args): self.assertNotEqual(self.run_shell(*args).returncode, 0)
        self.assertEqual(self.calls(), [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
