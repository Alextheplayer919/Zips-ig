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

    def test_manifest_xml_and_least_privilege_permissions(self):
        for module in ('host', 'demo-guest', 'contract'):
            root = self.manifest(module)
            expected = (['android.permission.ACCESS_NETWORK_STATE', 'android.permission.INTERNET',
                         'android.permission.SYSTEM_ALERT_WINDOW', 'android.permission.FOREGROUND_SERVICE',
                         'android.permission.POST_NOTIFICATIONS'] if module == 'host'
                        else ['dev.zips.clientbase.guest.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'] if module == 'demo-guest' else [])
            self.assertEqual(expected, [permission.get(ANDROID + 'name') for permission in root.findall('uses-permission')])
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
        workflow = (BASE / '.github/workflows/build-apk.yml').read_text()
        self.assertIn('tools/emulator_checks.sh', workflow)
        self.assertIn('engineFixtureRequired=true', (BASE / 'tools/run_device_checks.sh').read_text())

    def test_engine_probe_is_private_and_match_gated(self):
        service = self.manifest('host').find('application/service')
        self.assertEqual(':engine_probe', service.get(ANDROID + 'process'))
        self.assertEqual('false', service.get(ANDROID + 'exported'))
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/EngineProbeService.java').read_text()
        self.assertLess(source.index('EngineInput.verifyLibrary(engine)'), source.index('Class.forName'))
        self.assertLess(source.index('if (!matchesInstalledEngine'), source.index('Class.forName'))
        self.assertNotIn('System.load(', source)

    def test_bootstrap_inspection_does_not_load_guest_code(self):
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/DexBootstrapInspector.java').read_text()
        for forbidden in ('Class.forName(', 'DexClassLoader', 'System.load(', 'System.loadLibrary(', 'EngineNativeBridge'):
            self.assertNotIn(forbidden, source)
        for required in ('MAX_DEX', 'MAX_UNITS', 'reference_scan_complete', 'Files.deleteIfExists'):
            self.assertIn(required, source)
        service = (BASE / 'host/src/main/java/dev/zips/clientbase/host/EngineProbeService.java').read_text()
        self.assertLess(service.index('if (!matchesInstalledEngine'), service.index('DexBootstrapInspector.inspectApks'))
        self.assertLess(service.index('DexBootstrapInspector.inspectApks'), service.index('DexClassLoader loader'))

    def test_jni_bootstrap_record_matches_input_identity(self):
        import json
        source = json.loads((BASE / 'docs/JNI_BOOTSTRAP.json').read_text())
        engine = json.loads((BASE / 'docs/ENGINE_INPUT.json').read_text())
        self.assertEqual(engine['library_sha256'], source['library_sha256'])
        self.assertEqual('0x852e0e0', source['jni_onload']['elf_va'])
        self.assertEqual(288, source['jni_onload']['bytes'])
        self.assertEqual('com/mojang/minecraftpe/MainActivity', source['literal_references']['anchor_class']['value'])
        self.assertIn('sigaction', [call['name'] for call in source['jni_onload']['calls']])

    def test_jvm_bootstrap_is_gated_and_has_bounded_process_cleanup(self):
        runtime = (BASE / 'host/src/main/java/dev/zips/clientbase/host/MinecraftBootstrap.java').read_text()
        initialize = runtime.index('Class.forName(MAIN, true, loader)')
        for gate in ('verifyMainInitializer(main)', 'verifyParentInitializer(parent)', 'verifySelectedEngine(enginePath, game, apks)'):
            self.assertLess(runtime.index(gate), initialize)
        self.assertLess(runtime.index('verifySelectedEngine(enginePath, game, apks)'), runtime.index('Class.forName(MAIN, false, loader)'))
        self.assertIn('onMainThread(', runtime)
        self.assertNotIn('.newInstance(', runtime)
        self.assertNotIn('getDeclaredConstructor', runtime)
        self.assertIn('RTLD_NOLOAD', (BASE / 'host/src/main/cpp/engine_probe.cpp').read_text())
        controller = (BASE / 'host/src/main/java/dev/zips/clientbase/host/EngineProbeController.java').read_text()
        self.assertIn('getInt("uid", -1) == android.os.Process.myUid()', controller)
        self.assertIn('probePid != android.os.Process.myPid()', controller)
        self.assertIn('probePid = 0;', controller)

    def test_phone_bootstrap_metadata_matches_initialization_policy(self):
        import json
        report = json.loads((BASE / 'docs/BOOTSTRAP_DEVICE_TEST.json').read_text())
        main = report['main_activity']
        runtime = (BASE / 'host/src/main/java/dev/zips/clientbase/host/MinecraftBootstrap.java').read_text()
        code = re.search(r'KNOWN_CODE = "([^"]+)"', runtime).group(1)
        self.assertEqual(main['clinit']['code_units_hex'], code)
        self.assertEqual(45, len(code.split()))
        self.assertEqual(main['native_method_count'], len(main['native_methods']))
        self.assertIn(report['version_name'], runtime)
        self.assertIn(str(report['version_code']) + 'L', runtime)

    def test_reported_gameactivity_is_exactly_the_reviewed_noop(self):
        import json
        report = json.loads((BASE / 'docs/GAMEACTIVITY_DEVICE_TEST.json').read_text())
        parent = report['game_activity']
        self.assertEqual({'registers': 0, 'code_units_total': 1, 'code_units_hex': '000e',
                          'truncated': False, 'references': [], 'reference_scan_complete': True}, parent['clinit'])
        self.assertEqual(24, len(parent['native_methods']))
        self.assertEqual('PROBE_BLOCKED', report['result'])
        runtime = (BASE / 'host/src/main/java/dev/zips/clientbase/host/MinecraftBootstrap.java').read_text()
        for guard in ('code.getInt("registers") == 0', 'code.getInt("code_units_total") == 1',
                      '"000e".equals(code.getString("code_units_hex"))', '!code.getBoolean("truncated")',
                      'code.getBoolean("reference_scan_complete")', 'code.getJSONArray("references").length() == 0'):
            self.assertIn(guard, runtime)

    def test_exit_diagnostics_are_own_identity_filtered_and_bounded(self):
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/ProbeExitDiagnostics.java').read_text()
        for required in ('getHistoricalProcessExitReasons(context.getPackageName(), run.pid, 8)',
                         'run.uid != Process.myUid()', 'pid == run.pid', 'uid == run.uid',
                         'packageName + ":engine_probe"', 'TRACE_LIMIT', 'getTraceInputStream()',
                         'FullLogCapture.copy(input, output)', '2500, TimeUnit.MILLISECONDS'):
            self.assertIn(required, source)
        parser = (BASE / 'host/src/main/java/dev/zips/clientbase/host/NativeTombstoneSummary.java').read_text()
        self.assertIn('pid != expectedPid || uid != expectedUid', parser)
        self.assertIn('new int[] {100000}', parser)
        self.assertIn('frames < 32', parser)
        self.assertNotIn('FileOutputStream', parser)
        controller = (BASE / 'host/src/main/java/dev/zips/clientbase/host/EngineProbeController.java').read_text()
        self.assertIn('if (!running) return;', controller)
        self.assertIn('savedStage(), true, false)', controller)
        self.assertIn('diagnosticId != generation', controller)
        self.assertIn('12000', controller)

    def test_disconnect_evidence_does_not_claim_playfab_is_the_cause(self):
        import json
        report = json.loads((BASE / 'docs/JVM_DISCONNECT_DEVICE_TEST.json').read_text())
        self.assertEqual('JVM_INITIALIZATION_IN_PROGRESS', report['last_stage'])
        self.assertEqual('PlayFabMultiplayer', report['native_library_lookups_not_completion_receipts'][-1]['name'])
        self.assertTrue(report['cause'].startswith('Unknown:'))

    def test_network_permission_preflight_precedes_guest_class_loading(self):
        runtime = (BASE / 'host/src/main/java/dev/zips/clientbase/host/MinecraftBootstrap.java').read_text()
        self.assertIn('Manifest.permission.ACCESS_NETWORK_STATE', runtime)
        self.assertIn('context.checkSelfPermission', runtime)
        self.assertLess(runtime.index('verifyHostPermissions(context)'), runtime.index('Class.forName(MAIN, false, loader)'))
        self.assertLess(runtime.index('verifyHostPermissions(context)'), runtime.index('Class.forName(MAIN, true, loader)'))

    def test_framework_activity_is_fixed_private_fixture_not_game_launch(self):
        app = self.manifest('host').find('application')
        self.assertEqual('.HostComponentFactory', app.get(ANDROID + 'appComponentFactory'))
        fixture = next(a for a in app.findall('activity') if a.get(ANDROID + 'name') == '.HostedGuestPlaceholderActivity')
        self.assertEqual('false', fixture.get(ANDROID + 'exported'))
        self.assertFalse(any('mojang' in a.get(ANDROID + 'name', '') for a in app.findall('activity')))
        factory = (BASE / 'host/src/main/java/dev/zips/clientbase/host/HostComponentFactory.java').read_text()
        self.assertIn('super.instantiateActivity(loader, name, intent)', factory)
        self.assertIn('getComponent().getPackageName()', factory)
        runtime = (BASE / 'host/src/main/java/dev/zips/clientbase/host/FrameworkActivityRuntime.java').read_text()
        self.assertLess(runtime.index('GuestRuntime.load(context)'), runtime.index('Class.forName(FIXTURE'))
        self.assertIn('type.getClassLoader() != guest.loader', runtime)
        self.assertNotIn('.onCreate(', runtime)
        self.assertNotIn('setAccessible', runtime)
        self.assertNotIn('CONTEXT_INCLUDE_CODE', runtime)

    def test_activity_resource_wrapper_retains_host_identity_and_storage(self):
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/ActivityResourceContext.java').read_text()
        for required in ('resourceContext.getResources()', 'getResources().getAssets()', 'return guestLoader;',
                         'cloneInContext(this)', 'resourceContext.createConfigurationContext(override)'):
            self.assertIn(required, source)
        for forbidden in ('getPackageName()', 'getApplicationInfo()', 'getFilesDir()', 'checkPermission(', 'setAccessible'):
            self.assertNotIn(forbidden, source)

    def test_game_adapter_is_separate_process_and_compile_only_api_is_not_packaged(self):
        app = self.manifest('host').find('application')
        component = next(a for a in app.findall('activity') if a.get(ANDROID + 'name') == '.MinecraftHostedActivity')
        self.assertEqual(':game_host', component.get(ANDROID + 'process'))
        self.assertEqual('false', component.get(ANDROID + 'exported'))
        gradle = (BASE / 'host/build.gradle').read_text()
        self.assertIn("compileOnly project(':bedrock-api')", gradle)
        workflow = (BASE / '.github/workflows/build-apk.yml').read_text()
        self.assertIn('python3 tools/verify_apks.py build/apks', workflow)
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/GameActivityRuntime.java').read_text()
        self.assertLess(source.index('BedrockInputPins.verify'), source.index('MinecraftBootstrap.prepare'))
        self.assertIn('wrapper.getSuperclass() != prepared.mainClass', source)
        self.assertIn('requireGameProcess(context)', source)
        self.assertNotIn('.onCreate(', source)

    def test_game_startup_pins_match_supplied_archive_metadata(self):
        import json
        data = json.loads((BASE / 'docs/STARTUP_INPUT.json').read_text())
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/BedrockInputPins.java').read_text()
        for item in data['files']:
            for value in (item['name'], str(item['bytes']), item['sha256']):
                self.assertIn(value, source)
        controller = (BASE / 'host/src/main/java/dev/zips/clientbase/host/GameHostController.java').read_text()
        for required in ('data.getInt("uid", -1) != Process.myUid()', 'pid != Process.myPid()',
                         'binder.isBinderAlive()', '12000', 'request != collectionId', 'expected != generation'):
            self.assertIn(required, controller)

    def test_wrapper_helpers_share_loader_and_manifest_registers_actual_wrapper_name(self):
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/BootstrapClassLoader.java').read_text()
        self.assertIn('name.startsWith(root + "$"', source)
        self.assertIn('ownsHostClass(name)', source)
        wrapper = (BASE / 'host/src/main/java/dev/zips/clientbase/host/MinecraftHostedActivity.java').read_text()
        self.assertIn('new HostedSurfaceObserver(this, environment)', wrapper)
        factory = (BASE / 'host/src/main/java/dev/zips/clientbase/host/HostComponentFactory.java').read_text()
        self.assertIn('GameActivityRuntime.WRAPPER.equals(name)', factory)
        activity = self.manifest('host').find("application/activity[@" + ANDROID + "name='.MinecraftHostedActivity']")
        self.assertIsNotNone(activity)
        ui = (BASE / 'host/src/main/java/dev/zips/clientbase/host/MainActivity.java').read_text()
        self.assertIn('Intent.ACTION_CREATE_DOCUMENT', ui)
        self.assertIn('openOutputStream(target, "wt")', ui)

    def test_play_games_initializes_only_after_pins_and_before_original_activity(self):
        runtime = (BASE / 'host/src/main/java/dev/zips/clientbase/host/GameActivityRuntime.java').read_text()
        self.assertLess(runtime.index('PlayGamesStartup.initialize('), runtime.index('Class.forName(WRAPPER, true'))
        self.assertIn('BedrockInputPins.verify(game.sourceDir)', runtime)
        startup = (BASE / 'host/src/main/java/dev/zips/clientbase/host/PlayGamesStartup.java').read_text()
        for required in ('GameActivityRuntime.requireGameProcess(application)',
                         'com.google.android.gms.games.PlayGamesSdk',
                         'sdk.getClassLoader() != loader', 'Looper.getMainLooper()',
                         'getMethod("initialize", Context.class)', 'method.invoke(null, application)',
                         'finally { Thread.currentThread().setContextClassLoader(previous); }'):
            self.assertIn(required, startup)
        self.assertNotIn('com.google.android.gms.games.provider.PlayGamesInitProvider',
                         (BASE / 'host/src/main/AndroidManifest.xml').read_text())

    def test_firebase_startup_has_discovery_and_blocks_missing_default_before_jni(self):
        runtime = (BASE / 'host/src/main/java/dev/zips/clientbase/host/GameActivityRuntime.java').read_text()
        self.assertLess(runtime.index('FirebaseStartup.initialize('), runtime.index('PlayGamesStartup.initialize('))
        self.assertLess(runtime.index('FirebaseStartup.initialize('), runtime.index('Class.forName(WRAPPER, true'))
        startup = (BASE / 'host/src/main/java/dev/zips/clientbase/host/FirebaseStartup.java').read_text()
        for required in ('GameActivityRuntime.requireGameProcess(application)', 'initializeApp',
                         'initialized == null', 'initialized != getDefault.invoke(null)',
                         'getMessaging.invoke(null) == null', 'app.getClassLoader() != loader',
                         'finally { Thread.currentThread().setContextClassLoader(previous); }'):
            self.assertIn(required, startup)
        service = self.manifest('host').find("application/service[@" + ANDROID + "name='com.google.firebase.components.ComponentDiscoveryService']")
        self.assertIsNotNone(service)
        self.assertEqual('false', service.get(ANDROID + 'exported'))
        self.assertEqual(':game_host', service.get(ANDROID + 'process'))
        self.assertEqual(3, len(service.findall('meta-data')))

    def test_controls_are_external_and_memory_reads_are_fixed_read_only(self):
        activity = self.manifest('host').find("application/activity[@" + ANDROID + "name='.ControlsActivity']")
        self.assertEqual('false', activity.get(ANDROID + 'exported'))
        self.assertEqual('true', activity.get(ANDROID + 'resizeableActivity'))
        self.assertEqual('dev.zips.clientbase.host.controls', activity.get(ANDROID + 'taskAffinity'))
        self.assertIsNone(activity.get(ANDROID + 'process'))
        runtime = (BASE / 'host/src/main/java/dev/zips/clientbase/host/GameActivityRuntime.java').read_text()
        self.assertNotIn('HostedClickGui', runtime)
        self.assertFalse((BASE / 'host/src/main/java/dev/zips/clientbase/host/HostedClickGui.java').exists())
        native = (BASE / 'host/src/main/cpp/memory_snapshot.cpp').read_text()
        for required in ('dl_iterate_phdr(findModule', '"/proc/self/mem", O_RDONLY', 'pread(fd', 'MODULE_NOT_LOADED', 'NT_GNU_BUILD_ID'):
            self.assertIn(required, native)
        for forbidden in ('pwrite(', 'mprotect(', 'ptrace(', 'dlopen(', 'process_vm_writev('):
            self.assertNotIn(forbidden, native)
        client = (BASE / 'host/src/main/java/dev/zips/clientbase/host/MemoryClient.java').read_text()
        self.assertIn('8000', client)
        self.assertNotIn('GameHostController.start()', client)

    def test_overlay_is_optional_external_nonsticky_and_not_game_renderer(self):
        service = self.manifest('host').find("application/service[@" + ANDROID + "name='.OverlayService']")
        self.assertEqual('false', service.get(ANDROID + 'exported'))
        self.assertIsNone(service.get(ANDROID + 'process'))
        self.assertEqual('true', service.get(ANDROID + 'stopWithTask'))
        source = (BASE / 'host/src/main/java/dev/zips/clientbase/host/OverlayService.java').read_text()
        for required in ('START_NOT_STICKY', 'startForeground(', 'Settings.canDrawOverlays', 'stopWatchingMode', 'STOP_FOREGROUND_REMOVE'):
            self.assertIn(required, source)
        self.assertNotIn('return START_STICKY', source)
        window = (BASE / 'host/src/main/java/dev/zips/clientbase/host/OverlayWindow.java').read_text()
        for required in ('TYPE_APPLICATION_OVERLAY', 'FLAG_NOT_FOCUSABLE', 'FLAG_NOT_TOUCH_MODAL', 'ModuleCategory.values()'):
            self.assertIn(required, window)
        for forbidden in ('MATCH_PARENT', 'FLAG_LAYOUT_NO_LIMITS', 'GameHostController.start()', 'setFocusableInTouchMode(true)', 'MemoryClient', 'ClipboardManager', 'Refresh memory', 'Copy memory'):
            self.assertNotIn(forbidden, window)
        self.assertFalse(any((BASE / 'host/src').rglob('*.kt')))
        self.assertNotIn('compose', (BASE / 'host/build.gradle').read_text().lower())

    def test_category_gui_is_navigation_only_and_diagnostics_remain_in_controls(self):
        category = (BASE / 'host/src/main/java/dev/zips/clientbase/host/ModuleCategory.java').read_text()
        for title in ('Movement', 'Combat', 'Visual', 'Misc'):
            self.assertIn('"' + title + '"', category)
        window = (BASE / 'host/src/main/java/dev/zips/clientbase/host/OverlayWindow.java').read_text()
        self.assertIn('No modules yet', window)
        self.assertIn('button.setSelected(active)', window)
        for forbidden in ('Switch(', 'CheckBox(', 'MemoryClient', 'ClipboardManager', 'GameHostController'):
            self.assertNotIn(forbidden, window)
        controls = (BASE / 'host/src/main/java/dev/zips/clientbase/host/ControlsActivity.java').read_text()
        for required in ('new MemoryClient(', 'R.id.memory_copy', 'Copy memory report'):
            self.assertIn(required, controls)

    @unittest.skipUnless(shutil.which('g++'), 'C++ compiler not installed')
    def test_ported_module_policies_without_engine_or_network_side_effects(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = str(Path(directory) / 'module-policies-test')
            subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                            '-I', str(BASE / 'host/src/main/cpp/modules'),
                            str(BASE / 'tests/native/module_policies_test.cpp'), '-o', binary],
                           check=True, timeout=30)
            subprocess.run([binary], check=True, timeout=10)

    @unittest.skipUnless(shutil.which('g++'), 'C++ compiler not installed')
    def test_self_tracker_and_fly_session_lifecycle(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = str(Path(directory) / 'self-tracker-test')
            subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                            '-I', str(BASE / 'host/src/main/cpp/modules'),
                            str(BASE / 'tests/native/self_tracker_test.cpp'), '-o', binary],
                           check=True, timeout=30)
            subprocess.run([binary], check=True, timeout=10)
        source = (BASE / 'host/src/main/cpp/modules/module_policies.h').read_text()
        for removed in ('FlyDependencies', 'collisionClamp', 'adaptiveGovernor', 'bool adaptive'):
            self.assertNotIn(removed, source)

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
