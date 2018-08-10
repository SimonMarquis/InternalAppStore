package fr.smarquis.appstore

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import kotlinx.android.synthetic.main.item_application.view.*

class ApplicationViewHolder(
        v: View,
        private val callback: ApplicationAdapter.Callback,
        private val glide: RequestManager
) : RecyclerView.ViewHolder(v), View.OnClickListener {

    private val title: TextView = v.textView_application_title
    private val installed: TextView = v.textView_application_installed
    private val image: ImageView = v.imageView_application_icon

    private var application: Application? = null

    init {
        itemView.setOnClickListener(this)
    }


    fun bind(application: Application) {
        this.application = application
        ViewCompat.setTransitionName(image, application.imageTransitionName())
        renderImage()
        renderText()
    }

    private fun renderImage() {
        application?.loadImageInto(image, glide)
    }

    fun renderText() {
        title.text = application?.name
        installed.apply {
            val packageName = application?.packageName
            if (packageName != null && Utils.isApplicationInstalled(itemView.context, packageName)) {
                text = resources.getString(R.string.item_application_installed, Utils.applicationVersionName(itemView.context, packageName))
                visibility = View.VISIBLE
            } else {
                text = null
                visibility = View.GONE
            }
        }
    }

    override fun onClick(v: View?) {
        application?.let { callback.onItemClicked(it, this) }
    }

    fun unbind(glide: RequestManager) {
        glide.clear(image)
    }

    fun sharedElement(): Pair<View, String> {
        return Pair.create(image, ViewCompat.getTransitionName(image))
    }

}