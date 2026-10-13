package com.nox.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1, REQ_FILE = 2, REQ_NOTIF = 3;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private String pendingUri, pendingName;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#0c0e16"));
        getWindow().setNavigationBarColor(Color.parseColor("#0c0e16"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0c0e16"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage = the app's autosave
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // keep the app on its own page; never navigate away
                return !"appassets.androidplatform.net".equals(r.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // The page uses confirm()/prompt(); WebView shows nothing unless we do.
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult res) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                        .setPositiveButton(android.R.string.ok, (d, w) -> res.confirm())
                        .setOnCancelListener(d -> res.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult res) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                        .setPositiveButton(android.R.string.ok, (d, w) -> res.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> res.cancel())
                        .setOnCancelListener(d -> res.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, final JsPromptResult res) {
                final EditText et = new EditText(MainActivity.this);
                et.setText(def);
                et.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setView(et)
                        .setPositiveButton(android.R.string.ok, (d, w) -> res.confirm(et.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (d, w) -> res.cancel())
                        .setOnCancelListener(d -> res.cancel()).show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) { return true; }
        });

        web.addJavascriptInterface(new Bridge(), "NoxVPN");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        if (b != null) web.restoreState(b); else web.loadUrl(HOME);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        NoxVpnService.listener = json -> runOnUiThread(() -> push(json));
        push(NoxVpnService.lastJson);   // catch up with whatever happened while we were away
    }

    @Override
    protected void onPause() {
        super.onPause();
        NoxVpnService.listener = null;  // service keeps running; page re-syncs on resume
        web.evaluateJavascript("try{window.dispatchEvent(new Event('pagehide'))}catch(e){}", null);
    }

    private void push(String json) {
        if (web != null) web.evaluateJavascript("window.noxVpnUpdate&&window.noxVpnUpdate(" + json + ")", null);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // keep VPN + app alive instead of closing
    }

    /* ---------- VPN permission flow ---------- */

    private void requestConnect(String uri, String name) {
        pendingUri = uri;
        pendingName = name;
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            try {
                startActivityForResult(prep, REQ_VPN);
            } catch (Exception e) {
                push(NoxVpnService.stateJson("error", name, uri, 0, 0, 0, 0, 0, 0, e.getMessage()));
            }
        } else {
            startVpn();
        }
    }

    private void startVpn() {
        Intent i = new Intent(this, NoxVpnService.class).setAction(NoxVpnService.ACTION_CONNECT)
                .putExtra(NoxVpnService.EXTRA_URI, pendingUri).putExtra(NoxVpnService.EXTRA_NAME, pendingName);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) startVpn();
            else push(NoxVpnService.stateJson("error", pendingName, pendingUri, 0, 0, 0, 0, 0, 0, "VPN permission denied"));
        } else if (req == REQ_FILE && fileCallback != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(r);
            fileCallback = null;
        }
    }

    /* ---------- JS bridge: window.NoxVPN ---------- */

    private class Bridge {
        @JavascriptInterface
        public void connect(final String uri, final String name) {
            runOnUiThread(() -> requestConnect(uri, name));
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(() -> startService(new Intent(MainActivity.this, NoxVpnService.class)
                    .setAction(NoxVpnService.ACTION_DISCONNECT)));
        }

        @JavascriptInterface
        public String state() {
            return NoxVpnService.lastJson;
        }

        /** Backup export: WebView cannot download blob: URLs, so the page hands the text over. */
        @JavascriptInterface
        public void saveFile(final String name, final String content) {
            try {
                String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) throw new Exception("insert failed");
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                    where = "Downloads/" + safe;
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    File f = new File(dir, safe);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                    where = f.getAbsolutePath();
                }
                final String msg = where;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
            } catch (final Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }
}
