package fr.smarquis.appstore

import android.support.v7.widget.RecyclerView
import android.text.format.DateUtils
import android.view.View
import android.view.View.*
import android.widget.ProgressBar
import android.widget.TextView
import fr.smarquis.appstore.Version.Status.*
import kotlinx.android.synthetic.main.item_version.view.*

class VersionViewHolder(
        v: View,
        private val callback: VersionAdapter.Callback
) : RecyclerView.ViewHolder(v), View.OnClickListener, View.OnLongClickListener {

    private val name: TextView = v.textView_version_name
    private val timestamp: TextView = v.textView_version_timestamp
    private val description: TextView = v.textView_version_description
    private val progress: ProgressBar = v.progressBar_version

    private var version: Version? = null

    init {
        itemView.setOnClickListener(this)
        itemView.setOnLongClickListener(this)
    }

    fun bind(version: Version) {
        this.version = version
        renderTitleAndDescription()
        renderProgress()
    }

    private fun renderTitleAndDescription() {
        name.text = version?.name
        description.apply {
            text = version?.descriptionToHtml
            visibility = if (version?.descriptionToHtml.isNullOrBlank()) GONE else VISIBLE
            BetterLinkMovementMethod.applyTo(this, itemView)
        }
    }

    fun renderProgress() {
        when (version?.status) {
            DEFAULT -> {
                progress.progress = 0
                progress.isIndeterminate = false
                progress.visibility = if (version?.descriptionToHtml.isNullOrBlank()) GONE else INVISIBLE
                val now = System.currentTimeMillis()
                val time = version?.timestamp ?: now
                timestamp.text = DateUtils.getRelativeTimeSpanString(time, now, DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            }
            DOWNLOADING -> {
                progress.progress = version?.progress ?: 0
                progress.isIndeterminate = version?.progress == 0
                progress.visibility = VISIBLE
                timestamp.setText(R.string.item_version_downloading)
            }
            INSTALLING -> {
                progress.progress = 0
                progress.isIndeterminate = true
                progress.visibility = VISIBLE
                timestamp.setText(R.string.item_version_installing)
            }
            OPENING -> {
                progress.progress = 0
                progress.isIndeterminate = true
                progress.visibility = VISIBLE
                timestamp.setText(R.string.item_version_opening)
            }
            else -> {
                progress.visibility = GONE
                timestamp.text = null
            }
        }
    }

    fun unbind() {
        BetterLinkMovementMethod.clear(description)
        version = null
    }

    override fun onClick(v: View?) {
        version?.let {
            callback.onItemClicked(it, this)
        }
    }

    override fun onLongClick(v: View?): Boolean {
        version?.let {
            return callback.onItemLongClicked(it, this)
        }
        return false
    }

}