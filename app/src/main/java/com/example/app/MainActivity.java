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
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
    private boolean isConnected = false;
    private boolean inCall = false;

    // 日志显示区域
    private TextView logTextView;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0);
        webParams.weight = 1;
        webView.setLayoutParams(webParams);
        rootLayout.addView(webView);

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        logTextView = new TextView(this);
        logTextView.setPadding(16, 16, 16, 16);
        logTextView.setTextSize(14);
        logTextView.setTextColor(0xFF333333);
        logTextView.setBackgroundColor(0xFFF0F0F0);
        logTextView.setTextIsSelectable(true);  // 支持长按复制
        scrollView.addView(logTextView);
        rootLayout.addView(scrollView);

        setContentView(rootLayout);

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

    private void log(String message) {
        runOnUiThread(() -> {
            String current = logTextView.getText().toString();
            logTextView.setText(current + "\n" + message);
            // 自动滚动到底部
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
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
        handler.post(pollRunnable);
        log("启动 3秒轮询：等待连接");
    }

    private void pollServer() {
        new Thread(() -> {
            try {
                URL url = new URL(baseUrl + "poll/" + room);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                int responseCode = conn.getResponseCode();

                log("轮询请求发送：HTTP " + responseCode);

                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder content = new StringBuilder();
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        content.append(inputLine);
                    }
                    in.close();
                    String response = content.toString();

                    log("轮询返回：" + response);

                    JSONObject json = new JSONObject(response);
                    boolean roomExists = json.has("answer") || (json.has("candidates") && json.getJSONArray("candidates").length() > 0);

                    runOnUiThread(() -> {
                        if (roomExists) {
                            if (!isConnected) {
                                isConnected = true;
                                inCall = true;
                                sendNotification();
                                log("连接成功！进入通话状态，继续10秒房间检查");
                            } else {
                                log("10秒检查：房间仍然存在 → 保持通话状态");
                            }
                        } else {
                            if (isConnected || inCall) {
                                isConnected = false;
                                inCall = false;
                                log("房间已删除 → 通话结束，重启3秒轮询");
                            } else {
                                log("3秒轮询：房间不存在");
                            }
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        log("轮询失败，HTTP 状态码：" + responseCode);
                        if (inCall) {
                            inCall = false;
                            isConnected = false;
                            log("房间检查失败，重启3秒轮询");
                        }
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> log("轮询异常：" + e.getMessage()));
                e.printStackTrace();
            }

            // 继续下一次轮询（间隔根据状态动态调整）
            long delay = inCall ? 10000 : 3000;
            log("下次轮询将在 " + (delay / 1000) + " 秒后");
            handler.postDelayed(pollRunnable, delay);
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

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (inCall) {
                PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                Rational aspectRatio = new Rational(16, 9);
                pipBuilder.setAspectRatio(aspectRatio);
                enterPictureInPictureMode(pipBuilder.build());
                log("切出 App → 进入小窗（通话中）");
            } else {
                log("切出 App → 不进入小窗（无通话）");
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        log("小窗状态变化：" + (isInPictureInPictureMode ? "进入小窗" : "退出小窗"));
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
        if (handler != null && pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
    }

    private class MyWebViewClient extends WebViewClient {}
}
