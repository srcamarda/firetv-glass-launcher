package com.ivantrj.glasslauncher;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/** Shared binding and focus visuals for dock and grid tiles (both use item_tile.xml). */
final class Tiles {
    static final float FOCUS_SCALE = 1.08f;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    static void bind(View cell, AppInfo app) {
        View tile = cell.findViewById(R.id.tile);
        ImageView banner = cell.findViewById(R.id.banner);
        ImageView icon = cell.findViewById(R.id.icon);
        TextView label = cell.findViewById(R.id.label);
        tile.setClipToOutline(true);
        label.setText(app.label);
        label.setTypeface(Fonts.outfit(cell.getContext(), 400));
        if (app.banner != null) {
            banner.setImageDrawable(app.banner);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(app.icon);
            icon.setVisibility(View.VISIBLE);
        }
    }

    /** Resizes a cell (the grid shrinks its tiles at 5 columns). Sizes in px; cellW <= 0 keeps the XML sizes. */
    static void size(View cell, int cellW, int cellH, int tileW, int tileH, int iconSize, int labelTop) {
        if (cellW <= 0) return;
        setSize(cell, cellW, cellH);
        setSize(cell.findViewById(R.id.tile), tileW, tileH);
        setSize(cell.findViewById(R.id.icon), iconSize, iconSize);
        View label = cell.findViewById(R.id.label);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) label.getLayoutParams();
        if (lp.topMargin != labelTop) {
            lp.topMargin = labelTop;
            label.setLayoutParams(lp);
        }
    }

    private static void setSize(View v, int w, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.width == w && lp.height == h) return;
        lp.width = w;
        lp.height = h;
        v.setLayoutParams(lp);
    }

    static void setFocused(View cell, boolean focused, boolean animate) {
        setFocused(cell, focused, animate, false);
    }

    /** {@code labelAlways}: keep the name visible on unfocused tiles too. */
    static void setFocused(View cell, boolean focused, boolean animate, boolean labelAlways) {
        View tile = cell.findViewById(R.id.tile);
        View label = cell.findViewById(R.id.label);
        float scale = focused ? FOCUS_SCALE : 1f;
        float alpha = focused || labelAlways ? 1f : 0f;
        tile.animate().cancel();
        label.animate().cancel();
        if (animate) {
            tile.animate().scaleX(scale).scaleY(scale).setDuration(200).setInterpolator(EASE).start();
            label.animate().alpha(alpha).setDuration(focused ? 220 : 120).start();
        } else {
            tile.setScaleX(scale);
            tile.setScaleY(scale);
            label.setAlpha(alpha);
        }
    }

    private Tiles() {}
}
