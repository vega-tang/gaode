package com.vega.gaodeshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
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
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String COOKIE =
        "CONSENT=PENDING+987; SOCS=CAESHAgBEhJnd3NfMjAyMzA4MTAtMF9SQzIaAmRlIAEaBgiAo_CmBg";

    private static final String LAT = "([+-]?\\d{1,2}\\.\\d+)";
    private static final String LON = "([+-]?\\d{1,3}\\.\\d+)";

    private volatile String lastUrl = "";
    // {slat, slon, sname, dlat, dlon, dname, t}
    private volatile String[] route = null;

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

            String n = text.replaceAll("https?://\\S+", "").trim();
            if (n.isEmpty() && subject != null) n = subject.trim();
            if (n.isEmpty() && title != null) n = title.trim();
            int nl = n.indexOf('\n');
            if (nl > 0) n = n.substring(0, nl).trim();
            final String name = n;

            if (url == null) {
                if (name.isEmpty()) {
                    fail("找不到網址或地點名稱\n\n收到的內容：\n" + text);
                } else {
                    searchByName(name);
                }
                return;
            }

            new Thread(() -> {
                String[] c = null;
                try {
                    c = findCoords(url);
                } catch (Throwable ignored) {
                }
                final String[] coords = c;
                final String[] rt = route;
                runOnUiThread(() -> {
                    if (rt != null) {
                        openRoute(rt);
                    } else if (coords != null) {
                        openAmap("androidamap://viewMap?sourceApplication=gaodeshare"
                            + "&poiname=" + Uri.encode(name.isEmpty() ? "目的地" : name)
                            + "&lat=" + coords[0] + "&lon=" + coords[1] + "&dev=0");
                    } else {
                        String keywords = buildKeywords(name, addressFromUrl(lastUrl));
                        if (keywords.isEmpty()) {
                            String u = lastUrl.length() > 200 ? lastUrl.substring(0, 200) + "…" : lastUrl;
                            fail("沒抓到座標，也沒有地點名稱或地址\n\n網址：\n" + u);
                        } else {
                            Toast.makeText(this, "沒抓到座標，改用名稱搜尋", Toast.LENGTH_SHORT).show();
                            searchByName(keywords);
                        }
                    }
                });
            }).start();
        } catch (Throwable t) {
            fail("啟動失敗：\n" + t);
        }
    }

    // ---------- 路線 ----------

    private String param(String u, String key) {
        Matcher mm = Pattern.compile("[?&]" + key + "=([^&#]*)").matcher(u);
        if (!mm.find()) return "";
        try {
            return URLDecoder.decode(mm.group(1), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String[] latLon(String s) {
        Matcher mm = Pattern.compile("^\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*$").matcher(s);
        if (mm.find()) return new String[]{mm.group(1), mm.group(2)};
        return null;
    }

    private String[] parseRoute(String u) {
        try {
            String sname = "", dname = "";
            int i = u.indexOf("/maps/dir/");
            String rest = u.substring(i + "/maps/dir/".length());
            int q = rest.indexOf('?');
            if (q >= 0) rest = rest.substring(0, q);

            List<String> names = new ArrayList<>();
            for (String s : rest.split("/", -1)) {
                if (s.startsWith("@") || s.startsWith("data=")) break;
                names.add(URLDecoder.decode(s.replace("+", " "), "UTF-8").trim());
            }
            while (!names.isEmpty() && names.get(names.size() - 1).isEmpty()) {
                names.remove(names.size() - 1);
            }
            if (!names.isEmpty()) {
                dname = names.get(names.size() - 1);
                if (names.size() >= 2) sname = names.get(0);
            } else {
                sname = param(u, "origin");
                dname = param(u, "destination");
            }
            if (dname.isEmpty()) return null;

            String slat = "", slon = "", dlat = "", dlon = "";

            String[] sc = latLon(sname);
            String[] dc = latLon(dname);
            if (sc != null) { slat = sc[0]; slon = sc[1]; sname = "起點"; }
            if (dc != null) { dlat = dc[0]; dlon = dc[1]; dname = "終點"; }

            List<String[]> pts = new ArrayList<>();
            Matcher mm = Pattern.compile("!1d" + LON + "!2d" + LAT).matcher(u);
            while (mm.find()) pts.add(new String[]{mm.group(2), mm.group(1)});
            if (pts.isEmpty()) {
                mm = Pattern.compile("!3d" + LAT + "!4d" + LON).matcher(u);
                while (mm.find()) pts.add(new String[]{mm.group(1), mm.group(2)});
            }

            boolean hasOrigin = !sname.isEmpty();
            if (dlat.isEmpty() && !pts.isEmpty() && (!hasOrigin || pts.size() >= 2)) {
                String[] p = pts.get(pts.size() - 1);
                dlat = p[0];
                dlon = p[1];
            }
            if (slat.isEmpty() && hasOrigin && pts.size() >= 2) {
                String[] p = pts.get(0);
                slat = p[0];
                slon = p[1];
            }

            // Google: 0 開車 1 騎車 2 步行 3 大眾運輸
            // 高德 t: 0 駕車 1 公交 2 步行 3 騎行
            String t = "0";
            Matcher tm = Pattern.compile("!3e(\\d)").matcher(u);
            if (tm.find()) {
                String g = tm.group(1);
                if (g.equals("1")) t = "3";
                else if (g.equals("2")) t = "2";
                else if (g.equals("3")) t = "1";
            } else {
                String p = param(u, "travelmode");
                if (p.equals("bicycling")) t = "3";
                else if (p.equals("walking")) t = "2";
                else if (p.equals("transit")) t = "1";
            }

            return new String[]{slat, slon, sname, dlat, dlon, dname, t};
        } catch (Throwable e) {
            return null;
        }
    }

    private void openRoute(String[] r) {
        if (r[3].isEmpty()) {
            Toast.makeText(this, "路線沒抓到終點座標，改搜尋終點", Toast.LENGTH_SHORT).show();
            searchByName(r[5]);
            return;
        }
        StringBuilder sb = new StringBuilder("androidamap://route?sourceApplication=gaodeshare");
        if (!r[0].isEmpty()) {
            sb.append("&slat=").append(r[0]).append("&slon=").append(r[1]);
            if (!r[2].isEmpty()) sb.append("&sname=").append(Uri.encode(r[2]));
        }
        sb.append("&dlat=").append(r[3]).append("&dlon=").append(r[4]);
        sb.append("&dname=").append(Uri.encode(r[5]));
        sb.append("&dev=0&t=").append(r[6]);
        openAmap(sb.toString());
    }

    // ---------- 地點 ----------

    private boolean looksLikeAddress(String s) {
        return Pattern.compile("[市縣區鄉鎮路街段巷號]").matcher(s).find();
    }

    private String addressFromUrl(String u) {
        if (u == null) return "";
        Matcher mm = Pattern.compile("/place/([^/@?]+)").matcher(u);
        if (!mm.find()) return "";
        try {
            String a = URLDecoder.decode(mm.group(1).replace("+", " "), "UTF-8").trim();
            a = a.replaceFirst("^\\d+\\s*", "");
            a = a.replaceFirst("(?<=[區鄉鎮])[\\u4e00-\\u9fa5]{1,4}里", "");
            return a.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildKeywords(String name, String addr) {
        boolean useAddr = !addr.isEmpty() && looksLikeAddress(addr)
            && (name.isEmpty() || !addr.contains(name));
        if (name.isEmpty()) return useAddr ? addr : "";
        return useAddr ? name + " " + addr : name;
    }

    private void searchByName(String keywords) {
        openAmap("androidamap://poi?sourceApplication=gaodeshare&keywords="
            + Uri.encode(keywords) + "&dev=0");
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

    // ---------- 網路 ----------

    private HttpURLConnection open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Cookie", COOKIE);
        c.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.connect();
        return c;
    }

    private String[] findCoords(String start) throws Exception {
        String cur = start;
        String body = null;
        boolean retried = false;

        for (int i = 0; i < 10; i++) {
            lastUrl = cur;

            if (cur.contains("/maps/dir/")) {
                String[] rr = parseRoute(cur);
                if (rr != null) {
                    route = rr;
                    return null;
                }
            }

            String[] r = fromUrl(cur);
            if (r != null) return r;

            HttpURLConnection c = open(cur);
            int code = c.getResponseCode();

            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) return null;
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            if (code == 404 && !retried) {
                retried = true;
                c.disconnect();
                Thread.sleep(3000);
                continue;
            }
            if (code != 200) {
                c.disconnect();
                return null;
            }
            body = readBody(c);
            c.disconnect();
            break;
        }

        if (body == null) return null;
        return fromBody(body);
    }

    private String readBody(HttpURLConnection c) throws Exception {
        try (InputStream is = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int k;
            while ((k = is.read(buf)) > 0 && bo.size() < 3000000) bo.write(buf, 0, k);
            return bo.toString("UTF-8");
        }
    }

    private String[] m(String s, String pat) {
        Matcher mm = Pattern.compile(pat).matcher(s);
        if (mm.find()) return new String[]{mm.group(1), mm.group(2)};
        return null;
    }

    private String[] fromUrl(String s) {
        String[] r;
        if ((r = m(s, "!3d" + LAT + "!4d" + LON)) != null) return r;
        if ((r = m(s, "%213d" + LAT + "%214d" + LON)) != null) return r;
        if ((r = m(s, "[?&](?:q|ll|destination|daddr)=" + LAT + "(?:,|%2C|\\+)" + LON)) != null) return r;
        if ((r = m(s, "@" + LAT + "," + LON)) != null) return r;
        return null;
    }

    private String[] fromBody(String body) {
        String[] r;
        if ((r = m(body, "%213d" + LAT + "%214d" + LON)) != null) return r;
        if ((r = m(body, "\\[(?:null,null,|null,\\[)" + LAT + "," + LON + "]")) != null) return r;
        if ((r = m(body, "/@" + LAT + "," + LON)) != null) return r;
        if (!body.contains("<meta content=\"Google Maps\" itemprop=\"name\"")) {
            Matcher mm = Pattern.compile(
                "APP_INITIALIZATION_STATE=\\[\\[\\[[\\d.-]+," + LON + "," + LAT).matcher(body);
            if (mm.find()) return new String[]{mm.group(2), mm.group(1)};
        }
        return null;
    }
}
