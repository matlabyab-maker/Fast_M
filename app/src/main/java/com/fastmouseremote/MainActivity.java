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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
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
    private boolean showingMenu2 = false;
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
        showingMenu2 = false;
        showRemoteImage(false);
    }

    // The uploaded reference images are used as the actual screen backgrounds.
    // Transparent hit areas sit over the labels and do not add visible controls.
    private void showRemoteImage(boolean menu2) {
        showingMenu2 = menu2;
        FrameLayout canvas = new FrameLayout(this);
        canvas.setBackgroundColor(Color.rgb(173, 218, 232));
        ImageView background = new ImageView(this);
        background.setImageResource(menu2 ? R.drawable.remote_menu2 : R.drawable.remote_menu1);
        background.setScaleType(ImageView.ScaleType.FIT_XY);
        canvas.addView(background, new FrameLayout.LayoutParams(-1, -1));

        RemoteHitLayout hits = new RemoteHitLayout();
        canvas.addView(hits, new FrameLayout.LayoutParams(-1, -1));
        setContentView(canvas);
    }

    private class RemoteHitLayout extends ViewGroup {
        private final java.util.ArrayList<Hit> hitList = new java.util.ArrayList<>();
        RemoteHitLayout() {
            super(MainActivity.this);
            setClipChildren(true);
            if (!showingMenu2) {
                // Image 2: Point Zoom / Copy / Past; Menu 1 / Back; Page Up / Page Down;
                // Drag in the lower middle and Left Click at the lower right.
                addHit(0.00f,0.00f,0.162f,0.120f,()->send("ZOOM"));
                addHit(0.162f,0.00f,0.246f,0.120f,()->send("COPY"));
                addHit(0.246f,0.00f,0.326f,0.120f,()->send("PASTE"));
                addHit(0.00f,0.120f,0.184f,0.263f,()->showRemoteImage(true));
                addHit(0.184f,0.120f,0.326f,0.263f,()->send("BACK"));
                addHit(0.00f,0.263f,0.326f,0.625f,()->send("PAGE_UP"));
                addHit(0.00f,0.625f,0.326f,1.000f,()->send("PAGE_DOWN"));
                addHit(0.326f,0.263f,0.724f,1.000f,()->{});
                addHit(0.724f,0.263f,1.000f,1.000f,()->send("LEFT_CLICK"));
                addDragArea(0.326f,0.263f,0.724f,1.000f);
            } else {
                // Image 1: Page Up / Page Down, Menu 2, Left Click and Drag.
                addHit(0.00f,0.00f,0.195f,0.315f,()->send("PAGE_UP"));
                addHit(0.195f,0.00f,0.390f,0.315f,()->send("PAGE_DOWN"));
                addHit(0.00f,0.315f,0.390f,0.402f,()->showRemoteImage(false));
                addHit(0.00f,0.402f,0.390f,1.000f,()->send("LEFT_CLICK"));
                addDragArea(0.390f,0.402f,1.000f,1.000f);
            }
        }
        private void addHit(float l,float t,float r,float b,Runnable action) {
            View v = new View(MainActivity.this);
            v.setBackgroundColor(Color.TRANSPARENT);
            v.setOnClickListener(w -> action.run());
            addView(v);
            hitList.add(new Hit(v,l,t,r,b));
        }
        private void addDragArea(float l,float t,float r,float b) {
            View v = new View(MainActivity.this);
            v.setBackgroundColor(Color.TRANSPARENT);
            v.setOnTouchListener((view,e)->{
                try {
                    switch(e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            touchX=(int)e.getX(); touchY=(int)e.getY();
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if(connected) {
                                int x=(int)e.getX(), y=(int)e.getY();
                                int dx=x-touchX, dy=y-touchY; touchX=x; touchY=y;
                                if(dx!=0 || dy!=0) queueMove(dx,dy);
                            } else { touchX=(int)e.getX(); touchY=(int)e.getY(); }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            view.getParent().requestDisallowInterceptTouchEvent(false);
                            return true;
                        default: return true;
                    }
                } catch(RuntimeException ex) { return true; }
            });
            addView(v);
            hitList.add(new Hit(v,l,t,r,b));
        }
        @Override protected void onMeasure(int wSpec,int hSpec) {
            int w=MeasureSpec.getSize(wSpec), h=MeasureSpec.getSize(hSpec);
            setMeasuredDimension(w,h);
            for(Hit hitem:hitList) hitem.view.measure(MeasureSpec.makeMeasureSpec(Math.max(0,(int)(w*(hitem.r-hitem.l))),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,(int)(h*(hitem.b-hitem.t))),MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
            int w=r-l,h=b-t;
            for(Hit it:hitList) it.view.layout((int)(w*it.l),(int)(h*it.t),(int)(w*it.r),(int)(h*it.b));
        }
        private class Hit {
            View view; float l,t,r,b;
            Hit(View v,float l,float t,float r,float b){this.view=v;this.l=l;this.t=t;this.r=r;this.b=b;}
        }
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
