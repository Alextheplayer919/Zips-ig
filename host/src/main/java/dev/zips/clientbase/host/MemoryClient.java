package dev.zips.clientbase.host;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** One explicit request, no polling or automatic initialization, cancel on UI stop. */
final class MemoryClient {
    private static final AtomicInteger IDS=new AtomicInteger();
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final int request=IDS.updateAndGet(i -> i==Integer.MAX_VALUE?1:i+1);
    private Consumer<String> callback;
    private boolean bound,done;
    MemoryClient(Context context,Consumer<String> callback){this.context=context.getApplicationContext();this.callback=callback;}
    private final Runnable timeout=() -> finish("Memory request timed out. No writes or game restart attempted.");
    private final Messenger receiver=new Messenger(new Handler(Looper.getMainLooper(),message -> {
        if(!done && MemoryProtocol.matches(message,request) && message.getData().getInt("pid")!=Process.myPid())
            finish(message.getData().getString("report","Empty memory report"));
        return true;
    }));
    private boolean sendOverBinder(IBinder binder){
        if(binder==null || !binder.isBinderAlive())return false;
        try {
            Message message=Message.obtain(null,MemoryProtocol.SNAPSHOT);
            message.arg1=request;
            message.replyTo=receiver;
            new Messenger(binder).send(message);
            return true;
        }catch(android.os.RemoteException error){return false;}
    }
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(done)return;
            if(!sendOverBinder(binder))finish("Memory IPC unavailable: game process unreachable");
        }
        @Override public void onServiceDisconnected(ComponentName name){finish("Game process disconnected. No restart attempted.");}
        @Override public void onBindingDied(ComponentName name){finish("Memory connection died. Refresh manually.");}
        @Override public void onNullBinding(ComponentName name){finish("Memory binding unavailable.");}
    };
    void start(){
        IBinder liveBinder=GameHostLiveBinder.get();
        if(liveBinder!=null && sendOverBinder(liveBinder)){main.postDelayed(timeout,8000);return;}
        try {
            bound=context.bindService(new Intent(context,GameHostService.class),connection,Context.BIND_AUTO_CREATE);
            if(!bound)finish("Launch Bedrock from Client Base first (cold :game_host service bind is currently blocked by Android; Launch wakes :game_host automatically).");
            else main.postDelayed(timeout,8000);
        }catch(Exception error){finish("Memory connection unavailable: "+error.getMessage());}
    }
    private void finish(String result){if(done)return;Consumer<String> target=callback;cancel();if(target!=null)target.accept(result);}
    void cancel(){done=true;callback=null;main.removeCallbacks(timeout);if(bound){bound=false;try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}}}
}
