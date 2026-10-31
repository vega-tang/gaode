package com.vega.gaodeshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

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

    private String lastUrl = "";

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
            final String sharedName = raw.replaceAll("https?://\\S+", "").trim();

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
                        showChoice(res, sharedName, error);
                    } catch (Throwable t) {
                        fail("錯誤：\n" + t);
                    }
                });
            }).start();
        } catch (Throwable t) {
            fail("啟動失敗：\n" + t);
        }
    }

    private void showChoice(final String[] res, final String sharedName, String error) {
        final String name = !sharedName.isEmpty() ? sharedName
                : (res != null ? res[2] : "");
        String shortUrl = lastUrl.length() > 250 ? lastUrl.substring(0, 250) + "…" : lastUrl;

        StringBuilder sb = new StringBuilder();
        sb.append("地點：").append(name.isEmpty() ? "(無)" : name).append("\n");
        if (res != null) {
            sb.append("座標：").append(res[0]).append(", ").append(res[1]).append("\n");
            sb.append("來源：").append(res[3]).append("\n");
        } else {
            sb.append("座標：沒抓到\n");
            if (error != null) sb.append(error).append("\n");
        }
        sb.append("\n網址：").append(shortUrl);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("確認目的地")
            .setMessage(sb.toString())
            .setOnCancelListener(d -> finish());

        if (res != null) {
            b.setPositiveButton("開啟高德(座標)", (d, w) ->
                openAmap("androidamap://viewMap?sourceApplication=gaodeshare"
                    + "&poiname=" + Uri.encode(name.isEmpty() ? "目的地" : name)
                    + "&lat=" + res[0] + "&lon=" + res[1] + "&dev=1"));
        }
        b.setNegativeButton("用名稱搜尋", (d, w) -> {
            if (name.isEmpty()) {
                finish();
            } else {
                openAmap("androidamap://poi?sourceApplication=gaodeshare&keywords="
                    + Uri.encode(name) + "&dev=0");
            }
        });
        b.show();
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
        HttpURLConnection c = (HttpURLConnection) new URL(start).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "zh-TW");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.connect();
        lastUrl = c.getURL().toString();

        String[] r = parseUrl(lastUrl);
        if (r == null) {
            String body;
            try (InputStream is = c.getInputStream()) {
                byte[] buf = new byte[400000];
                int n = 0, k;
                while (n < buf.length && (k = is.read(buf, n, buf.length - n)) > 0) n += k;
                body = new String(buf, 0, n, "UTF-8");
            }
            r = parseBody(body);
            if (r != null) r[2] = nameFromUrl(lastUrl);
        }
        c.disconnect();
        return r;
    }

    private String[] find(String s, String pat, String src, String name) {
        Matcher m = Pattern.compile(pat).matcher(s);
        if (m.find()) {
            return new String[]{m.group(1), m.group(2), name, src};
        }
        return null;
    }

    private String[] parseUrl(String s) {
        String name = nameFromUrl(s);
        String[] r;
        if ((r = find(s, "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)", "網址 !3d!4d", name)) != null) return r;
        if ((r = find(s, "[?&](?:q|ll|destination)=(-?\\d+\\.\\d+)(?:,|%2C)(-?\\d+\\.\\d+)", "網址 q/ll", name)) != null) return r;
        if ((r = find(s, "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)", "網址 @", name)) != null) return r;
        return null;
    }

    private String[] parseBody(String s) {
        String[] r;
        if ((r = find(s, "center=(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)", "網頁 center", "")) != null) return r;
        if ((r = find(s, "\\[null,null,(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)\\]", "網頁 null", "")) != null) return r;
        if ((r = find(s, "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)", "網頁 !3d!4d", "")) != null) return r;
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
        return "";
    }
}
