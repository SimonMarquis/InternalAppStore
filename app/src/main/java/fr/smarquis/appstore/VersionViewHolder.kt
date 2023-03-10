package fr.smarquis.appstore

import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.OnClickListener
import android.view.View.OnLongClickListener
import android.view.View.VISIBLE
import androidx.recyclerview.widget.RecyclerView
import fr.smarquis.appstore.Utils.Companion.highlightFilter
import fr.smarquis.appstore.Version.Status.DEFAULT
import fr.smarquis.appstore.Version.Status.DOWNLOADING
import fr.smarquis.appstore.Version.Status.INSTALLING
import fr.smarquis.appstore.Version.Status.OPENING
import fr.smarquis.appstore.databinding.ItemVersionBinding

class VersionViewHolder(
    private val binding: ItemVersionBinding,
    private val callback: VersionAdapter.Callback,
) : RecyclerView.ViewHolder(binding.root), OnClickListener, OnLongClickListener {

    private var version: Version? = null
    private var filter: String? = null
    val anchor = binding.anchor

    init {
        itemView.setOnClickListener(this)
        itemView.setOnLongClickListener(this)
    }

    companion object {
        const val UNKNOWN_SIZE = "⋯" /*•••*/ /*∙∙∙*/ /*···*/
    }

    fun bind(version: Version, filter: String?, highlightKey: String?) {
        this.version = version
        this.filter = filter
        renderHighlight(highlightKey)
        renderTitleAndDescription()
        renderProgress()
    }

    private fun renderHighlight(key: String?) {
        binding.highlight.visibility = if (version?.key == key) VISIBLE else INVISIBLE
    }

    private fun renderTitleAndDescription() {
        binding.name.text = highlightFilter(version?.name, filter)
        binding.description.apply {
            text = highlightFilter(version?.descriptionToHtml, filter)
            visibility = if (version?.descriptionToHtml.isNullOrBlank()) GONE else VISIBLE
            BetterLinkMovementMethod.applyTo(this, itemView)
        }
    }

    fun renderProgress() {
        binding.size.text = version?.apkSizeBytesDisplay ?: UNKNOWN_SIZE

        val resourceId = when {
            version?.status == DOWNLOADING || version?.status == INSTALLING -> R.drawable.ic_cloud_sync_16dp
            version?.hasApkUrl() ?: false -> R.drawable.ic_cloud_search_16dp
            version?.hasApkRef() ?: false && version?.apkFileAvailable?.not() ?: false -> R.drawable.ic_cloud_download_16dp
            version?.hasApkRef() ?: false && version?.apkFileAvailable ?: false -> R.drawable.ic_cloud_check_16dp
            else -> R.drawable.ic_cloud_alert_16dp
        }
        binding.type.setImageResource(resourceId)

        when (version?.status) {
            DEFAULT -> {
                binding.progress.progress = 0
                binding.progress.isIndeterminate = false
                binding.progress.visibility = if (version?.descriptionToHtml.isNullOrBlank()) GONE else INVISIBLE
                binding.timestamp.text = Utils.relativeTimeSpan(version?.timestamp)
            }

            DOWNLOADING -> {
                binding.progress.progress = version?.progress ?: 0
                binding.progress.isIndeterminate = version?.progress == 0
                binding.progress.visibility = VISIBLE
                binding.timestamp.setText(R.string.item_version_downloading)
            }

            INSTALLING -> {
                binding.progress.progress = 0
                binding.progress.isIndeterminate = true
                binding.progress.visibility = VISIBLE
                binding.timestamp.setText(R.string.item_version_installing)
            }

            OPENING -> {
                binding.progress.progress = 0
                binding.progress.isIndeterminate = true
                binding.progress.visibility = VISIBLE
                binding.timestamp.setText(R.string.item_version_opening)
            }

            else -> {
                binding.progress.visibility = GONE
                binding.timestamp.text = null
            }
        }
    }

    fun unbind() {
        BetterLinkMovementMethod.clear(binding.description)
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
