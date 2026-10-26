package com.example.instagramautoreactor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String PREFS = "autoreactor";
    static final String KEY_ACTIVE = "active";
    static final String KEY_EMOJI = "emoji";
    static final String KEY_COUNT = "count";
    static final String KEY_STATUS = "status";
    static final String[] EMOJIS = {"❤️", "😂", "😮", "😢", "😡", "👍", "🙏", "🔥"};

    private TextView status;
    private TextView selected;
    private Switch activeSwitch;
    private String selectedEmoji = "❤️";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        selectedEmoji = prefs().getString(KEY_EMOJI, "❤️");
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        int ink = Color.rgb(31, 33, 48);
        int muted = Color.rgb(115, 117, 135);
        int accent = Color.rgb(192, 61, 129);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(34), dp(24), dp(28));
        page.setBackgroundColor(Color.rgb(250, 248, 251));
        scroll.addView(page);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.autoreactor_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(78), dp(78));
        logoParams.bottomMargin = dp(16);
        page.addView(logo, logoParams);

        TextView brand = text("◎  INSTAGRAM AUTOREACTOR", 13, accent, true);
        page.addView(brand);
        TextView title = text("React as you chat.", 29, ink, true);
        title.setPadding(0, dp(20), 0, dp(7));
        page.addView(title);
        page.addView(text("Automatically add your chosen emoji to messages you send in Instagram DMs, after you turn this on.", 15, muted, false));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(26);
        page.addView(card, cardParams);

        activeSwitch = new Switch(this);
        activeSwitch.setText("Auto-react is on");
        activeSwitch.setTextSize(17);
        activeSwitch.setTextColor(ink);
        activeSwitch.setChecked(prefs().getBoolean(KEY_ACTIVE, false));
        card.addView(activeSwitch);
        TextView note = text("Only new sent messages in Instagram count. Existing messages are ignored when you arm it.", 13, muted, false);
        note.setPadding(0, dp(8), 0, 0);
        card.addView(note);
        activeSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !isServiceEnabled()) {
                activeSwitch.setChecked(false);
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "Turn on Instagram Autoreactor in Accessibility settings, then return here.", Toast.LENGTH_LONG).show();
                return;
            }
            android.content.SharedPreferences.Editor edit = prefs().edit()
                    .putBoolean(KEY_ACTIVE, checked)
                    .putLong("armed_at", System.currentTimeMillis());
            if (checked) edit.putInt(KEY_COUNT, 0);
            edit.apply();
            refreshStatus();
        });

        TextView choose = text("Choose your reaction", 16, ink, true);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-1, -2);
        chooseParams.topMargin = dp(27);
        page.addView(choose, chooseParams);
        selected = text("Selected: " + selectedEmoji, 13, muted, false);
        selected.setPadding(0, dp(6), 0, dp(12));
        page.addView(selected);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (String emoji : EMOJIS) {
            TextView chip = text(emoji, 24, ink, false);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundColor(emoji.equals(selectedEmoji) ? Color.rgb(250, 226, 240) : Color.WHITE);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1);
            p.setMargins(dp(2), 0, dp(2), 0);
            row.addView(chip, p);
            chip.setOnClickListener(v -> {
                selectedEmoji = emoji;
                prefs().edit().putString(KEY_EMOJI, emoji).apply();
                selected.setText("Selected: " + emoji);
                for (int i = 0; i < row.getChildCount(); i++) {
                    row.getChildAt(i).setBackgroundColor(EMOJIS[i].equals(emoji) ? Color.rgb(250, 226, 240) : Color.WHITE);
                }
            });
        }
        page.addView(row);

        Button access = new Button(this);
        access.setText("Set up Android accessibility");
        LinearLayout.LayoutParams accessParams = new LinearLayout.LayoutParams(-1, -2);
        accessParams.topMargin = dp(24);
        page.addView(access, accessParams);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        TextView foot = text("Runs on this phone only. No login, messages, or credentials leave your device. Keep Instagram open to the conversation you want to use.", 12, muted, false);
        LinearLayout.LayoutParams footParams = new LinearLayout.LayoutParams(-1, -2);
        footParams.topMargin = dp(22);
        page.addView(foot, footParams);
        status = text("Service status: checking…", 13, muted, false);
        status.setPadding(0, dp(18), 0, 0);
        page.addView(status);
        setContentView(scroll);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean enabled = isServiceEnabled();
        String last = prefs().getString(KEY_STATUS, "Waiting for a new message");
        int count = prefs().getInt(KEY_COUNT, 0);
        status.setText((enabled ? "Accessibility service: on" : "Accessibility service: off") + "\n" + count + " reactions since arming\n" + last);
        if (activeSwitch != null && !enabled && activeSwitch.isChecked()) activeSwitch.setChecked(false);
    }

    private boolean isServiceEnabled() {
        String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return services != null && services.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
