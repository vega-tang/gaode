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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private static class Res {
        String lat, lng, src;
        String finalUrl = "";
        int hops = 0;
        StringBuilder log = new StringBuilder();
    }

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
            String text = null, subject = null, title = null;
            Intent in = getIntent();
            if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
                text = in.getStringExtra(Intent.EXTRA_TEXT);
                subject = in.getStringExtra(Intent.EXTRA_SUBJECT);
                title = in.getStringExtra(Intent.EXTRA_TITLE);
            } else if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
                text = in.getDataString();
            }

            if (text == null) {
                fail("請從 Google 地圖按「分享」再選本程式");
                return;
            }

            Matcher m = Pattern.compile("https?://\\S+").matcher(text);
            final String url = m.find() ? m.group() : null;
            if (url == null) {
                fail("找不到網址\n\n收到的內容：\n" + text);
                return;
            }

            String n = text.replaceAll("https?://\\S+", "").trim();
            if (n.isEmpty() && subject != null) n = subject.trim();
            if (n.isEmpty() && title != null) n = title.trim();
            // 只取第一行當名稱
            int nl = n.indexOf('\n');
            if (nl > 0) n = n.substring(0, nl).trim();
            final String name = n;

            final String dbg = "文字=" + abbr(text, 80)
                + "\n主旨=" + abbr(subject, 60)
                + "\n標題=" + abbr(title, 60);

            new Thread(() -> {
                Res r = null;
                String err = null;
                try {
                    r = resolve(url);
                } catch (Throwable t) {
                    err = t.toString();
                }
                final Res res = r;
                final String error = err;
                runOnUiThread(() -> {
                    try {
                        showChoice(res, name, error, dbg);
                    } catch (Throwable t) {
                        fail("錯誤：\n" + t);
                    }
                });
            }).start();
        } catch (Throwable t) {
            fail("啟動失敗：\n" + t);
        }
    }

    private String abbr(String s, int n) {
        if (s == null) return "(無)";
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private void showChoice(final Res res, final String name, String error, String dbg) {
        StringBuilder sb = new StringBuilder();
        sb.append("地點：").append(name.isEmpty() ? "(無)" : name).append("\n");
        if (res != null && res.lat != null) {
            sb.append("採用座標：").append(res.lat).append(", ").append(res.lng)
              .append("（").append(res.src).append("）\n");
        } else {
            sb.append("採用座標：沒抓到\n");
        }
        if (error != null) sb.append("錯誤：").append(error).append("\n");
        if (res != null) {
            sb.append("轉址次數：").append(res.hops).append("\n");
            sb.append("候選：\n").append(res.log.length() == 0 ? "(無)\n" : res.log.toString());
            sb.append("最終網址：").append(abbr(res.finalUrl, 160)).append("\n");
        }
        sb.append("\n").append(dbg);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("確認目的地")
            .setMessage(sb.toString())
            .setOnCancelListener(d -> finish());

        if (res != null && res.lat != null) {
            b.setPositiveButton("開啟高德(座標)", (d, w) ->
                openAmap("androidamap://viewMap?sourceApplication=gaodeshare"
                    + "&poiname=" + Uri.encode(name.isEmpty() ? "目的地" : name)
                    + "&lat=" + res.lat + "&lon=" + res.lng + "&dev=1"));
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

    private Res resolve(String start) throws Exception {
        Res r = new Res();
        List<String> chain = new ArrayList<>();
        String cur = start;
        String body = null;

        for (int i = 0; i < 8; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "zh-TW");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.connect();
            chain.add(cur);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) break;
                cur = new URL(new URL(cur), loc).toString();
                r.hops++;
                continue;
            }
            try (InputStream is = c.getInputStream()) {
                byte[] buf = new byte[400000];
                int n = 0, k;
                while (n < buf.length && (k = is.read(buf, n, buf.length - n)) > 0) n += k;
                body = new String(buf, 0, n, "UTF-8");
            } catch (Throwable ignored) {
            }
            c.disconnect();
            break;
        }

        r.finalUrl = cur;
        for (String u : chain) {
            tryPat(r, u, "網址 !3d!4d", "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)", true);
            tryPat(r, u, "網址 q/ll/dest", "[?&](?:q|ll|destination|daddr)=(-?\\d+\\.\\d+)(?:,|%2C|\\+)(-?\\d+\\.\\d+)", true);
            tryPat(r, u, "網址 @", "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)", true);
        }
        if (body != null) {
            tryPat(r, body, "網頁 !3d!4d", "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)", true);
            tryPat(r, body, "網頁 null,null", "\\[null,null,(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)\\]", true);
            tryPat(r, body, "網頁 center(不採用)", "center=(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)", false);
        }
        return r;
    }

    private void tryPat(Res r, String s, String label, String pat, boolean use) {
        Matcher m = Pattern.compile(pat).matcher(s);
        if (m.find()) {
            r.log.append("- ").append(label).append(": ")
                .append(m.group(1)).append(", ").append(m.group(2)).append("\n");
            if (use && r.lat == null) {
                r.lat = m.group(1);
                r.lng = m.group(2);
                r.src = label;
            }
        }
    }
}
