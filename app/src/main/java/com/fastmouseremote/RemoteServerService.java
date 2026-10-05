package com.fastmouseremote;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

public class RemoteServerService extends Service {
    public static final int PORT=47821;
    private volatile boolean running=false;
    private ServerSocket server;
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!running){running=true;startForeground(7,notification());new Thread(this::listen,"mouse-remote-server").start();}
        return START_STICKY;
    }
    private Notification notification(){String id="mouse_remote_channel";if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(id,"Fast Mouse Remote",NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}if(Build.VERSION.SDK_INT>=26)return new Notification.Builder(this,id).setContentTitle("Fast Mouse Remote").setContentText("در انتظار اتصال کنترلر محلی").setSmallIcon(android.R.drawable.ic_menu_compass).build();return new Notification.Builder(this).setContentTitle("Fast Mouse Remote").setContentText("در انتظار اتصال کنترلر محلی").setSmallIcon(android.R.drawable.ic_menu_compass).build();}
    private void listen(){while(running){try{server=new ServerSocket(PORT);while(running){Socket s=server.accept();new Thread(()->handle(s),"mouse-remote-client").start();}}catch(Exception e){if(running)try{Thread.sleep(800);}catch(InterruptedException ignored){}}finally{if(server!=null)try{server.close();}catch(Exception ignored){}server=null;}}}
    private void handle(Socket socket){try(Socket s=socket;BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()))){s.setSoTimeout(120000);String line;while(running&&(line=in.readLine())!=null){String[] p=line.trim().split("\\s+");if(p.length==0)continue;MouseAccessibilityService a=MouseAccessibilityService.instance;if(a==null)continue;try{switch(p[0]){case "MOVE":if(p.length>=3)a.moveCursor(Integer.parseInt(p[1]),Integer.parseInt(p[2]));break;case "CLICK_LEFT":a.click(false);break;case "CLICK_RIGHT":a.click(true);break;case "SCROLL":if(p.length>=2)a.scroll(Integer.parseInt(p[1]));break;case "BACK":a.goBack();break;case "DRAG_UP":a.swipe(-1);break;case "DRAG_DOWN":a.swipe(1);break;}}catch(Exception ignored){}}}catch(Exception ignored){}}
    @Override public void onDestroy(){running=false;try{if(server!=null)server.close();}catch(Exception ignored){}stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
