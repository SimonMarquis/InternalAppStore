package fr.smarquis.appstore

import android.content.SharedPreferences
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
        private val preferences: SharedPreferences

) : RecyclerView.Adapter<ApplicationViewHolder>(), LifecycleObserver, ChangeEventListener {

    companion object {

        val PAYLOAD_PACKAGE_CHANGED = Any()

    }

    interface Callback {
        fun onDataChanged()
        fun onItemClicked(application: Application, applicationViewHolder: ApplicationViewHolder)
        fun onItemLongClicked(application: Application, applicationViewHolder: ApplicationViewHolder): Boolean
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

    /*@OnLifecycleEvent(Lifecycle.Event.ON_CREATE)*/
    /*startListening should be called from the host Activity once access has been granted*/
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

    override fun onBindViewHolder(holder: ApplicationViewHolder, position: Int) = holder.bind(getItem(position), filter)

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
        when (type) {
            ADDED -> {
                val application = snapshots[newIndex]
                backupList.add(newIndex, application)
                if (application.filter(filter)) {
                    displayList.add(application)
                }
            }
            CHANGED -> {
                val application = snapshots[newIndex]
                val removedApplication = backupList.removeAt(newIndex)
                backupList.add(newIndex, application)
                val oldDisplayIndex = displayList.indexOf(removedApplication)
                if (application.filter(filter)) {
                    displayList.updateItemAt(oldDisplayIndex, application)
                } else {
                    displayList.removeItemAt(oldDisplayIndex)
                }
            }
            MOVED -> {
                val application = snapshots[newIndex]
                val removedApplication = backupList.removeAt(oldIndex)
                backupList.add(newIndex, application)
                if (application.filter(filter)) {
                    displayList.add(application)
                } else {
                    displayList.remove(removedApplication)
                }
            }
            REMOVED -> {
                val removedApplication = backupList.removeAt(newIndex)
                displayList.remove(removedApplication)
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

    fun onFavoriteToggle(application: Application) {
        application.apply {
            val indexOf = displayList.indexOf(this)
            applyFavoriteState(isFavorite.not(), preferences)
            displayList.updateItemAt(indexOf, this)
        }
    }

    fun indexOf(application: Application): Int = displayList.indexOf(application)

    private var filter: String? = null

    fun filter(): String? = filter

    fun filter(value: String?) {
        val search = if (value.isNullOrBlank()) null else value
        if (filter == search) {
            return
        }
        filter = search
        displayList.beginBatchedUpdates()
        if (search.isNullOrBlank()) {
            displayList.clear()
            displayList.addAll(backupList)
        } else {
            for (application in backupList) {
                if (application.filter(search)) {
                    displayList.add(application)
                } else {
                    displayList.remove(application)
                }
            }
        }
        displayList.endBatchedUpdates()
        notifyDataSetChanged()
    }

}