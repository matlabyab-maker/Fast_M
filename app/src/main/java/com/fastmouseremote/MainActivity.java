package com.fastmouseremote;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import java.net.Socket;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private LinearLayout root;
    private EditText ipInput;
    private TextView status;
    private volatile PrintWriter writer;
    private volatile Socket clientSocket;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private volatile boolean connected = false;
    private int touchX, touchY;
    // Coalesce high-frequency touch movement so a fast finger cannot build an unbounded queue.
    private final Object moveLock = new Object();
    private int pendingMoveX, pendingMoveY;
    private boolean moveWorkerRunning = false;
    private static final int PORT = 47821;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        showHome();
    }

    private void setup(String title) {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(245,247,251));
        scroll.addView(root);
        setContentView(scroll);
        TextView heading = new TextView(this); heading.setText(title); heading.setTextSize(25); heading.setTypeface(null, Typeface.BOLD); heading.setTextColor(Color.rgb(20,38,68));
        root.addView(heading, params(-1,-2,0,0,0,8));
    }
    private TextView text(String s, int size) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(Color.rgb(40,53,72)); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(0,dp(5),0,dp(5)); return t; }
    private LinearLayout.LayoutParams params(int w,int h,int l,int t,int r,int b) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h)); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private int dp(float x) { return (int)(x*getResources().getDisplayMetrics().density+0.5f); }
    private GradientDrawable bg(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private Button button(String label, Runnable action) { Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(Color.WHITE); b.setBackground(bg(Color.rgb(21,101,192),14)); b.setPadding(dp(12),dp(8),dp(12),dp(8)); b.setOnClickListener(v->action.run()); root.addView(b,params(-1,-2,0,7,0,5)); return b; }
    private void showHome() {
        setup("Fast M");
        root.addView(text("موس مستقل اندروید با کنترل از گوشی دوم\nاتصال محلی Wi-Fi / هات‌اسپات — بدون اینترنت",16),params(-1,-2,0,0,0,18));
        button("این گوشی: دستگاه هدف (موس)", this::showTarget);
        button("این گوشی: کنترلر / ریموت", this::showController);
        root.addView(text("برنامه را روی هر دو گوشی نصب کن. گوشی‌ها باید به یک شبکهٔ Wi-Fi یا هات‌اسپات وصل باشند.",14),params(-1,-2,0,16,0,0));
    }
    private void showTarget() {
        setup("دستگاه هدف");
        root.addView(text("۱) مجوز دسترس‌پذیری را فعال کن تا برنامه بتواند لمس و حرکت موس را روی صفحه اجرا کند.",15),params(-1,-2,0,0,0,8));
        button("باز کردن تنظیمات Accessibility", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(text("۲) برای نمایش نشانگر شناور، مجوز نمایش روی برنامه‌های دیگر را فعال کن.",15),params(-1,-2,0,10,0,4));
        button("مجوز نمایش روی برنامه‌ها", () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)));
        String ip=getLocalIp();
        root.addView(text("آدرس این گوشی: "+ip+"\nدرگاه: "+PORT+"\nبرای اتصال، فقط IP را در گوشی ریموت وارد کن.",18),params(-1,-2,0,18,0,12));
        button("شروع پذیرش اتصال ریموت", () -> {
            Intent i=new Intent(this,RemoteServerService.class);
            try { startService(i); Toast.makeText(this,"سرویس موس شروع شد؛ صفحه را باز نگه دار تا وضعیت را بررسی کنی.",Toast.LENGTH_LONG).show(); }
            catch(Exception e){ Toast.makeText(this,"شروع سرویس ناموفق بود: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
        });
        button("توقف سرویس موس", () -> { stopService(new Intent(this,RemoteServerService.class)); Toast.makeText(this,"سرویس متوقف شد",Toast.LENGTH_SHORT).show(); });
        button("بازگشت",this::showHome);
    }
    private String getLocalIp() {
        try { WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE); int ip=wm.getConnectionInfo().getIpAddress(); String s=Formatter.formatIpAddress(ip); if(!"0.0.0.0".equals(s)) return s; } catch(Exception ignored) {}
        return "IP را از جزئیات شبکهٔ Wi-Fi بررسی کن";
    }
    private void showController() {
        disconnect();
        setContentView(new LinearLayout(this));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(12), dp(14), dp(14));
        page.setBackgroundColor(Color.rgb(245,247,251));
        setContentView(page);

        TextView heading = text("Fast M — ریموت", 23);
        heading.setTypeface(null, Typeface.BOLD);
        page.addView(heading, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout connection = new LinearLayout(this);
        connection.setOrientation(LinearLayout.VERTICAL);
        connection.setPadding(dp(10), dp(4), dp(10), dp(4));
        connection.setBackground(bg(Color.WHITE, 12));
        ipInput = new EditText(this); ipInput.setSingleLine(true); ipInput.setHint("IP گوشی موس");
        ipInput.setTextSize(15); ipInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        connection.addView(ipInput, new LinearLayout.LayoutParams(-1, dp(45)));
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button connect = new Button(this); connect.setText("اتصال"); connect.setAllCaps(false); connect.setOnClickListener(v -> connectToTarget());
        actions.addView(connect, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button cut = new Button(this); cut.setText("قطع"); cut.setAllCaps(false); cut.setOnClickListener(v -> disconnect());
        actions.addView(cut, new LinearLayout.LayoutParams(0, dp(46), 1));
        connection.addView(actions);
        status = text("وضعیت: قطع", 13); connection.addView(status);
        page.addView(connection, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.HORIZONTAL); controls.setGravity(Gravity.CENTER);
        Button back = new Button(this); back.setText("Back"); back.setAllCaps(false); back.setTextSize(14);
        back.setOnClickListener(v -> send("BACK"));
        controls.addView(back, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button dragUp = new Button(this); dragUp.setText("Drag بالا"); dragUp.setAllCaps(false); dragUp.setTextSize(14);
        dragUp.setOnClickListener(v -> send("DRAG_UP"));
        controls.addView(dragUp, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button dragDown = new Button(this); dragDown.setText("Drag پایین"); dragDown.setAllCaps(false); dragDown.setTextSize(14);
        dragDown.setOnClickListener(v -> send("DRAG_DOWN"));
        controls.addView(dragDown, new LinearLayout.LayoutParams(0, dp(54), 1));
        page.addView(controls, new LinearLayout.LayoutParams(-1, dp(60)));

        TextView pad = text("میدان لمس و درگ\nانگشت را برای حرکت نشانگر بکش", 18);
        pad.setGravity(Gravity.CENTER); pad.setTextColor(Color.rgb(32,63,100));
        pad.setBackground(bg(Color.rgb(224,233,245), 18));
        LinearLayout.LayoutParams padParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        padParams.setMargins(0, dp(6), 0, 0);
        page.addView(pad, padParams);
        pad.setOnTouchListener((v,e)->{
            // Consume every touch event: never let a touch escape into a parent view.
            try {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchX = (int)e.getX(); touchY = (int)e.getY();
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (connected) {
                            int x=(int)e.getX(), y=(int)e.getY();
                            int dx=x-touchX, dy=y-touchY;
                            touchX=x; touchY=y;
                            if(dx!=0||dy!=0) queueMove(dx,dy);
                        } else {
                            touchX=(int)e.getX(); touchY=(int)e.getY();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    default:
                        return true;
                }
            } catch (RuntimeException ex) {
                // Keep a touch-handler problem from crashing the whole controller screen.
                return true;
            }
        });
    }
    private void connectToTarget() {
        String ip=ipInput.getText().toString().trim();
        if(ip.isEmpty()){Toast.makeText(this,"آدرس IP گوشی موس را وارد کن",Toast.LENGTH_SHORT).show();return;}
        status.setText("وضعیت: در حال اتصال…");
        new Thread(()->{try{Socket s=new Socket();s.connect(new java.net.InetSocketAddress(ip,PORT),4000);PrintWriter w=new PrintWriter(s.getOutputStream(),true);clientSocket=s;writer=w;connected=true;runOnUiThread(()->status.setText("وضعیت: متصل به "+ip));}catch(Exception e){connected=false;runOnUiThread(()->status.setText("وضعیت: اتصال ناموفق — "+e.getMessage()));}}).start();
    }
    private void queueMove(int dx, int dy) {
        synchronized (moveLock) {
            pendingMoveX += dx;
            pendingMoveY += dy;
            if (moveWorkerRunning) return;
            moveWorkerRunning = true;
        }
        try {
            sender.execute(() -> {
                while (true) {
                    int mx, my;
                    synchronized (moveLock) {
                        mx = pendingMoveX;
                        my = pendingMoveY;
                        pendingMoveX = 0;
                        pendingMoveY = 0;
                        if (mx == 0 && my == 0) {
                            moveWorkerRunning = false;
                            return;
                        }
                    }
                    PrintWriter w = writer;
                    if (!connected || w == null) continue;
                    try {
                        synchronized (w) {
                            w.println("MOVE " + mx + " " + my);
                            if (w.checkError()) connected = false;
                        }
                    } catch (RuntimeException ignored) { }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            synchronized (moveLock) { moveWorkerRunning = false; }
        }
    }

    private void send(String command) {
        if (!connected || command == null) return;
        try {
            sender.execute(() -> {
                PrintWriter w = writer;
                if (!connected || w == null) return;
                try {
                    synchronized (w) {
                        w.println(command);
                        if (w.checkError()) {
                            connected = false;
                            runOnUiThread(() -> { if (status != null) status.setText("وضعیت: خطای ارسال؛ دوباره متصل شو"); });
                        }
                    }
                } catch (RuntimeException ex) {
                    runOnUiThread(() -> { if (status != null) status.setText("وضعیت: خطای ارتباط"); });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private void disconnect(){connected=false;try{if(writer!=null)writer.close();if(clientSocket!=null)clientSocket.close();}catch(Exception ignored){}writer=null;clientSocket=null;if(status!=null)status.setText("وضعیت: قطع");}
    @Override protected void onDestroy(){disconnect();sender.shutdownNow();super.onDestroy();}
}
