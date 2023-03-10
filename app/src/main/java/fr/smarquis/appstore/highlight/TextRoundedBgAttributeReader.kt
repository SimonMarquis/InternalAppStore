package fr.smarquis.appstore.highlight

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.core.content.res.getDrawableOrThrow
import fr.smarquis.appstore.R

/**
 * Reads default attributes that [TextRoundedBgHelper] needs from resources. The attributes read
 * are:
 *
 * - chHorizontalPadding: the padding to be applied to left & right of the background
 * - chVerticalPadding: the padding to be applied to top & bottom of the background
 * - chDrawable: the drawable used to draw the background
 * - chDrawableLeft: the drawable used to draw left edge of the background
 * - chDrawableMid: the drawable used to draw for whole line
 * - chDrawableRight: the drawable used to draw right edge of the background
 */
class TextRoundedBgAttributeReader(context: Context, attrs: AttributeSet?) {

    val horizontalPadding: Int
    val verticalPadding: Int
    val drawable: Drawable
    val drawableLeft: Drawable
    val drawableMid: Drawable
    val drawableRight: Drawable

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.TextRoundedBgHelper, 0, R.style.RoundedBgTextView)
        horizontalPadding = typedArray.getDimensionPixelSize(R.styleable.TextRoundedBgHelper_roundedTextHorizontalPadding, 0)
        verticalPadding = typedArray.getDimensionPixelSize(R.styleable.TextRoundedBgHelper_roundedTextVerticalPadding, 0)
        drawable = typedArray.getDrawableOrThrow(R.styleable.TextRoundedBgHelper_roundedTextDrawable)
        drawableLeft = typedArray.getDrawableOrThrow(R.styleable.TextRoundedBgHelper_roundedTextDrawableLeft)
        drawableMid = typedArray.getDrawableOrThrow(R.styleable.TextRoundedBgHelper_roundedTextDrawableMid)
        drawableRight = typedArray.getDrawableOrThrow(R.styleable.TextRoundedBgHelper_roundedTextDrawableRight)
        typedArray.recycle()
    }
}
