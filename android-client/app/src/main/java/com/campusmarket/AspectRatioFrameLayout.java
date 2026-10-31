package com.campusmarket;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Keeps product-card media at a compact 4:3 ratio without another UI dependency. */
public final class AspectRatioFrameLayout extends FrameLayout {
    public AspectRatioFrameLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = View.MeasureSpec.getSize(widthSpec);
        if (width <= 0) { super.onMeasure(widthSpec, heightSpec); return; }
        int height = width * 3 / 4;
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    }
}
