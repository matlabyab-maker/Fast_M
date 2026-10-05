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
import android.view.accessibility.AccessibilityNodeInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.TextView;
import android.view.View;
import android.view.WindowManager.LayoutParams;

public class MouseAccessibilityService extends AccessibilityService {
    public static volatile MouseAccessibilityService instance;
    private WindowManager wm; private TextView cursor; private LayoutParams lp; private final Handler handler=new Handler(Looper.getMainLooper());
    private int x=150,y=250; private int screenW=720,screenH=1280;
    @Override public void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        android.util.DisplayMetrics dm=new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW=dm.widthPixels; screenH=dm.heightPixels;
        x=Math.max(1,Math.min(screenW-1,x)); y=Math.max(1,Math.min(screenH-1,y));
        handler.post(()->{
            if(cursor!=null) { try { if(cursor.getParent()!=null) wm.removeView(cursor); } catch(Exception ignored) {} cursor=null; lp=null; }
            showCursor();
        });
    }
    private void showCursor(){if(cursor!=null||wm==null)return;try{cursor=new TextView(this);
        // A computer-style arrow pointer rather than a dot; accessibility overlay spans system bars.
        cursor.setText("➤");
        cursor.setTextSize(32);
        cursor.setTextColor(Color.rgb(255, 80, 0));
        cursor.setShadowLayer(4,1,1,Color.BLACK);
        cursor.setRotation(-45f);
        cursor.setGravity(Gravity.CENTER);lp=new LayoutParams(42,42,LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,LayoutParams.FLAG_NOT_FOCUSABLE|LayoutParams.FLAG_NOT_TOUCHABLE|LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|Gravity.LEFT;lp.x=Math.max(0,Math.min(screenW-42,x-8));lp.y=Math.max(0,Math.min(screenH-42,y-8));wm.addView(cursor,lp);}catch(Exception e){cursor=null;}}
    public void moveCursor(int dx, int dy) {
        handler.post(() -> {
            x = Math.max(1, Math.min(screenW - 1, x + dx * 2));
            y = Math.max(1, Math.min(screenH - 1, y + dy * 2));
            if (cursor == null || lp == null) {
                showCursor();
            }
            if (cursor == null || lp == null) return;

            lp.x = Math.max(0, Math.min(screenW - 42, x - 8));
            lp.y = Math.max(0, Math.min(screenH - 42, y - 8));
            try {
                if (cursor.getParent() == null) {
                    cursor = null;
                    lp = null;
                    showCursor();
                } else {
                    wm.updateViewLayout(cursor, lp);
                }
            } catch (Exception ignored) {
                try {
                    if (cursor != null && cursor.getParent() != null) wm.removeView(cursor);
                } catch (Exception ignored2) { }
                cursor = null;
                lp = null;
                showCursor();
            }
        });
    }
    public void click(boolean longPress){handler.post(()->{Path p=new Path();p.moveTo(x,y);GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0,longPress?850:70);dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),null,null);});}
    public void scroll(int direction){handler.post(()->{Path p=new Path();p.moveTo(x,y);p.lineTo(x,y+(direction<0?-Math.min(420,screenH/3):Math.min(420,screenH/3)));dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,350)).build(),null,null);});}
    public void goBack(){handler.post(()->performGlobalAction(GLOBAL_ACTION_BACK));}
    public void swipe(int direction){handler.post(()->{Path p=new Path();int startY=Math.max(80,Math.min(screenH-80,y));int endY=Math.max(40,Math.min(screenH-40,startY+(direction<0?-Math.min(320,screenH/4):Math.min(320,screenH/4))));p.moveTo(x,startY);p.lineTo(x,endY);dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,400)).build(),null,null);});}

    public void typeText(String value) {
        handler.post(() -> {
            try {
                AccessibilityNodeInfo node=getRootInActiveWindow();
                if(node==null)return;
                AccessibilityNodeInfo focused=node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if(focused==null) focused=node;
                CharSequence old=focused.getText();
                String current=old==null?"":old.toString();
                if("\n".equals(value)) {
                    android.os.Bundle args=new android.os.Bundle();
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);
                    focused.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER);
                } else {
                    android.os.Bundle args=new android.os.Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,current+value);
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
                }
                if(focused!=node) focused.recycle();
                node.recycle();
            } catch(Exception ignored) {}
        });
    }

    public void backspace() {
        handler.post(() -> {
            try {
                AccessibilityNodeInfo root=getRootInActiveWindow();
                if(root==null)return;
                AccessibilityNodeInfo node=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if(node!=null) {
                    CharSequence text=node.getText();
                    if(text!=null && text.length()>0) {
                        android.os.Bundle args=new android.os.Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text.subSequence(0,text.length()-1));
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
                    }
                    node.recycle();
                }
                root.recycle();
            } catch(Exception ignored) {}
        });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){instance=null;handler.post(()->{if(cursor!=null&&wm!=null){try{wm.removeView(cursor);}catch(Exception ignored){}cursor=null;}});super.onDestroy();}
}
