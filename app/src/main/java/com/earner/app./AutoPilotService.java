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
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
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
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;

    private boolean isBusy = false;
    private long busyStartTime = 0;
    private boolean isFollowingTask = false;
    private int currentOrderId = 0;
    private String currentFbUid = "";
    private String currentActionType = "Follow";
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

        // ব্যাকগ্রাউন্ডের নিজস্ব WebView তৈরি
        serviceWebView = new WebView(getApplicationContext());
        WebSettings ws = serviceWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
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
                    mainHandler.removeCallbacks(executeJsRunnable);
                    int delay = currentActionType.equalsIgnoreCase("View") ? 15000 : 5500;
                    mainHandler.postDelayed(executeJsRunnable, delay);
                }
            }
        });

        startAutoLoop();
    }

    private final Runnable executeJsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFollowingTask) return;
            isFollowingTask = false;

            if (currentActionType.equalsIgnoreCase("View")) {
                finishTaskOnServer("CLICKED");
                return;
            }

            String jsCode = "(function() {" +
                    "if (document.querySelector('input[name=\"email\"], input[name=\"pass\"]')) return 'LOGGED_OUT';" +
                    "var already = document.querySelector('[aria-label=\"Following\"], [aria-label=\"ফলো করছেন\"], [aria-label=\"Liked\"], [aria-label=\"লাইক করেছেন\"]');" +
                    "if (already) return 'ALREADY';" +
                    "var els = document.querySelectorAll('div[role=\"button\"], a[role=\"button\"], button, div[tabindex=\"0\"], span, a');" +
                    "for (var i = 0; i < els.length; i++) {" +
                    "  var t = (els[i].innerText || '').trim().toLowerCase();" +
                    "  var a = (els[i].getAttribute('aria-label') || '').trim().toLowerCase();" +
                    "  if (t === 'follow' || t === 'ফলো করুন' || a === 'follow' || a === 'ফলো করুন' || " +
                    "      t === 'like' || t === 'লাইক' || a === 'like' || a === 'লাইক' || " +
                    "      t === 'add friend' || a === 'add friend' || t === 'বন্ধু হিসেবে যোগ করুন') {" +
                    "    els[i].scrollIntoView();" +
                    "    els[i].click(); return 'CLICKED';" +
                    "  }" +
                    "}" +
                    "return 'NOT_FOUND';" +
                    "})();";

            serviceWebView.evaluateJavascript(jsCode, result -> {
                String cleanResult = result != null ? result.replace("\"", "") : "ERROR";
                mainHandler.postDelayed(() -> {
                    serviceWebView.loadUrl("about:blank");
                    finishTaskOnServer(cleanResult);
                }, 3000);
            });
        }
    };

    private void finishTaskOnServer(String status) {
        if (status.equals("CLICKED") || status.equals("ALREADY")) {
            networkExecutor.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("worker_id", currentWorkerId);
                    body.put("order_id", currentOrderId);
                    body.put("fb_uid", currentFbUid);
                    body.put("action_type", currentActionType);
                    httpPost(SERVER_URL + "/api/complete-task", body.toString());
                } catch (Exception ignored) {}
                isBusy = false;
            });
        } else {
            isBusy = false;
        }
    }

    private void startAutoLoop() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkServerForTasks();
                mainHandler.postDelayed(this, 12000); // প্রতি ১২ সেকেন্ড পর পর ব্যাকগ্রাউন্ডে চেক
            }
        }, 5000);
    }

    private void checkServerForTasks() {
        if (prefs.getBoolean("is_user_logging_in", false)) return;

        int workerId = prefs.getInt("active_worker_id", 0);
        if (workerId <= 0) return;

        if (isBusy) {
            if (System.currentTimeMillis() - busyStartTime > 35000) {
                isBusy = false;
            } else {
                return;
            }
        }

        networkExecutor.execute(() -> {
            try {
                String accRes = httpGet(SERVER_URL + "/api/my-fb-accounts?worker_id=" + workerId);
                JSONObject accJson = new JSONObject(accRes);
                JSONArray accounts = accJson.optJSONArray("accounts");
                if (accounts == null || accounts.length() == 0) return;

                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject acc = accounts.getJSONObject(i);
                    String uid = acc.getString("fb_uid");
                    String b64Cookie = acc.optString("fb_cookie", "");

                    String taskRes = httpGet(SERVER_URL + "/api/get-task?fb_uid=" + uid + "&worker_id=" + workerId);
                    JSONObject taskJson = new JSONObject(taskRes);

                    if (taskJson.optBoolean("has_task", false)) {
                        int orderId = taskJson.getInt("order_id");
                        String targetUrl = taskJson.getString("target_link");
                        String actionType = taskJson.optString("action_type", "Follow");

                        String cookieStr = getLocalOrDecodeCookie(uid, b64Cookie);
                        if (!cookieStr.isEmpty()) {
                            isBusy = true;
                            busyStartTime = System.currentTimeMillis();
                            currentWorkerId = workerId;
                            currentOrderId = orderId;
                            currentFbUid = uid;
                            currentActionType = actionType;

                            mainHandler.post(() -> runBackgroundWebView(targetUrl, cookieStr));
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void runBackgroundWebView(String targetUrl, String cookieStr) {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(unused -> {
            for (String pair : cookieStr.split(";")) {
                String p = pair.trim();
                if (!p.isEmpty()) {
                    cm.setCookie("https://facebook.com", p + "; Domain=.facebook.com; Path=/");
                    cm.setCookie("https://m.facebook.com", p + "; Domain=.facebook.com; Path=/");
                }
            }
            cm.flush();
            String mobileUrl = targetUrl.replace("www.facebook.com", "m.facebook.com").replace("web.facebook.com", "m.facebook.com");
            isFollowingTask = true;
            serviceWebView.loadUrl(mobileUrl);
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setContentTitle("⚡ F2fearn Auto-Pilot চালু আছে")
                .setContentText("অ্যাপ বন্ধ থাকলেও ব্যাকগ্রাউন্ডে অটো ইনকাম চলছে...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

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
