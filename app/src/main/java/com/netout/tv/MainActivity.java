package com.netout.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "NetoutTV";
    private static final String START_URL = "https://netout.pages.dev/home";
    private static final int DEBUG_PORT = 8080;

    private WebView webView;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private String navScript = "";
    private boolean injectedThisLoad = false;
    private DebugServer debugServer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> logHistory = Collections.synchronizedList(new ArrayList<>());

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

        WebView.setWebContentsDebuggingEnabled(true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        // Desktop/TV User-Agent without "Mobile" token
        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace(" Mobile", "") + " NetoutTV");
        webView.setInitialScale(0);
        webView.setBackgroundColor(Color.BLACK);
        webView.addJavascriptInterface(new TvBridge(), "TvBridge");
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        navScript = readAsset("tv_nav.js");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.requestFocus(View.FOCUS_DOWN);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                String host = r.getUrl().getHost();
                if (AdBlocker.isBlockedUrl(u, host)) {
                    return AdBlocker.empty();
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) return true;
                if (AdBlocker.isBlockedUrl(r.getUrl().toString(), r.getUrl().getHost())) return true;
                if (r.isForMainFrame() && !r.hasGesture() && v.getUrl() != null) {
                    android.net.Uri cur = android.net.Uri.parse(v.getUrl());
                    android.net.Uri target = r.getUrl();
                    if (cur.getHost() != null && target.getHost() != null && !sameSite(cur.getHost(), target.getHost())) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                injectedThisLoad = false;
                addLog("Page started: " + url);
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                super.onPageCommitVisible(v, url);
                injectNav();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                injectNav();
                CookieManager.getInstance().flush();
                v.requestFocus(View.FOCUS_DOWN);
                addLog("Page finished: " + url);
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
                return false;
            }

            @Override
            public void onProgressChanged(WebView v, int progress) {
                if (progress >= 15 && !injectedThisLoad) {
                    injectedThisLoad = true;
                    injectNav();
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                addLog("Console [" + msg.messageLevel() + "]: " + msg.message() + " (" + msg.sourceId() + ":" + msg.lineNumber() + ")");
                return super.onConsoleMessage(msg);
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }

        // Start Local Web Debugger on Port 8080
        startDebugServer();
    }

    private void injectNav() {
        if (!navScript.isEmpty()) {
            webView.evaluateJavascript(navScript, null);
        }
    }

    private void addLog(String message) {
        Log.d(TAG, message);
        while (logHistory.size() > 80) {
            logHistory.remove(0);
        }
        logHistory.add(System.currentTimeMillis() + " " + message);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }

        // Handle fullscreen video
        if (customView != null) {
            if (action == KeyEvent.ACTION_DOWN) {
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
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        webView.evaluateJavascript("window.__tvToggle&&__tvToggle()", null);
                        return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }

        // D-Pad remote navigation
        boolean isDpad = false;
        String jsKey = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:    jsKey = "ArrowUp";    isDpad = true; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  jsKey = "ArrowDown";  isDpad = true; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  jsKey = "ArrowLeft";  isDpad = true; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: jsKey = "ArrowRight"; isDpad = true; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                jsKey = "Enter"; isDpad = true; break;
        }

        if (isDpad) {
            if (action == KeyEvent.ACTION_DOWN) {
                boolean isRepeat = event.getRepeatCount() > 0;
                addLog("Key: " + jsKey + " repeat=" + isRepeat);
                String js = "window.__tvKey&&__tvKey('" + jsKey + "'," + isRepeat + ")";
                webView.evaluateJavascript(js, null);
            }
            return true;
        }

        return super.dispatchKeyEvent(event);
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
        return super.onKeyDown(keyCode, event);
    }

    public class TvBridge {
        @JavascriptInterface
        public void showKeyboard() {
            mainHandler.post(() -> {
                webView.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
            });
        }

        @JavascriptInterface
        public void hideKeyboard() {
            mainHandler.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
            });
        }

        @JavascriptInterface
        public void log(String msg) {
            addLog("[JS] " + msg);
        }
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
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open(name), StandardCharsets.UTF_8))) {
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
        if (debugServer != null) {
            debugServer.stop();
        }
        root.removeAllViews();
        webView.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------------
    // Embedded Local Web Debugger on port 8080 (Accessible via http://tv-ip:8080)
    // ------------------------------------------------------------------------
    private void startDebugServer() {
        try {
            debugServer = new DebugServer(DEBUG_PORT);
            debugServer.start();
            addLog("Debug server started on port " + DEBUG_PORT);
        } catch (Exception e) {
            addLog("Debug server failed: " + e.getMessage());
        }
    }

    private class DebugServer implements Runnable {
        private final int port;
        private ServerSocket serverSocket;
        private volatile boolean running = true;

        public DebugServer(int port) {
            this.port = port;
        }

        public void start() {
            new Thread(this, "NetoutDebugServer").start();
        }

        public void stop() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {}
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(port);
                while (running) {
                    Socket socket = serverSocket.accept();
                    handleClient(socket);
                }
            } catch (Exception e) {
                if (running) addLog("Server error: " + e.getMessage());
            }
        }

        private void handleClient(Socket socket) {
            new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                     OutputStream out = socket.getOutputStream();
                     PrintWriter pw = new PrintWriter(out)) {

                    String line = in.readLine();
                    if (line == null) return;

                    String[] parts = line.split(" ");
                    if (parts.length < 2) return;
                    String method = parts[0];
                    String path = parts[1];

                    if (path.startsWith("/key?k=")) {
                        String key = path.substring(7);
                        mainHandler.post(() -> {
                            if (key.equals("Back")) {
                                if (webView.canGoBack()) webView.goBack();
                            } else {
                                webView.evaluateJavascript("window.__tvKey&&__tvKey('" + key + "', false)", null);
                            }
                        });
                        sendResponse(pw, "200 OK", "text/plain", "OK: " + key);
                    } else if (path.startsWith("/eval?js=")) {
                        String encodedJs = path.substring(9);
                        String js = URLDecoder.decode(encodedJs, "UTF-8");
                        mainHandler.post(() -> webView.evaluateJavascript(js, res -> addLog("Eval result: " + res)));
                        sendResponse(pw, "200 OK", "text/plain", "Evaluated");
                    } else if (path.startsWith("/logs")) {
                        StringBuilder sb = new StringBuilder();
                        synchronized (logHistory) {
                            for (String l : logHistory) sb.append(l).append("\n");
                        }
                        sendResponse(pw, "200 OK", "text/plain", sb.toString());
                    } else if (path.equals("/reload")) {
                        mainHandler.post(() -> webView.reload());
                        sendResponse(pw, "200 OK", "text/plain", "Reloaded");
                    } else {
                        // Serve Web Remote HTML
                        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                                "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
                                "<title>Netout TV Remote & Debugger</title>" +
                                "<style>" +
                                "body{background:#111;color:#eee;font-family:sans-serif;margin:0;padding:20px;text-align:center;}" +
                                "h2{color:#ffd400;margin-bottom:15px;}" +
                                ".dpad{display:inline-grid;grid-template-columns:80px 80px 80px;grid-gap:10px;margin:20px auto;}" +
                                "button{background:#2a2a2a;color:#fff;border:2px solid #555;padding:15px;font-size:18px;border-radius:10px;cursor:pointer;touch-action:manipulation;}" +
                                "button:active{background:#ffd400;color:#000;}" +
                                ".btn-ok{background:#d32f2f;font-weight:bold;}" +
                                ".actions{margin:15px 0;}" +
                                ".actions button{padding:10px 18px;font-size:15px;margin:5px;}" +
                                "#logs{background:#000;color:#00ff66;font-family:monospace;height:240px;overflow-y:auto;padding:10px;text-align:left;border-radius:8px;font-size:13px;border:1px solid #333;margin-top:20px;white-space:pre-wrap;}" +
                                "</style></head><body>" +
                                "<h2>📺 Netout TV Remote & Debugger</h2>" +
                                "<div class='dpad'>" +
                                "<div></div><button onclick=\"sendKey('ArrowUp')\">▲ UP</button><div></div>" +
                                "<button onclick=\"sendKey('ArrowLeft')\">◀ LEFT</button>" +
                                "<button class='btn-ok' onclick=\"sendKey('Enter')\">OK</button>" +
                                "<button onclick=\"sendKey('ArrowRight')\">RIGHT ▶</button>" +
                                "<div></div><button onclick=\"sendKey('ArrowDown')\">▼ DOWN</button><div></div>" +
                                "</div>" +
                                "<div class='actions'>" +
                                "<button onclick=\"sendKey('Back')\">↩ BACK</button>" +
                                "<button onclick=\"fetch('/reload')\">🔄 RELOAD</button>" +
                                "<button onclick=\"evalJs('window.__tvGetInfo&&console.log(JSON.stringify(window.__tvGetInfo()))')\">ℹ️ INFO</button>" +
                                "</div>" +
                                "<div style='margin-top:10px;'>" +
                                "<input id='jsIn' placeholder='document.querySelector(...) style...' style='width:60%;padding:10px;background:#222;color:#fff;border:1px solid #444;border-radius:6px;'>" +
                                "<button onclick=\"evalCustom()\" style='padding:10px 15px;margin-left:5px;'>Run JS</button>" +
                                "</div>" +
                                "<div id='logs'>Connecting to logs...</div>" +
                                "<script>" +
                                "function sendKey(k){ fetch('/key?k='+k); }" +
                                "function evalCustom(){ var v=document.getElementById('jsIn').value; if(v) fetch('/eval?js='+encodeURIComponent(v)); }" +
                                "function pollLogs(){ fetch('/logs').then(r=>r.text()).then(t=>{ var el=document.getElementById('logs'); el.innerText=t; el.scrollTop=el.scrollHeight; }).catch(()=>{}); }" +
                                "setInterval(pollLogs, 1000);" +
                                "pollLogs();" +
                                "</script></body></html>";
                        sendResponse(pw, "200 OK", "text/html; charset=utf-8", html);
                    }
                } catch (Exception ignored) {}
            }).start();
        }

        private void sendResponse(PrintWriter pw, String status, String contentType, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            pw.print("HTTP/1.1 " + status + "\r\n");
            pw.print("Content-Type: " + contentType + "\r\n");
            pw.print("Content-Length: " + bytes.length + "\r\n");
            pw.print("Connection: close\r\n\r\n");
            pw.print(body);
            pw.flush();
        }
    }
}
