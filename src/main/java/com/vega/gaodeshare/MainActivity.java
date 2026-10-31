
package com.vega.gaodeshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static class ShareData {
        String name = "";
        String address = "";
        String text = "";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            ShareData share = readShareIntent(getIntent());

            String keywords = buildKeywords(share);

            if (keywords.isEmpty()) {
                fail("沒有取得地點名稱或地址。\n\n請從 Google 地圖的地點頁面按分享，再選擇本程式。");
                return;
            }

            // 直接使用店名及地址搜尋高德，不再解析 Google 網址。
            openAmapSearch(keywords);

        } catch (Throwable e) {
            fail("啟動失敗：\n" + e);
        }
    }

    private ShareData readShareIntent(Intent intent) {

        ShareData data = new ShareData();

        if (intent == null) return data;

        String text = "";
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String title = intent.getStringExtra(Intent.EXTRA_TITLE);

        if (Intent.ACTION_SEND.equals(intent.getAction())) {

            text = intent.getStringExtra(Intent.EXTRA_TEXT);

        } else if (Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null) {

            text = intent.getDataString();
        }

        if (text == null) text = "";

        // 移除 Google Maps 網址，只保留店名及地址。
        text = text.replaceAll(
                "(?i)https?://[^\\s<>\"']+",
                " "
        );

        // 清理空白及多餘標點。
        String[] lines = text.split("[\\r\\n]+");

        List<String> cleanLines = new ArrayList<>();

        for (String line : lines) {

            String s = line.trim();

            if (!s.isEmpty()) {
                cleanLines.add(s);
            }
        }

        if (!cleanLines.isEmpty()) {

            data.name = cleanLines.get(0);

            if (cleanLines.size() >= 2) {

                StringBuilder address = new StringBuilder();

                for (int i = 1; i < cleanLines.size(); i++) {

                    if (address.length() > 0) {
                        address.append(" ");
                    }

                    address.append(cleanLines.get(i));
                }

                data.address = address.toString();
            }
        }

        if (data.name.isEmpty() && subject != null) {
            data.name = subject.trim();
        }

        if (data.name.isEmpty() && title != null) {
            data.name = title.trim();
        }

        // 如果第一行本身就是地址，就不要當成店名。
        if (data.address.isEmpty()
                && looksLikeAddress(data.name)) {

            data.address = data.name;
            data.name = "";
        }

        data.text = text;

        return data;
    }

    private boolean looksLikeAddress(String s) {

        if (s == null || s.isEmpty()) return false;

        return Pattern.compile(
                "[市縣區鄉鎮路街段巷弄號樓]"
        ).matcher(s).find();
    }

    private String buildKeywords(ShareData data) {

        String name = data.name.trim();
        String address = data.address.trim();

        if (name.isEmpty()) {
            return address;
        }

        if (address.isEmpty()) {
            return name;
        }

        if (address.contains(name)) {
            return address;
        }

        return name + " " + address;
    }

    private void openAmapSearch(String keywords) {

        String uri =
                "androidamap://poi"
                + "?sourceApplication=gaodeshare"
                + "&keywords=" + Uri.encode(keywords)
                + "&dev=0";

        try {

            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(uri)
            );

            intent.setPackage("com.autonavi.minimap");

            startActivity(intent);
            finish();

        } catch (Throwable e) {

            try {

                Intent fallback = new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(uri)
                );

                startActivity(fallback);
                finish();

            } catch (Throwable ignored) {

                fail(
                        "無法開啟高德地圖。\n"
                        + "請確認已安裝 Google Play 版高德地圖。\n\n"
                        + e
                );
            }
        }
    }

    private void fail(String message) {

        try {

            new AlertDialog.Builder(this)
                    .setTitle("錯誤")
                    .setMessage(message)
                    .setPositiveButton(
                            "關閉",
                            (dialog, which) -> finish()
                    )
                    .setOnCancelListener(
                            dialog -> finish()
                    )
                    .show();

        } catch (Throwable ignored) {

            finish();
        }
    }
}
