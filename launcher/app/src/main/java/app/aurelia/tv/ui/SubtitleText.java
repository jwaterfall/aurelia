package app.aurelia.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LineBackgroundSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.aurelia.tv.R;

/**
 * The player's text subtitles, in the launcher's typeface rather than Android's captions. Three looks:
 * white with a wide soft shadow; all of it on one card of the menus' glass (without their blur, which video cannot
 * have behind it); or classic captions, each line on a solid black box. Pictures for subtitles stay with media3's
 * SubtitleView.
 */
public class SubtitleText extends TextView {

    public static final int SHADOW = 0;
    public static final int GLASS = 1;
    public static final int CLASSIC = 2;

    private final float dp;
    private int style = SHADOW;

    public SubtitleText(Context context) {
        this(context, null);
    }

    public SubtitleText(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        setTypeface(getResources().getFont(R.font.outfit_medium));
        setTextColor(0xFFFFFFFF);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setIncludeFontPadding(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setStyle(SHADOW);
    }

    public void setStyle(int style) {
        this.style = style;
        int px = (int) dp;
        if (style == GLASS) {
            setBackgroundResource(R.drawable.subtitle_glass);
            setShadowLayer(0, 0, 0, 0);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            setLineSpacing(3 * dp, 1f);
            setPadding(22 * px, 11 * px, 22 * px, 13 * px);
        } else if (style == CLASSIC) {
            setBackground(null);
            setShadowLayer(0, 0, 0, 0);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            // Room between the lines for each one's box, and at the sides for the boxes' ends.
            setLineSpacing(6 * dp, 1f);
            setPadding(10 * px, 4 * px, 10 * px, 4 * px);
        } else {
            setBackground(null);
            setShadowLayer(9 * dp, 0, 1 * dp, 0xD9000000);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            setLineSpacing(2 * dp, 1f);
            // The shadow spreads past the letters; padding keeps it from being cut off.
            setPadding(12 * px, 12 * px, 12 * px, 12 * px);
        }
        setCue(getText());
    }

    /** Shows {@code text} (nothing for null or empty), in the current look. */
    public void setCue(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            setText("");
            setVisibility(INVISIBLE);
            return;
        }
        SpannableStringBuilder s = new SpannableStringBuilder(text);
        for (Box b : s.getSpans(0, s.length(), Box.class)) s.removeSpan(b);
        if (style == CLASSIC) s.setSpan(new Box(), 0, s.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        setText(s);
        setVisibility(VISIBLE);
    }

    /** Classic captions' black box behind each line, as wide as the line's words. */
    private final class Box implements LineBackgroundSpan {
        private final Paint fill = new Paint();

        Box() {
            fill.setColor(0xFF000000);
        }

        @Override
        public void drawBackground(@NonNull Canvas canvas, @NonNull Paint paint, int left, int right, int top,
                                   int baseline, int bottom, @NonNull CharSequence text, int start, int end,
                                   int line) {
            int e = end;
            while (e > start && Character.isWhitespace(text.charAt(e - 1))) e--;
            if (e <= start) return;
            float width = paint.measureText(text, start, e);
            float centre = (left + right) / 2f;
            Paint.FontMetrics fm = paint.getFontMetrics();
            float padX = 8 * dp, padY = 3 * dp;
            canvas.drawRect(centre - width / 2f - padX, baseline + fm.ascent - padY,
                    centre + width / 2f + padX, baseline + fm.descent + padY, fill);
        }
    }
}
