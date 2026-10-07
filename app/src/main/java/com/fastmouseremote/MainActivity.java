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
    private TextView dragTouchView;
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
        button("تنظیمات دسترسی و مجوزها", this::showAccessSettings);
        root.addView(text("برنامه را روی هر دو گوشی نصب کن. گوشی‌ها باید به یک شبکهٔ Wi-Fi یا هات‌اسپات وصل باشند.",14),params(-1,-2,0,16,0,0));
    }
    private void showTarget() {
        setup("دستگاه هدف");
        root.addView(text("۱) مجوز دسترس‌پذیری را فعال کن تا برنامه بتواند لمس و حرکت موس را روی صفحه اجرا کند.",15),params(-1,-2,0,0,0,8));
        button("باز کردن تنظیمات Accessibility", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(text("۲) برای نمایش نشانگر شناور، مجوز نمایش روی برنامه‌های دیگر را فعال کن.",15),params(-1,-2,0,10,0,4));
        button("مجوز نمایش روی برنامه‌ها", () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)));
        button("تنظیمات دسترسی و مجوزهای کامل", this::showAccessSettings);
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
    private void showAccessSettings() {
        setup("تنظیمات دسترسی و مجوزها");
        root.addView(text("دسترسی‌های مورد استفادهٔ Fast M را از همین بخش مدیریت کن. هر گزینه مستقیماً صفحهٔ تنظیمات مربوط به خودش را باز می‌کند.",15),
                params(-1,-2,0,0,0,10));
        button("Accessibility / دسترس‌پذیری",
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("نمایش روی برنامه‌های دیگر",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)));
        button("Usage Access / دسترسی استفاده",
                () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        button("اعلان‌های برنامه",
                () -> {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(i);
                });
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            button("باتری / عدم محدودیت فعالیت",
                    () -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            i.setData(android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        }
                    });
        }
        button("اطلاعات و مجوزهای خود برنامه",
                () -> {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                });
        button("بازگشت", this::showHome);
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
        Button keyboardShortcut = keyboardKey("⌨ کیبورد", 13, Color.rgb(255,247,224));
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(dp(105), dp(38), Gravity.TOP|Gravity.RIGHT);
        kp.topMargin = dp(42); kp.rightMargin = dp(5);
        canvas.addView(keyboardShortcut, kp);
        keyboardShortcut.setOnClickListener(v -> showKeyboardRemote());
        addHorizontalControls(canvas);
        setContentView(canvas);
    }



    // Menu 3: large Fast Keyboard-style panel on the left and mouse controls on the right.
    /**
     * سین کیبورد — مرجع قطعی:
     * تصویر keyboard_reference.jpg همان تصویر ارسالی کاربر است.
     * هیچ بازطراحی یا جابه‌جایی دکمه‌ای انجام نمی‌شود؛ فقط Hotspot شفاف
     * روی مختصات خود تصویر قرار می‌گیرد تا ظاهر دقیقاً همان تصویر بماند.
     */
    private void showKeyboardRemote() {
        final int W = 1665;
        final int H = 953;

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.rgb(173,218,232));

        ImageView reference = new ImageView(this);
        reference.setImageResource(R.drawable.keyboard_reference);
        reference.setScaleType(ImageView.ScaleType.FIT_CENTER);
        reference.setAdjustViewBounds(false);
        FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(-1,-1);
        screen.addView(reference,imageLp);

        // مختصات Hotspotها بر اساس دقیقاً همان تصویر 1665×953 هستند.
        // محاسبهٔ مقیاس و حاشیهٔ FIT_CENTER در addReferenceHotspot انجام می‌شود.

        // ردیف بالایی
        addReferenceHotspot(screen,0,0,122,145, () -> send("PASTE"));
        addReferenceHotspot(screen,122,0,243,145, () -> send("COPY_ALL"));
        addReferenceHotspot(screen,243,0,365,145, () -> send("COPY_SCREEN"));
        addReferenceHotspot(screen,365,0,486,145, () -> send("CUT"));
        addReferenceHotspot(screen,486,0,607,145, () -> send("UNDO"));
        addReferenceHotspot(screen,607,0,729,145, () -> send("REDO"));
        addReferenceHotspot(screen,729,0,850,145, () -> send("HISTORY"));
        addReferenceHotspot(screen,850,0,972,145, () -> showAccessSettings());
        addReferenceHotspot(screen,972,0,1093,145, () -> showKeyboardRemote());
        addReferenceHotspot(screen,1093,0,1217,145, () -> showRemoteImage(false));

        // ردیف واژه‌های فارسی
        addReferenceHotspot(screen,0,145,153,258, () -> sendText("دکمه"));
        addReferenceHotspot(screen,153,145,304,258, () -> sendText("و"));
        addReferenceHotspot(screen,304,145,455,258, () -> sendText("در"));
        addReferenceHotspot(screen,455,145,607,258, () -> sendText("را"));
        addReferenceHotspot(screen,607,145,758,258, () -> sendText("همان"));
        addReferenceHotspot(screen,758,145,910,258, () -> sendText("که"));
        addReferenceHotspot(screen,910,145,1061,258, () -> sendText("ارسال"));
        addReferenceHotspot(screen,1061,145,1217,258, () -> sendText("فایل"));

        // اعداد و نمادها
        String[] nums={"۱","۲","۳","۴","۵","۶","۷","۸","۹","۰"};
        int[] nx={0,106,211,317,423,528,634,740,846,951};
        for(int i=0;i<10;i++){
            final String key=nums[i];
            int x1=nx[i], x2=(i==9?1060:nx[i+1]);
            addReferenceHotspot(screen,x1,258,x2,389,()->sendText(key));
        }
        addReferenceHotspot(screen,1060,258,1217,389,()->send("KEY_BACKSPACE"));

        // حروف فارسی ردیف 1
        String[] r1={"ض","ص","ث","ق","ف","غ","ع","ه","خ","ح","ج"};
        for(int i=0;i<r1.length;i++){
            final String key=r1[i];
            addReferenceHotspot(screen,i*100,389,(i+1)*100,520,()->sendText(key));
        }
        // Enter
        addReferenceHotspot(screen,1095,389,1217,651,()->sendText("\n"));

        // حروف فارسی ردیف 2
        String[] r2={"ش","س","ی","ب","ل","ا","ت","ن","م","ک","گ"};
        for(int i=0;i<r2.length;i++){
            final String key=r2[i];
            addReferenceHotspot(screen,i*100,520,(i+1)*100,651,()->sendText(key));
        }

        // Caps + ردیف فارسی سوم
        addReferenceHotspot(screen,0,651,121,783,()->send("CAPS"));
        String[] r3={"ظ","ط","ژ","ز","ر","ذ","د","پ","و","چ"};
        int[] rx={121,230,339,448,557,666,775,884,993,1095};
        for(int i=0;i<r3.length;i++){
            final String key=r3[i];
            int x1=rx[i], x2=(i==9?1217:rx[i+1]);
            addReferenceHotspot(screen,x1,651,x2,783,()->sendText(key));
        }
        addReferenceHotspot(screen,1095,651,1217,783,()->sendText("؟"));

        // ردیف پایین
        addReferenceHotspot(screen,0,783,91,953,()->send("WAVE"));
        addReferenceHotspot(screen,91,783,218,953,()->sendText("123"));
        addReferenceHotspot(screen,218,783,324,953,()->send("FA"));
        addReferenceHotspot(screen,324,783,582,953,()->sendText(" "));
        addReferenceHotspot(screen,582,783,660,953,()->sendText("،"));
        addReferenceHotspot(screen,660,783,740,953,()->sendText("."));
        addReferenceHotspot(screen,740,783,818,953,()->sendText("♦"));
        addReferenceHotspot(screen,818,783,925,953,()->send("MOVE -18 0"));
        addReferenceHotspot(screen,925,783,1031,953,()->send("MOVE 18 0"));
        addReferenceHotspot(screen,1031,783,1124,953,()->send("MOVE 0 -18"));
        addReferenceHotspot(screen,1124,783,1217,953,()->send("MOVE 0 18"));

        // پنل Menu 3 — دقیقاً سمت راست عکس
        addReferenceHotspot(screen,1218,0,1665,125,()->showKeyboardRemote());
        addReferenceHotspot(screen,1218,125,1665,303,()->clickLeftFromReference());
        addReferenceTouchHotspot(screen,1218,303,1665,928);
        addReferenceHotspot(screen,1218,928,1665,953,()->showAccessSettings());

        setContentView(screen);
    }

    private void clickLeftFromReference() {
        if (!connected) {
            Toast.makeText(this,"ابتدا اتصال را برقرار کنید",Toast.LENGTH_SHORT).show();
            return;
        }
        send("CLICK_LEFT");
    }

    private void addReferenceHotspot(FrameLayout root, int x1,int y1,int x2,int y2, final Runnable action) {
        View hit = new View(this);
        hit.setBackgroundColor(Color.TRANSPARENT);
        hit.setClickable(true);
        hit.setOnClickListener(v -> action.run());
        root.addView(hit,new FrameLayout.LayoutParams(Math.max(1,x2-x1),Math.max(1,y2-y1)));
        hit.setTag(new int[]{x1,y1,x2,y2});
        hit.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            FrameLayout parent=(FrameLayout)v.getParent();
            int pw=parent.getWidth(), ph=parent.getHeight();
            if(pw<=0||ph<=0)return;
            float scale=Math.min((float)pw/1665f,(float)ph/953f);
            float ox=(pw-1665f*scale)/2f, oy=(ph-953f*scale)/2f;
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)v.getLayoutParams();
            int[] q=(int[])v.getTag();
            lp.width=Math.max(1,Math.round((q[2]-q[0])*scale));
            lp.height=Math.max(1,Math.round((q[3]-q[1])*scale));
            lp.leftMargin=Math.round(ox+q[0]*scale);
            lp.topMargin=Math.round(oy+q[1]*scale);
            v.setLayoutParams(lp);
        });
    }

    private void addReferenceTouchHotspot(FrameLayout root, int x1,int y1,int x2,int y2) {
        View touch = new View(this);
        touch.setBackgroundColor(Color.TRANSPARENT);
        root.addView(touch,new FrameLayout.LayoutParams(1,1));
        touch.setTag(new int[]{x1,y1,x2,y2});
        touch.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            FrameLayout parent=(FrameLayout)v.getParent();
            int pw=parent.getWidth(), ph=parent.getHeight();
            if(pw<=0||ph<=0)return;
            float scale=Math.min((float)pw/1665f,(float)ph/953f);
            float ox=(pw-1665f*scale)/2f, oy=(ph-953f*scale)/2f;
            int[] q=(int[])v.getTag();
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)v.getLayoutParams();
            lp.width=Math.max(1,Math.round((q[2]-q[0])*scale));
            lp.height=Math.max(1,Math.round((q[3]-q[1])*scale));
            lp.leftMargin=Math.round(ox+q[0]*scale);
            lp.topMargin=Math.round(oy+q[1]*scale);
            v.setLayoutParams(lp);
        });
        final int[] last={0,0};
        touch.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                last[0]=(int)e.getX(); last[1]=(int)e.getY();
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                int x=(int)e.getX(), y=(int)e.getY();
                int dx=x-last[0],dy=y-last[1];
                last[0]=x;last[1]=y;
                if(connected&&(dx!=0||dy!=0))
                    queueMove(Math.round(dx*sensitivity*pointerSpeed),Math.round(dy*sensitivity*pointerSpeed));
                return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            return true;
        });
    }

    private Button keyboardKey(String label,int size,int color) {
        Button b=new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(size);
        b.setTextColor(Color.rgb(75,52,74));
        b.setPadding(dp(1),dp(1),dp(1),dp(1));
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(5));
        d.setStroke(dp(1),Color.rgb(73,156,193));b.setBackground(d);
        b.setMinHeight(0);b.setMinimumHeight(0);
        return b;
    }

    private void sendText(String value) {
        if(value==null || value.isEmpty()) return;
        // Text commands are sent to the target's focused accessibility node.
        String encoded=android.util.Base64.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);
        send("TEXT "+encoded);
    }

    private void addHorizontalControls(FrameLayout canvas) {
        HorizontalControl sensitivityControl = new HorizontalControl("حساسیت", true, sensitivity);
        HorizontalControl speedControl = new HorizontalControl("سرعت", false, pointerSpeed);
        int width=dp(120), height=dp(38);
        FrameLayout.LayoutParams p1=new FrameLayout.LayoutParams(width,height,Gravity.TOP|Gravity.RIGHT);
        p1.topMargin=dp(2); p1.rightMargin=dp(4); canvas.addView(speedControl,p1);
        FrameLayout.LayoutParams p2=new FrameLayout.LayoutParams(width,height,Gravity.TOP|Gravity.RIGHT);
        p2.topMargin=dp(2); p2.rightMargin=dp(128); canvas.addView(sensitivityControl,p2);
    }

    private class HorizontalControl extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label; private final boolean isSensitivity;
        private float value; private final int minValue=1,maxValue=5;
        HorizontalControl(String label,boolean isSensitivity,float initialValue){
            super(MainActivity.this); this.label=label; this.isSensitivity=isSensitivity;
            value=Math.max(minValue,Math.min(maxValue,initialValue));
            setContentDescription(label); setBackgroundColor(Color.argb(115,245,247,251));
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c); float left=dp(10),right=getWidth()-dp(10),cy=getHeight()/2f+dp(7);
            paint.setStrokeWidth(dp(3)); paint.setColor(Color.rgb(70,70,70)); c.drawLine(left,cy,right,cy,paint);
            float f=(value-minValue)/(float)(maxValue-minValue), tx=left+f*(right-left);
            paint.setColor(isSensitivity?Color.rgb(220,40,140):Color.rgb(20,110,220)); c.drawCircle(tx,cy,dp(7),paint);
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(10)); paint.setColor(Color.rgb(35,35,35));
            c.drawText(label+" "+Math.round(value),getWidth()/2f,dp(12),paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){getParent().requestDisallowInterceptTouchEvent(true);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE||e.getActionMasked()==MotionEvent.ACTION_UP){
                float left=dp(10),right=getWidth()-dp(10);
                float f=Math.max(0f,Math.min(1f,(e.getX()-left)/(right-left)));
                value=minValue+f*(maxValue-minValue);
                if(isSensitivity)sensitivity=value;else pointerSpeed=value;invalidate();
                if(e.getActionMasked()==MotionEvent.ACTION_UP)getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            return true;
        }
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
