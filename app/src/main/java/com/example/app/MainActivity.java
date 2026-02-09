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

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

// 使用旧的 support 包
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;
    private String authKey = "";
    private String room = "1";  // 默认房间号，可改
    private String baseUrl = "https://bh.gitj.dpdns.org/";  // 替换为你的 Worker 域名
    private Handler handler;
    private Runnable pollRunnable;
    private boolean isConnected = false;  // 连接状态

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 直接代码创建 WebView 全屏
        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);  // 允许自动播放 WebRTC 视频
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);  // 支持混合内容

        webView.setWebViewClient(new MyWebViewClient());

        // 支持 WebRTC 权限自动授权
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());  // 自动允许
            }
        });

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);

        // 创建通知渠道
        createNotificationChannel();

        // 检查首次启动
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
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                authKey = input.getText().toString().trim();
                prefs.edit().putString("auth_key", authKey).putBoolean("first_run", false).apply();
                loadUrlWithKey();
                startPolling();
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
                finish();
            }
        });
        builder.show();
    }

    private void loadUrlWithKey() {
        String fullUrl = baseUrl + "?auth=" + authKey + "&room=" + room;
        webView.loadUrl(fullUrl);
    }

    private void startPolling() {
        handler = new Handler(Looper.getMainLooper());
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                pollServer();
                if (!isConnected) {
                    handler.postDelayed(this, 3000);
                }
            }
        };
        handler.post(pollRunnable);
    }

    private void pollServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
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
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                sendNotification("Connection Established", "Someone has connected to the room!");
                                stopPolling();
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void sendNotification(String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(MainActivity.this, "connection_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(MainActivity.this);
        notificationManager.notify(1, builder.build());
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

    // === PiP 支持：只有检测到有活跃视频时才进入小窗 ===
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        // 检查网页是否有正在播放的视频
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
                    // 无视频 → 不进入小窗，直接回到桌面
                }
            });
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        // 无需额外处理
    }

    private class MyWebViewClient extends WebViewClient {
        // 如果有自定义逻辑保留，否则为空
    }
}
