package fr.smarquis.appstore

import android.content.Context
import android.text.InputFilter
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton
import androidx.emoji.widget.EmojiTextViewHelper


class EmojiButton @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0)
    : AppCompatButton(context, attrs, defStyleAttr) {

    private var _helper: EmojiTextViewHelper? = null

    private val helper: EmojiTextViewHelper
        get() {
            if (_helper == null) {
                _helper = EmojiTextViewHelper(this)
            }
            return _helper as EmojiTextViewHelper
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

}