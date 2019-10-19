package fr.smarquis.appstore

import android.util.SparseArray
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wrap an action on a version to a unique request code
 */
class VersionRequest(
        val action: Action,
        val version: Version?
) {

    companion object {

        private val requestIds: AtomicInteger = AtomicInteger(1)

        private val requests: SparseArray<VersionRequest> = SparseArray()

        /**
         * Creates the wrapper and returns the request code
         */
        fun create(action: Action, version: Version? = null): Int {
            requestIds.incrementAndGet().let {
                requests.put(it, VersionRequest(action, version))
                return it
            }
        }

        /**
         * Extract the wrapper from the request code
         */
        fun extract(id: Int): VersionRequest? {
            val request = requests.get(id, null)
            requests.remove(id)
            return request
        }
    }

    enum class Action {
        INSTALL, OPEN, UNINSTALL
    }

}