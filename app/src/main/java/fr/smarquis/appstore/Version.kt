package fr.smarquis.appstore

import androidx.annotation.Keep
import com.google.firebase.database.DataSnapshot

@Keep
data class Version(
        var key: String? = null,
        val name: String? = null,
        val description: String? = null,
        val timestamp: Long? = null,
        val apkRef: String? = null,
        val apkGeneration: Long? = null,
        val apkUrl: String? = null) : Comparable<Version> {

    companion object {

        private val SAFE_PARSER: (DataSnapshot) -> Version? = { snapshot ->
            snapshot.getValue(Version::class.java)?.apply {
                key = snapshot.key
            }
        }

        fun parse(dataSnapshot: DataSnapshot) = SAFE_PARSER(dataSnapshot)

    }

    val semver by lazy { SemVer.parse(name) }

    val descriptionToHtml by lazy {
        Utils.parseHtml(description)
    }

    enum class Status {
        DEFAULT,
        DOWNLOADING,
        INSTALLING,
        OPENING
    }

    var status: Status = Status.DEFAULT

    /**
     * Keep track of progress when #status is #DOWNLOADING
     */
    var progress: Int = 0

    fun updateStatus(status: Status, progress: Int = 0) {
        this.status = status
        this.progress = progress
    }

    override fun compareTo(other: Version): Int {
        val compared = SemVer.nonNull(semver).compareTo(SemVer.nonNull(other.semver))
        return -when (compared) {
            0 -> (timestamp ?: 0L).compareTo(other.timestamp ?: 0L)
            else -> compared
        }
    }

}

