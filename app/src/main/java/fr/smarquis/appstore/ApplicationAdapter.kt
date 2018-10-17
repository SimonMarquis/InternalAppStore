package fr.smarquis.appstore

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import com.bumptech.glide.RequestManager
import com.firebase.ui.database.FirebaseRecyclerAdapter
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query

class ApplicationAdapter(
        query: Query,
        private val glide: RequestManager,
        private val callback: Callback

) : FirebaseRecyclerAdapter<Application, ApplicationViewHolder>(recyclerOptions(query)) {

    companion object {

        private fun recyclerOptions(query: Query) = FirebaseRecyclerOptions.Builder<Application>().setQuery(query) { Application.parse(it)!! }.build()

        val PAYLOAD_PACKAGE_CHANGED = Any()

    }

    interface Callback {
        fun showEmptyState()
        fun onItemClicked(application: Application, applicationViewHolder: ApplicationViewHolder)
    }

    override fun onDataChanged() {
        super.onDataChanged()
        if (itemCount == 0) {
            callback.showEmptyState()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApplicationViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.item_application, parent, false)
        return ApplicationViewHolder(view, callback, glide)
    }

    override fun onBindViewHolder(holder: ApplicationViewHolder, position: Int, application: Application) {
        holder.bind(application)
    }

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

    override fun onError(error: DatabaseError) {
        Log.w("ApplicationAdapter", "message:${error.message}, code:${error.code}")
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