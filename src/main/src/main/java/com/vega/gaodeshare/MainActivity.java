package com.vega.gaodeshare;

import android.app.Activity;
import android.content.ActivityNotFoundException;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String raw = null;
        Intent in = getIntent();
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            raw = in.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            raw = in.getDataString();
        }

        if (raw == null) {
            Toast.makeText(this, "請從 Google 地圖按「分享」再選本程式", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Matcher m = Pattern.compile("https?://\\S+").matcher(raw);
        final String url = m.find() ? m.group() : null;
        final String sharedText = raw;

        if (url == null) {
            Toast.makeText(this, "找不到網址", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Toast.makeText(this, "解析中…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String[] r = null;
            try {
                r = resolve(url);
            } catch (Exception ignored) {
            }
            final String[] res = r;
            runOnUiThread(() -> {
                if (res == null) {
                    String name = sharedText.replaceAll("https?://\\S+", "").trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "解析失敗", Toast.LENGTH_LONG).show();
                    } else {
                        openAmap("androidamap://poi?sourceApplication=gaodeshare&keywords="
                                + Uri.encode(name) + "&dev=0");
                    }
                } else {
                    openAmap("androidamap://viewMap?sourceApplication=gaodeshare"
                            + "&poiname=" + Uri.encode(res[2])
                            + "&lat=" + res[0] + "&lon=" + res[1] + "&dev=1");
                }
                finish();
            });
        }).start();
    }

    private void openAmap(String uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.setPackage("com.autonavi.minimap");
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "找不到高德地圖，請先安裝", Toast.LENGTH_LONG).show();
        }
    }

    private String[] resolve(String start) throws Exception {
        String finalUrl = start;
        String body = "";

        HttpURLConnection c = (HttpURLConnection) new URL(start).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "zh-TW");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.connect();
        finalUrl = c.getURL().toString();

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
