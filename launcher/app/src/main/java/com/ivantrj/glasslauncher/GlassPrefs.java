package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** Glass's own settings (the "Glass settings" menu), shared by HomeActivity and HomeButtonService. */
final class GlassPrefs {
    static final String NAME = "settings";

    static final String BING = "bing";                         // boolean, Bing image of the day as wallpaper
    static final String BING_IDX = "bing_idx";                 // int 0..7: 0 = today, n = n days ago
    static final String BING_FETCHED_DAY = "bing_fetched_day"; // yyyyMMdd of the last successful fetch
    static final String BING_FETCHED_IDX = "bing_fetched_idx"; // bing_idx used for that fetch
    static final String BING_URL = "bing_url";                 // image URL of the saved file
    static final String BING_CREDIT = "bing_credit";           // "Title (© Photographer/Agency)"
    static final String DIM = "dim";                           // int index into DIM_ALPHA
    static final String BACK_LONG_PRESS = "back_long_press";   // boolean, hold Back opens Glass
    static final String GRID_COLS = "grid_cols";               // int, 4 or 5 apps per grid row
    static final String GRID_LABELS_ALWAYS = "grid_labels";    // boolean, names under every grid tile
    static final String SHOW_GREETING = "show_greeting";       // boolean, greeting and date above the clock

    /** Black overlay alpha on the wallpaper: light, medium (the original 115), strong. */
    static final int[] DIM_ALPHA = {70, 115, 160};
    static final int DIM_DEFAULT = 1;
    static final int BING_DAYS = 8; // Bing serves today plus the previous 7 days
    static final int GRID_COLS_MIN = 4, GRID_COLS_MAX = 5;

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private GlassPrefs() {}
}
