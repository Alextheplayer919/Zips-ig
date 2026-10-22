#!/usr/bin/env python3
"""Local source guards + actual POSIX native identity test, NOT Android runtime tests."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

BASE = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


class ClientBaseSourceTests(unittest.TestCase):
    def manifest(self, module):
        return ET.parse(BASE / module / 'src/main/AndroidManifest.xml').getroot()

    def test_manifest_xml_and_no_permissions(self):
        for module in ('host', 'demo-guest', 'contract'):
            root = self.manifest(module)
            self.assertEqual([], root.findall('uses-permission'))
        for path in BASE.glob('*/src/main/res/**/*.xml'):
            ET.parse(path)

    def test_package_visibility_and_extracted_native_libraries(self):
        queries = self.manifest('host').findall('queries/package')
        self.assertEqual({'dev.zips.clientbase.guest', 'com.mojang.minecraftpe'},
                         {query.get(ANDROID + 'name') for query in queries})
        app = self.manifest('demo-guest').find('application')
        self.assertEqual('true', app.get(ANDROID + 'extractNativeLibs'))
        gradle = (BASE / 'demo-guest/build.gradle').read_text()
        self.assertIn('useLegacyPackaging = true', gradle)
        self.assertIn("abiFilters 'arm64-v8a', 'x86_64'", gradle)

    def test_protocol_version_matches(self):
        contract = (BASE / 'contract/src/main/java/dev/zips/clientbase/contract/GuestEntry.java').read_text()
        version = re.search(r'API_VERSION = (\d+)', contract).group(1)
        metadata = self.manifest('demo-guest').find('application/meta-data')
        self.assertEqual('dev.zips.clientbase.API_VERSION', metadata.get(ANDROID + 'name'))
        self.assertEqual(version, metadata.get(ANDROID + 'value'))

    def test_loader_checks_trust_before_executing_guest(self):
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/GuestRuntime.java').read_text()
        self.assertLess(source.index('pm.checkSignatures'), source.index('Class.forName'))
        self.assertLess(source.index('Unsupported demo protocol'), source.index('Class.forName'))
        self.assertIn('info.splitSourceDirs', source)
        self.assertIn('app.getClassLoader()', source)
        self.assertIn('static synchronized LoadedGuest load', source)
        self.assertIn('if (cached != null) return cached', source)

    def test_separate_applications_and_shared_parent_contract(self):
        ids = []
        for module in ('host', 'demo-guest'):
            source = (BASE / module / 'build.gradle').read_text()
            ids.append(re.search(r"applicationId '([^']+)'", source).group(1))
            self.assertIn("implementation project(':contract')", source)
            self.assertIn('targetSdk 33', source)
        self.assertNotEqual(*ids)
        self.assertNotIn("project(':demo-guest')", (BASE / 'host/build.gradle').read_text())

    def test_official_wrapper_identity(self):
        path = BASE / 'gradle/wrapper/gradle-wrapper.jar'
        self.assertTrue(zipfile.is_zipfile(path))
        self.assertEqual('2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046',
                         hashlib.sha256(path.read_bytes()).hexdigest())

    def test_jni_names_match_guest_declarations(self):
        java = (BASE / 'demo-guest/src/main/java/dev/zips/clientbase/guest/DemoGuestEntry.java').read_text()
        cpp = (BASE / 'demo-guest/src/main/cpp/demo_jni.cpp').read_text()
        methods = re.findall(r'private static native int (\w+)\(\)', java)
        self.assertEqual(['nativeProcessId', 'nativeUserId'], methods)
        for method in methods:
            self.assertIn('Java_dev_zips_clientbase_guest_DemoGuestEntry_' + method, cpp)

    def test_engine_pins_match_provenance(self):
        import json
        metadata = json.loads((BASE / 'docs/ENGINE_INPUT.json').read_text())
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/EngineInput.java').read_text()
        for key in ('archive_sha256', 'library_sha256'):
            self.assertIn(metadata[key], source)
        for key in ('archive_bytes', 'library_bytes'):
            self.assertIn(str(metadata[key]) + 'L', source)
        self.assertLess(source.index('ARCHIVE_SHA.equals(fingerprint)'), source.index('new SevenZFile('))

    def test_engine_probe_is_private_and_match_gated(self):
        service = self.manifest('host').find('application/service')
        self.assertEqual(':engine_probe', service.get(ANDROID + 'process'))
        self.assertEqual('false', service.get(ANDROID + 'exported'))
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/EngineProbeService.java').read_text()
        self.assertLess(source.index('EngineInput.verifyLibrary(engine)'), source.index('Class.forName'))
        self.assertLess(source.index('if (!matchesInstalledEngine'), source.index('Class.forName'))
        self.assertNotIn('System.load(', source)

    @unittest.skipUnless(shutil.which('g++'), 'C++ compiler not installed')
    def test_real_native_process_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'identity.cpp'
            source.write_text('#include "process_identity.h"\n#include <iostream>\n'
                              'int main() { auto p = clientbase::current_identity(); '
                              'std::cout << p.pid << " " << p.uid << std::endl; }\n')
            subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                            '-I', str(BASE / 'demo-guest/src/main/cpp'), str(source),
                            '-o', str(root / 'identity')], check=True, timeout=30)
            with subprocess.Popen([str(root / 'identity')], stdout=subprocess.PIPE, text=True) as process:
                output, _ = process.communicate(timeout=5)
                self.assertEqual(0, process.returncode)
                self.assertEqual([process.pid, os.getuid()], list(map(int, output.split())))


if __name__ == '__main__':
    unittest.main(verbosity=2)
