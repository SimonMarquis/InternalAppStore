package fr.smarquis.appstore

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.NonNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback
import com.bumptech.glide.RequestManager
import com.firebase.ui.common.ChangeEventType
import com.firebase.ui.common.ChangeEventType.*
import com.firebase.ui.database.ChangeEventListener
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.firebase.ui.database.ObservableSnapshotArray
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query

class ApplicationAdapter(
        lifecycleOwner: LifecycleOwner,
        query: Query,
        private val callback: Callback,
        private val glide: RequestManager,

) : RecyclerView.Adapter<ApplicationViewHolder>(), LifecycleObserver, ChangeEventListener {

    companion object {

        val PAYLOAD_PACKAGE_CHANGED = Any()

    }

    interface Callback {
        fun onDataChanged()
        fun onItemClicked(application: Application, applicationViewHolder: ApplicationViewHolder)
    }

    private val snapshots: ObservableSnapshotArray<Application> = FirebaseRecyclerOptions.Builder<Application>().setQuery(query) { Application.parse(it, preferences)!! }.build().snapshots

    private val sortedListCallback = object : SortedListAdapterCallback<Application>(this) {

        private val comparator = compareByDescending<Application> { it.isFavorite }.then(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })

        override fun areItemsTheSame(item1: Application?, item2: Application?): Boolean = item1?.key.equals(item2?.key)

        override fun areContentsTheSame(oldItem: Application?, newItem: Application?): Boolean = oldItem == newItem && oldItem?.isFavorite == newItem?.isFavorite

        override fun compare(o1: Application?, o2: Application?): Int = comparator.compare(o1, o2)
    }

    private val backupList: MutableList<Application> = ArrayList()
    private val displayList: SortedList<Application> = SortedList(Application::class.java, sortedListCallback)
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
    fun getItem(position: Int): Application = displayList[position]

    override fun getItemId(position: Int): Long = stableIds.getOrPut(getItem(position).key.orEmpty()) { stableIds.size.toLong() }

    override fun getItemCount(): Int = displayList.size()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApplicationViewHolder = ApplicationViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_application, parent, false), callback, glide)

    override fun onBindViewHolder(holder: ApplicationViewHolder, position: Int) = holder.bind(getItem(position), search)

    override fun onBindViewHolder(holder: ApplicationViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }

        for (payload in payloads) {
            when (payload) {
                PAYLOAD_PACKAGE_CHANGED -> holder.renderText()
            }
        }
    }

    override fun onViewRecycled(holder: ApplicationViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind(glide)
    }

    override fun onDataChanged() = callback.onDataChanged()

    override fun onChildChanged(type: ChangeEventType, snapshot: DataSnapshot, newIndex: Int, oldIndex: Int) {
        val application = snapshots[newIndex]
        when (type) {
            ADDED -> {
                backupList.add(newIndex, application)
                displayList.add(application)
            }
            CHANGED -> {
                val removedApplication = backupList.removeAt(newIndex)
                backupList.add(newIndex, application)
                val oldDisplayIndex = displayList.indexOf(removedApplication)
                displayList.updateItemAt(oldDisplayIndex, application)
            }
            MOVED -> {
                val removedApplication = backupList.removeAt(oldIndex)
                backupList.add(newIndex, application)
                displayList.add(application)
            }
            REMOVED -> {
                backupList.removeAt(newIndex)
                displayList.remove(application)
            }
            else -> throw IllegalStateException("Incomplete case statement")
        }
    }

    override fun onError(e: DatabaseError) {
        Log.w("ApplicationAdapter", e.toException())
    }

    fun onPackageChanged(packageName: String) {
        // This could be improved with some sort of look-up table:
        // Map<packageName:String, application:Application> of snapshots for instance
        for (i in 0 until itemCount) {
            if (getItem(i).packageName == packageName) {
                notifyItemChanged(i, PAYLOAD_PACKAGE_CHANGED)
                return
            }
        }
    }

}