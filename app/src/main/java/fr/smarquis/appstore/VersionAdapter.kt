package fr.smarquis.appstore

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.NonNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback
import com.firebase.ui.common.ChangeEventType
import com.firebase.ui.database.ChangeEventListener
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.firebase.ui.database.ObservableSnapshotArray
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query

class VersionAdapter(
        lifecycleOwner: LifecycleOwner,
        query: Query,
        private val callback: Callback
) : RecyclerView.Adapter<VersionViewHolder>(), LifecycleObserver, ChangeEventListener {

    companion object {

        val PAYLOAD_PROGRESS_CHANGED = Any()

    }

    interface Callback {
        fun onDataChanged()
        fun onItemClicked(version: Version, versionViewHolder: VersionViewHolder)
        fun onItemLongClicked(version: Version, versionViewHolder: VersionViewHolder): Boolean
        fun onChildAdded(version: Version)
    }

    private val snapshots: ObservableSnapshotArray<Version> = FirebaseRecyclerOptions.Builder<Version>().setQuery(query) { Version.parse(it)!! }.build().snapshots

    private val sortedListCallback = object : SortedListAdapterCallback<Version>(this) {

        override fun areItemsTheSame(item1: Version?, item2: Version?): Boolean = item1?.key.equals(item2?.key)

        override fun areContentsTheSame(oldItem: Version?, newItem: Version?): Boolean = oldItem == newItem

        override fun compare(o1: Version?, o2: Version?): Int = if (o1 != null && o2 != null) o1.compareTo(o2) else 0

    }

    private val backupList: MutableList<Version> = ArrayList()
    private val displayList: SortedList<Version> = SortedList(Version::class.java, sortedListCallback)
    private val stableIds = HashMap<String, Long>()

    init {
        setHasStableIds(true)
        lifecycleOwner.lifecycle.addObserver(this)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    @Suppress("unused")
    fun startListening() {
        if (!snapshots.isListening(this)) {
            snapshots.addChangeEventListener(this)
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    @Suppress("unused")
    fun stopListening() {
        snapshots.removeChangeEventListener(this)
        backupList.clear()
        displayList.clear()
        stableIds.clear()
    }

    @NonNull
    fun getItem(position: Int): Version = displayList[position]

    override fun getItemId(position: Int): Long = stableIds.getOrPut(getItem(position).key.orEmpty()) { stableIds.size.toLong() }

    override fun getItemCount(): Int = displayList.size()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VersionViewHolder = VersionViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_version, parent, false), callback)

    override fun onBindViewHolder(holder: VersionViewHolder, position: Int) {
        val version = getItem(position)
        DEBUG_SCREENSHOT_STATUS?.let {
            if (position == 0) {
                version.status = it.first
                version.progress = it.second
            }
        }
        holder.bind(version, filter, highlightVersionKey)
    }


    override fun onBindViewHolder(holder: VersionViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }

        for (payload in payloads) {
            when (payload) {
                PAYLOAD_PROGRESS_CHANGED -> holder.renderProgress()
            }
        }
    }

    override fun onViewRecycled(holder: VersionViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    override fun onDataChanged() = callback.onDataChanged()

    override fun onChildChanged(type: ChangeEventType, snapshot: DataSnapshot, newIndex: Int, oldIndex: Int) {
        when (type) {
            ChangeEventType.ADDED -> {
                val version = snapshots[newIndex]
                backupList.add(newIndex, version)
                if (version.filter(filter)) {
                    displayList.add(version)
                }
                callback.onChildAdded(version)
            }
            ChangeEventType.CHANGED -> {
                val version = snapshots[newIndex]
                val removedVersion = backupList.removeAt(newIndex)
                backupList.add(newIndex, version)
                val oldDisplayIndex = displayList.indexOf(removedVersion)
                if (version.filter(filter)) {
                    displayList.updateItemAt(oldDisplayIndex, version)
                } else {
                    displayList.removeItemAt(oldDisplayIndex)
                }
            }
            ChangeEventType.MOVED -> {
                val version = snapshots[newIndex]
                val removedVersion = backupList.removeAt(oldIndex)
                backupList.add(newIndex, version)
                if (version.filter(filter)) {
                    displayList.add(version)
                } else {
                    displayList.remove(removedVersion)
                }
            }
            ChangeEventType.REMOVED -> {
                val removedVersion = backupList.removeAt(newIndex)
                backupList.removeAt(newIndex)
                displayList.remove(removedVersion)
            }
            else -> throw IllegalStateException("Incomplete case statement")
        }
    }

    override fun onError(e: DatabaseError) {
        Log.w("VersionAdapter", e.toException())
    }

    private var highlightVersionKey: String? = null

    fun highlightVersion(key: String?) {
        this.highlightVersionKey = key
    }

    fun updateVersionProgress(version: Version) {
        val indexOf = displayList.indexOf(version)
        if (indexOf != SortedList.INVALID_POSITION) {
            displayList.get(indexOf).apply {
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

    fun positionOf(version: Version): Int = displayList.indexOf(version)

    private var filter: String? = null

    fun filter(): String? = filter

    fun filter(value: String?) {
        val search = if (value.isNullOrBlank()) null else value
        if (filter == search) {
            return
        }
        filter = value
        displayList.beginBatchedUpdates()
        if (value.isNullOrBlank()) {
            displayList.clear()
            displayList.addAll(backupList)
        } else {
            for (version in backupList) {
                if (version.filter(value)) {
                    displayList.add(version)
                } else {
                    displayList.remove(version)
                }
            }
        }
        displayList.endBatchedUpdates()
        notifyDataSetChanged()
    }

}