package fr.smarquis.appstore

import android.content.Context
import android.graphics.Canvas
import android.text.InputFilter
import android.text.Spanned
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withTranslation
import androidx.emoji.widget.EmojiTextViewHelper
import fr.smarquis.appstore.highlight.TextRoundedBgAttributeReader
import fr.smarquis.appstore.highlight.TextRoundedBgHelper

class EmojiTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var _helper: EmojiTextViewHelper? = null

    private val helper: EmojiTextViewHelper
        get() {
            if (_helper == null) {
                _helper = EmojiTextViewHelper(this)
            }
            return _helper as EmojiTextViewHelper
        }

    private val textRoundedBgHelper: TextRoundedBgHelper by lazy {
        TextRoundedBgAttributeReader(context, attrs).let {
            TextRoundedBgHelper(
                horizontalPadding = it.horizontalPadding,
                verticalPadding = it.verticalPadding,
                drawable = it.drawable,
                drawableLeft = it.drawableLeft,
                drawableMid = it.drawableMid,
                drawableRight = it.drawableRight,
            )
        }
    }

    init {
        helper.updateTransformationMethod()
    }

    override fun setFilters(filters: Array<InputFilter>) {
        super.setFilters(helper.getFilters(filters))
    }

    override fun setAllCaps(allCaps: Boolean) {
        super.setAllCaps(allCaps)
        helper.setAllCaps(allCaps)
    }

    override fun onDraw(canvas: Canvas) {
        // need to draw bg first so that text can be on top during super.onDraw()
        if (text is Spanned && layout != null) {
            canvas.withTranslation(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat()) {
                textRoundedBgHelper.draw(canvas, text as Spanned, layout)
            }
        }
        super.onDraw(canvas)
    }

}
