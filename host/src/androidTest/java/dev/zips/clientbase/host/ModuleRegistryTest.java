package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ModuleRegistryTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private void awaitHostStopped(Context c) {
        long deadline = SystemClock.uptimeMillis() + 5000L;
        while (SystemClock.uptimeMillis() < deadline && ModuleClient.isGameHostProcessRunning(c)) {
            SystemClock.sleep(40L);
        }
    }

    @Test public void registrationsProtocolAndStateParserNeverFabricateLiveReadiness() throws Exception {
        assertEquals(2, ModuleRegistry.all().size());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ModuleRegistry.Entry entry : ModuleRegistry.all()) {
            assertTrue(ids.add(entry.id));
            assertTrue(entry.defaultStatus.startsWith("OFF"));
            assertTrue(entry.toggleId != 0 && entry.statusId != 0);
        }
        assertEquals("MotionFly", ModuleRegistry.forCategory(ModuleCategory.MOVEMENT).get(0).name);
        assertEquals("KillAura", ModuleRegistry.forCategory(ModuleCategory.COMBAT).get(0).name);
        assertTrue(ModuleRegistry.forCategory(ModuleCategory.VISUAL).isEmpty());
        assertTrue(ModuleRegistry.forCategory(ModuleCategory.MISC).isEmpty());

        try {
            GameActivityRuntime.moduleControl(context(), 0, false, false, true, false, false);
            fail("Module runtime allowed in UI process");
        } catch (SecurityException expected) {
            assertTrue(expected.getMessage().contains("dedicated host process"));
        }

        Message req = ModuleProtocol.request(ModuleProtocol.APPLY, 7, true, false, true, false, false);
        req.sendingUid = Process.myUid();
        req.replyTo = new Messenger(new Handler(Looper.getMainLooper()));
        assertTrue(ModuleProtocol.accepts(req));
        req.sendingUid = -1;
        assertFalse(ModuleProtocol.accepts(req));
        req.sendingUid = Process.myUid();
        req.what = MemoryProtocol.SNAPSHOT;
        assertFalse(ModuleProtocol.accepts(req));

        ModuleState armed = ModuleState.parse(
                "supported=1\nhooks_installed=1\nin_world=0\nfly_armed=1\nfly_active=0\n"
                + "aura_armed=1\naura_active=0\nattack_mobs=1\nmulti_target=1\ncheck_hurt_time=0\n"
                + "self_runtime_id=0\nauth_packets=0\nmotion_injections=0\naura_ticks=0\naura_attacks=0\n"
                + "message=Armed\n---\nDiagnostic");
        assertEquals("ARMED", armed.badgeText(ModuleState.MOTION_FLY));
        assertEquals("ARMED", armed.badgeText(ModuleState.KILL_AURA));
        assertTrue(armed.multiTarget);

        ModuleState active = ModuleState.parse(
                "supported=1\nhooks_installed=1\nin_world=1\nfly_armed=1\nfly_active=1\n"
                + "aura_armed=1\naura_active=1\nattack_mobs=0\nmulti_target=0\ncheck_hurt_time=1\n"
                + "self_runtime_id=42\nauth_packets=10\nmotion_injections=9\naura_ticks=20\naura_attacks=3\n"
                + "message=Live\n---\nDiagnostic");
        assertEquals("ON", active.badgeText(ModuleState.MOTION_FLY));
        assertEquals("ON", active.badgeText(ModuleState.KILL_AURA));
        assertFalse(active.attackMobs);
        assertTrue(active.checkHurtTime);
        assertEquals(42L, active.selfRuntimeId);

        try { ModuleRegistry.all().clear(); fail("Mutable registrations"); }
        catch (UnsupportedOperationException expected) {}
        try { ModuleRegistry.forCategory(ModuleCategory.MOVEMENT).clear(); fail("Mutable category"); }
        catch (UnsupportedOperationException expected) {}
    }

    @Test public void isolatedNativeBridgeFailsClosedWithoutVerifiedBedrockEngine() throws Exception {
        Context c = context();
        File directory = Files.createTempDirectory(c.getCodeCacheDir().toPath(), "module-fixture-").toFile();
        File copied = new File(directory, "libclientbase_engineprobe.so");
        File original = new File(c.getApplicationInfo().nativeLibraryDir, copied.getName());
        Files.copy(original.toPath(), copied.toPath());
        assertTrue(copied.setReadOnly());
        try {
            BootstrapClassLoader loader = new BootstrapClassLoader(
                    c.getApplicationInfo().sourceDir, directory.getAbsolutePath(),
                    directory.getAbsolutePath(), c.getClassLoader(), (n, p) -> {});
            Class<?> bridge = Class.forName("dev.zips.clientbase.host.EngineNativeBridge", true, loader);
            assertSame(loader, bridge.getClassLoader());
            String raw = (String) bridge.getMethod("moduleControl",
                    String.class, int.class, boolean.class, boolean.class,
                    boolean.class, boolean.class, boolean.class)
                    .invoke(null, copied.getAbsolutePath(), 1, true, true, true, true, false);
            ModuleState state = ModuleState.parse(raw);
            assertFalse(state.hooksInstalled);
            assertFalse(state.flyArmed);
            assertFalse(state.flyActive);
            assertFalse(state.auraArmed);
            assertFalse(state.auraActive);
            assertEquals("OFF", state.badgeText(ModuleState.MOTION_FLY));
            assertEquals("OFF", state.badgeText(ModuleState.KILL_AURA));
        } finally {
            copied.delete();
            directory.delete();
        }
    }

    @Test public void realModuleIpcRejectsEnableWithoutLiveBedrockSessionAndPreservesStageReport() throws Exception {
        Context c = context();
        awaitHostStopped(c);
        String before = GameHostReport.read(GameHostReport.stageFile(c));
        CountDownLatch latch = new CountDownLatch(1);
        final ModuleState[] reply = {null};
        ModuleClient client = MinecraftBootstrap.onMainThread(() -> {
            ModuleClient mc = new ModuleClient(c, ModuleProtocol.APPLY, true, true, true, true, false,
                    /*requireRunningHost=*/false, state -> {
                        reply[0] = state;
                        latch.countDown();
                    });
            mc.start();
            return mc;
        });
        try {
            assertTrue("Expected :game_host module reply", latch.await(10, TimeUnit.SECONDS));
            assertFalse(reply[0].hooksInstalled);
            assertFalse(reply[0].flyArmed);
            assertFalse(reply[0].flyActive);
            assertFalse(reply[0].auraArmed);
            assertFalse(reply[0].auraActive);
            assertEquals("OFF", reply[0].badgeText(ModuleState.MOTION_FLY));
            assertEquals("OFF", reply[0].badgeText(ModuleState.KILL_AURA));
            assertTrue(reply[0].message, reply[0].message.contains("No live prepared Activity session"));
            assertEquals(before, GameHostReport.read(GameHostReport.stageFile(c)));
        } finally {
            MinecraftBootstrap.onMainThread(() -> { client.cancel(); return null; });
            awaitHostStopped(c);
        }
    }

    @Test public void controlsKeepTechnicalReportsOutsideTheModuleMenu() throws Exception {
        Context c = context();
        awaitHostStopped(c);
        try (ActivityScenario<ControlsActivity> controls = ActivityScenario.launch(ControlsActivity.class)) {
            controls.onActivity(a -> {
                assertNotNull(a.findViewById(R.id.memory_refresh));
                assertNotNull(a.findViewById(R.id.memory_copy));
                assertNotNull(a.findViewById(R.id.module_engine_refresh));
                assertNotNull(a.findViewById(R.id.module_engine_disarm));
                assertNotNull(a.findViewById(R.id.module_engine_copy));
                assertTrue(((TextView) a.findViewById(R.id.memory_report)).getText().length() > 0);
                assertTrue(((TextView) a.findViewById(R.id.module_engine_report)).getText().length() > 0);
                a.findViewById(R.id.module_engine_refresh).performClick();
            });
            long deadline = SystemClock.uptimeMillis() + 10000L;
            final String[] text = {""};
            while (SystemClock.uptimeMillis() < deadline) {
                controls.onActivity(a -> text[0] = ((TextView) a.findViewById(R.id.module_engine_report)).getText().toString());
                if (text[0].contains("INTERNAL MODULE ENGINE")) break;
                SystemClock.sleep(50L);
            }
            assertTrue(text[0], text[0].contains("INTERNAL MODULE ENGINE"));
        } finally {
            awaitHostStopped(c);
        }
    }
}
