package fr.smarquis.appstore

import android.graphics.Typeface
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import kotlinx.android.synthetic.main.item_application.view.*

class ApplicationViewHolder(
        v: View,
        private val callback: ApplicationAdapter.Callback,
        private val glide: RequestManager
) : RecyclerView.ViewHolder(v), View.OnClickListener, View.OnLongClickListener {

    private val title: TextView = v.textView_application_title
    private val installed: TextView = v.textView_application_installed
    private val image: ImageView = v.imageView_application_icon
    private val favorite: ImageView = v.imageView_application_favorite

    private var application: Application? = null
    private var filter: String? = null

    init {
        itemView.setOnClickListener(this)
        itemView.setOnLongClickListener(this)
    }

    private val colorAccent = ContextCompat.getColor(itemView.context, R.color.colorAccent)
    private val defaultColor = title.currentTextColor

    fun bind(application: Application, filter: String?) {
        this.application = application
        this.filter = filter
        ViewCompat.setTransitionName(image, application.imageTransitionName())
        renderFavorite()
        renderImage()
        renderText()
    }

    private fun renderFavorite() {
        favorite.visibility = if (application?.isFavorite == true) VISIBLE else GONE
    }

    private fun renderImage() {
        application?.loadImageInto(image, glide)
    }

    fun renderText() {
        title.setTextColor(if (application?.isFavorite == true) colorAccent else defaultColor)
        title.setTypeface(null, if (application?.isFavorite == true) Typeface.BOLD else Typeface.NORMAL)
        title.text = Utils.highlightFilter(application?.name, filter)
        installed.apply {
            setTextColor(if (application?.isFavorite == true) colorAccent else defaultColor)
            val packageName = application?.packageName
            if (packageName != null && Utils.isApplicationInstalled(itemView.context, packageName)) {
                val name = Utils.applicationVersionName(itemView.context, packageName)
                val time = Utils.relativeTimeSpan(Utils.applicationLastUpdateTime(itemView.context, packageName))
                text = resources.getString(R.string.item_application_installed, name, time)
                visibility = VISIBLE
            } else {
                text = null
                visibility = GONE
            }
        }
    }

    override fun onClick(v: View?) {
        application?.let { callback.onItemClicked(it, this) }
    }

    override fun onLongClick(v: View?): Boolean {
        return application?.let { callback.onItemLongClicked(it, this) } ?: false
    }

    fun unbind(glide: RequestManager) {
        glide.clear(image)
    }

    fun sharedElement(): Pair<View, String> {
        return Pair.create(image, ViewCompat.getTransitionName(image))
    }

}