package com.fastmouseremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.view.View;
import android.view.WindowManager.LayoutParams;

public class MouseAccessibilityService extends AccessibilityService {
    public static volatile MouseAccessibilityService instance;
    private WindowManager wm; private TextView cursor; private LayoutParams lp; private final Handler handler=new Handler(Looper.getMainLooper());
    private int x=150,y=250; private int screenW=720,screenH=1280;
    @Override public void onServiceConnected(){super.onServiceConnected();instance=this;wm=(WindowManager)getSystemService(WINDOW_SERVICE);android.util.DisplayMetrics dm=new android.util.DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(dm);screenW=dm.widthPixels;screenH=dm.heightPixels;x=screenW/2;y=screenH/2;handler.post(this::showCursor);}
    private void showCursor(){if(cursor!=null||wm==null)return;try{cursor=new TextView(this);cursor.setText("●");cursor.setTextSize(29);cursor.setTextColor(Color.rgb(255,72,72));cursor.setShadowLayer(4,0,0,Color.BLACK);cursor.setGravity(Gravity.CENTER);lp=new LayoutParams(42,42,LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,LayoutParams.FLAG_NOT_FOCUSABLE|LayoutParams.FLAG_NOT_TOUCHABLE|LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|Gravity.LEFT;lp.x=x-21;lp.y=y-21;wm.addView(cursor,lp);}catch(Exception e){cursor=null;}}
    public void moveCursor(int dx,int dy){handler.post(()->{x=Math.max(2,Math.min(screenW-2,x+dx*2));y=Math.max(2,Math.min(screenH-2,y+dy*2));if(cursor==null)showCursor();if(cursor!=null&&lp!=null){lp.x=x-21;lp.y=y-21;try{wm.updateViewLayout(cursor,lp);}catch(Exception ignored){}}});}
    public void click(boolean longPress){handler.post(()->{Path p=new Path();p.moveTo(x,y);GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0,longPress?850:70);dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),null,null);});}
    public void scroll(int direction){handler.post(()->{Path p=new Path();p.moveTo(x,y);p.lineTo(x,y+(direction<0?-Math.min(420,screenH/3):Math.min(420,screenH/3)));dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,350)).build(),null,null);});}
    public void goBack(){handler.post(()->performGlobalAction(GLOBAL_ACTION_BACK));}
    public void swipe(int direction){handler.post(()->{Path p=new Path();int startY=Math.max(80,Math.min(screenH-80,y));int endY=Math.max(40,Math.min(screenH-40,startY+(direction<0?-Math.min(320,screenH/4):Math.min(320,screenH/4))));p.moveTo(x,startY);p.lineTo(x,endY);dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,400)).build(),null,null);});}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){instance=null;handler.post(()->{if(cursor!=null&&wm!=null){try{wm.removeView(cursor);}catch(Exception ignored){}cursor=null;}});super.onDestroy();}
}
