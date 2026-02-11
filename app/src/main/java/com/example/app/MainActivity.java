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
        showKeyInputDialog();
    }

    private void showKeyInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("输入认证密钥");
        final EditText input = new EditText(this);
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            String newKey = input.getText().toString().trim();
            if (newKey.isEmpty()) {
                showErrorAndRetry("密钥不能为空");
                return;
            }
            authKey = newKey;
            prefs.edit().putString("auth_key", authKey).putBoolean("first_run", false).apply();
            loadUrlWithKey();
            checkIfKeyValid();
        });
        builder.setNegativeButton("取消", (dialog, which) -> {
            dialog.cancel();
            finish();
        });
        builder.setCancelable(false);
        builder.show();
    }

    private void showErrorAndRetry(String message) {
        new AlertDialog.Builder(this)
                .setTitle("错误")
                .setMessage(message)
                .setPositiveButton("重新输入", (dialog, which) -> showKeyInputDialog())
                .setNegativeButton("退出", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void loadUrlWithKey() {
        String fullUrl = baseUrl + "?auth=" + authKey + "&room=" + room;
        webView.loadUrl(fullUrl);
    }

    private void checkIfKeyValid() {
        webView.evaluateJavascript(
            "(function() {" +
            "  try {" +
            "    var title = document.title.toLowerCase();" +
            "    var bodyText = document.body.innerText.toLowerCase();" +
            "    if (title.includes('bing') || bodyText.includes('bing') || " +
            "        document.querySelector('meta[name=\"msvalidate.01\"]') || " +
            "        document.querySelector('link[href*=\"bing.com\"]')) {" +
            "      return 'invalid';" +
            "    }" +
            "    return 'valid';" +
            "  } catch(e) {" +
            "    return 'error';" +
            "  }" +
            "})()",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String result) {
                    String res = result != null ? result.replace("\"", "") : "error";
                    if ("invalid".equals(res)) {
                        showErrorAndRetry("密钥错误，请重新输入");
                    } else {
                        // 密钥有效，继续轮询
                        startPolling();
                    }
                }
            });
    }

    private void startPolling() {
        pollRunnable = this::pollServer;
        handler.post(pollRunnable);
    }

    private void pollServer() {
        new Thread(() -> {
            try {
                URL url = new URL(baseUrl + "poll/" + room);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();

                if (code == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder content = new StringBuilder();
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        content.append(inputLine);
                    }
                    in.close();
                    String response = content.toString();

                    JSONObject json = new JSONObject(response);

                    boolean roomActive = json.has("answer") && !json.isNull("answer");

                    runOnUiThread(() -> {
                        if (roomActive) {
                            if (!isConnected) {
                                isConnected = true;
                                sendNotification();
                            }
                        } else {
                            isConnected = false;
                        }
                    });
                } else {
                    runOnUiThread(() -> isConnected = false);
                }
            } catch (Exception e) {
                runOnUiThread(() -> isConnected = false);
                e.printStackTrace();
            }

            handler.postDelayed(pollRunnable, 10000);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isConnected) {
            PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
            Rational aspectRatio = new Rational(16, 9);
            pipBuilder.setAspectRatio(aspectRatio);
            enterPictureInPictureMode(pipBuilder.build());
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
        if (handler != null && pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
    }

    private class MyWebViewClient extends WebViewClient {}
}
