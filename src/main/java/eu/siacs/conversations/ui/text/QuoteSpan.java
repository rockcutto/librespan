package eu.siacs.conversations.ui.text;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.LeadingMarginSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.core.graphics.ColorUtils;

public class QuoteSpan extends CharacterStyle implements LeadingMarginSpan {

	private final int color;

	private final int dashColor;

	private final int width;
	private final int paddingLeft;
	private final int paddingRight;
	private final float radius;

	private static final float WIDTH_SP = 1.5f;
	private static final float PADDING_LEFT_SP = 1.5f;
	private static final float PADDING_RIGHT_SP = 8f;
	private static final float RADIUS_SP = 1.25f;

	public QuoteSpan(int color, int dashColor, DisplayMetrics metrics) {
		this.color = color;
		this.dashColor = dashColor;
		this.width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, WIDTH_SP, metrics);
		this.paddingLeft = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, PADDING_LEFT_SP, metrics);
		this.paddingRight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, PADDING_RIGHT_SP, metrics);
		this.radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, RADIUS_SP, metrics);
	}

	@Override
	public void updateDrawState(TextPaint tp) {
		tp.setColor(this.color);
	}

	@Override
	public int getLeadingMargin(boolean first) {
		return paddingLeft + width + paddingRight;
	}

	@Override
	public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
			CharSequence text, int start, int end, boolean first, Layout layout) {
		Paint.Style style = p.getStyle();
		int color = p.getColor();
		p.setStyle(Paint.Style.FILL);
		final int requestedDashColor = dashColor != -1 ? this.dashColor : this.color;
		p.setColor(
				ColorUtils.setAlphaComponent(
						requestedDashColor,
						Math.min(Color.alpha(requestedDashColor), 150)));
		final float left = Math.min(x + dir * paddingLeft, x + dir * (paddingLeft + width));
		final float right = Math.max(x + dir * paddingLeft, x + dir * (paddingLeft + width));
		c.drawRoundRect(left, top, right, bottom, radius, radius, p);
		p.setStyle(style);
		p.setColor(color);
	}

	@ColorInt
	public int getColor() {
		return this.color;
	}
}