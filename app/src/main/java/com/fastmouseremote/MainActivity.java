package com.fastmouseremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import java.net.Socket;
import java.io.PrintWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

public class MainActivity extends Activity {
    private LinearLayout root;
    private EditText ipInput;
    private TextView status;
    private volatile PrintWriter writer;
    private volatile Socket clientSocket;
    private volatile BufferedReader reader;
    private volatile long lastPongAt = 0L;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean heartbeatStarted = false;
    private final Object reconnectLock = new Object();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private volatile boolean connected = false;
    private int touchX, touchY;
    private boolean showingMenu2 = false;
    private volatile String targetAddress = null;
    private volatile float sensitivity = 1.0f;
    private volatile float pointerSpeed = 2.0f;
    // Coalesce high-frequency touch movement so a fast finger cannot build an unbounded queue.
    private final Object moveLock = new Object();
    private int pendingMoveX, pendingMoveY;
    private boolean moveWorkerRunning = false;
    private static final int PORT = 47821;
    private static final String PREFS_NAME = "FastMSettings";
    private static final String KEY_TARGET_IP = "last_target_ip";

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
        root.addView(text("آدرس این گوشی: "+ip+"\nدرگاه TCP: "+PORT+"\nگوشی ریموت باید همین IP و درگاه را استفاده کند.",18),params(-1,-2,0,18,0,12));
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
        final EditText ip = new EditText(this);
        String savedIp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_TARGET_IP, "");
        ip.setText(savedIp);
        ip.setSelection(ip.getText().length());
        ip.setSingleLine(true);
        ip.setHint("IP گوشی هدف، مثلاً 192.168.1.5");
        new AlertDialog.Builder(this)
            .setTitle("اتصال به گوشی موس")
            .setMessage("ابتدا در گوشی مقصد «شروع پذیرش اتصال ریموت» را بزن. هر دو گوشی باید روی یک Wi-Fi یا هات‌اسپات باشند.")
            .setView(ip)
            .setNegativeButton("لغو", (d,w) -> showHome())
            .setPositiveButton("اتصال", (d,w) -> {
                String address = ip.getText().toString().trim();
                if (!address.isEmpty()) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_TARGET_IP, address).apply();
                    targetAddress = address; // preserve target for automatic retries, even after a failed first attempt
                }
                connectToTarget(address, () -> {
                    showingMenu2 = false;
                    showRemoteImage(false);
                    Toast.makeText(this, "اتصال برقرار شد", Toast.LENGTH_SHORT).show();
                });
            })
            .setCancelable(false)
            .show();
    }

    private void connectToTarget(String address, Runnable onConnected) {
        if (address == null || address.trim().isEmpty()) {
            Toast.makeText(this, "IP گوشی هدف را وارد کن", Toast.LENGTH_LONG).show();
            return;
        }
        targetAddress = address.trim();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_TARGET_IP, targetAddress).apply();
        Toast.makeText(this, "در حال اتصال…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.connect(new java.net.InetSocketAddress(address.trim(), PORT), 5000);
                PrintWriter w = new PrintWriter(s.getOutputStream(), true);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                clientSocket = s;
                writer = w;
                reader = r;
                targetAddress = address.trim();
                connected = true;
                lastPongAt = System.currentTimeMillis();
                startReader(r, s);
                startHeartbeat();
                runOnUiThread(onConnected);
            } catch (Exception e) {
                connected = false;
                // Keep the IP and continue automatic reconnect attempts.
                startHeartbeat();
                runOnUiThread(() -> Toast.makeText(this,
                    "فعلاً اتصال برقرار نشد؛ IP ذخیره شد و اتصال خودکار ادامه می‌یابد.",
                    Toast.LENGTH_LONG).show());
            }
        }, "fast-m-connect").start();
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
        // Two slim vertical controls in the otherwise empty right-hand area.
        addVerticalControl(canvas, "حساسیت", true, sensitivity, 0.925f);
        addVerticalControl(canvas, "سرعت", false, pointerSpeed, 0.965f);
        setContentView(canvas);
    }



    // Menu 3: large Fast Keyboard-style panel on the left and mouse controls on the right.
    private void showKeyboardRemote() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.rgb(173,218,232));

        // The user's supplied keyboard image is the actual visual keyboard.
        // FIT_XY keeps every visible section in the same relative arrangement.
        ImageView keyboardImage = new ImageView(this);
        keyboardImage.setImageResource(R.drawable.remote_keyboard_scene3);
        keyboardImage.setScaleType(ImageView.ScaleType.FIT_XY);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(0,-1,Gravity.LEFT);
        klp.width = (int)(getResources().getDisplayMetrics().widthPixels*0.74f);
        screen.addView(keyboardImage, klp);

        // Transparent touch layer uses the exact boundaries of the supplied image.
        Scene3KeyboardHits keyboardHits = new Scene3KeyboardHits();
        screen.addView(keyboardHits, klp);

        // Right side: exactly two menu buttons + Left Click; everything below is Drag.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0,0,0,0);
        panel.setBackgroundColor(Color.rgb(173,218,232));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1,-1,Gravity.RIGHT);
        plp.leftMargin = klp.width;
        screen.addView(panel,plp);

        LinearLayout menus = new LinearLayout(this);
        menus.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(menus,new LinearLayout.LayoutParams(-1,0,1.0f));

        Button menu1 = keyboardKey("Menu 1",15,Color.rgb(255,190,70));
        Button menu2 = keyboardKey("Menu 2",15,Color.rgb(255,190,70));
        menus.addView(menu1,new LinearLayout.LayoutParams(0,-1,1f));
        menus.addView(menu2,new LinearLayout.LayoutParams(0,-1,1f));
        menu1.setOnClickListener(v->{ flashScene3Button(menu1); showRemoteImage(false); });
        menu2.setOnClickListener(v->{ flashScene3Button(menu2); showRemoteImage(true); });

        Button click = keyboardKey("Left Click",16,Color.rgb(239,143,218));
        panel.addView(click,new LinearLayout.LayoutParams(-1,0,1.0f));
        click.setOnClickListener(v->{ flashScene3Button(click); send("CLICK_LEFT"); });

        TextView touch = new TextView(this);
        touch.setText("Touch / Drag");
        touch.setTextSize(15);
        touch.setTextColor(Color.rgb(76,53,74));
        touch.setGravity(Gravity.CENTER);
        touch.setBackground(bg(Color.rgb(173,218,232),0));
        panel.addView(touch,new LinearLayout.LayoutParams(-1,0,8.0f));

        final int[] last = {0,0};
        touch.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                last[0]=(int)e.getX(); last[1]=(int)e.getY();
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE && connected){
                int x=(int)e.getX(), y=(int)e.getY();
                int dx=x-last[0], dy=y-last[1];
                last[0]=x; last[1]=y;
                if(dx!=0 || dy!=0)
                    queueMove(Math.round(dx*sensitivity*pointerSpeed),
                              Math.round(dy*sensitivity*pointerSpeed));
                return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP ||
               e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            return true;
        });

        setContentView(screen);
    }

    private void flashScene3Button(final View v) {
        final android.graphics.drawable.Drawable old = v.getBackground();
        android.graphics.drawable.GradientDrawable glow =
                new android.graphics.drawable.GradientDrawable();
        glow.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        glow.setColor(Color.argb(150,255,225,0));
        glow.setStroke(dp(2),Color.rgb(255,190,0));
        glow.setCornerRadius(dp(5));
        v.setBackground(glow);
        v.postDelayed(()->{ if(v.getParent()!=null) v.setBackground(old); },160);
    }

    // Transparent hit regions mapped from the 1280x552 source image.
    // Coordinates are normalized, so they remain aligned when the keyboard panel scales.
    private class Scene3KeyboardHits extends ViewGroup {
        private final java.util.ArrayList<Scene3Hit> hits = new java.util.ArrayList<>();

        Scene3KeyboardHits() {
            super(MainActivity.this);
            setClipChildren(true);

            // Top row: 10 controls.
            add(0,0,127,85,()->send("PASTE"));
            add(127,0,255,85,()->send("COPY_ALL"));
            add(255,0,383,85,()->send("COPY_SCREEN"));
            add(383,0,511,85,()->send("CUT"));
            add(511,0,639,85,()->send("UNDO"));
            add(639,0,767,85,()->send("REDO"));
            add(767,0,895,85,()->send("HISTORY"));
            add(895,0,1023,85,()->showRemoteImage(false));
            add(1023,0,1151,85,()->showRemoteImage(true));
            add(1151,0,1280,85,()->send("RESIZE"));

            // Second row: 8 Persian word/phrase keys.
            String[] row2={"دکمه","و","در","را","همان","که","ارسال","فایل"};
            int[] x2={0,159,318,478,638,798,958,1118,1280};
            for(int i=0;i<8;i++){ final String k=row2[i]; add(x2[i],85,x2[i+1],153,()->sendText(k)); }

            // Number/symbol row: 10 numbers + Backspace.
            String[] nums={"۱","۲","۳","۴","۵","۶","۷","۸","۹","۰"};
            int[] xn={0,111,222,333,444,555,667,779,891,1003,1115,1280};
            for(int i=0;i<10;i++){ final String k=nums[i]; add(xn[i],153,xn[i+1],231,()->sendText(k)); }
            add(1115,153,1280,231,()->send("KEY_BACKSPACE"));

            // Arabic row 1 (the Enter key spans both Arabic rows).
            String[] ar1={"ض","ص","ث","ق","ف","غ","ع","ه","خ","ح","ج"};
            int[] xa={0,104,208,312,417,522,627,732,837,942,1047,1152,1280};
            for(int i=0;i<11;i++){ final String k=ar1[i]; add(xa[i],231,xa[i+1],310,()->sendText(k)); }
            add(1152,231,1280,389,()->sendText("\n"));

            // Arabic row 2.
            String[] ar2={"ش","س","ی","ب","ل","ا","ت","ن","م","ک","گ"};
            for(int i=0;i<11;i++){ final String k=ar2[i]; add(xa[i],310,xa[i+1],389,()->sendText(k)); }

            // Arabic row 3.
            String[] ar3={"Caps","ظ","ط","ژ","ز","ر","ذ","د","پ","و","چ","؟"};
            int[] xb={0,125,229,333,438,543,648,753,858,963,1068,1173,1280};
            for(int i=0;i<12;i++){
                final String k=ar3[i];
                if("Caps".equals(k)) add(xb[i],389,xb[i+1],467,()->send("CAPS"));
                else add(xb[i],389,xb[i+1],467,()->sendText(k));
            }

            // Bottom row.
            add(0,467,95,552,()->send("EMOJI"));
            add(95,467,229,552,()->send("SYMBOLS"));
            add(229,467,334,552,()->send("LANG_FA"));
            add(334,467,609,552,()->sendText(" "));
            add(609,467,693,552,()->sendText("،"));
            add(693,467,777,552,()->sendText("."));
            add(777,467,861,552,()->send("DIAMOND"));
            add(861,467,972,552,()->send("MOVE -18 0"));
            add(972,467,1084,552,()->send("MOVE 18 0"));
            add(1084,467,1180,552,()->send("MOVE 0 -18"));
            add(1180,467,1280,552,()->send("MOVE 0 18"));
        }

        private void add(int l,int t,int r,int b,final Runnable action){
            View v=new View(MainActivity.this);
            v.setBackgroundColor(Color.TRANSPARENT);
            v.setOnClickListener(w->{ flashScene3Button(w); action.run(); });
            addView(v);
            hits.add(new Scene3Hit(v,l/1280f,t/552f,r/1280f,b/552f));
        }

        @Override protected void onMeasure(int ws,int hs){
            int w=MeasureSpec.getSize(ws), h=MeasureSpec.getSize(hs);
            setMeasuredDimension(w,h);
            for(Scene3Hit q:hits){
                q.view.measure(
                    MeasureSpec.makeMeasureSpec(Math.max(1,(int)(w*(q.r-q.l))),MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(1,(int)(h*(q.b-q.t))),MeasureSpec.EXACTLY));
            }
        }
        @Override protected void onLayout(boolean c,int l,int t,int r,int b){
            int w=r-l,h=b-t;
            for(Scene3Hit q:hits)
                q.view.layout((int)(w*q.l),(int)(h*q.t),(int)(w*q.r),(int)(h*q.b));
        }
        private class Scene3Hit{
            View view; float l,t,r,b;
            Scene3Hit(View v,float l,float t,float r,float b){this.view=v;this.l=l;this.t=t;this.r=r;this.b=b;}
        }
    }

    private void sendText(String value) {
        if(value==null || value.isEmpty()) return;
        // Text commands are sent to the target's focused accessibility node.
        String encoded=android.util.Base64.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);
        send("TEXT "+encoded);
    }

    private void addVerticalControl(FrameLayout canvas, String label, boolean isSensitivity,
                                    float initialValue, float xFraction) {
        VerticalControl control = new VerticalControl(label, isSensitivity, initialValue);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(24), dp(150),
                Gravity.TOP | Gravity.RIGHT);
        lp.topMargin = 0;
        lp.rightMargin = dp(isSensitivity ? 30 : 2); // consecutive slim rails, aligned to the top edge
        canvas.addView(control, lp);
    }

    private class VerticalControl extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private final boolean isSensitivity;
        private float value;
        private float downY;
        private final int minValue = 1, maxValue = 5;

        VerticalControl(String label, boolean isSensitivity, float initialValue) {
            super(MainActivity.this);
            this.label = label;
            this.isSensitivity = isSensitivity;
            this.value = Math.max(minValue, Math.min(maxValue, initialValue));
            setContentDescription(label);
            setBackgroundColor(Color.argb(35, 0, 0, 0));
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float cx = getWidth()/2f;
            float top = dp(28), bottom = getHeight()-dp(10);
            paint.setStrokeWidth(dp(3));
            paint.setColor(Color.rgb(70,70,70));
            c.drawLine(cx, top, cx, bottom, paint);
            float fraction = (value-minValue)/(float)(maxValue-minValue);
            float thumbY = bottom - fraction*(bottom-top);
            paint.setColor(isSensitivity ? Color.rgb(220,40,140) : Color.rgb(20,110,220));
            c.drawCircle(cx, thumbY, dp(7), paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(10));
            paint.setColor(Color.rgb(35,35,35));
            c.drawText(isSensitivity ? "حس" : "سر", cx, dp(12), paint);
            c.drawText(String.valueOf(Math.round(value)), cx, getHeight()-dp(1), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
                downY=e.getY(); updateValue(e.getY()); getParent().requestDisallowInterceptTouchEvent(true); return true;
            }
            if (e.getActionMasked()==MotionEvent.ACTION_MOVE) { updateValue(e.getY()); return true; }
            if (e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL) {
                updateValue(e.getY()); getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true;
            }
            return true;
        }
        private void updateValue(float y) {
            float top=dp(28), bottom=getHeight()-dp(10);
            float f=1f-(Math.max(top,Math.min(bottom,y))-top)/(bottom-top);
            value=minValue+f*(maxValue-minValue);
            if(isSensitivity) sensitivity=0.5f+((value-1f)*0.375f); // 0.5x to 2.0x
            else pointerSpeed=0.5f+((value-1f)*0.875f); // 0.5x to 4.0x
            invalidate();
        }
        @Override public boolean performClick() { super.performClick(); return true; }
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
                addHit(0.00f,0.263f,0.326f,0.625f,()->send("SCROLL -1"));
                addHit(0.00f,0.625f,0.326f,1.000f,()->send("SCROLL 1"));
                addHit(0.724f,0.263f,1.000f,1.000f,()->send("CLICK_LEFT"));
                addDragArea(0.326f,0.263f,0.724f,1.000f);
            } else {
                // Menu 2 image coordinates are aligned to the visible button boundaries.
                // Top row: Menu 2 / Back; second row: Page Up / Page Down.
                addHit(0.00f,0.00f,0.195f,0.150f,()->showRemoteImage(false));
                addHit(0.195f,0.00f,0.390f,0.150f,()->send("BACK"));
                addHit(0.00f,0.150f,0.195f,0.390f,()->send("SCROLL -1"));
                addHit(0.195f,0.150f,0.390f,0.390f,()->send("SCROLL 1"));
                // Left Click and Drag are separated by the visible vertical divider.
                addHit(0.00f,0.390f,0.390f,1.000f,()->send("CLICK_LEFT"));
                addDragArea(0.390f,0.390f,1.000f,1.000f);
                // Keep the third-scene shortcut in a small, otherwise-empty top-right area.
                addHit(0.820f,0.000f,0.985f,0.140f,()->showKeyboardRemote());
            }
        }
        private void flashTap(View v) {
            final android.graphics.drawable.Drawable old = v.getBackground();
            android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable();
            glow.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            glow.setColor(Color.argb(150, 255, 225, 0));
            glow.setStroke(dp(2), Color.rgb(255, 190, 0));
            v.setBackground(glow);
            v.postDelayed(() -> {
                if (v.getParent() != null) v.setBackground(old);
            }, 160);
        }

        private void addHit(float l,float t,float r,float b,Runnable action) {
            View v = new View(MainActivity.this);
            v.setBackgroundColor(Color.TRANSPARENT);
            v.setOnClickListener(w -> {
                flashTap(w);
                action.run();
            });
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
                                if(dx!=0 || dy!=0) {
                                    float multiplier = sensitivity * pointerSpeed;
                                    queueMove(Math.round(dx * multiplier), Math.round(dy * multiplier));
                                }
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
                    sendWithReconnect("MOVE " + mx + " " + my);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            synchronized (moveLock) { moveWorkerRunning = false; }
        }
    }

    private void startReader(BufferedReader r, Socket socket) {
        new Thread(() -> {
            try {
                String line;
                while (connected && !socket.isClosed() && (line = r.readLine()) != null) {
                    if (line.startsWith("PONG")) {
                        lastPongAt = System.currentTimeMillis();
                        if (line.contains("NO_ACCESSIBILITY")) {
                            runOnUiThread(() -> Toast.makeText(this,
                                "سرویس دسترس‌پذیری گوشی مقصد فعال نیست؛ نشانگر قابل کنترل نیست",
                                Toast.LENGTH_LONG).show());
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (clientSocket == socket) connected = false;
            }
        }, "fast-m-reader").start();
    }

    private void startHeartbeat() {
        if (heartbeatStarted) return;
        heartbeatStarted = true;
        heartbeat.scheduleWithFixedDelay(() -> {
            // Keep trying for as long as the user has not explicitly disconnected/exited.
            String address = targetAddress;
            if (address == null || address.isEmpty()) return;

            long age = System.currentTimeMillis() - lastPongAt;
            if (connected && age > 15000L) {
                closeSocketOnly();
            }

            if (!connected || clientSocket == null || clientSocket.isClosed() || writer == null) {
                try {
                    reconnectBlocking();
                } catch (Exception ignored) {
                    // Do not clear targetAddress: next scheduled pass retries automatically.
                    connected = false;
                }
                return;
            }

            try {
                PrintWriter w = writer;
                if (w != null) {
                    synchronized (w) {
                        w.println("PING");
                        if (w.checkError()) {
                            connected = false;
                            closeSocketOnly();
                        }
                    }
                }
            } catch (Exception ignored) {
                closeSocketOnly();
            }
        }, 1, 3, TimeUnit.SECONDS);
    }

    private void send(String command) {
        if (command == null) return;
        try {
            sender.execute(() -> sendWithReconnect(command));
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    private void sendWithReconnect(String command) {
        for (int attempt=0; attempt<2; attempt++) {
            try {
                if (!connected || writer == null || clientSocket == null || clientSocket.isClosed()) {
                    reconnectBlocking();
                }
                PrintWriter w = writer;
                if (!connected || w == null) return;
                synchronized (w) {
                    w.println(command);
                    if (!w.checkError()) return;
                }
            } catch (Exception ignored) { }
            closeSocketOnly();
        }
        connected = false;
        runOnUiThread(() -> Toast.makeText(this,
            "ارتباط قطع شد؛ IP و شبکهٔ گوشی مقصد را بررسی کن", Toast.LENGTH_SHORT).show());
    }

    private void reconnectBlocking() throws Exception {
        synchronized (reconnectLock) {
            String address = targetAddress;
            if (address == null || address.isEmpty())
                throw new IllegalStateException("Manual disconnect or no target address");
            if (connected && clientSocket != null && !clientSocket.isClosed()
                    && writer != null && !writer.checkError()) return;

            closeSocketOnly();
            Socket s = new Socket();
            try {
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.connect(new java.net.InetSocketAddress(address, PORT), 2500);
                PrintWriter w = new PrintWriter(s.getOutputStream(), true);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                // User may have pressed disconnect while connect() was in progress.
                if (targetAddress == null) {
                    s.close();
                    throw new IllegalStateException("Manual disconnect");
                }
                clientSocket = s;
                writer = w;
                reader = r;
                connected = true;
                lastPongAt = System.currentTimeMillis();
                startReader(r, s);
                startHeartbeat();
            } catch (Exception e) {
                try { s.close(); } catch (Exception ignored) { }
                throw e;
            }
        }
    }

    private void closeSocketOnly() {
        connected = false;
        try { if(writer!=null) writer.close(); } catch(Exception ignored) { }
        try { if(clientSocket!=null) clientSocket.close(); } catch(Exception ignored) { }
        try { if(reader!=null) reader.close(); } catch(Exception ignored) { }
        reader = null;
        writer = null;
        clientSocket = null;
    }
    private void disconnect(){targetAddress=null;closeSocketOnly();if(status!=null)status.setText("وضعیت: قطع");}
    @Override protected void onDestroy(){
        // Keep the connection/reconnect loop alive across temporary Activity recreation.
        // Explicit user stop/disconnect is the only place that clears targetAddress.
        super.onDestroy();
    }
}
