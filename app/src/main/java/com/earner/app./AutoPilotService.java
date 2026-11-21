package com.earner.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoPilotService extends Service {
    private static final String CHANNEL_ID = "F2fearnAutoPilotChannel";
    private static final String SERVER_URL = "https://devid01895.pythonanywhere.com";

    private WebView serviceWebView;
    private String defaultMobileUA = "";

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;

    private boolean isBusy = false;
    private long busyStartTime = 0;
    private boolean isFollowingTask = false;
    private int currentOrderId = 0;
    private String currentFbUid = "";
    private int currentWorkerId = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("FB_ACCOUNTS_VAULT", MODE_PRIVATE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "F2fearn::AutoPilotLock");
            wakeLock.acquire();
        }

        serviceWebView = new WebView(getApplicationContext());
        serviceWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        WebSettings ws = serviceWebView.getSettings();
        defaultMobileUA = ws.getUserAgentString();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(serviceWebView, true);

        serviceWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !url.startsWith("http://") && !url.startsWith("https://");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isFollowingTask && !url.equals("about:blank")) {
                    mainHandler.removeCallbacks(executeFollowJsRunnable);
                    mainHandler.postDelayed(executeFollowJsRunnable, 7000);
                }
            }
        });

        setLiveStatus("⚡ ২৪/৭ ব্যাকগ্রাউন্ড অটো-পাইলট ফলো কাজের জন্য প্রস্তুত!");
        startAutoLoop();
    }

    private void setLiveStatus(String text) {
        prefs.edit().putString("live_status", text).apply();
        mainHandler.post(() -> updateNotification(text));
    }

    private final Runnable executeFollowJsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFollowingTask) return;
            isFollowingTask = false;

            String jsFollow = "(function() {" +
                    "if (document.querySelector('input[name=\"email\"], input[name=\"pass\"]')) return 'LOGGED_OUT';" +
                    "var already = document.querySelector('[aria-label=\"Following\"], [aria-label=\"ফলো করছেন\"], [aria-label=\"ফলো করা হচ্ছে\"], [aria-label=\"Cancel request\"], [aria-label=\"অনুরোধ বাতিল করুন\"]');" +
                    "if (already) return 'ALREADY_FOLLOWING';" +
                    "var els = document.querySelectorAll('div[role=\"button\"], a[role=\"button\"], button, div[tabindex=\"0\"], span, a');" +
                    "for (var i = 0; i < els.length; i++) {" +
                    "  var t = (els[i].innerText || '').trim().toLowerCase();" +
                    "  var a = (els[i].getAttribute('aria-label') || '').trim().toLowerCase();" +
                    "  if (t === 'follow' || t === 'ফলো করুন' || a === 'follow' || a === 'ফলো করুন' || " +
                    "      t === 'add friend' || a === 'add friend' || t === 'বন্ধু হিসেবে যোগ করুন') {" +
                    "    els[i].scrollIntoView({block:'center'}); els[i].click(); return 'CLICKED';" +
                    "  }" +
                    "}" +
                    "return 'NOT_FOUND';" +
                    "})();";

            serviceWebView.evaluateJavascript(jsFollow, result -> {
                String cleanResult = result != null ? result.replace("\"", "") : "NOT_FOUND";
                mainHandler.postDelayed(() -> {
                    serviceWebView.loadUrl("about:blank");
                    reportTaskResultToServer(cleanResult);
                }, 3000);
            });
        }
    };

    private void reportTaskResultToServer(String status) {
        networkExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("worker_id", currentWorkerId);
                body.put("order_id", currentOrderId);
                body.put("fb_uid", currentFbUid);

                if (status.equals("CLICKED")) {
                    httpPost(SERVER_URL + "/api/complete-task", body.toString());
                    setLiveStatus("✅ আইডি (" + currentFbUid + ") ফলো সম্পন্ন করেছে!");
                } else {
                    body.put("reason", status);
                    httpPost(SERVER_URL + "/api/fail-task", body.toString());
                    setLiveStatus("⏭️ অর্ডার #" + currentOrderId + " (" + status + ") - স্কিপ করা হয়েছে");
                }
            } catch (Exception e) {
                setLiveStatus("⚠️ সার্ভার আপডেট এরর");
            }
            isBusy = false;
        });
    }

    private void startAutoLoop() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkServerForTasks();
                mainHandler.postDelayed(this, 15000);
            }
        }, 3000);
    }

    private void checkServerForTasks() {
        if (prefs.getBoolean("is_user_logging_in", false)) return;

        int workerId = prefs.getInt("active_worker_id", 0);
        if (workerId <= 0) {
            setLiveStatus("ℹ️ ইউজার আইডি সক্রিয় করতে অ্যাপে একবার লগইন করুন।");
            return;
        }

        if (isBusy) {
            if (System.currentTimeMillis() - busyStartTime > 55000) {
                isBusy = false;
                isFollowingTask = false;
            } else {
                return;
            }
        }

        networkExecutor.execute(() -> {
            try {
                String taskRes = httpGet(SERVER_URL + "/api/worker/poll-task?worker_id=" + workerId);
                JSONObject taskJson = new JSONObject(taskRes);

                if (taskJson.optBoolean("has_task", false)) {
                    int orderId = taskJson.getInt("order_id");
                    String targetUrl = taskJson.getString("target_link");
                    String uid = taskJson.getString("fb_uid");
                    String b64Cookie = taskJson.optString("fb_cookie", "");

                    String cookieStr = getLocalOrDecodeCookie(uid, b64Cookie);
                    currentWorkerId = workerId;
                    currentOrderId = orderId;
                    currentFbUid = uid;

                    if (cookieStr.isEmpty()) {
                        reportTaskResultToServer("NO_COOKIE");
                        return;
                    }

                    isBusy = true;
                    busyStartTime = System.currentTimeMillis();
                    setLiveStatus("🚀 আইডি (" + uid + ") দিয়ে ফলো চলছে...");
                    mainHandler.post(() -> runBackgroundWebView(targetUrl, cookieStr));
                }
            } catch (Exception e) {
                setLiveStatus("⚠️ নেটওয়ার্ক চেকিং...");
            }
        });
    }

    private void runBackgroundWebView(String targetUrl, String cookieStr) {
        serviceWebView.resumeTimers();
        serviceWebView.getSettings().setUserAgentString(defaultMobileUA);
        serviceWebView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        );
        serviceWebView.layout(0, 0, 1080, 1920);

        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(unused -> {
            for (String pair : cookieStr.split(";")) {
                String p = pair.trim();
                if (!p.isEmpty()) {
                    cm.setCookie("https://facebook.com", p + "; Domain=.facebook.com; Path=/");
                    cm.setCookie("https://www.facebook.com", p + "; Domain=.facebook.com; Path=/");
                    cm.setCookie("https://m.facebook.com", p + "; Domain=.facebook.com; Path=/");
                }
            }
            cm.flush();
            String finalUrl = targetUrl.replace("www.facebook.com", "m.facebook.com").replace("web.facebook.com", "m.facebook.com");
            isFollowingTask = true;
            serviceWebView.loadUrl(finalUrl);
        });
    }

    private String getLocalOrDecodeCookie(String uid, String b64) {
        try {
            String jsonStr = prefs.getString("cookies_map", "{}");
            JSONObject obj = new JSONObject(jsonStr);
            String c = obj.optString(uid, "");
            if (!c.isEmpty()) return c;
            if (b64 != null && !b64.isEmpty()) {
                return new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8");
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) sb.append(line);
        in.close();
        return sb.toString();
    }

    private void httpPost(String urlStr, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.close();
        conn.getResponseCode();
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("⚡ F2fearn Auto-Pilot")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(101, buildNotification(text));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = buildNotification("ব্যাকগ্রাউন্ডে অটো ফলো চলছে...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(101, notification);
        }
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "F2fearn Auto-Pilot", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
