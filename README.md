# 高德轉換器

在 Android 上，把 Google 地圖的地點或路線分享給這個 App，它會自動打開高德地圖。

## 功能

- 從 Google 地圖的地點頁按「分享」，選「高德轉換器」，高德會搜尋該地點（店名加地址）。
- 分享的是路線時，會帶入終點，並對應開車、步行、騎車、大眾運輸等交通方式。
- 支援 maps.app.goo.gl 短網址。
- 分享內容沒有網址時，直接用分享的文字當關鍵字在高德搜尋。

## 使用方式

1. 在 Google 地圖選一個地點，按「分享」。
2. 在分享選單選「高德轉換器」。
3. 高德地圖自動打開，顯示搜尋結果，點一下結果再按導航。

## 安裝

1. 到本倉庫的 Releases 頁面，下載最新的 GaodeShare.apk。
2. 用手機安裝。第一次安裝，手機可能會要求允許瀏覽器安裝未知來源的 App。
3. 手機需要先安裝高德地圖（套件名稱 com.autonavi.minimap）。

### 更新

每次編譯產生的簽章都不同，所以更新時 Android 會拒絕直接覆蓋。請先移除舊版，再安裝新版。

## 已知限制

- Google 的地點分享連結只帶地址，沒有座標，所以無法直接指定位置，需要在高德的搜尋結果點一下。
- 路線分享同樣沒有座標時，只會帶終點。起點如果不是目前位置，高德會用目前位置當起點。
- 高德在台灣的路線資料可能不完整，會影響能不能順利規劃路線。
- 只支援 Android，只能開啟高德地圖（標準版）。

## 運作方式

程式只做三件事：

1. 從分享內容取出網址和地點名稱。
2. 展開短網址，從展開後的網址取出地址（或路線的起訖點）。
3. 組成高德的 URI（androidamap://poi 或 androidamap://route）並開啟高德。

程式碼在 src/main/java/com/vega/gaodeshare/MainActivity.java。

## 編譯

推送到 main 分支後，GitHub Actions 會自動編譯，並把 APK 發佈到 Releases。設定檔在 .github/workflows/build.yml。

編譯環境：JDK 17、Gradle 8.7、Android Gradle Plugin 8.5.2、compileSdk 34、minSdk 24。
