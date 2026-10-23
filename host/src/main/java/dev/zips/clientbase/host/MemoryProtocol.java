package dev.zips.clientbase.host;

import android.os.Bundle;
import android.os.Message;
import android.os.Process;

/** Fixed read-only operation. No addresses, offsets, write payloads or arbitrary engine paths in IPC. */
final class MemoryProtocol {
    static final int SNAPSHOT = 2;
    static boolean accepts(Message request) {
        return request.what == SNAPSHOT && request.sendingUid == Process.myUid() && request.replyTo != null && request.arg1 > 0;
    }
    static Message response(int request, String report) {
        Message reply=Message.obtain(null,SNAPSHOT); reply.arg1=request;reply.arg2=Process.myPid();
        Bundle data=new Bundle();data.putInt("pid",Process.myPid());data.putInt("uid",Process.myUid());
        data.putString("report",report.length()>16000?report.substring(0,16000):report);reply.setData(data);return reply;
    }
    static boolean matches(Message reply, int request) {
        Bundle data=reply.getData();
        return reply.sendingUid==Process.myUid() && reply.what==SNAPSHOT && reply.arg1==request && data.getInt("uid",-1)==Process.myUid()
                && data.getInt("pid",0)>0 && data.getInt("pid")==reply.arg2;
    }
}
