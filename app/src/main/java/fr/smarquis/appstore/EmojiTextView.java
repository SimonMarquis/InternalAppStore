package fr.smarquis.appstore;

import android.content.Context;
import android.text.InputFilter;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;
import androidx.emoji.widget.EmojiTextViewHelper;

/**
 * TextView widget enhanced with emoji capability by using {@link EmojiTextViewHelper}.
 */
public class EmojiTextView extends AppCompatTextView {
    private EmojiTextViewHelper mEmojiTextViewHelper;

    /**
     * Prevent calling {@link #init()} multiple times in case super() constructors
     * call other constructors.
     */
    private boolean mInitialized;

    public EmojiTextView(Context context) {
        super(context);
        init();
    }

    public EmojiTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EmojiTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        if (!mInitialized) {
            mInitialized = true;
            getEmojiTextViewHelper().updateTransformationMethod();
        }
    }

    @Override
    public void setFilters(InputFilter[] filters) {
        super.setFilters(getEmojiTextViewHelper().getFilters(filters));
    }

    @Override
    public void setAllCaps(boolean allCaps) {
        super.setAllCaps(allCaps);
        getEmojiTextViewHelper().setAllCaps(allCaps);
    }

    private EmojiTextViewHelper getEmojiTextViewHelper() {
        if (mEmojiTextViewHelper == null) {
            mEmojiTextViewHelper = new EmojiTextViewHelper(this);
        }
        return mEmojiTextViewHelper;
    }
}
