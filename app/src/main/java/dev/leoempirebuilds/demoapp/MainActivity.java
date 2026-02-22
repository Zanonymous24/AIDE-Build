package dev.leoempirebuilds.demoapp;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.reward.RewardItem;
import com.google.android.gms.ads.reward.RewardedVideoAd;
import com.google.android.gms.ads.reward.RewardedVideoAdListener;
import android.content.BroadcastReceiver;
import android.content.Context;

public class MainActivity extends Activity implements RewardedVideoAdListener {
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int FILE_CHOOSER_REQUEST_CODE = 2;
    private WebView webView;
    private AdView adView;
    private InterstitialAd interstitialAd;
    private RewardedVideoAd rewardedVideoAd;
    private Handler handler;
    private ProgressBar progressBar;

    private ValueCallback<Uri[]> uploadMessage;

    private long downloadID;
    private boolean doubleBackToExitPressedOnce = false;

    private float initialY = 0;
    private boolean isRefreshing = false;
    private boolean isScrollingUp = false;

    private DownloadManager downloadManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.AppTheme);
        setContentView(R.layout.main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);

        // Configure WebView settings
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);

        // Set the WebViewClient
        webView.setWebViewClient(new WebViewClient() {
				@Override
				public void onPageStarted(WebView view, String url, Bitmap favicon) {
					progressBar.setVisibility(View.VISIBLE);
				}

				@Override
				public void onPageFinished(WebView view, String url) {
					progressBar.setVisibility(View.GONE);
					isRefreshing = false;
				}

				@Override
				public boolean shouldOverrideUrlLoading(WebView view, String url) {
					if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("tg:")) {
						Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
						startActivity(intent);
						return true;
					} else if (url.startsWith("intent://")) {
						try {
							Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
							if (intent != null) {
								intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
								startActivity(intent);
								return true;
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
						return false;
					} else {
						view.loadUrl(url);
						return false;
					}
				}
			});

        // Set WebChromeClient for file uploads
        webView.setWebChromeClient(new WebChromeClient() {
				@Override
				public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
					if (uploadMessage != null) {
						uploadMessage.onReceiveValue(null);
					}
					uploadMessage = filePathCallback;
					Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
					intent.addCategory(Intent.CATEGORY_OPENABLE);
					intent.setType("*/*");
					startActivityForResult(Intent.createChooser(intent, "File Chooser"), FILE_CHOOSER_REQUEST_CODE);
					return true;
				}
			});

        // Set the DownloadListener
        webView.setDownloadListener(new DownloadListener() {
				@Override
				public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
					if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
						requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
					} else {
						String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
						downloadFile(url, contentDisposition, mimeType, fileName);
					}
				}
			});

        // Load the initial URL
        webView.loadUrl("file:///android_asset/dev/leoempire/splash.html");

        // Set touch listener for pull to refresh
        webView.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View v, MotionEvent event) {
					switch (event.getAction()) {
						case MotionEvent.ACTION_DOWN:
							initialY = event.getY();
							isScrollingUp = false;
							break;
						case MotionEvent.ACTION_MOVE:
							if (event.getY() - initialY > 200 && !isRefreshing && !webView.canScrollVertically(-1)) {
								isRefreshing = true;
								refreshPage();
							} else if (event.getY() < initialY) {
								isScrollingUp = true;
							}
							break;
						case MotionEvent.ACTION_UP:
							if (isScrollingUp) {
								isScrollingUp = false;
							}
							break;
					}
					return false;
				}
			});

        // Initialize Mobile Ads SDK
        MobileAds.initialize(this, getString(R.string.admob_app_id));

        // Find the AdView
        adView = findViewById(R.id.adView);
        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);

        // Initialize InterstitialAd
        interstitialAd = new InterstitialAd(this);
        interstitialAd.setAdUnitId(getString(R.string.admob_interstitial_id));
        interstitialAd.loadAd(new AdRequest.Builder().build());

        // Initialize RewardedVideoAd
        rewardedVideoAd = MobileAds.getRewardedVideoAdInstance(this);
        rewardedVideoAd.setRewardedVideoAdListener(this);
        loadRewardedVideoAd();

        // Initialize Handler for ad timing
        handler = new Handler();

        // Show interstitial ads every 2 minutes (60,000 ms) only when loaded
        handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					if (interstitialAd.isLoaded()) {
						interstitialAd.show();
					} else {
						interstitialAd.loadAd(new AdRequest.Builder().build());
					}
					handler.postDelayed(this, 60000); // 2 minutes
				}
			}, 60000); // Initial delay of 2 minutes

        // Show rewarded ads every 2 minutes and 30 seconds (150,000 ms) only when loaded
        handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					if (rewardedVideoAd.isLoaded()) {
						rewardedVideoAd.show();
					} else {
						loadRewardedVideoAd();
					}
					handler.postDelayed(this, 80000); // 2 minutes 30 seconds
				}
			}, 80000); // Initial delay of 2 minutes 30 seconds
    }

    private void downloadFile(String url, String contentDisposition, String mimeType, String fileName) {
		// Attempt to get the file extension from the MIME type
		String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);

		// If the extension is still null or empty, use a fallback
		if (extension == null || extension.isEmpty()) {
			extension = "bin"; // Fallback to 'bin' if no valid extension is found
		}

		// Remove any existing extension in the file name and append the correct extension
		fileName = fileName.replaceAll("\\.[^.]*$", "") + "." + extension;

		// Create the DownloadManager request
		DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
		request.allowScanningByMediaScanner();
		request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
		request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

		// Enqueue the request in the DownloadManager
		downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
		downloadID = downloadManager.enqueue(request);

		// Show a toast notification
		Toast.makeText(this, "Download Started: " + fileName, Toast.LENGTH_SHORT).show();
	}
	

    // BroadcastReceiver for download completion
    private BroadcastReceiver onDownloadComplete = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == downloadID) {
                Toast.makeText(MainActivity.this, "Download Completed", Toast.LENGTH_SHORT).show();
            }
        }
    };

    // Handle pull to refresh
    private void refreshPage() {
        webView.reload();
        Toast.makeText(this, "Refreshing...", Toast.LENGTH_SHORT).show();
    }

    // Load RewardedVideoAd
    private void loadRewardedVideoAd() {
        rewardedVideoAd.loadAd(getString(R.string.admob_rewarded_id), new AdRequest.Builder().build());
    }

    // Handle double back press to exit
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else if (doubleBackToExitPressedOnce) {
            super.onBackPressed();
        } else {
            doubleBackToExitPressedOnce = true;
            Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show();

            new Handler().postDelayed(new Runnable() {
					@Override
					public void run() {
						doubleBackToExitPressedOnce = false;
					}
				}, 2000);
        }
    }

    // Handle file chooser activity result
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (uploadMessage == null) return;
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, intent);
            uploadMessage.onReceiveValue(result);
            uploadMessage = null;
        }
    }

    // Rewarded video ad callbacks
    @Override
    public void onRewarded(RewardItem rewardItem) {
        // Reward the user
    }

    @Override
    public void onRewardedVideoAdLoaded() {
        // Ad loaded
    }

    @Override
    public void onRewardedVideoAdOpened() {
        // Ad opened
    }

    @Override
    public void onRewardedVideoStarted() {
        // Video started
    }

    @Override
    public void onRewardedVideoAdClosed() {
        // Load the next rewarded video ad
        loadRewardedVideoAd();
    }

    @Override
    public void onRewardedVideoAdLeftApplication() {
        // Handle when the user leaves the app
    }

    @Override
    public void onRewardedVideoAdFailedToLoad(int i) {
        // Handle ad load failure
    }

    //@Override
    //public void onRewardedVideoCompleted() {
        // Video completed
    //}
}

