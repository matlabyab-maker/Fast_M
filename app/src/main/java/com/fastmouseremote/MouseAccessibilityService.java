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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.WindowManager.LayoutParams;

public class MouseAccessibilityService extends AccessibilityService {
    public static volatile MouseAccessibilityService instance;
    private WindowManager wm; private View cursor; private LayoutParams lp; private final Handler handler=new Handler(Looper.getMainLooper());
    private int x=150,y=250; private int screenW=720,screenH=1280;
    private boolean serviceAlive = false;
    private final Runnable cursorWatchdog = new Runnable() {
        @Override public void run() {
            if (!serviceAlive) return;
            ensureCursorVisible();
            handler.postDelayed(this, 900L);
        }
    };
    @Override public void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        refreshDisplayBounds();
        x=Math.max(1,Math.min(screenW-1,x)); y=Math.max(1,Math.min(screenH-1,y));
        serviceAlive = true;
        handler.post(() -> {
            removeCursorView();
            showCursor();
            handler.removeCallbacks(cursorWatchdog);
            handler.postDelayed(cursorWatchdog, 900L);
        });
    }
    private void refreshDisplayBounds() {
        if (wm == null) return;
        try {
            android.util.DisplayMetrics dm=new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW=Math.max(1, dm.widthPixels);
            screenH=Math.max(1, dm.heightPixels);
        } catch (Exception ignored) { }
    }

    private void removeCursorView() {
        View old = cursor;
        cursor = null;
        lp = null;
        if (old != null && wm != null) {
            try { if (old.getParent() != null) wm.removeViewImmediate(old); } catch (Exception ignored) { }
        }
    }

    /** Recreate the non-touchable accessibility overlay if Android detaches or hides it. */
    private void ensureCursorVisible() {
        if (!serviceAlive || wm == null) return;
        refreshDisplayBounds();
        if (cursor == null || lp == null) {
            showCursor();
            return;
        }
        try {
            if (cursor.getParent() == null) {
                cursor = null;
                lp = null;
                showCursor();
                return;
            }
            if (cursor.getVisibility() != View.VISIBLE) cursor.setVisibility(View.VISIBLE);
            cursor.invalidate();
            int size = dp(36);
            lp.x = Math.max(0, Math.min(Math.max(0, screenW-size), x - dp(2)));
            lp.y = Math.max(0, Math.min(Math.max(0, screenH-size), y - dp(2)));
            wm.updateViewLayout(cursor, lp);
        } catch (Exception e) {
            removeCursorView();
            showCursor();
        }
    }

    private void showCursor() {
        if (wm == null || !serviceAlive) return;
        if (cursor != null) {
            try { if (cursor.getParent() != null) { cursor.setVisibility(View.VISIBLE); return; } } catch (Exception ignored) {}
            cursor = null;
            lp = null;
        }
        try {
            CursorView pointer = new CursorView(this);
            cursor = pointer;
            int size = dp(36);
            lp = new LayoutParams(size, size,
                    LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCHABLE
                            | LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = Math.max(0, Math.min(Math.max(0, screenW-size), x - dp(2)));
            lp.y = Math.max(0, Math.min(Math.max(0, screenH-size), y - dp(2)));
            cursor.setVisibility(View.VISIBLE);
            wm.addView(cursor, lp);
            cursor.invalidate();
        } catch (Exception e) {
            cursor = null;
            lp = null;
            // The watchdog will retry if the system temporarily rejects the overlay.
        }
    }

    private int dp(float v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class CursorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        CursorView(AccessibilityService context) {
            super(context);
            setWillNotDraw(false);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Draw directly in the view's pixel coordinate system; do not scale twice.
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            Path arrow = new Path();
            arrow.moveTo(w * 0.06f, h * 0.03f);
            arrow.lineTo(w * 0.12f, h * 0.82f);
            arrow.lineTo(w * 0.34f, h * 0.62f);
            arrow.lineTo(w * 0.52f, h * 0.96f);
            arrow.lineTo(w * 0.70f, h * 0.87f);
            arrow.lineTo(w * 0.52f, h * 0.56f);
            arrow.lineTo(w * 0.88f, h * 0.53f);
            arrow.close();
            paint.setColor(Color.rgb(255, 210, 0));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(arrow, paint);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w * 0.05f));
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(arrow, paint);
        }
    }
    public void moveCursor(int dx, int dy) {
        handler.post(() -> {
            x = Math.max(0, Math.min(Math.max(0, screenW - dp(36)), x + dx * 2));
            y = Math.max(0, Math.min(Math.max(0, screenH - dp(36)), y + dy * 2));
            if (cursor == null || lp == null) {
                showCursor();
            }
            if (cursor == null || lp == null) return;

            lp.x = Math.max(0, Math.min(Math.max(0, screenW - dp(36)), x - dp(2)));
            lp.y = Math.max(0, Math.min(Math.max(0, screenH - dp(36)), y - dp(2)));
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
    /** Only performs a primary tap or a sustained primary press. Secondary click is intentionally not emulated. */
    public void click(boolean longPress){
        if (longPress) return; // Never turn a right-click request into an accidental primary click.
        handler.post(()->{
            Path p=new Path(); p.moveTo(x,y);
            GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0,70);
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),null,null);
        });
    }
    public void scroll(int direction){handler.post(()->{Path p=new Path();p.moveTo(x,y);p.lineTo(x,y+(direction<0?-Math.min(420,screenH/3):Math.min(420,screenH/3)));dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,350)).build(),null,null);});}
    public boolean goBack(){ return performGlobalAction(GLOBAL_ACTION_BACK); }
    /** System navigation is performed through Android global actions, not fake pointer clicks. */
    public boolean goHome(){ return performGlobalAction(GLOBAL_ACTION_HOME); }
    public boolean showRecents(){ return performGlobalAction(GLOBAL_ACTION_RECENTS); }
    public boolean showQuickSettings(){ return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); }
    public boolean showNotifications(){ return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); }
    public void swipe(int direction){handler.post(()->{Path p=new Path();int startY=Math.max(80,Math.min(screenH-80,y));int endY=Math.max(40,Math.min(screenH-40,startY+(direction<0?-Math.min(320,screenH/4):Math.min(320,screenH/4))));p.moveTo(x,startY);p.lineTo(x,endY);dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,400)).build(),null,null);});}

    private AccessibilityNodeInfo focusedInput() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null) { root.recycle(); return focused; }
        return root;
    }

    public void editAction(int action) {
        handler.post(() -> {
            AccessibilityNodeInfo node = null;
            try {
                node = focusedInput();
                if (node != null) node.performAction(action);
            } catch (Exception ignored) {
            } finally { if (node != null) node.recycle(); }
        });
    }

    public void selectAllAndAction(int action) {
        handler.post(() -> {
            AccessibilityNodeInfo node = null;
            try {
                node = focusedInput();
                if (node == null || !node.isEditable()) return;
                node.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL);
                node.performAction(action);
            } catch (Exception ignored) {
            } finally {
                if (node != null) node.recycle();
            }
        });
    }

    public void typeText(String value) {
        handler.post(() -> {
            AccessibilityNodeInfo node = null;
            try {
                node = focusedInput();
                if (node == null || !node.isEditable()) return;
                CharSequence old = node.getText();
                String current = old == null ? "" : old.toString();
                int start = node.getTextSelectionStart();
                int end = node.getTextSelectionEnd();
                if (start < 0 || end < 0 || start > current.length() || end > current.length()) {
                    start = end = current.length();
                }
                if (start > end) { int t=start; start=end; end=t; }
                String updated = current.substring(0,start) + value + current.substring(end);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated);
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) {
                    android.os.Bundle sel = new android.os.Bundle();
                    sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start + value.length());
                    sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + value.length());
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
                }
            } catch(Exception ignored) {
            } finally { if (node != null) node.recycle(); }
        });
    }

    public void backspace() {
        handler.post(() -> {
            AccessibilityNodeInfo node = null;
            try {
                node = focusedInput();
                if (node == null || !node.isEditable()) return;
                CharSequence old = node.getText();
                if (old == null) return;
                String current = old.toString();
                int start = node.getTextSelectionStart(), end = node.getTextSelectionEnd();
                if (start < 0 || end < 0 || start > current.length() || end > current.length()) start=end=current.length();
                if (start > end) { int t=start; start=end; end=t; }
                if (start == end && start > 0) start--;
                if (start == end) return;
                String updated = current.substring(0,start) + current.substring(end);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated);
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) {
                    android.os.Bundle sel = new android.os.Bundle();
                    sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start);
                    sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,start);
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,sel);
                }
            } catch(Exception ignored) {
            } finally { if (node != null) node.recycle(); }
        });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window changes can detach overlays on some Android builds; verify the pointer after them.
        if (serviceAlive) handler.post(this::ensureCursorVisible);
    }
    @Override public void onInterrupt() { if (serviceAlive) handler.post(this::ensureCursorVisible); }
    @Override public void onDestroy(){
        serviceAlive = false;
        handler.removeCallbacks(cursorWatchdog);
        handler.post(this::removeCursorView);
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
