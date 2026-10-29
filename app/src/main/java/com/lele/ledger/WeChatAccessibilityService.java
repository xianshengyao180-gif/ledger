package com.lele.ledger;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Local receipt proposals. No gestures, payment actions, raw-text logs or automatic bill inserts. */
public final class WeChatAccessibilityService extends AccessibilityService {
    private static final String TAG = "LedgerReader";
    private static final String WECHAT = "com.tencent.mm";
    static final String PREFS = "wechat_reader";
    static final String CONSENT = "local_screenshot_consent_v1";
    static final String STATUS = "status";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scan = this::inspectPage;
    private TextRecognizer recognizer;
    private Db db;
    private String pageKind;
    private int pageWindow = -1, generation, attempts;
    private boolean scanPending, inFlight, handledVisit, destroyed;
    private long lastCapture;

    static boolean hasScreenshotConsent(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(CONSENT, false);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        destroyed = false;
        if (db != null) db.close();
        db = new Db(this);
        status("服务已连接，请重新打开已完成的红包或转账详情");
        Log.i(TAG, "reader_connected; proposals_only=true");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || destroyed) return;
        CharSequence eventPackage = event.getPackageName();
        if (eventPackage != null && !WECHAT.contentEquals(eventPackage)) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPackage != null) {
            String name = event.getClassName() == null ? "" : event.getClassName().toString();
            if (name.startsWith(WECHAT + ".")) {
                String kind = null;
                if (name.endsWith(".RemittanceDetailUI")) kind = WeChatReceiptParser.TRANSFER;
                if (name.endsWith(".LuckyMoneyNewDetailUI")) kind = WeChatReceiptParser.RED_PACKET;
                if (!java.util.Objects.equals(kind, pageKind) || event.getWindowId() != pageWindow) {
                    generation++;
                    pageKind = kind;
                    pageWindow = event.getWindowId();
                    attempts = 0;
                    handledVisit = false;
                    if (kind != null) Log.i(TAG, "receipt_page_enter; kind=" + kind
                            + "; window_valid=" + (pageWindow >= 0));
                    handler.removeCallbacks(scan);
                    scanPending = false;
                }
            }
        }
        if (pageKind == null || handledVisit || attempts >= 3) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) scheduleScan(400);
    }

    private void scheduleScan(long delay) {
        if (!scanPending && !destroyed) {
            scanPending = true;
            handler.postDelayed(scan, delay);
        }
    }

    private AccessibilityNodeInfo currentRoot() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (power == null || !power.isInteractive() || (keyguard != null && keyguard.isDeviceLocked())) return null;
        AccessibilityNodeInfo root;
        try { root = getRootInActiveWindow(); }
        catch (RuntimeException error) { return null; }
        if (root != null && WECHAT.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                && root.getWindowId() == pageWindow) return root;
        if (root != null) root.recycle();
        return null;
    }

    private boolean stillOnPage(int token) {
        if (destroyed || token != generation || pageKind == null) return false;
        AccessibilityNodeInfo root = currentRoot();
        if (root == null) return false;
        root.recycle();
        return true;
    }

    private void inspectPage() {
        scanPending = false;
        if (destroyed || pageKind == null || handledVisit || inFlight || attempts >= 3) return;
        AccessibilityNodeInfo root = currentRoot();
        if (root == null) return;
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        List<WeChatReceiptParser.Line> lines = new ArrayList<>();
        queue.add(root);
        int count = 0;
        try {
            while (!queue.isEmpty() && count < 1500) {
                count++;
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    if (node.isVisibleToUser()) {
                        Rect rect = new Rect();
                        node.getBoundsInScreen(rect);
                        CharSequence text = node.getText();
                        if (text == null || text.length() == 0) text = node.getContentDescription();
                        if (text != null && text.length() > 0) {
                            for (String line : text.toString().split("\\n")) {
                                lines.add(new WeChatReceiptParser.Line(line, rect.left, rect.top,
                                        rect.width(), rect.height()));
                            }
                        }
                    }
                    for (int i = 0; i < node.getChildCount() && queue.size() + count < 1500; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.add(child);
                    }
                } finally {
                    node.recycle();
                }
            }
            WeChatReceiptParser.Receipt receipt = WeChatReceiptParser.parse(lines, pageKind, System.currentTimeMillis());
            Log.i(TAG, "node_scan; nodes=" + count + "; text_lines=" + lines.size());
            if (receipt != null) {
                offer(receipt, "nodes");
                return;
            }
        } catch (RuntimeException error) {
            status("页面读取中断，请重新打开详情");
        } finally {
            while (!queue.isEmpty()) queue.removeFirst().recycle();
        }
        if (!hasScreenshotConsent(this)) {
            status("微信未提供可读文字，可在设置中开启本地文字识别");
            return;
        }
        if (Build.VERSION.SDK_INT < 34) {
            status("此页面未提供文字；本地窗口识别需要 Android 14 或以上");
            return;
        }
        long gap = SystemClock.uptimeMillis() - lastCapture;
        if (gap < 2000) {
            scheduleScan(2000 - gap);
            return;
        }
        captureWindow();
    }

    @androidx.annotation.RequiresApi(34)
    private void captureWindow() {
        final int token = generation;
        final String kind = pageKind;
        attempts++;
        lastCapture = SystemClock.uptimeMillis();
        inFlight = true;
        try {
            takeScreenshotOfWindow(pageWindow, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap image = null;
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        if (!hasScreenshotConsent(WeChatAccessibilityService.this) || !stillOnPage(token)) {
                            inFlight = false;
                            return;
                        }
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware != null) {
                            try { image = hardware.copy(Bitmap.Config.ARGB_8888, false); }
                            finally { hardware.recycle(); }
                        }
                    } catch (RuntimeException error) {
                        inFlight = false;
                        status("无法读取此窗口，请重新打开详情");
                        return;
                    }
                    if (image == null) {
                        inFlight = false;
                        status("此窗口没有可识别图像");
                        return;
                    }
                    recognize(image, kind, token);
                }

                @Override public void onFailure(int errorCode) {
                    inFlight = false;
                    if (!stillOnPage(token)) return;
                    status("系统不允许读取此窗口，请检查辅助功能权限或手动记账");
                    Log.i(TAG, "window_capture_failed; code=" + errorCode);
                }
            });
        } catch (RuntimeException error) {
            inFlight = false;
            status("窗口识别权限不可用，请在系统设置中检查服务");
        }
    }

    private void recognize(Bitmap image, String kind, int token) {
        try {
            if (recognizer == null) recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            recognizer.process(InputImage.fromBitmap(image, 0))
                    .addOnSuccessListener(text -> {
                        if (!hasScreenshotConsent(this) || !stillOnPage(token)) return;
                        List<WeChatReceiptParser.Line> lines = new ArrayList<>();
                        for (Text.TextBlock block : text.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                Rect rect = line.getBoundingBox();
                                if (rect != null) lines.add(new WeChatReceiptParser.Line(line.getText(),
                                        rect.left, rect.top, rect.width(), rect.height()));
                            }
                        }
                        WeChatReceiptParser.Receipt receipt = WeChatReceiptParser.parse(lines, kind, System.currentTimeMillis());
                        Log.i(TAG, "local_ocr_complete; lines=" + lines.size() + "; parsed=" + (receipt != null));
                        if (receipt == null) Log.i(TAG, WeChatReceiptParser.diagnostics(lines));
                        if (receipt != null && WeChatReceiptParser.TRANSFER.equals(kind))
                            Log.i(TAG, "transfer_time_found=" + (receipt.occurredAt > 0));
                        if (receipt != null) offer(receipt, "local_ocr");
                        else status("未识别到金额明确的已完成收付款，请手动核对");
                    })
                    .addOnFailureListener(error -> {
                        if (stillOnPage(token)) status("本地文字识别失败，请重新打开详情或手动记账");
                    })
                    .addOnCompleteListener(task -> {
                        image.recycle();
                        inFlight = false;
                        if (!destroyed && !handledVisit && attempts < 3 && pageKind != null) scheduleScan(2000);
                    });
        } catch (RuntimeException error) {
            image.recycle();
            inFlight = false;
            status("本地文字识别不可用，请手动记账");
        }
    }

    private void offer(WeChatReceiptParser.Receipt receipt, String source) {
        if (destroyed || db == null) return;
        try {
            boolean added = WeChatReceiptStore.offer(db, receipt);
            handledVisit = true;
            status(added ? "已识别，回到账本核对金额、方向和日期后入账"
                    : "相同页面内容已识别过；若是另一笔，请手动记账");
            Log.i(TAG, "receipt_proposal; source=" + source + "; added=" + added);
            sendBroadcast(new Intent(MainActivity.ACTION_BILLS_CHANGED).setPackage(getPackageName()));
        } catch (RuntimeException error) {
            status("识别结果未能保存，请重新打开详情");
        }
    }

    private void status(String text) {
        if (!destroyed) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(STATUS, text).apply();
    }

    @Override public void onInterrupt() {
        generation++;
        pageKind = null;
        handler.removeCallbacks(scan);
        scanPending = false;
    }

    @Override public void onDestroy() {
        destroyed = true;
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.close();
        if (db != null) db.close();
        super.onDestroy();
    }
}
