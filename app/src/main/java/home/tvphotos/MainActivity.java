package home.tvphotos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** What the TV shows when the app is opened: where to go on the phone, and the Photos & playlists, preview and PIN buttons. */
public class MainActivity extends Activity {
    private Store store;
    private TextView address, count;
    private ImageView qr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PhotoServer.start(this);
        store = new Store(this);

        // Text and buttons on the left, the QR code on the right: stacked, they did not fit 1080 px.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        int pad = dp(48);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER);
        text.addView(label("TV Photos", 44, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL)));
        text.addView(label("On your phone, open this address and enter your PIN:", 22, 0xB3FFFFFF, null), margin(0, 24));
        address = label("", 40, Color.WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.addView(address, margin(0, 12));
        text.addView(label("First time, or forgot the PIN? Choose New random PIN below.", 18, 0x80FFFFFF, null), margin(0, 12));
        count = label("", 20, 0x99FFFFFF, null);
        text.addView(count, margin(0, 20));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        Button manage = button("Photos & playlists");
        manage.setOnClickListener(v -> startActivity(new Intent(this, ManageActivity.class)));
        buttons.addView(manage);
        Button preview = button("Preview screensaver");
        preview.setOnClickListener(v -> preview());
        Button newPin = button("New random PIN");
        newPin.setOnClickListener(v -> confirmNewPin());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(24);
        buttons.addView(preview, lp);
        buttons.addView(newPin, lp);
        text.addView(buttons, margin(0, 40));
        root.addView(text);

        LinearLayout scan = new LinearLayout(this);
        scan.setOrientation(LinearLayout.VERTICAL);
        scan.setGravity(Gravity.CENTER_HORIZONTAL);
        qr = new ImageView(this);
        scan.addView(qr);
        scan.addView(label("or scan this", 18, 0x99FFFFFF, null), margin(0, 12));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = dp(56);
        sp.gravity = Gravity.CENTER_VERTICAL;
        root.addView(scan, sp);

        setContentView(root);
        manage.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String url = Store.url();
        address.setText(url == null ? "(the TV is not on the network)" : url);
        if (url != null) {
            Bitmap code = Qr.make(url, dp(110));
            qr.setImageBitmap(code);
            qr.setBackgroundColor(Color.WHITE);
            qr.setLayoutParams(sized(margin(0, 0), code.getWidth(), code.getHeight()));
        }
        qr.setVisibility(url == null ? View.GONE : View.VISIBLE);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (java.io.File f : store.list()) names.add(f.getName());
        int n = names.size(), combos = Library.read(store.library).combos(names).size();
        count.setText((n == 1 ? "1 photo" : n + " photos")
                + (combos == 0 ? "" : combos == 1 ? " · 1 combination" : " · " + combos + " combinations"));
    }

    /**
     * A stray OK must not replace a PIN the family knows, so ask first; then show the new PIN, the only place
     * the TV ever shows one.
     */
    private void confirmNewPin() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("New random PIN?")
                .setMessage("This replaces the current PIN. Every phone will need to enter the new one."
                        + "\n\nYou can choose your own PIN again on the phone page.")
                .setPositiveButton("Make new PIN", (d, w) -> showPin(store.newPin()))
                .setNegativeButton("Keep current PIN", null)
                .show()
                .getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private void showPin(String pin) {
        TextView big = label(pin, 64, Color.WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        big.setLetterSpacing(0.3f);
        big.setPadding(0, dp(16), 0, dp(8));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Your new PIN")
                .setView(big)
                .setMessage("Enter it on the phone page. It is not shown again.")
                .setPositiveButton("Done", null)
                .show();
    }

    /** Starts the screensaver the TV has selected, the same way it starts after the idle time. */
    private void preview() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")));
        } catch (RuntimeException e) {
            Toast.makeText(this, "This TV cannot start the screensaver from here.", Toast.LENGTH_LONG).show();
        }
    }

    private TextView label(String text, int sp, int color, Typeface face) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        if (face != null) v.setTypeface(face);
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setPadding(dp(28), dp(14), dp(28), dp(14));
        b.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, pill(0xFFFFFFFF));
        bg.addState(new int[]{}, pill(0x33FFFFFF));
        b.setBackground(bg);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Color.BLACK, Color.WHITE}));
        return b;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(32));
        return d;
    }

    private LinearLayout.LayoutParams margin(int left, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(left), dp(top), 0, 0);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private static LinearLayout.LayoutParams sized(LinearLayout.LayoutParams lp, int w, int h) {
        lp.width = w;
        lp.height = h;
        return lp;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
