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
import android.widget.Toast;

import org.json.JSONObject;

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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("connection_channel", "Connection Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void promptForKey() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Auth Key");
        final EditText input = new EditText(this);
        builder.setView(input);
        builder.setPositiveButton("OK", (dialog, which) -> {
            authKey = input.getText().toString().trim();
            prefs.edit().putString("auth_key", authKey).putBoolean("first_run", false).apply();
            loadUrlWithKey();
            startPolling();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            finish();
        });
        builder.show();
    }

    private void loadUrlWithKey() {
        String fullUrl = baseUrl + "?auth=" + authKey + "&room=" + room;
        webView.loadUrl(fullUrl);
    }

    private void startPolling() {
        handler = new Handler(Looper.getMainLooper());
        pollRunnable = () -> {
            pollServer();
            if (!isConnected) {
                handler.postDelayed(this, 3000);
            }
        };
        handler.post(pollRunnable);
    }

    private void pollServer() {
        new Thread(() -> {
            try {
                URL url = new URL(baseUrl + "poll/" + room);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder content = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                in.close();
                String response = content.toString();

                JSONObject json = new JSONObject(response);
                if (json.has("answer") && !json.isNull("answer") || (json.has("candidates") && json.getJSONArray("candidates").length() > 0)) {
                    isConnected = true;
                    runOnUiThread(() -> {
                        sendNotification("Connection Established", "Someone has connected to the room!");
                        stopPolling();
                        startVideoHangupCheck();  // 启动挂断检测
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void sendNotification(String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "connection_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        NotificationManagerCompat.from(this).notify(1, builder.build());
    }

    private void stopPolling() {
        if (handler != null && pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // 挂断检测：每10秒检查一次视频状态
    private void startVideoHangupCheck() {
        videoCheckRunnable = () -> {
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
                value -> {
                    String res = value != null ? value.replace("\"", "") : "error";
                    if ("inactive".equals(res) || "error".equals(res)) {
                        isConnected = false;
                        Toast.makeText(this, "检测到通话挂断，正在重新轮询...", Toast.LENGTH_SHORT).show();
                        startPolling();
                    }
                    videoCheckHandler.postDelayed(this, 10000);
                });
        };
        videoCheckHandler.postDelayed(videoCheckRunnable, 3000);
    }

    // PiP：只有检测到有视频时才进入
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        // 检测页面中的视频元素
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
            value -> {
                String res = value != null ? value.replace("\"", "") : "error";
                if ("has_video".equals(res)) {
                    // 只有检测到正在播放的视频才触发 PiP 模式
                    PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                    builder.setAspectRatio(new Rational(16, 9));
                    enterPictureInPictureMode(builder.build());
                } else {
                    // 如果没有视频，可以选择不触发 PiP 或做其他处理
                    Log.d("PiP", "没有找到视频或视频不可用");
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
