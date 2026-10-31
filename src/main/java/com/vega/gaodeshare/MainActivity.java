
package com.vega.gaodeshare;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private WebView webView;
    private Handler handler = new Handler(Looper.getMainLooper());

    private String sharedText = "";
    private String googleUrl = "";
    private String placeName = "";
    private String placeAddress = "";

    private boolean finished = false;
    private int attempts = 0;

    private static final int MAX_ATTEMPTS = 12;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        finished = false;
        attempts = 0;

        sharedText = "";

        if (intent == null) {
            finish();
            return;
        }

        String action = intent.getAction();

        if (Intent.ACTION_SEND.equals(action)) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) {
                sharedText = data.toString();
            }
        }

        if (sharedText == null) {
            sharedText = "";
        }

        googleUrl = findGoogleUrl(sharedText);

        if (googleUrl.isEmpty()) {
            placeName = cleanText(sharedText);
            openAmapSearch();
            return;
        }

        // 先從分享內容或網址取得備援地點資訊
        extractFallbackPlace(googleUrl);

        // 使用 WebView 載入 Google Maps，讓 JavaScript 執行
        startGoogleWebView(googleUrl);
    }

    private String findGoogleUrl(String text) {
        Pattern pattern = Pattern.compile(
                "https?://[^\\s<>\"']+",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String url = matcher.group();

            url = url.replaceAll("[),，。]+$", "");

            if (url.contains("google.com/maps")
                    || url.contains("maps.app.goo.gl")
                    || url.contains("goo.gl/maps")) {
                return url;
            }
        }

        if (text.startsWith("http://")
                || text.startsWith("https://")) {
            return text.trim();
        }

        return "";
    }

    private String cleanText(String text) {
        if (text == null) return "";

        String result = text.replaceAll(
                "https?://\\S+",
                ""
        );

        return result.trim();
    }

    private void extractFallbackPlace(String url) {
        try {
            String decoded = URLDecoder.decode(
                    url,
                    "UTF-8"
            );

            // Google Maps /place/地址+地點名稱/
            Matcher matcher = Pattern.compile(
                    "/place/([^/?]+)"
            ).matcher(decoded);

            if (matcher.find()) {
                String value = matcher.group(1)
                        .replace("+", " ")
                        .replace("%20", " ");

                // 以完整路徑作為搜尋備援
                placeName = value;
            }

            // 從分享文字補充資訊
            String text = cleanText(sharedText);

            if (!text.isEmpty()) {
                placeName = text;
            }

        } catch (Exception ignored) {
        }
    }

    private void startGoogleWebView(String url) {
        runOnUiThread(() -> {
            webView = new WebView(this);

            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setLoadsImagesAutomatically(false);
            webView.getSettings().setBlockNetworkImage(true);

            webView.setWebViewClient(new WebViewClient() {

                @Override
                public boolean shouldOverrideUrlLoading(
                        WebView view,
                        WebResourceRequest request
                ) {
                    return false;
                }

                @Override
                public void onPageFinished(
                        WebView view,
                        String loadedUrl
                ) {
                    super.onPageFinished(view, loadedUrl);

                    if (finished) return;

                    if (loadedUrl != null) {
                        googleUrl = loadedUrl;
                    }

                    extractFromJavaScript();
                }
            });

            webView.loadUrl(url);

            // Google Maps 可能需要等待 JavaScript 完成資料載入
            handler.postDelayed(
                    this::extractFromJavaScript,
                    1800
            );
        });
    }

    private void extractFromJavaScript() {
        if (finished || webView == null) return;

        attempts++;

        String javascript =
                "(function(){"
                + "var u=window.location.href||'';"
                + "var t=document.title||'';"
                + "var h=document.documentElement.innerHTML||'';"
                + "var metas=document.getElementsByTagName('meta');"
                + "var m={};"
                + "for(var i=0;i<metas.length;i++){"
                + "var k=metas[i].getAttribute('property')"
                + "||metas[i].getAttribute('name');"
                + "var v=metas[i].getAttribute('content');"
                + "if(k&&v)m[k]=v;"
                + "}"
                + "return JSON.stringify({url:u,title:t,html:h,meta:m});"
                + "})();";

        webView.evaluateJavascript(
                javascript,
                result -> {
                    if (finished) return;

                    try {
                        String jsonText = decodeJavascriptResult(result);

                        JSONObject obj = new JSONObject(jsonText);

                        String url = obj.optString("url", "");
                        String title = obj.optString("title", "");
                        String html = obj.optString("html", "");
                        JSONObject meta = obj.optJSONObject("meta");

                        if (!url.isEmpty()) {
                            googleUrl = url;
                        }

                        if (placeName.isEmpty()
                                && !title.isEmpty()
                                && !title.equals("Google 地圖")) {
                            placeName = title;
                        }

                        double[] coords = findCoordinates(
                                url,
                                html,
                                meta
                        );

                        if (coords != null) {
                            openAmapCoordinate(
                                    coords[0],
                                    coords[1]
                            );
                            return;
                        }

                    } catch (Exception ignored) {
                    }

                    if (attempts < MAX_ATTEMPTS) {
                        handler.postDelayed(
                                this::extractFromJavaScript,
                                1000
                        );
                    } else {
                        openAmapSearch();
                    }
                }
        );
    }

    private String decodeJavascriptResult(String result) {
        if (result == null || result.equals("null")) {
            return "";
        }

        try {
            return new org.json.JSONTokener(result)
                    .nextValue()
                    .toString();
        } catch (Exception e) {
            return result;
        }
    }

    private double[] findCoordinates(
            String url,
            String html,
            JSONObject meta
    ) {
        String[] sources = {
                url,
                html
        };

        // 1. Google Maps URL 中的 @lat,lng
        Pattern atPattern = Pattern.compile(
                "@(-?\\d{1,3}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)"
        );

        // 2. Google Maps data 中的 !3d緯度!4d經度
        Pattern dataPattern = Pattern.compile(
                "!3d(-?\\d{1,3}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)"
        );

        // 3. Google Maps 其他座標格式
        Pattern centerPattern = Pattern.compile(
                "!2d(-?\\d{1,3}\\.\\d+)!3d(-?\\d{1,3}\\.\\d+)"
        );

        for (String source : sources) {
            if (source == null || source.isEmpty()) continue;

            Matcher m = atPattern.matcher(source);
            if (m.find()) {
                return validCoordinates(
                        m.group(1),
                        m.group(2)
                );
            }

            m = dataPattern.matcher(source);
            if (m.find()) {
                return validCoordinates(
                        m.group(1),
                        m.group(2)
                );
            }

            m = centerPattern.matcher(source);
            if (m.find()) {
                return validCoordinates(
                        m.group(2),
                        m.group(1)
                );
            }
        }

        // 4. HTML Meta 座標
        if (meta != null) {
            String lat = meta.optString(
                    "place:location:latitude",
                    ""
            );

            String lon = meta.optString(
                    "place:location:longitude",
                    ""
            );

            double[] result = validCoordinates(lat, lon);

            if (result != null) {
                return result;
            }

            lat = meta.optString("og:latitude", "");
            lon = meta.optString("og:longitude", "");

            result = validCoordinates(lat, lon);

            if (result != null) {
                return result;
            }
        }

        return null;
    }

    private double[] validCoordinates(
            String latText,
            String lonText
    ) {
        try {
            double lat = Double.parseDouble(latText);
            double lon = Double.parseDouble(lonText);

            if (lat < -90 || lat > 90
                    || lon < -180 || lon > 180) {
                return null;
            }

            // 排除明顯不是台灣的錯誤座標不在此處處理，
            // 讓高德自行處理其他國家的座標。
            return new double[]{lat, lon};

        } catch (Exception e) {
            return null;
        }
    }

    // WGS84 轉 GCJ-02，供中國地圖座標使用
    private double[] wgs84ToGcj02(
            double lat,
            double lon
    ) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }

        double a = 6378245.0;
        double ee = 0.00669342162296594323;

        double dLat = transformLat(
                lon - 105.0,
                lat - 35.0
        );

        double dLon = transformLon(
                lon - 105.0,
                lat - 35.0
        );

        double radLat = lat / 180.0 * Math.PI;

        double magic = Math.sin(radLat);
        magic = 1 - ee * magic * magic;

        double sqrtMagic = Math.sqrt(magic);

        dLat = (dLat * 180.0)
                / ((a * (1 - ee))
                / (magic * sqrtMagic) * Math.PI);

        dLon = (dLon * 180.0)
                / (a / sqrtMagic * Math.cos(radLat) * Math.PI);

        return new double[]{
                lat + dLat,
                lon + dLon
        };
    }

    private boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347
                || lat < 0.8293 || lat > 55.8271;
    }

    private double transformLat(double x, double y) {
        double ret = -100 + 2 * x + 3 * y
                + 0.2 * y * y
                + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));

        ret += (20 * Math.sin(6 * x * Math.PI)
                + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;

        ret += (20 * Math.sin(y * Math.PI)
                + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;

        ret += (160 * Math.sin(y / 12 * Math.PI)
                + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;

        return ret;
    }

    private double transformLon(double x, double y) {
        double ret = 300 + x + 2 * y
                + 0.1 * x * x
                + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));

        ret += (20 * Math.sin(6 * x * Math.PI)
                + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;

        ret += (20 * Math.sin(x * Math.PI)
                + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;

        ret += (150 * Math.sin(x / 12 * Math.PI)
                + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;

        return ret;
    }

    private void openAmapCoordinate(double lat, double lon) {
        if (finished) return;
        finished = true;

        // 台灣等中國境外座標不轉換；
        // 中國境內 WGS84 座標轉成 GCJ-02。
        double[] converted = wgs84ToGcj02(lat, lon);

        String name = placeName.isEmpty()
                ? "Google Maps 地點"
                : placeName;

        try {
            String uri = "androidamap://viewMap"
                    + "?sourceApplication=WeiJia"
                    + "&poiname=" + encode(name)
                    + "&lat=" + converted[0]
                    + "&lon=" + converted[1]
                    + "&dev=1";

            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(uri)
            );

            intent.setPackage("com.autonavi.minimap");

            startActivity(intent);

        } catch (Exception e) {
            openAmapSearchInternal();
        }

        cleanup();
    }

    private void openAmapSearch() {
        if (finished) return;
        finished = true;

        openAmapSearchInternal();
        cleanup();
    }

    private void openAmapSearchInternal() {
        String keyword = placeName;

        if (keyword == null || keyword.trim().isEmpty()) {
            keyword = cleanText(sharedText);
        }

        if (keyword == null || keyword.trim().isEmpty()) {
            keyword = "Google Maps 地點";
        }

        try {
            String uri = "androidamap://poi"
                    + "?sourceApplication=WeiJia"
                    + "&keywords=" + encode(keyword)
                    + "&dev=0";

            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(uri)
            );

            intent.setPackage("com.autonavi.minimap");

            startActivity(intent);

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "無法開啟高德地圖",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);

        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }

        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
