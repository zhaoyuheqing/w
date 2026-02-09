package com.example.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

// 使用旧的 support 包（保持不变）
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

    private static final String CHANNEL_ID = "connection_alert";  // 换新渠道 ID，避免旧设置影响

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
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new MyWebViewClient());

        // 支持 WebRTC 权限自动授权
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);

        // 创建通知渠道（用新 ID + 高重要性）
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),  // "连接提醒"
                    NotificationManager.IMPORTANCE_HIGH  // 升级为 HIGH → 有声音 + 更容易抬头
            );
            channel.setDescription(getString(R.string.channel_description));
            channel.enableVibration(true);  // 启用振动
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});  // 振动模式
            // 默认声音由系统处理（HIGH 重要性通常自带铃声）

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void promptForKey() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("输入认证密钥");
        final EditText input = new EditText(this);
        builder.setView(input);
        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                authKey = input.getText().toString().trim();
                prefs.edit().putString("auth_key", authKey).putBoolean("first_run", false).apply();
                loadUrlWithKey();
                startPolling();
            }
        });
        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
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
                    if (json.has("answer") && !json.isNull("answer") || 
                        (json.has("candidates") && json.getJSONArray("candidates").length() > 0)) {
                        isConnected = true;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                sendNotification();
                                stopPolling();
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "网络异常，正在重试...", Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    private void sendNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(MainActivity.this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.notification_title))     // "连接成功"
                .setContentText(getString(R.string.notification_message))    // "有人已加入房间！"
                .setPriority(NotificationCompat.PRIORITY_HIGH)               // 高优先级
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)  // 默认声音 + 振动
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

    // 假设你原来有这个内部类，如果没有就保留或删除
    private class MyWebViewClient extends WebViewClient {
        // 如果有自定义逻辑保留，否则可以删掉或留空
    }
}
