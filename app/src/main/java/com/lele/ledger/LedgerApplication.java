package com.lele.ledger;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;

/** Apply the saved palette even when a notification opens a secondary page first. */
public final class LedgerApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        boolean dark = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("dark_mode", false);
        AppCompatDelegate.setDefaultNightMode(dark
                ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }
}
