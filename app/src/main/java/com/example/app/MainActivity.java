package com.example.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;  // 补全

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;
    private String authKey = "";
    private String room = "1";
    private String baseUrl = "https://bh.gitj.dpdns.org/";
    private Handler handler;
    private Runnable pollRunnable;
    private boolean isConnected = false;

    private Handler videoCheckHandler = new Handler(Looper.getMainLooper());
    private Runnable videoCheckRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new MyWebViewClient());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);

        createNotificationChannel();

        if (prefs.getBoolean("first_run", true)) {
            promptForKey();
        } else {
            authKey = prefs.getString("auth_key", "");
            loadUrlWithKey();
            startPolling();
        }
    }

    // ... createNotificationChannel, promptForKey, loadUrlWithKey, startPolling, pollServer, sendNotification, stopPolling, onBackPressed 全部保持你原始成功版本 ...

    // 只新增挂断检测和条件 PiP
    private void startVideoHangupCheck() {
        videoCheckRunnable = new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                    "(function() {" +
                    "  try {" +
                    "    var videos = document.getElementsByTagName('video');" +
                    "    for (var i = 0; i < videos.length; i++) {" +
                    "      var v = videos[i];" +
                    "      if (v.srcObject && !v.paused && !v.ended && v.currentTime > 0.1) {" +
                    "        return 'active';" +
                    "      }" +
                    "    }" +
                    "    return 'inactive';" +
                    "  } catch(e) {" +
                    "    return 'error';" +
                    "  }" +
                    "})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String result) {
                            String res = result != null ? result.replace("\"", "") : "error";
                            if ("inactive".equals(res) || "error".equals(res)) {
                                isConnected = false;
                                Toast.makeText(MainActivity.this, "检测到通话挂断，正在重新轮询...", Toast.LENGTH_SHORT).show();
                                startPolling();
                            }
                            videoCheckHandler.postDelayed(this, 10000);
                        }
                    });
            }
        };
        videoCheckHandler.postDelayed(videoCheckRunnable, 3000);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        webView.evaluateJavascript(
            "(function() {" +
            "  try {" +
            "    var videos = document.getElementsByTagName('video');" +
            "    for (var i = 0; i < videos.length; i++) {" +
            "      var v = videos[i];" +
            "      if (v.srcObject && !v.paused && !v.ended && v.currentTime > 0.1) {" +
            "        return 'has_video';" +
            "      }" +
            "    }" +
            "    return 'no_video';" +
            "  } catch(e) {" +
            "    return 'error';" +
            "  }" +
            "})()",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String result) {
                    String res = result != null ? result.replace("\"", "") : "error";
                    if ("has_video".equals(res)) {
                        PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                        Rational aspectRatio = new Rational(16, 9);
                        pipBuilder.setAspectRatio(aspectRatio);
                        enterPictureInPictureMode(pipBuilder.build());
                    }
                }
            });
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoCheckHandler != null && videoCheckRunnable != null) {
            videoCheckHandler.removeCallbacks(videoCheckRunnable);
        }
    }

    private class MyWebViewClient extends WebViewClient {}
}
