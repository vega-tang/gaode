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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-S9280) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final String LAT = "([+-]?\\d{1,2}(?:\\.\\d+)?)";
    private static final String LON = "([+-]?\\d{1,3}(?:\\.\\d+)?)";

    private volatile String lastUrl = "";

    private static class ShareData {
        String url = "";
        String name = "";
        String address = "";
    }

    private void fail(String msg) {
        if (isFinishing()) return;
        try {
            new AlertDialog.Builder(this)
                    .setTitle("無法開啟目的地")
                    .setMessage(msg)
                    .setPositiveButton("關閉", (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
        } catch (Throwable ignored) {
            finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            ShareData share = readShareIntent(getIntent());
            if (share.url.isEmpty()) {
                String keywords = buildKeywords(share.name, share.address);
                if (!keywords.isEmpty()) searchByName(keywords);
                else fail("沒有收到 Google 地圖網址或地點資訊。\n\n請在 Google 地圖的地點頁按「分享」，再選擇本程式。");
                return;
            }

            final String initialUrl = share.url;
            final String initialName = share.name;
            final String initialAddress = share.address;
            Toast.makeText(this, "正在解析 Google 地圖位置…", Toast.LENGTH_SHORT).show();

            new Thread(() -> {
                String[] coords = null;
                String finalUrl = initialUrl;
                try {
                    coords = findCoords(initialUrl);
                    finalUrl = lastUrl;
                } catch (Throwable ignored) {
                    finalUrl = lastUrl;
                }

                final String[] resultCoords = coords;
                final String resolvedUrl = finalUrl;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    String name = firstNonEmpty(initialName, placeNameFromUrl(resolvedUrl));
                    String address = firstNonEmpty(initialAddress, addressFromUrl(resolvedUrl));
                    String keywords = buildKeywords(name, address);

                    if (resultCoords != null) {
                        double lat = Double.parseDouble(resultCoords[0]);
                        double lon = Double.parseDouble(resultCoords[1]);
                        // Google Maps 座標通常是 WGS-84；只在中國大陸境內轉 GCJ-02。
                        boolean mainlandChina = isInsideMainlandChina(lat, lon);
                        if (mainlandChina) {
                            double[] gcj = wgs84ToGcj02(lat, lon);
                            lat = gcj[0];
                            lon = gcj[1];
                        }
                        openAmapView(name.isEmpty() ? "目的地" : name, lat, lon,
                                mainlandChina ? 1 : 0);
                    } else if (!keywords.isEmpty()) {
                        Toast.makeText(this, "未能從 Google 網頁取得座標，改用地點名稱／地址搜尋",
                                Toast.LENGTH_LONG).show();
                        searchByName(keywords);
                    } else {
                        fail("Google 地圖網址已展開，但沒有找到座標，也沒有可供高德搜尋的地點名稱或地址。\n\n展開後網址：\n"
                                + resolvedUrl);
                    }
                });
            }).start();
        } catch (Throwable t) {
            fail("啟動時發生錯誤：\n" + t);
        }
    }

    private ShareData readShareIntent(Intent intent) {
        ShareData data = new ShareData();
        if (intent == null) return data;

        String text = null;
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String title = intent.getStringExtra(Intent.EXTRA_TITLE);
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            text = intent.getDataString();
        }
        if (text == null) text = "";

        Matcher urlMatcher = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE).matcher(text);
        if (urlMatcher.find()) data.url = trimUrlPunctuation(urlMatcher.group());

        String withoutUrl = text.replaceAll("https?://[^\\s<>\\\"']+", " ");
        String[] lines = withoutUrl.split("[\\r\\n]+");
        List<String> cleanLines = new ArrayList<>();
        for (String line : lines) {
            String s = line.trim();
            if (!s.isEmpty()) cleanLines.add(s);
        }

        if (!cleanLines.isEmpty()) {
            data.name = cleanLines.get(0);
            if (cleanLines.size() >= 2) {
                StringBuilder address = new StringBuilder();
                for (int i = 1; i < cleanLines.size(); i++) {
                    if (address.length() > 0) address.append(' ');
                    address.append(cleanLines.get(i));
                }
                data.address = address.toString();
            }
        }
        if (data.name.isEmpty() && subject != null) data.name = subject.trim();
        if (data.name.isEmpty() && title != null) data.name = title.trim();
        if (data.address.isEmpty() && looksLikeAddress(data.name)) {
            data.address = data.name;
            data.name = "";
        }
        return data;
    }

    private String trimUrlPunctuation(String value) {
        if (value == null) return "";
        return value.replaceAll("[),，。；;]+$", "").trim();
    }

    private String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b == null ? "" : b.trim();
    }

    private boolean looksLikeAddress(String s) {
        return s != null && Pattern.compile("[市縣區鄉鎮路街段巷弄號樓]").matcher(s).find();
    }

    private String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String placeNameFromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        Matcher m = Pattern.compile("/maps/place/([^/@?]+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (!m.find()) return "";
        return decode(m.group(1)).replace(',', ' ').replaceAll("\\s+", " ").trim();
    }

    private String addressFromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        Matcher m = Pattern.compile("/maps/place/([^/@?]+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (!m.find()) return "";
        String value = decode(m.group(1)).replace(',', ' ').trim();
        value = value.replaceFirst("^\\d{3,6}\\s*", "").replaceAll("\\s+", " ");
        return looksLikeAddress(value) ? value : "";
    }

    private String buildKeywords(String name, String address) {
        name = name == null ? "" : name.trim();
        address = address == null ? "" : address.trim();
        if (name.isEmpty()) return address;
        if (address.isEmpty() || address.contains(name)) return name;
        return name + " " + address;
    }

    private void searchByName(String keywords) {
        openAmap("androidamap://poi?sourceApplication=gaodeshare&keywords="
                + Uri.encode(keywords) + "&dev=0");
    }

    private void openAmapView(String name, double lat, double lon, int dev) {
        String uri = "androidamap://viewMap?sourceApplication=gaodeshare"
                + "&poiname=" + Uri.encode(name)
                + "&lat=" + String.format(Locale.US, "%.7f", lat)
                + "&lon=" + String.format(Locale.US, "%.7f", lon)
                + "&dev=" + dev;
        openAmap(uri);
    }

    private void openAmap(String uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            intent.setPackage("com.autonavi.minimap");
            startActivity(intent);
            finish();
        } catch (Throwable e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                startActivity(fallback);
                finish();
            } catch (Throwable ignored) {
                fail("無法開啟高德地圖。\n請確認已安裝 Google Play 版高德地圖。\n\n" + e);
            }
        }
    }

    private HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.connect();
        return c;
    }

    private String[] findCoords(String startUrl) throws Exception {
        String currentUrl = startUrl;
        String html = null;

        for (int i = 0; i < 12; i++) {
            lastUrl = currentUrl;
            String[] fromUrl = coordinatesFromText(currentUrl);
            if (fromUrl != null) return fromUrl;

            HttpURLConnection c = null;
            try {
                c = openConnection(currentUrl);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    if (location == null || location.trim().isEmpty()) return null;
                    currentUrl = new URL(new URL(currentUrl), location).toString();
                    continue;
                }
                if (code == 404 && i < 2) {
                    c.disconnect();
                    c = null;
                    Thread.sleep(1200);
                    continue; // 下一輪重新建立連線，不重用已失敗的 connection。
                }
                if (code != HttpURLConnection.HTTP_OK) return null;
                html = readBody(c);
                c.disconnect();
                c = null;
                break;
            } finally {
                if (c != null) c.disconnect();
            }
        }

        if (html == null) return null;
        String[] result = coordinatesFromText(html);
        if (result != null) return result;
        String normalized = html.replace("\\u003d", "=")
                .replace("\\u0026", "&").replace("\\/", "/")
                .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"");
        return coordinatesFromText(normalized);
    }

    private String readBody(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1 && out.size() < 5_000_000) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    private String[] matchCoordinates(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            String lat = m.group(1);
            String lon = m.group(2);
            if (validCoordinates(lat, lon)) return new String[]{lat, lon};
        }
        return null;
    }

    private boolean validCoordinates(String latText, String lonText) {
        try {
            double lat = Double.parseDouble(latText);
            double lon = Double.parseDouble(lonText);
            return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                    && !(Math.abs(lat) < 0.000001 && Math.abs(lon) < 0.000001);
        } catch (Exception e) {
            return false;
        }
    }

    private String[] coordinatesFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] result;
        // !3d / !4d 是 Google Maps 地點 pin 座標，優先於地圖視窗中心點。
        result = matchCoordinates(text, "!3d" + LAT + "!4d" + LON);
        if (result != null) return result;
        result = matchCoordinates(text, "%213d" + LAT + "%214d" + LON);
        if (result != null) return result;
        result = matchCoordinates(text, "@" + LAT + "\\s*,\\s*" + LON);
        if (result != null) return result;
        result = matchCoordinates(text,
                "[?&](?:q|query|ll|center|destination|daddr)=\\s*" + LAT
                        + "(?:,|%2c|\\+|%20)\\s*" + LON);
        if (result != null) return result;
        String decoded = text.replace("&#33;", "!").replace("&#x21;", "!");
        if (!decoded.equals(text)) return matchCoordinates(decoded, "!3d" + LAT + "!4d" + LON);
        return null;
    }

    private boolean isInsideMainlandChina(double lat, double lon) {
        return lat >= 0.8293 && lat <= 55.8271 && lon >= 72.004 && lon <= 137.8347;
    }

    private double transformLat(double x, double y) {
        double value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y
                + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        value += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        value += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        value += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return value;
    }

    private double transformLon(double x, double y) {
        double value = 300.0 + x + 2.0 * y + 0.1 * x * x
                + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        value += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        value += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        value += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return value;
    }

    private double[] wgs84ToGcj02(double lat, double lon) {
        final double a = 6378245.0;
        final double ee = 0.00669342162296594323;
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - ee * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }
}
