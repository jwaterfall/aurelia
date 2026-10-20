package com.gathin.tvhome.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.gathin.tvhome.R;

/**
 * A FrameLayout whose height follows its width (app:aspectRatio, width / height; 16:9 by default). Its content is
 * clipped to its background's outline, so artwork takes the rounded corners of a card.
 */
public class AspectRatioFrameLayout extends FrameLayout {

    private float ratio = 16f / 9f;

    public AspectRatioFrameLayout(Context context) {
        this(context, null);
    }

    public AspectRatioFrameLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AspectRatioFrameLayout);
        ratio = a.getFloat(R.styleable.AspectRatioFrameLayout_aspectRatio, ratio);
        a.recycle();
        setClipToOutline(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width / ratio);
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    }
}
