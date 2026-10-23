package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Message;
import android.os.Messenger;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real JNI memory reads against our own copied library, not fake/proprietary Minecraft code. */
@RunWith(AndroidJUnit4.class)
public final class MemoryIntegrationTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    @Test public void nativeSnapshotReadsOwnLoadedElfAndDoesNotLoadAnUnloadedCandidate() throws Exception {
        Context c=context();File directory=Files.createTempDirectory(c.getCodeCacheDir().toPath(),"memory-fixture-").toFile();
        File loaded=new File(directory,"libclientbase_engineprobe.so"),unloaded=new File(directory,"libunloaded_fixture.so");
        File original=new File(c.getApplicationInfo().nativeLibraryDir,loaded.getName());
        Files.copy(original.toPath(),loaded.toPath());Files.copy(original.toPath(),unloaded.toPath());
        assertTrue(loaded.setReadOnly());assertTrue(unloaded.setReadOnly());
        try {
            BootstrapClassLoader loader=new BootstrapClassLoader(c.getApplicationInfo().sourceDir,directory.getAbsolutePath(),directory.getAbsolutePath(),c.getClassLoader(),(n,p)->{});
            Class<?> bridge=Class.forName("dev.zips.clientbase.host.EngineNativeBridge",true,loader);
            assertSame(loader,bridge.getClassLoader());
            String missing=(String)bridge.getMethod("memorySnapshot",String.class).invoke(null,unloaded.getAbsolutePath());
            assertTrue(missing,missing.contains("MODULE_NOT_LOADED"));
            String value=(String)bridge.getMethod("memorySnapshot",String.class).invoke(null,loaded.getAbsolutePath());
            assertTrue(value,value.contains("PID: "+Process.myPid()));
            assertTrue(value,value.contains("ELF64_HEADER_READ_OK"));
            assertTrue(value,value.contains("7f454c46"));
            assertTrue(value,value.matches("(?s).*Build ID: [0-9a-f]{8,128}\\n.*"));
            assertTrue(value,value.contains("ELF load segment"));
            assertTrue(value.length()<16000);
            assertTrue(((String)bridge.getMethod("memorySnapshot",String.class).invoke(null,unloaded.getAbsolutePath())).contains("MODULE_NOT_LOADED"));
            assertEquals(1,bridge.getMethod("initializationCount").invoke(null));
        } finally {loaded.delete();unloaded.delete();directory.delete();}
    }
    @Test public void gameMemoryCannotExecuteInUiProcess() throws Exception {
        try {GameActivityRuntime.memorySnapshot(context());fail("Game read allowed in UI");}
        catch(SecurityException expected){assertTrue(expected.getMessage().contains("dedicated host process"));}
    }
    @Test public void memoryProtocolRejectsOtherUidOperationRequestAndEnvelope() {
        Message request=Message.obtain(null,MemoryProtocol.SNAPSHOT);request.arg1=3;request.sendingUid=Process.myUid();
        request.replyTo=new Messenger(new Handler(Looper.getMainLooper()));assertTrue(MemoryProtocol.accepts(request));
        request.sendingUid=-1;assertFalse(MemoryProtocol.accepts(request));request.sendingUid=Process.myUid();
        request.what=8;assertFalse(MemoryProtocol.accepts(request));request.what=MemoryProtocol.SNAPSHOT;
        request.arg1=0;assertFalse(MemoryProtocol.accepts(request));request.arg1=3;
        request.replyTo=null;assertFalse(MemoryProtocol.accepts(request));
        Message reply=MemoryProtocol.response(3,"fixture");reply.sendingUid=Process.myUid();assertTrue(MemoryProtocol.matches(reply,3));
        assertFalse(MemoryProtocol.matches(reply,4));reply.sendingUid=-1;assertFalse(MemoryProtocol.matches(reply,3));
        reply.sendingUid=Process.myUid();reply.getData().putInt("pid",123);assertFalse(MemoryProtocol.matches(reply,3));
    }
    @Test public void actualMemoryIpcWithoutGameDoesNotPrepareOrLaunchIt() throws Exception {
        Context c=context();File stage=GameHostReport.stageFile(c);
        String before=GameHostReport.read(stage);CountDownLatch reply=new CountDownLatch(1);String[] value={null};
        MemoryClient client=MinecraftBootstrap.onMainThread(() -> {
            MemoryClient connection=new MemoryClient(c,text -> {value[0]=text;reply.countDown();});connection.start();return connection;
        });
        try {
            assertTrue("No memory IPC result",reply.await(12,TimeUnit.SECONDS));
            assertTrue(value[0],value[0].contains("No live prepared Activity session"));
            assertEquals(before,GameHostReport.read(stage));
        } finally {MinecraftBootstrap.onMainThread(() -> {client.cancel();return null;});}
    }
}
