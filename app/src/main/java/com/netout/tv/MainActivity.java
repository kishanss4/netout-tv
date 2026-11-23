package com.netout.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.net.Uri;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainActivity extends Activity {

    private static final String START_URL = "https://netout.pages.dev/home";

    private WebView webView;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private String navScript = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersive();

        root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(true);            // so we can deny popups
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        navScript = readAsset("tv_nav.js");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        // D-pad / remote focus support
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.requestFocus(View.FOCUS_DOWN);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (AdBlocker.isBlockedUrl(u.toString(), u.getHost())) {
                    return AdBlocker.empty();
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (!scheme.equals("http") && !scheme.equals("https")) return true; // intent://, market://
                if (AdBlocker.isBlockedUrl(u.toString(), u.getHost())) return true;
                // block automatic (no remote press) jumps to a different site = ad redirects
                if (r.isForMainFrame() && !r.hasGesture() && v.getUrl() != null) {
                    String cur = Uri.parse(v.getUrl()).getHost();
                    if (cur != null && u.getHost() != null && !sameSite(cur, u.getHost())) return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                v.evaluateJavascript(navScript, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                v.evaluateJavascript(navScript, null);
                CookieManager.getInstance().flush();
                v.requestFocus(View.FOCUS_DOWN);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                enterImmersive();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                customViewCallback = null;
                webView.setVisibility(View.VISIBLE);
                webView.requestFocus();
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                return false; // deny all popups / new windows
            }

            @Override
            public void onProgressChanged(WebView v, int progress) {
                if (progress > 30) v.evaluateJavascript(navScript, null);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources()); // e.g. protected media (DRM) ID
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.getWebChromeClient().onHideCustomView();
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        if (customView != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    webView.evaluateJavascript("window.__tvSeek&&__tvSeek(10)", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    webView.evaluateJavascript("window.__tvSeek&&__tvSeek(-10)", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    webView.evaluateJavascript("window.__tvToggle&&__tvToggle()", null);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private static boolean sameSite(String a, String b) {
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a)
                || root(a).equals(root(b));
    }

    private static String root(String h) {
        String[] p = h.split("\\.");
        return p.length < 2 ? h : p[p.length - 2] + "." + p[p.length - 1];
    }

    private String readAsset(String name) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open(name), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        webView.saveState(out);
    }

    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onPause() { super.onPause(); webView.onPause(); CookieManager.getInstance().flush(); }

    @Override
    protected void onDestroy() {
        root.removeAllViews();
        webView.destroy();
        super.onDestroy();
    }
}
