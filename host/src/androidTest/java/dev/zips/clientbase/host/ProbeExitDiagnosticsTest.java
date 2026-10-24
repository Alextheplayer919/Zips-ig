package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ProbeExitDiagnosticsTest {
    @Test public void tombstoneExtractsOnlyCrashIdentitySignalAndFrames() throws Exception {
        byte[] frame = join(v(1, 0x1234), text(4, "fixture_abort"), text(6, "libfixture.so"), text(8, "f00d"));
        byte[] thread = join(v(1, 43), text(2, "fixture-main"), msg(4, frame), text(3, "SECRET_REGISTER"), text(5, "SECRET_MEMORY"));
        byte[] map = join(v(1, 43), msg(2, thread));
        byte[] signal = join(v(1, 6), text(2, "SIGABRT"), v(3, -6), text(4, "SI_TKILL"));
        // Map before PID/TID intentionally tests valid out-of-order protobuf fields.
        byte[] bytes = join(msg(16, map), identity(), msg(10, signal), text(14, "fixture assertion"), text(17, "SECRET_MAPS"));
        String result = NativeTombstoneSummary.decode(bytes, 43, 10001, false);
        assertTrue(result, result.contains("SIGABRT"));
        assertTrue(result, result.contains("fixture assertion"));
        assertTrue(result, result.contains("rel_pc=0x1234 libfixture.so fixture_abort"));
        assertFalse(result, result.contains("SECRET_"));
        try { NativeTombstoneSummary.decode(bytes, 44, 10001, false); fail("Foreign trace accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("mismatch")); }
    }

    @Test public void truncatedAndMalformedTracesFailClosedOrExplicitlyMarkPartial() throws Exception {
        for (byte[] bytes : new byte[][] {new byte[0], new byte[] {(byte) 0xff}, msg(16, new byte[] {0})}) {
            try { NativeTombstoneSummary.decode(bytes, 43, 10001, false); fail("Unidentified trace accepted"); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
        byte[] partial = join(identity(), new byte[] {(byte) 0x82, 1, 10, 0}); // Incomplete field 16.
        String result = NativeTombstoneSummary.decode(partial, 43, 10001, true);
        assertTrue(result, result.contains("incomplete"));
        assertTrue(result, result.contains("input cap reached"));
        assertTrue(result, result.contains("frames unavailable"));
    }

    @Test public void recordMatchingRequiresPidUidProcessAndRunTime() {
        ProbeExitDiagnostics.Run run = new ProbeExitDiagnostics.Run(43, 10001, 10000, "test");
        String pkg = "dev.zips.clientbase.host";
        assertTrue(ProbeExitDiagnostics.matches(run, 43, 10001, pkg + ":engine_probe", 11000, pkg));
        assertFalse(ProbeExitDiagnostics.matches(run, 44, 10001, pkg + ":engine_probe", 11000, pkg));
        assertFalse(ProbeExitDiagnostics.matches(run, 43, 10002, pkg + ":engine_probe", 11000, pkg));
        assertFalse(ProbeExitDiagnostics.matches(run, 43, 10001, "com.mojang.minecraftpe", 11000, pkg));
        assertFalse(ProbeExitDiagnostics.matches(run, 43, 10001, pkg + ":engine_probe", 1000, pkg));
        assertFalse(ProbeExitDiagnostics.matches(run, 43, 10001, pkg + ":engine_probe", 1000000, pkg));
    }

    @Test public void androidReportsOwnChildTerminationWithoutRunningEngine() throws Exception {
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch connected = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { disconnected.countDown(); }
        };
        long started = System.currentTimeMillis(); int pid = 0;
        assertTrue(host.bindService(new Intent(host, EngineProbeService.class), connection, Context.BIND_AUTO_CREATE));
        try {
            assertTrue("No private service binding", connected.await(10, TimeUnit.SECONDS));
            ActivityManager am = host.getSystemService(ActivityManager.class);
            for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                if ((host.getPackageName() + ":engine_probe").equals(info.processName) && info.uid == Process.myUid()) pid = info.pid;
            }
            assertTrue(pid > 0 && pid != Process.myPid());
            // No Messenger request is sent: no import, guest definition or engine load.
            Process.killProcess(pid);
            assertTrue("No child-death callback", disconnected.await(10, TimeUnit.SECONDS));
        } finally { host.unbindService(connection); }
        String result = ProbeExitDiagnostics.collect(host,
                new ProbeExitDiagnostics.Run(pid, Process.myUid(), started, "TEST intentional SIGKILL; no Minecraft code"));
        assertTrue(result, result.contains("Exit reason:"));
        assertTrue(result, result.contains("Expected child PID: " + pid));
        assertTrue(result, result.contains("TEST intentional SIGKILL"));
        assertFalse(result, result.contains("Exit reason: NATIVE_CRASH"));
    }

    private static byte[] identity() throws IOException { return join(v(5, 43), v(6, 43), v(7, 10001)); }
    private static byte[] text(int field, String value) throws IOException { return msg(field, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] msg(int field, byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, ((long) field << 3) | 2); varint(out, bytes.length); out.write(bytes); return out.toByteArray();
    }
    private static byte[] v(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); varint(out, (long) field << 3); varint(out, value); return out.toByteArray();
    }
    private static void varint(ByteArrayOutputStream out, long value) {
        do { int b = (int) value & 127; value >>>= 7; out.write(b | (value == 0 ? 0 : 128)); } while (value != 0);
    }
    private static byte[] join(byte[]... chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] chunk : chunks) out.write(chunk); return out.toByteArray();
    }
}
