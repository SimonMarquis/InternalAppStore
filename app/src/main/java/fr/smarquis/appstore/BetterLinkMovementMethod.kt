package fr.smarquis.appstore

import android.text.Selection
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.method.Touch
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.text.getSpans
import androidx.core.text.toSpannable


class BetterLinkMovementMethod : LinkMovementMethod() {

    companion object {
        private val instance: BetterLinkMovementMethod by lazy {
            BetterLinkMovementMethod()
        }

        fun applyTo(view: TextView, forwardTo: View) {
            if (view.text.toSpannable().getSpans<ClickableSpan>().isEmpty()) {
                clear(view)
            } else {
                view.setTag(R.id.better_link_movement_method_forward_to, forwardTo)
                view.movementMethod = BetterLinkMovementMethod.instance
            }
        }

        fun clear(view: TextView) {
            view.movementMethod = null
            view.setTag(R.id.better_link_movement_method_forward, null)
            view.setTag(R.id.better_link_movement_method_forward_to, null)
        }

    }


    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val forward = widget.getTag(R.id.better_link_movement_method_forward) as Boolean?
        val to = widget.getTag(R.id.better_link_movement_method_forward_to) as View?
        if (forward == true && to is View) {
            event.offsetLocation(widget.x, widget.y)
            to.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                widget.setTag(R.id.better_link_movement_method_forward, null)
            }
            Touch.onTouchEvent(widget, buffer, event)
            return false
        }

        if (event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_DOWN) {
            return Touch.onTouchEvent(widget, buffer, event)
        }

        val links = getClickableSpans(event, widget, buffer)
        if (links == null || links.isEmpty()) {
            Selection.removeSelection(buffer)
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (to is View) {
                    widget.setTag(R.id.better_link_movement_method_forward, true)
                    to.onTouchEvent(event)
                }
                Touch.onTouchEvent(widget, buffer, event)
                return false
            }
            return Touch.onTouchEvent(widget, buffer, event)
        } else {
            when (event.action) {
                MotionEvent.ACTION_UP -> links[0].onClick(widget)
                MotionEvent.ACTION_DOWN -> Selection.setSelection(buffer, buffer.getSpanStart(links[0]), buffer.getSpanEnd(links[0]))
            }
            Touch.onTouchEvent(widget, buffer, event)
            return true
        }
    }

    private fun getClickableSpans(event: MotionEvent, widget: TextView, buffer: Spannable): Array<out ClickableSpan>? {
        var x = event.x.toInt()
        var y = event.y.toInt()
        x -= widget.totalPaddingLeft
        y -= widget.totalPaddingTop
        x += widget.scrollX
        y += widget.scrollY
        val layout = widget.layout
        val line = layout.getLineForVertical(y)
        val off = layout.getOffsetForHorizontal(line, x.toFloat())
        return buffer.getSpans(off, off, ClickableSpan::class.java)
    }

}
