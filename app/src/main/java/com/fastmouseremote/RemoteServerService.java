package com.fastmouseremote;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class RemoteServerService extends Service {
    public static final int PORT = 47821;
    private volatile boolean running = false;
    private volatile ServerSocket server;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            startForeground(7, notification());
            new Thread(this::listen, "mouse-remote-server").start();
        }
        return START_STICKY;
    }

    private Notification notification() {
        String id = "mouse_remote_channel";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(id, "Fast Mouse Remote", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
            return new Notification.Builder(this, id).setContentTitle("Fast Mouse Remote")
                    .setContentText("در انتظار اتصال کنترلر محلی")
                    .setSmallIcon(android.R.drawable.ic_menu_compass).build();
        }
        return new Notification.Builder(this).setContentTitle("Fast Mouse Remote")
                .setContentText("در انتظار اتصال کنترلر محلی")
                .setSmallIcon(android.R.drawable.ic_menu_compass).build();
    }

    private void listen() {
        while (running) {
            try (ServerSocket listening = new ServerSocket()) {
                // Listen on all local interfaces so Wi-Fi and hotspot clients can reach this phone.
                listening.setReuseAddress(true);
                listening.bind(new java.net.InetSocketAddress("0.0.0.0", PORT));
                server = listening;
                while (running) {
                    Socket socket = listening.accept();
                    new Thread(() -> handle(socket), "mouse-remote-client").start();
                }
            } catch (Exception e) {
                if (running) try { Thread.sleep(800); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            } finally { server = null; }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);
            String line;
            while (running && (line = in.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 0 || p[0].isEmpty()) continue;
                if ("PING".equals(p[0])) {
                    out.println(MouseAccessibilityService.instance == null ? "PONG NO_ACCESSIBILITY" : "PONG OK");
                    continue;
                }
                MouseAccessibilityService a = MouseAccessibilityService.instance;
                if (a == null) { out.println("ERR NO_ACCESSIBILITY"); continue; }
                try {
                    switch (p[0]) {
                        case "MOVE":
                            if (p.length < 3) throw new IllegalArgumentException("MOVE requires dx dy");
                            a.moveCursor(Integer.parseInt(p[1]), Integer.parseInt(p[2])); out.println("OK MOVE"); break;
                        case "CLICK_LEFT": a.click(false); out.println("OK CLICK_LEFT"); break;
                        case "CLICK_RIGHT":
                            // Do not fake right-click with a long primary press: that can activate controls accidentally.
                            out.println("ERR RIGHT_CLICK_UNSUPPORTED"); break;
                        case "SCROLL":
                            if (p.length < 2) throw new IllegalArgumentException("SCROLL requires direction");
                            a.scroll(Integer.parseInt(p[1])); out.println("OK SCROLL"); break;
                        case "BACK": out.println(a.goBack() ? "OK BACK" : "ERR BACK_FAILED"); break;
                        case "HOME": out.println(a.goHome() ? "OK HOME" : "ERR HOME_FAILED"); break;
                        case "RECENTS": out.println(a.showRecents() ? "OK RECENTS" : "ERR RECENTS_FAILED"); break;
                        case "QUICK_SETTINGS": out.println(a.showQuickSettings() ? "OK QUICK_SETTINGS" : "ERR QUICK_SETTINGS_FAILED"); break;
                        case "NOTIFICATIONS": out.println(a.showNotifications() ? "OK NOTIFICATIONS" : "ERR NOTIFICATIONS_FAILED"); break;
                        case "DRAG_UP": a.swipe(-1); out.println("OK DRAG_UP"); break;
                        case "DRAG_DOWN": a.swipe(1); out.println("OK DRAG_DOWN"); break;
                        case "PAGE_UP": a.swipe(-1); out.println("OK PAGE_UP"); break;
                        case "PAGE_DOWN": a.swipe(1); out.println("OK PAGE_DOWN"); break;
                        case "TEXT":
                            if (p.length < 2) throw new IllegalArgumentException("TEXT requires base64 data");
                            a.typeText(new String(android.util.Base64.decode(p[1], android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8));
                            out.println("OK TEXT_QUEUED"); break;
                        case "KEY_BACKSPACE": a.backspace(); out.println("OK BACKSPACE_QUEUED"); break;
                        case "COPY": a.editAction(AccessibilityNodeInfo.ACTION_COPY); out.println("OK COPY_QUEUED"); break;
                        case "COPY_ALL": a.selectAllAndAction(AccessibilityNodeInfo.ACTION_COPY); out.println("OK COPY_ALL_QUEUED"); break;
                        case "CUT": a.editAction(AccessibilityNodeInfo.ACTION_CUT); out.println("OK CUT_QUEUED"); break;
                        case "PASTE": a.editAction(AccessibilityNodeInfo.ACTION_PASTE); out.println("OK PASTE_QUEUED"); break;
                        case "UNDO": case "REDO":
                            // Android Accessibility exposes no portable standard undo/redo action.
                            out.println("ERR " + p[0] + "_UNSUPPORTED"); break;
                        case "ZOOM": out.println("ERR ZOOM_UNSUPPORTED"); break;
                        default: out.println("ERR UNKNOWN_COMMAND"); break;
                    }
                } catch (Exception e) {
                    out.println("ERR BAD_COMMAND");
                }
            }
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        running = false;
        ServerSocket s = server;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
