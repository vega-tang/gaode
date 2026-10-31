
package com.vega.gaodeshare;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;

import org.json.JSONObject;
import org.json.JSONTokener;

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
    private boolean mainPageError = false;

    private int attempts = 0;

    private static final int MAX_ATTEMPTS = 15;
    private static final long RETRY_INTERVAL = 1200;

    private Toast currentToast;

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

    // =====================================================
    // 接收啟動及分享 Intent
    // =====================================================

    private void handleIncomingIntent(Intent intent) {

        cleanupWebViewOnly();

        finished = false;
        mainPageError = false;
        attempts = 0;

        sharedText = "";
        googleUrl = "";
        placeName = "";
        placeAddress = "";

        if (intent == null) {
            showLaunchHint();
            return;
        }

        String action = intent.getAction();

        // 直接開啟 App
        if (Intent.ACTION_MAIN.equals(action)
                || action == null) {

            showLaunchHint();
            return;
        }

        // Google Maps 分享
        if (Intent.ACTION_SEND.equals(action)) {

            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);

            if (sharedText == null) {
                CharSequence extra =
                        intent.getCharSequenceExtra(Intent.EXTRA_TEXT);

                if (extra != null) {
                    sharedText = extra.toString();
                }
            }

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

        // 分享進來但沒有 Google Maps 網址
        if (googleUrl.isEmpty()) {

            placeName = cleanText(sharedText);

            if (!placeName.isEmpty()) {
                openAmapSearch();
            } else {
                showLaunchHint();
            }

            return;
        }

        showToast("正在讀取 Google Maps 地點…");

        // 不解析原始分享網址座標
        // 交由 WebView 載入網頁後取得資料
        startGoogleWebView(googleUrl);
    }

    private void showLaunchHint() {

        showToast(
                "請先開啟 Google 地圖，再分享給高德轉換器"
        );

        handler.postDelayed(() -> finish(), 1800);
    }

    private void showToast(String message) {

        if (currentToast != null) {
            currentToast.cancel();
        }

        currentToast = Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
        );

        currentToast.show();
    }

    // =====================================================
    // 分享文字處理
    // =====================================================

    private String findGoogleUrl(String text) {

        if (text == null) return "";

        Pattern pattern = Pattern.compile(
                "https?://[^\\s<>\"']+",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {

            String url = matcher.group();

            url = url.replaceAll("[),，。]+$", "");

            String lower = url.toLowerCase();

            if (lower.contains("google.com/maps")
                    || lower.contains("maps.app.goo.gl")
                    || lower.contains("goo.gl/maps")) {

                return url;
            }
        }

        return "";
    }

    private String cleanText(String text) {

        if (text == null) return "";

        return text.replaceAll(
                "https?://\\S+",
                ""
        ).trim();
    }

    // =====================================================
    // WebView
    // =====================================================

    private void startGoogleWebView(String url) {

        try {

            FrameLayout container = new FrameLayout(this);

            webView = new WebView(this);

            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(1, 1);

            webView.setLayoutParams(params);
            webView.setAlpha(0f);

            container.addView(webView);

            setContentView(container);

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

                    mainPageError = false;

                    if (loadedUrl != null) {
                        googleUrl = loadedUrl;
                    }

                    handler.removeCallbacks(retryRunnable);

                    handler.postDelayed(
                            retryRunnable,
                            1200
                    );
                }

                @Override
                public void onReceivedError(
                        WebView view,
                        WebResourceRequest request,
                        WebResourceError error
                ) {

                    super.onReceivedError(view, request, error);

                    if (request != null && request.isForMainFrame()) {
                        mainPageError = true;
                    }
                }
            });

            webView.loadUrl(url);

            handler.postDelayed(
                    retryRunnable,
                    3000
            );

        } catch (Exception e) {

            showFailure("無法啟動網頁解析");
        }
    }

    // =====================================================
    // WebView JavaScript 解析
    // =====================================================

    private final Runnable retryRunnable = new Runnable() {

        @Override
        public void run() {

            if (finished || webView == null) return;

            extractFromJavaScript();
        }
    };

    private void extractFromJavaScript() {

        if (finished || webView == null) return;

        attempts++;

        String javascript =
                "(function(){"

                + "var u=location.href||'';"
                + "var t=document.title||'';"

                + "var metas=document.getElementsByTagName('meta');"
                + "var m={};"

                + "for(var i=0;i<metas.length;i++){"
                + "var k=metas[i].getAttribute('property')"
                + "||metas[i].getAttribute('name');"
                + "var v=metas[i].getAttribute('content');"
                + "if(k&&v)m[k]=v;"
                + "}"

                + "var h=document.documentElement"
                + "?document.documentElement.innerHTML:'';"

                // 優先讀取 Google Maps 地點座標
                + "var patterns=["
                + "new RegExp('!3d(-?[0-9.]+)!4d(-?[0-9.]+)'),"
                + "new RegExp('!2d(-?[0-9.]+)!3d(-?[0-9.]+)'),"
                + "new RegExp('@(-?[0-9.]+),\\\\s*(-?[0-9.]+)')"
                + "];"

                + "var sources=[h,u];"
                + "var lat='';"
                + "var lon='';"

                + "for(var p=0;p<patterns.length;p++){"
                + "for(var s=0;s<sources.length;s++){"
                + "var q=sources[s].match(patterns[p]);"
                + "if(q){"
                + "if(p===1){lon=q[1];lat=q[2];}"
                + "else{lat=q[1];lon=q[2];}"
                + "break;"
                + "}"
                + "}"
                + "if(lat&&lon)break;"
                + "}"

                + "if(!lat||!lon){"
                + "lat=m['place:location:latitude']||m['og:latitude']||'';"
                + "lon=m['place:location:longitude']||m['og:longitude']||'';"
                + "}"

                + "return JSON.stringify({"
                + "url:u,"
                + "title:t,"
                + "ogtitle:m['og:title']||'',"
                + "description:m['og:description']||'',"
                + "lat:lat,"
                + "lon:lon"
                + "});"

                + "})();";

        webView.evaluateJavascript(
                javascript,
                result -> processJavaScriptResult(result)
        );
    }

    private void processJavaScriptResult(String result) {

        if (finished) return;

        try {

            Object decoded =
                    new JSONTokener(result).nextValue();

            JSONObject obj;

            if (decoded instanceof String) {
                obj = new JSONObject((String) decoded);
            } else if (decoded instanceof JSONObject) {
                obj = (JSONObject) decoded;
            } else {
                throw new Exception("無效的網頁資料");
            }

            String loadedUrl = obj.optString("url", "");
            String title = obj.optString("title", "");
            String ogTitle = obj.optString("ogtitle", "");
            String description = obj.optString("description", "");

            String lat = obj.optString("lat", "");
            String lon = obj.optString("lon", "");

            if (!loadedUrl.isEmpty()) {
                googleUrl = loadedUrl;
            }

            // 只接受有效地點標題
            if (isValidPlaceTitle(title)) {
                placeName = cleanPlaceTitle(title);
            }

            if (placeName.isEmpty() && isValidPlaceTitle(ogTitle)) {
                placeName = cleanPlaceTitle(ogTitle);
            }

            if (isValidDescription(description)) {
                placeAddress = description.trim();
            }

            double[] coords = validCoordinates(lat, lon);

            if (coords == null) {
                coords = findCoordinates(loadedUrl);
            }

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
                    retryRunnable,
                    RETRY_INTERVAL
            );

        } else {

            // 只有找到有效地名才交給高德搜尋
            if (!placeName.isEmpty()) {

                openAmapSearch();

            } else if (!placeAddress.isEmpty()) {

                placeName = placeAddress;
                openAmapSearch();

            } else {

                showFailure(
                        mainPageError
                                ? "Google Maps 網頁載入失敗"
                                : "無法從 Google Maps 取得地點資料"
                );
            }
        }
    }

    // =====================================================
    // 地名有效性檢查
    // =====================================================

    private boolean isValidPlaceTitle(String title) {

        if (title == null || title.trim().isEmpty()) {
            return false;
        }

        String lower = title.toLowerCase();

        String[] invalid = {
                "google maps",
                "google 地圖",
                "google 地图",
                "網頁無法使用",
                "网页无法使用",
                "page not found",
                "access denied",
                "error",
                "錯誤",
                "错误",
                "請稍候",
                "please wait",
                "sign in",
                "登入",
                "登录",
                "404",
                "403"
        };

        for (String word : invalid) {

            if (lower.contains(word.toLowerCase())) {
                return false;
            }
        }

        return true;
    }

    private String cleanPlaceTitle(String title) {

        if (title == null) return "";

        String result = title.trim();

        String[] suffixes = {
                " - Google Maps",
                " · Google Maps",
                " - Google 地圖",
                " · Google 地圖",
                " - Google 地图",
                " · Google 地图"
        };

        for (String suffix : suffixes) {

            if (result.endsWith(suffix)) {

                result = result.substring(
                        0,
                        result.length() - suffix.length()
                ).trim();
            }
        }

        return result;
    }

    private boolean isValidDescription(String description) {

        if (description == null) return false;

        String value = description.trim();

        if (value.length() < 3) return false;

        String lower = value.toLowerCase();

        return !lower.contains("google maps")
                && !lower.contains("page not found")
                && !lower.contains("access denied")
                && !lower.contains("網頁無法使用");
    }

    // =====================================================
    // 從 WebView 已載入的頁面網址尋找座標
    // 不讀取原始分享網址
    // =====================================================

    private double[] findCoordinates(String url) {

        if (url == null || url.isEmpty()) return null;

        Pattern dataPattern = Pattern.compile(
                "!3d(-?[0-9]{1,3}\\.[0-9]+)!4d(-?[0-9]{1,3}\\.[0-9]+)"
        );

        Matcher m = dataPattern.matcher(url);

        if (m.find()) {
            return validCoordinates(
                    m.group(1),
                    m.group(2)
            );
        }

        Pattern centerPattern = Pattern.compile(
                "!2d(-?[0-9]{1,3}\\.[0-9]+)!3d(-?[0-9]{1,3}\\.[0-9]+)"
        );

        m = centerPattern.matcher(url);

        if (m.find()) {
            return validCoordinates(
                    m.group(2),
                    m.group(1)
            );
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

            if (lat == 0 && lon == 0) {
                return null;
            }

            return new double[]{lat, lon};

        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================
    // WGS84 轉 GCJ-02
    // =====================================================

    private double[] wgs84ToGcj02(double lat, double lon) {

        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }

        double a = 6378245.0;
        double ee = 0.00669342162296594323;

        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);

        double radLat = lat / 180.0 * Math.PI;

        double magic = Math.sin(radLat);
        magic = 1 - ee * magic * magic;

        double sqrtMagic = Math.sqrt(magic);

        dLat = (dLat * 180.0)
                / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI);

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

    // =====================================================
    // 高德跳轉
    // =====================================================

    private void openAmapCoordinate(double lat, double lon) {

        if (finished) return;

        finished = true;

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

            cleanup();

        } catch (Exception e) {

            finished = false;
            openAmapSearch();
        }
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
            keyword = placeAddress;
        }

        if (keyword == null || keyword.trim().isEmpty()) {
            showFailure("沒有取得有效的地點名稱");
            return;
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

    private void showFailure(String message) {

        if (finished) return;

        finished = true;

        showToast(message);

        handler.postDelayed(
                this::cleanup,
                2200
        );
    }

    private String encode(String value) {

        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    // =====================================================
    // 清理
    // =====================================================

    private void cleanupWebViewOnly() {

        handler.removeCallbacksAndMessages(null);

        if (webView != null) {

            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();

            webView = null;
        }
    }

    private void cleanup() {

        handler.removeCallbacksAndMessages(null);

        if (webView != null) {

            webView.stopLoading();
            webView.setWebViewClient(null);
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
