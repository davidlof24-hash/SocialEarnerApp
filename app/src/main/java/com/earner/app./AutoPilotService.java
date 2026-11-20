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
    private String defaultMobileUA = "";
    private final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

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
    private String currentCommentText = "";
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
                    mainHandler.removeCallbacks(executeJsRunnable);
                    int delay = currentActionType.equalsIgnoreCase("View") ? 18000 : 7000;
                    mainHandler.postDelayed(executeJsRunnable, delay);
                }
            }
        });

        setLiveStatus("⚡ ২৪/৭ ব্যাকগ্রাউন্ড অটো-পাইলট কাজের জন্য প্রস্তুত!");
        startAutoLoop();
    }

    private void setLiveStatus(String text) {
        prefs.edit().putString("live_status", text).apply();
        mainHandler.post(() -> updateNotification(text));
    }

    private final Runnable executeJsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFollowingTask) return;
            isFollowingTask = false;

            // ১. ভিডিও ভিউ (View)
            if (currentActionType.equalsIgnoreCase("View")) {
                serviceWebView.evaluateJavascript("var v=document.querySelector('video'); if(v) v.play(); 'VIEWED';", r -> {
                    mainHandler.postDelayed(() -> {
                        serviceWebView.loadUrl("about:blank");
                        finishTaskOnServer("CLICKED");
                    }, 3000);
                });
                return;
            }

            // ২. ফলো (Mobile Web - এটি ১০০% সফল)
            if (currentActionType.equalsIgnoreCase("Follow")) {
                String jsFollow = "(function() {" +
                        "if (document.querySelector('input[name=\"email\"], input[name=\"pass\"]')) return 'LOGGED_OUT';" +
                        "var already = document.querySelector('[aria-label=\"Following\"], [aria-label=\"ফলো করছেন\"], [aria-label=\"Cancel request\"]');" +
                        "if (already) return 'ALREADY_FOLLOWING';" +
                        "var els = document.querySelectorAll('div[role=\"button\"], a[role=\"button\"], button, div[tabindex=\"0\"], span, a');" +
                        "for (var i = 0; i < els.length; i++) {" +
                        "  var t = (els[i].innerText || '').trim().toLowerCase();" +
                        "  var a = (els[i].getAttribute('aria-label') || '').trim().toLowerCase();" +
                        "  if (t === 'follow' || t === 'ফলো করুন' || a === 'follow' || a === 'ফলো করুন' || " +
                        "      t === 'add friend' || a === 'add friend' || t === 'বন্ধু হিসেবে যোগ করুন') {" +
                        "    els[i].scrollIntoView(); els[i].click(); return 'CLICKED';" +
                        "  }" +
                        "}" +
                        "return 'NOT_FOUND';" +
                        "})();";
                runSingleStepJs(jsFollow);
                return;
            }

            // ৩. সাধারণ লাইক (Desktop Mode - www.facebook.com)
            if (currentActionType.equalsIgnoreCase("Like")) {
                serviceWebView.evaluateJavascript("window.scrollBy(0, 350);", unused -> {
                    mainHandler.postDelayed(() -> {
                        String jsLike = "(function() {" +
                                "if (document.querySelector('input[name=\"email\"], input[name=\"pass\"]')) return 'LOGGED_OUT';" +
                                "var unlikes = document.querySelectorAll('[aria-label=\"Remove Like\"], [aria-label=\"Unlike\"], [aria-label=\"লাইক তুলে নিন\"]');" +
                                "if (unlikes.length > 0) return 'ALREADY_LIKED';" +
                                "var btns = document.querySelectorAll('div[role=\"button\"], span[role=\"button\"]');" +
                                "for (var i = 0; i < btns.length; i++) {" +
                                "  var a = (btns[i].getAttribute('aria-label') || '').trim();" +
                                "  var t = (btns[i].innerText || '').trim();" +
                                "  if (a === 'Like' || a === 'লাইক' || t === 'Like' || t === 'লাইক') {" +
                                "    btns[i].scrollIntoView({block:'center'});" +
                                "    btns[i].click(); return 'CLICKED';" +
                                "  }" +
                                "}" +
                                "return 'NOT_FOUND_' + (document.title ? document.title.substring(0, 12) : 'EMPTY');" +
                                "})();";
                        runSingleStepJs(jsLike);
                    }, 1500);
                });
                return;
            }

            // ৪. কাস্টম রিঅ্যাকশন (Love, Haha, Wow, Sad, Angry - Desktop Mode Hover)
            if (currentActionType.equalsIgnoreCase("Love") || currentActionType.equalsIgnoreCase("Haha") ||
                currentActionType.equalsIgnoreCase("Wow") || currentActionType.equalsIgnoreCase("Sad") ||
                currentActionType.equalsIgnoreCase("Angry")) {

                serviceWebView.evaluateJavascript("window.scrollBy(0, 350);", unused -> {
                    mainHandler.postDelayed(() -> {
                        String hoverJs = "(function() {" +
                                "if (document.querySelector('input[name=\"email\"], input[name=\"pass\"]')) return 'LOGGED_OUT';" +
                                "var btns = document.querySelectorAll('div[role=\"button\"], span[role=\"button\"]');" +
                                "for (var i = 0; i < btns.length; i++) {" +
                                "  var a = (btns[i].getAttribute('aria-label') || '').trim();" +
                                "  var t = (btns[i].innerText || '').trim();" +
                                "  if (a === 'Like' || a === 'লাইক' || t === 'Like' || t === 'লাইক' || a === 'Remove Like' || a === 'লাইক তুলে নিন') {" +
                                "    btns[i].scrollIntoView({block:'center'});" +
                                "    btns[i].dispatchEvent(new MouseEvent('pointerover', {bubbles:true, cancelable:true}));" +
                                "    btns[i].dispatchEvent(new MouseEvent('mouseover', {bubbles:true, cancelable:true}));" +
                                "    btns[i].dispatchEvent(new MouseEvent('mouseenter', {bubbles:true, cancelable:true}));" +
                                "    return 'HOVERED';" +
                                "  }" +
                                "}" +
                                "return 'LIKE_NOT_FOUND';" +
                                "})();";

                        serviceWebView.evaluateJavascript(hoverJs, res -> {
                            String cleanH = res != null ? res.replace("\"", "") : "";
                            if (!cleanH.equals("HOVERED")) {
                                serviceWebView.loadUrl("about:blank");
                                finishTaskOnServer(cleanH);
                                return;
                            }
                            mainHandler.postDelayed(() -> {
                                String reactTarget = currentActionType.toLowerCase();
                                String banglaTarget = reactTarget;
                                if (reactTarget.equals("love")) banglaTarget = "ভালোবাসা";
                                else if (reactTarget.equals("haha")) banglaTarget = "হাহা";
                                else if (reactTarget.equals("wow")) banglaTarget = "ওয়াও";
                                else if (reactTarget.equals("sad")) banglaTarget = "দুঃখিত";
                                else if (reactTarget.equals("angry")) banglaTarget = "রাগ";

                                String clickReactJs = "(function() {" +
                                        "var els = document.querySelectorAll('div[role=\"button\"][aria-label], [aria-label]');" +
                                        "for (var i = 0; i < els.length; i++) {" +
                                        "  var a = (els[i].getAttribute('aria-label') || '').trim().toLowerCase();" +
                                        "  if (a === '" + reactTarget + "' || a === '" + banglaTarget + "') {" +
                                        "    els[i].click(); return 'CLICKED';" +
                                        "  }" +
                                        "}" +
                                        "return 'TRAY_NOT_OPEN';" +
                                        "})();";
                                runSingleStepJs(clickReactJs);
                            }, 1600);
                        });
                    }, 1500);
                });
                return;
            }

            // ৫. পোস্ট শেয়ার (Desktop Mode)
            if (currentActionType.equalsIgnoreCase("Share")) {
                serviceWebView.evaluateJavascript("window.scrollBy(0, 350);", unused -> {
                    mainHandler.postDelayed(() -> {
                        String openShareJs = "(function() {" +
                                "var btns = document.querySelectorAll('div[role=\"button\"]');" +
                                "for (var i = 0; i < btns.length; i++) {" +
                                "  var a = (btns[i].getAttribute('aria-label') || '').trim();" +
                                "  var t = (btns[i].innerText || '').trim();" +
                                "  if (a === 'Send this to friends or post it on your profile.' || a === 'Share' || a === 'শেয়ার করুন' || a === 'শেয়ার করুন' || t === 'Share' || t === 'শেয়ার' || t === 'শেয়ার') {" +
                                "    btns[i].scrollIntoView({block:'center'}); btns[i].click(); return 'OPENED';" +
                                "  }" +
                                "}" +
                                "return 'SHARE_NOT_FOUND';" +
                                "})();";

                        serviceWebView.evaluateJavascript(openShareJs, res -> {
                            String cleanS = res != null ? res.replace("\"", "") : "";
                            if (!cleanS.equals("OPENED")) {
                                serviceWebView.loadUrl("about:blank");
                                finishTaskOnServer(cleanS);
                                return;
                            }
                            mainHandler.postDelayed(() -> {
                                String clickShareNowJs = "(function() {" +
                                        "var els = document.querySelectorAll('div[role=\"menuitem\"], div[role=\"button\"], span');" +
                                        "for (var i = 0; i < els.length; i++) {" +
                                        "  var t = (els[i].innerText || '').trim();" +
                                        "  if (t.includes('Share now') || t.includes('এখনই শেয়ার') || t.includes('এখনই শেয়ার') || t.includes('Share to feed')) {" +
                                        "    els[i].click(); return 'CLICKED';" +
                                        "  }" +
                                        "}" +
                                        "return 'SHARE_MENU_NOT_FOUND';" +
                                        "})();";
                                runSingleStepJs(clickShareNowJs);
                            }, 2500);
                        });
                    }, 1500);
                });
                return;
            }

            // ৬. পোস্ট কমেন্ট (Desktop Mode)
            if (currentActionType.equalsIgnoreCase("Comment")) {
                String safeComment = currentCommentText.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
                if (safeComment.isEmpty()) safeComment = "Nice!";
                final String finalSafeComment = safeComment;
                serviceWebView.evaluateJavascript("window.scrollBy(0, 450);", unused -> {
                    mainHandler.postDelayed(() -> {
                        String jsComment = "(function() {" +
                                "var box = document.querySelector('div[aria-label=\"Write a comment\"], div[aria-label=\"একটি মন্তব্য লিখুন\"], div[aria-label=\"Write a public comment…\"], div[role=\"textbox\"]');" +
                                "if (box) {" +
                                "  box.scrollIntoView({block:'center'}); box.focus();" +
                                "  document.execCommand('insertText', false, '" + finalSafeComment + "');" +
                                "  var sendBtn = document.querySelector('div[aria-label=\"Comment\"], div[aria-label=\"মন্তব্য\"], div[aria-label=\"Submit\"]');" +
                                "  if (sendBtn) { setTimeout(function(){ sendBtn.click(); }, 500); return 'CLICKED'; }" +
                                "  box.dispatchEvent(new KeyboardEvent('keydown', {'key':'Enter', 'keyCode':13, 'which':13, bubbles:true}));" +
                                "  return 'CLICKED';" +
                                "}" +
                                "return 'BOX_NOT_FOUND';" +
                                "})();";
                        runSingleStepJs(jsComment);
                    }, 1500);
                });
            }
        }
    };

    private void runSingleStepJs(String js) {
        serviceWebView.evaluateJavascript(js, result -> {
            String cleanResult = result != null ? result.replace("\"", "") : "ERROR";
            mainHandler.postDelayed(() -> {
                serviceWebView.loadUrl("about:blank");
                finishTaskOnServer(cleanResult);
            }, 3000);
        });
    }

    private void finishTaskOnServer(String status) {
        if (status.equals("CLICKED")) {
            networkExecutor.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("worker_id", currentWorkerId);
                    body.put("order_id", currentOrderId);
                    body.put("fb_uid", currentFbUid);
                    body.put("action_type", currentActionType);
                    httpPost(SERVER_URL + "/api/complete-task", body.toString());
                    setLiveStatus("✅ আইডি (" + currentFbUid + ") [" + currentActionType + "] সম্পন্ন করেছে!");
                } catch (Exception e) {
                    setLiveStatus("⚠️ সার্ভার আপডেট এরর");
                }
                isBusy = false;
            });
        } else {
            setLiveStatus("⚠️ অর্ডার #" + currentOrderId + " (" + currentActionType + "): " + status);
            isBusy = false;
        }
    }

    private void startAutoLoop() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkServerForTasks();
                mainHandler.postDelayed(this, 10000);
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
            if (System.currentTimeMillis() - busyStartTime > 45000) {
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
                        String commentText = taskJson.optString("comment_text", "");

                        String cookieStr = getLocalOrDecodeCookie(uid, b64Cookie);
                        if (!cookieStr.isEmpty()) {
                            isBusy = true;
                            busyStartTime = System.currentTimeMillis();
                            currentWorkerId = workerId;
                            currentOrderId = orderId;
                            currentFbUid = uid;
                            currentActionType = actionType;
                            currentCommentText = commentText;

                            setLiveStatus("🚀 আইডি (" + uid + ") দিয়ে [" + actionType + "] চলছে...");
                            mainHandler.post(() -> runBackgroundWebView(targetUrl, cookieStr, actionType));
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                setLiveStatus("⚠️ নেটওয়ার্ক চেকিং...");
            }
        });
    }

    private void runBackgroundWebView(String targetUrl, String cookieStr, String actionType) {
        serviceWebView.resumeTimers();

        // ফলো বাদে অন্য সব কাজের জন্য ডেস্কটপ ভিউ ব্যবহার করা হবে (যাতে লাইক/রিঅ্যাক্ট বাটন মিস না হয়)
        boolean useDesktop = !actionType.equalsIgnoreCase("Follow");

        if (useDesktop) {
            serviceWebView.getSettings().setUserAgentString(DESKTOP_UA);
            serviceWebView.measure(
                    View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY)
            );
            serviceWebView.layout(0, 0, 1280, 900);
        } else {
            serviceWebView.getSettings().setUserAgentString(defaultMobileUA);
            serviceWebView.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            );
            serviceWebView.layout(0, 0, 1080, 1920);
        }

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
            String finalUrl;
            if (useDesktop) {
                finalUrl = targetUrl.replace("m.facebook.com", "www.facebook.com").replace("mbasic.facebook.com", "www.facebook.com");
            } else {
                finalUrl = targetUrl.replace("www.facebook.com", "m.facebook.com").replace("web.facebook.com", "m.facebook.com");
            }
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
        Notification notification = buildNotification("ব্যাকগ্রাউন্ডে অটো ইনকাম চলছে...");
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
