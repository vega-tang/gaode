
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
import android.webkit.WebResourceResponse;

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
    private boolean pageLoadFailed = false;
    private int attempts = 0;

    private static final int MAX_ATTEMPTS = 15;
    private static final long RETRY_INTERVAL = 1200;

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
        cleanupWebViewOnly();

        finished = false;
        pageLoadFailed = false;
        attempts = 0;

        sharedText = "";
        googleUrl = "";
        placeName = "";
        placeAddress = "";

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

        // 不從原始分享網址解析座標。
        // 只讓 WebView 載入 Google Maps 後解析網頁內容。
        startGoogleWebView(googleUrl);
    }

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

            if (url.contains("google.com/maps")
                    || url.contains("maps.app.goo.gl")
                    || url.contains("goo.gl/maps")) {
                return url;
            }
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

    // =====================================================
    // WebView：載入 Google Maps 網頁
    // =====================================================

    private void startGoogleWebView(String url) {

        runOnUiThread(() -> {

            webView = new WebView(this);

            // 讓 WebView 真正附加到 Activity，
            // 但保持隱藏，不影響使用者畫面。
            FrameLayout container = new FrameLayout(this);

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
            webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);

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

                    pageLoadFailed = false;

                    if (loadedUrl != null) {
                        googleUrl = loadedUrl;
                    }

                    // 網頁載入完成後，等待 Google Maps 動態資料。
                    handler.removeCallbacks(retryRunnable);

                    handler.postDelayed(
                            retryRunnable,
                            1000
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
                        pageLoadFailed = true;
                    }
                }

                @Override
                public void onReceivedHttpError(
                        WebView view,
                        WebResourceRequest request,
                        WebResourceResponse response
                ) {
                    super.onReceivedHttpError(view, request, response);

                    if (request != null && request.isForMainFrame()) {
                        if (response != null
                                && response.getStatusCode() >= 400) {
                            pageLoadFailed = true;
                        }
                    }
                }
            });

            webView.loadUrl(url);

            handler.postDelayed(
                    retryRunnable,
                    2500
            );
        });
    }

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (finished) return;

            extractFromJavaScript();
        }
    };

    // =====================================================
    // 方法 2：從載入後的 Google Maps 網頁取得資料
    // =====================================================

    private void extractFromJavaScript() {

        if (finished || webView == null) return;

        attempts++;

        String javascript =
                "(function(){"

                + "var u=window.location.href||'';"
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

                // 優先尋找地點詳細資料座標。
                + "var patterns=["
                + "/!3d(-?\\\\d{1,3}\\\\.\\\\d+)!4d(-?\\\\d{1,3}\\\\.\\\\d+)/,"
                + "/!2d(-?\\\\d{1,3}\\\\.\\\\d+)!3d(-?\\\\d{1,3}\\\\.\\\\d+)/,"
                + "/@(-?\\\\d{1,3}\\\\.\\\\d+),\\\\s*(-?\\\\d{1,3}\\\\.\\\\d+)/"
                + "];"

                + "var sources=[u,h];"
                + "var lat='';"
                + "var lon='';"

                + "for(var p=0;p<patterns.length;p++){"
                + "for(var s=0;s<sources.length;s++){"
                + "var match=sources[s].match(patterns[p]);"
                + "if(match){"

                + "if(p===0){lat=match[1];lon=match[2];}"
                + "else if(p===1){lon=match[1];lat=match[2];}"
                + "else{lat=match[1];lon=match[2];}"

                + "break;"
                + "}"
                + "}"
                + "if(lat&&lon)break;"
                + "}"

                // Meta 座標作為備援。
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
                result -> {

                    if (finished) return;

                    try {

                        String jsonText =
                                decodeJavascriptResult(result);

                        JSONObject obj =
                                new JSONObject(jsonText);

                        String url =
                                obj.optString("url", "");

                        String title =
                                obj.optString("title", "");

                        String ogTitle =
                                obj.optString("ogtitle", "");

                        String description =
                                obj.optString("description", "");

                        String lat =
                                obj.optString("lat", "");

                        String lon =
                                obj.optString("lon", "");

                        if (!url.isEmpty()) {
                            googleUrl = url;
                        }

                        // 只接受真正的 Google Maps 地點標題。
                        String candidate = "";

                        if (isValidPlaceTitle(title)) {
                            candidate = cleanPlaceTitle(title);
                        }

                        if (candidate.isEmpty()
                                && isValidPlaceTitle(ogTitle)) {
                            candidate = cleanPlaceTitle(ogTitle);
                        }

                        if (!candidate.isEmpty()) {
                            placeName = candidate;
                        }

                        if (isValidDescription(description)) {
                            placeAddress = description.trim();
                        }

                        // 先使用 JavaScript 已經從網頁取出的座標。
                        double[] coords =
                                validCoordinates(lat, lon);

                        if (coords == null) {
                            // 再從載入後的頁面網址或頁面資料檢查。
                            coords = findCoordinates(
                                    googleUrl,
                                    ""
                            );
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

                        // 網頁解析失敗，不能拿錯誤標題當地名。
                        if (!placeName.isEmpty()) {
                            openAmapSearch();
                        } else {
                            showFailure();
                        }
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

    // =====================================================
    // 地名及錯誤頁面判斷
    // =====================================================

    private boolean isValidPlaceTitle(String title) {

        if (title == null) return false;

        String value = title.trim();

        if (value.isEmpty()) return false;

        String lower = value.toLowerCase();

        String[] invalid = {
                "google maps",
                "google 地圖",
                "google 地图",
                "網頁無法使用",
                "网页无法使用",
                "無法連線",
                "无法连接",
                "page not found",
                "404",
                "403",
                "access denied",
                "error",
                "錯誤",
                "错误",
                "請稍候",
                "please wait",
                "sign in",
                "登入",
                "登录"
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
                " - Google 地圖",
                " · Google Maps",
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
                && !lower.contains("網頁無法使用")
                && !lower.contains("page not found")
                && !lower.contains("access denied");
    }

    // =====================================================
    // 從 WebView 已載入的頁面資料尋找座標
    // 注意：不解析最初收到的分享網址
    // =====================================================

    private double[] findCoordinates(
            String loadedUrl,
            String html
    ) {

        String[] sources = {
                html,
                loadedUrl
        };

        Pattern dataPattern = Pattern.compile(
                "!3d(-?\\d{1,3}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)"
        );

        Pattern centerPattern = Pattern.compile(
                "!2d(-?\\d{1,3}\\.\\d+)!3d(-?\\d{1,3}\\.\\d+)"
        );

        Pattern atPattern = Pattern.compile(
                "@(-?\\d{1,3}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)"
        );

        for (String source : sources) {

            if (source == null || source.isEmpty()) {
                continue;
            }

            Matcher m = dataPattern.matcher(source);

            if (m.find()) {
                double[] result = validCoordinates(
                        m.group(1),
                        m.group(2)
                );

                if (result != null) return result;
            }

            m = centerPattern.matcher(source);

            if (m.find()) {
                double[] result = validCoordinates(
                        m.group(2),
                        m.group(1)
                );

                if (result != null) return result;
            }

            m = atPattern.matcher(source);

            if (m.find()) {
                double[] result = validCoordinates(
                        m.group(1),
                        m.group(2)
                );

                if (result != null) return result;
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

            // 排除 (0,0) 及明顯無效值。
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

    // =====================================================
    // 開啟高德地圖
    // =====================================================

    private void openAmapCoordinate(
            double lat,
            double lon
    ) {

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

        } catch (Exception e) {

            finished = false;
            openAmapSearch();
            return;
        }

        cleanup();
    }

    // 方法 3：交給高德搜尋地名
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
            keyword = cleanText(sharedText);
        }

        if (keyword == null || keyword.trim().isEmpty()) {
            showFailure();
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

    private void showFailure() {

        if (finished) return;

        finished = true;

        Toast.makeText(
                this,
                "無法讀取 Google Maps 地點，請確認網路或稍後再試",
                Toast.LENGTH_LONG
        ).show();

        cleanup();
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
            webView.loadUrl("about:blank");
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
