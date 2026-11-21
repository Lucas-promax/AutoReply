package com.autoreply.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://autoreply.invalid";
    static final String WECHAT = "com.tencent.mm";
    private static final AtomicReference<String> pendingCapture = new AtomicReference<>();

    private final AtomicReference<String> pendingImport = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebView browser;
    private ReplyApi api;

    static void openCapture(WeChatAccessibilityService service, JSONObject capture) {
        pendingCapture.set(capture.toString());
        service.startActivity(new Intent(service, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new ReplyApi(getSharedPreferences("settings", MODE_PRIVATE), getAssets());
        receiveImport(getIntent());

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(0xffedf3ef);
        browser = new WebView(this);
        browser.setBackgroundColor(0xffedf3ef);
        if (Build.VERSION.SDK_INT >= 35) {
            content.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                int types = android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout()
                    | android.view.WindowInsets.Type.ime();
                android.graphics.Insets insets = windowInsets.getInsets(types);
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return android.view.WindowInsets.CONSUMED;
            });
        }
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setAllowFileAccess(false);
        browser.getSettings().setAllowContentAccess(false);
        browser.addJavascriptInterface(new Bridge(), "AutoReplyNative");
        browser.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String path = uri.getPath();
                String type = assetType(path);
                if ("https".equals(uri.getScheme()) && "autoreply.invalid".equals(uri.getAuthority())
                    && type != null) {
                    String filename = path.equals("/") ? "index.html" : path.substring(1);
                    try {
                        return new WebResourceResponse(type, "UTF-8", 200, "OK",
                            Collections.singletonMap("Content-Security-Policy",
                                "default-src 'none'; script-src 'self'; style-src 'self'; form-action 'none'; frame-ancestors 'none'"),
                            getAssets().open("web/" + filename));
                    } catch (IOException ignored) {
                        // Resource is missing from the APK.
                    }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        });
        content.addView(browser);
        setContentView(content);
        browser.loadUrl(ORIGIN + "/");
    }

    private static String assetType(String path) {
        if (path == null) return null;
        switch (path) {
            case "/": return "text/html";
            case "/style.css":
            case "/android.css": return "text/css";
            case "/app.js":
            case "/android.js": return "text/javascript";
            default: return null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        receiveImport(intent);
        browser.evaluateJavascript("window.autoReplyImportAvailable?.()", null);
    }

    private void receiveImport(Intent intent) {
        String capture = pendingCapture.getAndSet(null);
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            try {
                CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (shared != null && shared.length() > 0) {
                    pendingImport.set(new JSONObject()
                        .put("text", shared.subSequence(0, Math.min(shared.length(), 32000)).toString())
                        .toString());
                    return;
                }
            } catch (JSONException | RuntimeException ignored) {
                // A malformed share intent does not change the current conversation.
            }
        }
        if (capture != null) pendingImport.set(capture);
    }

    @Override
    public void onBackPressed() {
        browser.evaluateJavascript("window.autoReplyBack?.() === true", handled -> {
            if (!"true".equals(handled)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        browser.removeJavascriptInterface("AutoReplyNative");
        browser.destroy();
        super.onDestroy();
    }

    private final class Bridge {
        @JavascriptInterface
        public String takeImport() {
            return pendingImport.getAndSet(null);
        }

        @JavascriptInterface
        public void request(String id, String path, String payload) {
            if (worker.isShutdown()) return;
            worker.execute(() -> {
                JSONObject response = api.request(path, payload);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    browser.evaluateJavascript("window.autoReplyResponse("
                        + JSONObject.quote(id) + "," + JSONObject.quote(response.toString()) + ")", null);
                });
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("AutoReply", text)));
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }

        @JavascriptInterface
        public void openWeChat() {
            runOnUiThread(() -> {
                Intent intent = getPackageManager().getLaunchIntentForPackage(WECHAT);
                if (intent == null) {
                    Toast.makeText(MainActivity.this, "手机上未找到微信。", Toast.LENGTH_SHORT).show();
                } else {
                    startActivity(intent);
                }
            });
        }
    }
}
