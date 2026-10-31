package com.vega.gaodeshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private void fail(String msg) {
        try {
            new AlertDialog.Builder(this)
                .setTitle("錯誤")
                .setMessage(msg)
                .setPositiveButton("關閉", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            String raw = null;
            Intent in = getIntent();
            if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
                raw = in.getStringExtra(Intent.EXTRA_TEXT);
            } else if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
                raw = in.getDataString();
            }

            if (raw == null) {
                fail("請從 Google 地圖按「分享」再選本程式");
                return;
            }

            Matcher m = Pattern.compile("https?://\\S+").matcher(raw);
            final String url = m.find() ? m.group() : null;
            final String sharedText = raw;

            if (url == null) {
                fail("找不到網址\n\n收到的內容：\n" + raw);
                return;
            }

            new Thread(() -> {
                String[] r = null;
                String err = null;
                try {
                    r = resolve(url);
                } catch (Throwable t) {
                    err = t.toString();
                }
                final String[] res = r;
                final String error = err;
                runOnUiThread(() -> {
                    try {
                        if (res == null) {
                            String name = sharedText.replaceAll("https?://\\S+", "").trim();
                            if (name.isEmpty()) {
                                fail("解析失敗\n" + (error == null ? "" : error));
                            } else {
                                openAmap("androidamap://poi?sourceApplication=gaodeshare&keywords="
                                        + Uri.encode(name) + "&dev=0");
                            }
                        } else {
                            openAmap("androidamap://viewMap?sourceApplication=gaodeshare"
                                    + "&poiname=" + Uri.encode(res[2])
                                    + "&lat=" + res[0] + "&lon=" + res[1] + "&dev=1");
                        }
                    } catch (Throwable t) {
                        fail("開啟高德失敗：\n" + t);
                    }
                });
            }).start();
        } catch (Throwable t) {
            fail("啟動失敗：\n" + t);
        }
    }

    private void openAmap(String uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.setPackage("com.autonavi.minimap");
        try {
            startActivity(i);
            finish();
        } catch (Throwable e) {
            fail("無法開啟高德地圖：\n" + e);
        }
    }

    private String[] resolve(String start) throws Exception {
        String body = "";

        HttpURLConnection c = (HttpURLConnection) new URL(start).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "zh-TW");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.connect();
        String finalUrl = c.getURL().toString();

        String[] r = parse(finalUrl);
        if (r == null) {
            try (InputStream is = c.getInputStream()) {
                byte[] buf = new byte[300000];
                int n = 0, k;
                while (n < buf.length && (k = is.read(buf, n, buf.length - n)) > 0) n += k;
                body = new String(buf, 0, n, "UTF-8");
            }
            r = parse(body);
            if (r != null) r[2] = nameFromUrl(finalUrl);
        }
        c.disconnect();
        return r;
    }

    private String[] parse(String s) {
        String[] pats = {
            "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)",
            "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)",
            "[?&](?:q|ll|destination|center)=(-?\\d+\\.\\d+)(?:,|%2C)(-?\\d+\\.\\d+)"
        };
        for (String p : pats) {
            Matcher m = Pattern.compile(p).matcher(s);
            if (m.find()) {
                return new String[]{m.group(1), m.group(2), nameFromUrl(s)};
            }
        }
        return null;
    }

    private String nameFromUrl(String u) {
        Matcher m = Pattern.compile("/place/([^/@?]+)").matcher(u);
        if (m.find()) {
            try {
                return URLDecoder.decode(m.group(1).replace("+", " "), "UTF-8");
            } catch (Exception ignored) {
            }
        }
        return "目的地";
    }
}
