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

import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;
    private String authKey = "";
    private String room = "1";
    private String baseUrl = "https://bh.gitj.dpdns.org/";
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;
    private Runnable roomCheckRunnable;
    private boolean isConnected = false;
    private boolean inCall = false;  // 是否在通话中（房间存在）

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
            NotificationChannel channel = new NotificationChannel("connection_channel", "连接提醒", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void promptForKey() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("输入认证密钥");
        final EditText input = new EditText(this);
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            authKey = input.getText().toString().trim();
            prefs.edit().putString("auth_key", authKey).putBoolean("first_run", false).apply();
            loadUrlWithKey();
            startPolling();
        });
        builder.setNegativeButton("取消", (dialog, which) -> {
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
        pollRunnable = this::pollServer;
        handler.post(pollRunnable);  // 立即开始3秒轮询
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
                    if (!isConnected) {
                        isConnected = true;
                        inCall = true;
                        runOnUiThread(() -> {
                            sendNotification();
                            stopPolling();  // 停止3秒轮询
                            startRoomCheck();  // 开始10秒房间检查
                        });
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void sendNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "connection_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("连接成功")
                .setContentText("有人已加入房间！")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        NotificationManagerCompat.from(this).notify(1, builder.build());
    }

    private void stopPolling() {
        if (pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
    }

    // 每10秒检查房间是否还存在
    private void startRoomCheck() {
        roomCheckRunnable = () -> {
            new Thread(() -> {
                try {
                    URL url = new URL(baseUrl + "poll/" + room);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();

                    // 如果房间不存在，服务器返回非200或空数据
                    if (code != 200) {
                        runOnUiThread(() -> {
                            isConnected = false;
                            inCall = false;
                            sendNotification("房间已结束", "通话已断开，正在重新等待...");
                            startPolling();  // 重启3秒轮询
                        });
                    } else {
                        // 房间还在，继续检查
                        handler.postDelayed(roomCheckRunnable, 10000);
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        isConnected = false;
                        inCall = false;
                        startPolling();
                    });
                    e.printStackTrace();
                }
            }).start();
        };
        handler.postDelayed(roomCheckRunnable, 10000);  // 首次延迟10秒
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (inCall) {  // 只有房间存在（通话中）才进入小窗
                PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                Rational aspectRatio = new Rational(16, 9);
                pipBuilder.setAspectRatio(aspectRatio);
                enterPictureInPictureMode(pipBuilder.build());
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
            if (roomCheckRunnable != null) handler.removeCallbacks(roomCheckRunnable);
        }
    }

    private class MyWebViewClient extends WebViewClient {}
}
