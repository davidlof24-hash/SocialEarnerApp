package com.earner.app;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private WebView mainWebView;
    private WebView hiddenWebView;
    private WebView fbLoginWebView;
    private LinearLayout fbLoginContainer;

    private final String APP_URL = "https://devid01895.pythonanywhere.com/app";
    private boolean loginHandled = false;
    private boolean isFollowingTask = false;
    private int currentOrderId = 0;
    private String currentFbUid = "";

    private SharedPreferences prefs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("FB_ACCOUNTS_VAULT", MODE_PRIVATE);

        // মূল রুট লেআউট তৈরি
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ১. মেইন ড্যাশবোর্ড WebView
        mainWebView = new WebView(this);
        setupWebView(mainWebView);
        mainWebView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        mainWebView.setWebViewClient(new WebViewClient());
        rootLayout.addView(mainWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ২. ব্যাকগ্রাউন্ড অটো-ফলো WebView (অদৃশ্য)
        hiddenWebView = new WebView(this);
        setupWebView(hiddenWebView);
        hiddenWebView.setVisibility(View.INVISIBLE);
        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isFollowingTask) {
                    isFollowingTask = false;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        String jsCode = "(function() {" +
                                "var already = document.querySelector('[aria-label=\"Following\"], [aria-label=\"ফলো করছেন\"], [aria-label=\"Liked\"]');" +
                                "if (already) return 'ALREADY';" +
                                "var els = document.querySelectorAll('div[role=\"button\"], a[role=\"button\"], button, span');" +
                                "for (var i = 0; i < els.length; i++) {" +
                                "  var t = (els[i].innerText || '').trim();" +
                                "  var a = (els[i].getAttribute('aria-label') || '').trim();" +
                                "  if (t === 'Follow' || t === 'ফলো করুন' || a === 'Follow' || a === 'ফলো করুন' || t === 'Like' || a === 'Like' || t === 'লাইক' || a === 'লাইক') {" +
                                "    els[i].click(); return 'CLICKED';" +
                                "  }" +
                                "}" +
                                "return 'NOT_FOUND';" +
                                "})();";
                        hiddenWebView.evaluateJavascript(jsCode, result -> {
                            String cleanResult = result != null ? result.replace("\"", "") : "ERROR";
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                mainWebView.evaluateJavascript(
                                        "if(window.onNativeFollowDone) window.onNativeFollowDone(" + currentOrderId + ", '" + currentFbUid + "', '" + cleanResult + "');",
                                        null);
                            }, 2500);
                        });
                    }, 4000);
                }
            }
        });
        rootLayout.addView(hiddenWebView, new FrameLayout.LayoutParams(1, 1));

        // ৩. ফেসবুক লগইন পপ-আপ কনটেইনার
        fbLoginContainer = new LinearLayout(this);
        fbLoginContainer.setOrientation(LinearLayout.VERTICAL);
        fbLoginContainer.setBackgroundColor(Color.WHITE);
        fbLoginContainer.setVisibility(View.GONE);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#1877F2"));
        topBar.setPadding(20, 20, 20, 20);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ফেসবুক আইডি লগইন করুন");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button closeBtn = new Button(this);
        closeBtn.setText("বাতিল (Close)");
        closeBtn.setOnClickListener(v -> fbLoginContainer.setVisibility(View.GONE));

        topBar.addView(title);
        topBar.addView(closeBtn);
        fbLoginContainer.addView(topBar);

        fbLoginWebView = new WebView(this);
        setupWebView(fbLoginWebView);
        fbLoginWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (loginHandled) return;
                String cookies = CookieManager.getInstance().getCookie("https://m.facebook.com");
                if (cookies != null && cookies.contains("c_user=") && cookies.contains("xs=")) {
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
                        saveFbCookie(uid, cookies);
                        final String finalUid = uid;
                        runOnUiThread(() -> {
                            fbLoginContainer.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this, "ফেসবুক আইডি (" + finalUid + ") যুক্ত হয়েছে!", Toast.LENGTH_SHORT).show();
                            mainWebView.evaluateJavascript("if(window.onNativeFbAdded) window.onNativeFbAdded('" + finalUid + "');", null);
                        });
                    }
                }
            }
        });
        fbLoginContainer.addView(fbLoginWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootLayout.addView(fbLoginContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);
        mainWebView.loadUrl(APP_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView wv) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
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

    private String getFbCookie(String uid) {
        try {
            String jsonStr = prefs.getString("cookies_map", "{}");
            JSONObject obj = new JSONObject(jsonStr);
            return obj.optString(uid, "");
        } catch (Exception e) { return ""; }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void openFbLogin() {
            runOnUiThread(() -> {
                loginHandled = false;
                CookieManager.getInstance().removeAllCookies(unused -> {
                    CookieManager.getInstance().flush();
                    fbLoginContainer.setVisibility(View.VISIBLE);
                    fbLoginWebView.loadUrl("https://m.facebook.com");
                });
            });
        }

        @JavascriptInterface
        public void runFollowTask(int orderId, String targetUrl, String uid) {
            runOnUiThread(() -> {
                String savedCookie = getFbCookie(uid);
                if (savedCookie.isEmpty()) {
                    mainWebView.evaluateJavascript("if(window.onNativeFollowDone) window.onNativeFollowDone(" + orderId + ", '" + uid + "', 'NO_COOKIE');", null);
                    return;
                }
                currentOrderId = orderId;
                currentFbUid = uid;
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(unused -> {
                    for (String pair : savedCookie.split(";")) {
                        String p = pair.trim();
                        if (!p.isEmpty()) {
                            cm.setCookie("https://.facebook.com", p);
                            cm.setCookie("https://m.facebook.com", p);
                        }
                    }
                    cm.flush();
                    String mobileUrl = targetUrl.replace("www.facebook.com", "m.facebook.com").replace("web.facebook.com", "m.facebook.com");
                    isFollowingTask = true;
                    hiddenWebView.loadUrl(mobileUrl);
                });
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (fbLoginContainer.getVisibility() == View.VISIBLE) {
            fbLoginContainer.setVisibility(View.GONE);
        } else if (mainWebView.canGoBack()) {
            mainWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
