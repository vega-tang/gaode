package com.vega.gaodeshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public class MainActivity extends Activity {

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
            }

            String name = "";
            if (text != null) name = text.replaceAll("https?://\\S+", "").trim();
            if (name.isEmpty() && subject != null) name = subject.trim();
            if (name.isEmpty() && title != null) name = title.trim();
            int nl = name.indexOf('\n');
            if (nl > 0) name = name.substring(0, nl).trim();

            if (name.isEmpty()) {
                fail("沒有收到地點名稱，請從 Google 地圖的地點頁按「分享」。\n\n收到的內容：\n" + text);
                return;
            }

            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                "androidamap://poi?sourceApplication=gaodeshare&keywords="
                + Uri.encode(name) + "&dev=0"));
            i.setPackage("com.autonavi.minimap");
            startActivity(i);
            finish();
        } catch (Throwable t) {
            fail("錯誤：\n" + t);
        }
    }
}
