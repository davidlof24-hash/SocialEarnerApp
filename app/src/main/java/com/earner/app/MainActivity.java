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
import android.webkit.WebResourceRequest;
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
    private Handler loginCheckHandler = new Handler(Looper.getMainLooper());
    private Runnable loginCheckRunnable;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("FB_ACCOUNTS_VAULT", MODE_PRIVATE);

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ১. ব্যাকগ্রাউন্ড অটো-ফলো WebView (ফুল স্ক্রিন সাইজ, কিন্তু ড্যাশবোর্ডের পেছনে লুকানো)
        hiddenWebView = new WebView(this);
        setupWebView(hiddenWebView);
        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true; // fb:// বা intent:// অ্যাপে চলে যাওয়া আটকাবে
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isFollowingTask && !url.equals("about:blank")) {
                    isFollowingTask = false;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        String jsCode = "(function() {" +
                                "if (document.querySelector('input[name=\"email\"], input[name=\"pass\"]')) return 'LOGGED_OUT';" +
                                "var already = document.querySelector('[aria-label=\"Following\"], [aria-label=\"ফলো করছেন\"], [aria-label=\"Liked\"], [aria-label=\"লাইক করেছেন\"]');" +
                                "if (already) return 'ALREADY';" +
                                "var els = document.querySelectorAll('div[role=\"button\"], a[role=\"button\"], button, div[tabindex=\"0\"], span');" +
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
                                "return 'NOT_FOUND_' + (document.title ? document.title.substring(0, 20) : 'EMPTY');" +
                                "})();";
                        hiddenWebView.evaluateJavascript(jsCode, result -> {
                            String cleanResult = result != null ? result.replace("\"", "") : "ERROR";
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                hiddenWebView.loadUrl("about:blank");
                                mainWebView.evaluateJavascript(
                                        "if(window.onNativeFollowDone) window.onNativeFollowDone(" + currentOrderId + ", '" + currentFbUid + "', '" + cleanResult + "');",
                                        null);
                            }, 3000);
                        });
                    }, 5500);
                }
            }
        });
        rootLayout.addView(hiddenWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ২. মেইন ড্যাশবোর্ড WebView (এটি hiddenWebView-এর উপরে থেকে সব ঢেকে রাখবে)
        mainWebView = new WebView(this);
        setupWebView(mainWebView);
        mainWebView.setBackgroundColor(Color.parseColor("#0b1329"));
        mainWebView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        mainWebView.setWebViewClient(new WebViewClient());
        rootLayout.addView(mainWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ৩. ফেসবুক লগইন পপ-আপ কনটেইনার
        fbLoginContainer = new LinearLayout(this);
        fbLoginContainer.setOrientation(LinearLayout.VERTICAL);
        fbLoginContainer.setBackgroundColor(Color.WHITE);
        fbLoginContainer.setVisibility(View.GONE);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#1877F2"));
        topBar.setPadding(15, 15, 15, 15);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ফেসবুক লগইন");
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
                loginCheckHandler.removeCallbacksAndMessages(null);
                saveFbCookie(uid, cookies);
                final String finalUid = uid;
                runOnUiThread(() -> {
                    fbLoginContainer.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "আইডি (" + finalUid + ") সেভ হয়েছে!", Toast.LENGTH_SHORT).show();
                    mainWebView.evaluateJavascript("if(window.onNativeFbAdded) window.onNativeFbAdded('" + finalUid + "');", null);
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
                    startLoginPolling();
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
                            cm.setCookie("https://facebook.com", p + "; Domain=.facebook.com; Path=/");
                            cm.setCookie("https://m.facebook.com", p + "; Domain=.facebook.com; Path=/");
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
            loginCheckHandler.removeCallbacksAndMessages(null);
            fbLoginContainer.setVisibility(View.GONE);
        } else if (mainWebView.canGoBack()) {
            mainWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
