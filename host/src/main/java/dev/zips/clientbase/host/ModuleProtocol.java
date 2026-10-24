package dev.zips.clientbase.host;

import android.os.Bundle;
import android.os.Message;
import android.os.Process;

/** Dedicated module-control IPC (opcodes 6..8). Opcode 2 memory inspection remains strictly read-only. */
final class ModuleProtocol {
    static final int QUERY = 6;
    static final int APPLY = 7;
    static final int DISARM = 8;

    static boolean operation(int what) {
        return what >= QUERY && what <= DISARM;
    }

    static boolean accepts(Message request) {
        return operation(request.what)
                && request.sendingUid == Process.myUid()
                && request.replyTo != null
                && request.arg1 > 0;
    }

    static Message request(int operation, int requestId,
                           boolean fly, boolean aura,
                           boolean attackMobs, boolean multiTarget, boolean checkHurtTime) {
        Message msg = Message.obtain(null, operation);
        msg.arg1 = requestId;
        Bundle data = new Bundle();
        data.putBoolean("fly", fly);
        data.putBoolean("aura", aura);
        data.putBoolean("mobs", attackMobs);
        data.putBoolean("multi", multiTarget);
        data.putBoolean("hurt", checkHurtTime);
        msg.setData(data);
        return msg;
    }

    static Message response(int operation, int requestId, String payload) {
        Message reply = Message.obtain(null, operation);
        reply.arg1 = requestId;
        reply.arg2 = Process.myPid();
        Bundle data = new Bundle();
        data.putInt("pid", Process.myPid());
        data.putInt("uid", Process.myUid());
        String bounded = payload.length() > 12000 ? payload.substring(0, 12000) : payload;
        data.putString("payload", bounded);
        reply.setData(data);
        return reply;
    }

    static boolean matches(Message reply, int operation, int requestId) {
        Bundle data = reply.getData();
        return reply.sendingUid == Process.myUid()
                && reply.what == operation
                && reply.arg1 == requestId
                && data.getInt("uid", -1) == Process.myUid()
                && data.getInt("pid", 0) > 0
                && data.getInt("pid") == reply.arg2;
    }

    private ModuleProtocol() {}
}
