package fr.smarquis.appstore

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.collection.ArrayMap
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback
import com.firebase.ui.common.ChangeEventType
import com.firebase.ui.database.FirebaseRecyclerAdapter
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference

class VersionAdapter(
        query: DatabaseReference,
        private val callback: Callback
) : FirebaseRecyclerAdapter<Version, VersionViewHolder>(recyclerOptions(query)) {

    companion object {

        private fun recyclerOptions(query: DatabaseReference) = FirebaseRecyclerOptions.Builder<Version>().setQuery(query) { Version.parse(it)!! }.build()

        val PAYLOAD_PROGRESS_CHANGED = Any()
    }

    interface Callback {
        fun onItemClicked(version: Version, versionViewHolder: VersionViewHolder)
        fun onItemLongClicked(version: Version, versionViewHolder: VersionViewHolder): Boolean
        fun onItemChanged(version: Version)
    }

    private val sortedListCallback = object : SortedListAdapterCallback<Version>(this) {

        override fun areItemsTheSame(item1: Version?, item2: Version?): Boolean {
            return item1?.key.equals(item2?.key)
        }

        override fun areContentsTheSame(oldItem: Version?, newItem: Version?): Boolean {
            return oldItem == newItem
        }

        override fun compare(o1: Version?, o2: Version?): Int {
            return when {
                o1 == null || o2 == null -> 0
                else -> o1.compareTo(o2)
            }
        }

    }

    private val sortedList = SortedList(Version::class.java, sortedListCallback)

    private val lut = ArrayMap<String, Version>(10)

    private var highlightVersionKey: String? = null

    fun highlightVersion(key: String?) {
        this.highlightVersionKey = key
    }

    override fun onChildChanged(type: ChangeEventType, snapshot: DataSnapshot, newIndex: Int, oldIndex: Int) {
        val version = Version.parse(snapshot) ?: return
        when (type) {
            ChangeEventType.ADDED -> {
                lut[version.key] = version
                sortedList.add(version)
            }
            ChangeEventType.REMOVED -> {
                lut[version.key] = null
                sortedList.remove(version)
            }
            ChangeEventType.CHANGED, ChangeEventType.MOVED -> {
                lut[version.key]?.let {
                    sortedList.updateItemAt(sortedList.indexOf(it), version)
                }
                lut[version.key] = version

                if (type == ChangeEventType.CHANGED) {
                    callback.onItemChanged(version)
                }
            }
        }
    }

    override fun getItem(position: Int): Version = sortedList.get(position)

    override fun getItemCount(): Int = sortedList.size()

    override fun getItemViewType(position: Int): Int = R.layout.item_version

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VersionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_version, parent, false)
        return VersionViewHolder(view, callback)
    }

    override fun onBindViewHolder(holder: VersionViewHolder, position: Int, version: Version) {
        holder.bind(version)
        if (version.key == highlightVersionKey) {
            highlightVersionKey = null
            Utils.highlight(holder)
        }
    }


    override fun onBindViewHolder(holder: VersionViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            for (payload in payloads) {
                when (payload) {
                    PAYLOAD_PROGRESS_CHANGED -> holder.renderProgress()
                }
            }
        }
    }

    override fun onViewRecycled(holder: VersionViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    override fun onError(error: DatabaseError) {
        Log.w("VersionAdapter", "message:${error.message}, code:${error.code}")
    }


    fun updateVersionProgress(version: Version) {
        val indexOf = sortedList.indexOf(version)
        if (indexOf != SortedList.INVALID_POSITION) {
            sortedList.get(indexOf).apply {
                status = version.status
                progress = version.progress
            }
            // Fake payload to avoid glitch when scrolling
            notifyItemChanged(indexOf, PAYLOAD_PROGRESS_CHANGED)
        } else {
            Log.e("VersionAdapter", "Version not found in the list!")
            // Note: We could retry and search the whole list for the same version key
        }
    }

}