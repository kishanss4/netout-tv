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
                addLog("Console: " + msg.message());
                return super.onConsoleMessage(msg);
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }

        // Start Local Web Debugger & Companion on Port 8080
        startDebugServer();
    }

    private void injectNav() {
        if (!navScript.isEmpty()) {
            webView.evaluateJavascript(navScript, null);
        }
    }

    private void addLog(String message) {
        Log.d(TAG, message);
        while (logHistory.size() > 100) {
            logHistory.remove(0);
        }
        logHistory.add(message);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }

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
                addLog("Key: " + jsKey);
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
    // Embedded Web Server: Remote Control, Play on TV, and Sync API
    // ------------------------------------------------------------------------
    private void startDebugServer() {
        try {
            debugServer = new DebugServer(DEBUG_PORT);
            debugServer.start();
            addLog("Server running on port " + DEBUG_PORT);
        } catch (Exception e) {
            addLog("Server error: " + e.getMessage());
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
            new Thread(this, "NetoutServer").start();
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
                try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     OutputStream out = socket.getOutputStream();
                     PrintWriter pw = new PrintWriter(out)) {

                    String line = in.readLine();
                    if (line == null) return;

                    String[] parts = line.split(" ");
                    if (parts.length < 2) return;
                    String method = parts[0];
                    String rawPath = parts[1];

                    // Allow CORS from any origin so mobile browser bookmarklets can communicate directly
                    if (method.equalsIgnoreCase("OPTIONS")) {
                        sendCorsPreflight(pw);
                        return;
                    }

                    if (rawPath.startsWith("/play?url=")) {
                        String encodedUrl = rawPath.substring(10);
                        String url = URLDecoder.decode(encodedUrl, "UTF-8");
                        addLog("Casting URL: " + url);
                        mainHandler.post(() -> webView.loadUrl(url));
                        sendResponse(pw, "200 OK", "application/json", "{\"status\":\"playing\",\"url\":\"" + url + "\"}");

                    } else if (rawPath.startsWith("/sync?")) {
                        // Dynamic sync commands from phone
                        String query = rawPath.substring(6);
                        String action = getQueryParam(query, "action");

                        if ("openModal".equals(action)) {
                            String id = getQueryParam(query, "id");
                            String type = getQueryParam(query, "type");
                            addLog("Sync modal: " + id + " (" + type + ")");
                            mainHandler.post(() -> webView.evaluateJavascript("window.openModal&&openModal('" + id + "', '" + type + "')", null));
                        } else if ("play".equals(action)) {
                            addLog("Sync play click");
                            mainHandler.post(() -> webView.evaluateJavascript(
                                    "var p = document.getElementById('modal-play') || document.querySelector('.play-button'); if(p) p.click();", null));
                        } else if ("seek".equals(action)) {
                            String sec = getQueryParam(query, "sec");
                            mainHandler.post(() -> webView.evaluateJavascript("window.__tvSeek&&__tvSeek(" + sec + ")", null));
                        } else if ("toggle".equals(action)) {
                            mainHandler.post(() -> webView.evaluateJavascript("window.__tvToggle&&__tvToggle()", null));
                        } else if ("close".equals(action)) {
                            mainHandler.post(() -> webView.evaluateJavascript("window.closePlayer&&closePlayer(); history.back();", null));
                        }

                        sendResponse(pw, "200 OK", "application/json", "{\"status\":\"ok\"}");

                    } else if (rawPath.startsWith("/key?k=")) {
                        String key = rawPath.substring(7);
                        mainHandler.post(() -> {
                            if (key.equals("Back")) {
                                if (webView.canGoBack()) webView.goBack();
                            } else {
                                webView.evaluateJavascript("window.__tvKey&&__tvKey('" + key + "', false)", null);
                            }
                        });
                        sendResponse(pw, "200 OK", "text/plain", "OK: " + key);

                    } else if (rawPath.startsWith("/eval?js=")) {
                        String encodedJs = rawPath.substring(9);
                        String js = URLDecoder.decode(encodedJs, "UTF-8");
                        mainHandler.post(() -> webView.evaluateJavascript(js, res -> addLog("Eval: " + res)));
                        sendResponse(pw, "200 OK", "text/plain", "Evaluated");

                    } else if (rawPath.startsWith("/logs")) {
                        StringBuilder sb = new StringBuilder();
                        synchronized (logHistory) {
                            for (String l : logHistory) sb.append(l).append("\n");
                        }
                        sendResponse(pw, "200 OK", "text/plain", sb.toString());

                    } else if (rawPath.equals("/reload")) {
                        mainHandler.post(() -> webView.reload());
                        sendResponse(pw, "200 OK", "text/plain", "Reloaded");

                    } else {
                        // Serve Companion & Remote HTML UI
                        String hostHeader = socket.getLocalAddress().getHostAddress() + ":" + port;
                        String html = getCompanionHtml(hostHeader);
                        sendResponse(pw, "200 OK", "text/html; charset=utf-8", html);
                    }
                } catch (Exception ignored) {}
            }).start();
        }

        private String getQueryParam(String query, String name) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    try {
                        return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    } catch (Exception e) {
                        return pair.substring(eq + 1);
                    }
                }
            }
            return "";
        }

        private void sendCorsPreflight(PrintWriter pw) {
            pw.print("HTTP/1.1 204 No Content\r\n");
            pw.print("Access-Control-Allow-Origin: *\r\n");
            pw.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            pw.print("Access-Control-Allow-Headers: *\r\n");
            pw.print("Connection: close\r\n\r\n");
            pw.flush();
        }

        private void sendResponse(PrintWriter pw, String status, String contentType, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            pw.print("HTTP/1.1 " + status + "\r\n");
            pw.print("Access-Control-Allow-Origin: *\r\n");
            pw.print("Content-Type: " + contentType + "\r\n");
            pw.print("Content-Length: " + bytes.length + "\r\n");
            pw.print("Connection: close\r\n\r\n");
            pw.print(body);
            pw.flush();
        }

        private String getCompanionHtml(String host) {
            // Bookmarklet code that hooks into netout.pages.dev
            String bml = "javascript:(function(){" +
                    "if(window.__netoutCastActive){alert('Cast to TV is already active!');return;}" +
                    "window.__netoutCastActive=true;" +
                    "var TV_HOST='http://" + host + "';" +
                    "function send(action,params){" +
                    "  var q='?action='+action;" +
                    "  for(var k in params) q+='&'+k+'='+encodeURIComponent(params[k]);" +
                    "  fetch(TV_HOST+'/sync'+q,{mode:'no-cors'}).catch(function(){});" +
                    "}" +
                    "var bar=document.createElement('div');" +
                    "bar.style='position:fixed;bottom:15px;left:50%;transform:translateX(-50%);background:#181818;color:#ffd400;padding:12px 20px;border-radius:30px;box-shadow:0 6px 20px rgba(0,0,0,0.8);z-index:999999;display:flex;align-items:center;gap:12px;font-family:sans-serif;font-weight:bold;font-size:14px;border:2px solid #ffd400;';" +
                    "bar.innerHTML='<span>📺 CAST TO TV ACTIVE</span>'+" +
                    "'<button id=\"_castPlay\" style=\"background:#ffd400;color:#000;border:none;padding:6px 12px;border-radius:15px;font-weight:bold;cursor:pointer;\">PLAY/PAUSE</button>'+" +
                    "'<button id=\"_castSeekBack\" style=\"background:#333;color:#fff;border:none;padding:6px 10px;border-radius:15px;cursor:pointer;\">-10s</button>'+" +
                    "'<button id=\"_castSeekFwd\" style=\"background:#333;color:#fff;border:none;padding:6px 10px;border-radius:15px;cursor:pointer;\">+10s</button>'+" +
                    "'<button id=\"_castClose\" style=\"background:#d32f2f;color:#fff;border:none;padding:6px 10px;border-radius:15px;cursor:pointer;\">✕</button>';" +
                    "document.body.appendChild(bar);" +
                    "document.getElementById('_castPlay').onclick=function(){send('toggle',{});};" +
                    "document.getElementById('_castSeekBack').onclick=function(){send('seek',{sec:'-10'});};" +
                    "document.getElementById('_castSeekFwd').onclick=function(){send('seek',{sec:'10'});};" +
                    "document.getElementById('_castClose').onclick=function(){bar.remove();window.__netoutCastActive=false;};" +
                    "var origOpen = window.openModal;" +
                    "if(origOpen){window.openModal=function(id,type){send('openModal',{id:id,type:type});return origOpen.apply(this,arguments);};}" +
                    "document.addEventListener('click',function(e){" +
                    "  var p=e.target.closest('#modal-play, .play-button, .play-button2, .episode-item, .next-button');" +
                    "  if(p){ send('play',{}); send('url',{url:location.href}); }" +
                    "},true);" +
                    "alert('🟢 Connected to TV! Every movie or episode you click will now sync and play on your TV.');" +
                    "})();";

            return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                    "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<title>Netout TV Cast & Remote</title>" +
                    "<style>" +
                    "body{background:#0e0e0e;color:#eee;font-family:system-ui,-apple-system,sans-serif;margin:0;padding:20px;text-align:center;}" +
                    "h2{color:#ffd400;margin:10px 0 5px;font-size:24px;}" +
                    ".tagline{color:#aaa;font-size:14px;margin-bottom:20px;}" +
                    ".card{background:#181818;border:1px solid #2a2a2a;border-radius:14px;padding:20px;margin-bottom:20px;text-align:left;box-shadow:0 4px 15px rgba(0,0,0,0.5);}" +
                    ".card h3{color:#ffd400;margin-top:0;font-size:18px;display:flex;align-items:center;gap:8px;}" +
                    ".bml-btn{display:block;background:linear-gradient(135deg,#ffd400,#ff9900);color:#000;font-weight:bold;text-decoration:none;padding:15px;text-align:center;border-radius:10px;font-size:16px;margin:15px 0;box-shadow:0 4px 12px rgba(255,212,0,0.4);}" +
                    "input{width:100%;box-sizing:border-box;padding:12px;background:#242424;color:#fff;border:1px solid #444;border-radius:8px;font-size:15px;margin-bottom:10px;}" +
                    "button.btn-primary{background:#d32f2f;color:#fff;border:none;padding:12px 20px;border-radius:8px;font-weight:bold;font-size:15px;cursor:pointer;width:100%;}" +
                    ".dpad{display:inline-grid;grid-template-columns:75px 75px 75px;grid-gap:10px;margin:15px auto;}" +
                    ".dpad button{background:#252525;color:#fff;border:1px solid #444;padding:16px 0;font-size:17px;border-radius:10px;cursor:pointer;touch-action:manipulation;}" +
                    ".dpad button:active{background:#ffd400;color:#000;}" +
                    ".btn-ok{background:#d32f2f!important;font-weight:bold;}" +
                    ".media-btns{display:flex;justify-content:center;gap:10px;margin-top:10px;}" +
                    ".media-btns button{background:#222;border:1px solid #444;color:#fff;padding:10px 14px;border-radius:8px;cursor:pointer;font-size:14px;}" +
                    "#logs{background:#000;color:#00ff66;font-family:monospace;height:180px;overflow-y:auto;padding:10px;text-align:left;border-radius:8px;font-size:12px;border:1px solid #222;white-space:pre-wrap;}" +
                    "</style></head><body>" +
                    "<h2>📺 Netout TV Companion</h2>" +
                    "<div class='tagline'>Connected to Tata Play Binge (" + host + ")</div>" +

                    "<div class='card'>" +
                    "<h3>📲 Step 1: Open Netout on Your Phone</h3>" +
                    "<p style='color:#ccc;font-size:14px;line-height:1.5;'>Open Netout in a new tab on your phone to browse, search, and pick movies with touch:</p>" +
                    "<a href='https://netout.pages.dev/home' target='_blank' class='bml-btn' style='background:#2a2a2a;color:#ffd400;border:1px solid #ffd400;'>🌐 Open netout.pages.dev in New Tab</a>" +
                    "</div>" +

                    "<div class='card'>" +
                    "<h3>🪄 Step 2: One-Tap 'Cast to TV' Bookmark</h3>" +
                    "<p style='color:#ccc;font-size:14px;line-height:1.5;'>Copy this code or bookmark it on your phone. When on <b>netout.pages.dev</b>, tap it once to activate Live TV Sync!</p>" +
                    "<textarea id='bmlCode' readonly style='width:100%;height:65px;background:#111;color:#aaa;border:1px solid #333;border-radius:6px;font-size:11px;padding:8px;box-sizing:border-box;'>" + bml + "</textarea>" +
                    "<button onclick=\"copyBml()\" class='btn-primary' style='background:#ffd400;color:#000;margin-top:10px;'>📋 Copy Bookmarklet Code</button>" +
                    "</div>" +

                    "<div class='card'>" +
                    "<h3>🔗 Quick Cast URL directly</h3>" +
                    "<input id='castUrl' placeholder='Paste movie/series link (e.g. netout.pages.dev/home?movie=...)' />" +
                    "<button class='btn-primary' onclick=\"castUrl()\">▶ Play Directly on TV</button>" +
                    "</div>" +

                    "<div class='card' style='text-align:center;'>" +
                    "<h3>🎮 TV Remote & Playback</h3>" +
                    "<div class='dpad'>" +
                    "<div></div><button onclick=\"sendKey('ArrowUp')\">▲</button><div></div>" +
                    "<button onclick=\"sendKey('ArrowLeft')\">◀</button>" +
                    "<button class='btn-ok' onclick=\"sendKey('Enter')\">OK</button>" +
                    "<button onclick=\"sendKey('ArrowRight')\">▶</button>" +
                    "<div></div><button onclick=\"sendKey('ArrowDown')\">▼</button><div></div>" +
                    "</div>" +
                    "<div class='media-btns'>" +
                    "<button onclick=\"sendSync('seek&sec=-10')\">⏪ -10s</button>" +
                    "<button onclick=\"sendSync('toggle')\" style='background:#d32f2f;'>⏯ Play/Pause</button>" +
                    "<button onclick=\"sendSync('seek&sec=10')\">+10s ⏩</button>" +
                    "<button onclick=\"sendKey('Back')\">↩ Back</button>" +
                    "</div>" +
                    "</div>" +

                    "<div class='card'>" +
                    "<h3>📋 Live Activity Logs</h3>" +
                    "<div id='logs'>Loading...</div>" +
                    "</div>" +

                    "<script>" +
                    "function copyBml(){ var t=document.getElementById('bmlCode'); t.select(); document.execCommand('copy'); alert('Copied! Add a bookmark on your phone and paste this in the URL field.'); }" +
                    "function castUrl(){ var u=document.getElementById('castUrl').value; if(u){ fetch('/play?url='+encodeURIComponent(u)); alert('Sent to TV!'); } }" +
                    "function sendKey(k){ fetch('/key?k='+k); }" +
                    "function sendSync(p){ fetch('/sync?action='+p); }" +
                    "function pollLogs(){ fetch('/logs').then(r=>r.text()).then(t=>{ var el=document.getElementById('logs'); el.innerText=t; el.scrollTop=el.scrollHeight; }).catch(()=>{}); }" +
                    "setInterval(pollLogs, 1200); pollLogs();" +
                    "</script></body></html>";
        }
    }
}
