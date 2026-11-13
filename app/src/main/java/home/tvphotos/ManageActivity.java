package home.tvphotos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * The phone page on the TV: the same page, served by this app's own PhotoServer on 127.0.0.1, in its TV
 * layout (?tv=1), driven with the remote. Signed in by handing the WebView the page's cookie; it cannot
 * go to any other address.
 */
public class ManageActivity extends Activity {
    private static final String ORIGIN = "http://127.0.0.1:" + PhotoServer.PORT;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private int retries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PhotoServer.start(this);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setCookie(ORIGIN, "tvp=" + new Store(this).token() + "; Path=/");

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(11, 13, 18));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !sameOrigin(request.getUrl()); // stay on this TV's page
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // The page server may still be starting (just after boot or an update): try again shortly.
                if (request.isForMainFrame() && retries++ < 10) handler.postDelayed(view::reload, 1000);
            }
        });
        web.setWebChromeClient(new Dialogs());
        setContentView(web);
        web.loadUrl(ORIGIN + "/?tv=1");
        web.requestFocus();
    }

    /**
     * The page's confirm() and prompt() as plain TV dialogs (the WebView's own say "The page at
     * http://127.0.0.1 says"). Confirms open on Cancel, so a stray OK never deletes anything.
     */
    private final class Dialogs extends WebChromeClient {
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            AlertDialog d = new AlertDialog.Builder(ManageActivity.this).setMessage(message)
                    .setPositiveButton("OK", (x, w) -> result.confirm())
                    .setOnCancelListener(x -> result.cancel()).create();
            d.show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            AlertDialog d = new AlertDialog.Builder(ManageActivity.this).setMessage(message)
                    .setPositiveButton("OK", (x, w) -> result.confirm())
                    .setNegativeButton("Cancel", (x, w) -> result.cancel())
                    .setOnCancelListener(x -> result.cancel()).create();
            d.setOnShowListener(x -> d.getButton(DialogInterface.BUTTON_NEGATIVE).requestFocus());
            d.show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String value, JsPromptResult result) {
            EditText input = new EditText(ManageActivity.this);
            input.setSingleLine(true);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            if (value != null) {
                input.setText(value);
                input.setSelection(input.length());
            }
            FrameLayout pad = new FrameLayout(ManageActivity.this);
            int side = Math.round(24 * getResources().getDisplayMetrics().density);
            pad.setPadding(side, 0, side, 0);
            pad.addView(input);
            boolean[] done = {false};
            AlertDialog d = new AlertDialog.Builder(ManageActivity.this).setTitle(message).setView(pad)
                    .setPositiveButton("OK", (x, w) -> { done[0] = true; result.confirm(input.getText().toString()); })
                    .setNegativeButton("Cancel", (x, w) -> { done[0] = true; result.cancel(); })
                    .setOnDismissListener(x -> { if (!done[0]) result.cancel(); }).create();
            // The keyboard's Done key saves too.
            input.setOnEditorActionListener((v, action, event) -> {
                if (done[0]) return true; // Enter can arrive as both key down and key up
                done[0] = true;
                result.confirm(input.getText().toString());
                d.dismiss();
                return true;
            });
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            d.show();
            input.requestFocus();
            return true;
        }
    }

    private static boolean sameOrigin(Uri uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == PhotoServer.PORT;
    }

    /** Back first closes what is open on the page (a sheet, Select, a playlist); then it leaves. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.tvBack ? tvBack() : false", result -> {
            if (!"true".equals(result)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
