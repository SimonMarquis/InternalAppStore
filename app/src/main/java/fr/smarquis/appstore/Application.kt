package fr.smarquis.appstore

import android.content.SharedPreferences
import android.os.Parcelable
import androidx.annotation.Keep
import com.google.firebase.database.DataSnapshot
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
data class Application(
    var key: String? = null,
    val name: String? = null,
    val packageName: String? = null,
    val description: String? = null,
    val image: String? = null,
    val link1: Link? = null,
    val link2: Link? = null,
    val link3: Link? = null,
    val link4: Link? = null,
    val link5: Link? = null,
) : Parcelable {

    @IgnoredOnParcel
    val descriptionToHtml by lazy {
        Utils.parseHtml(description)
    }

    @IgnoredOnParcel
    var isFavorite = false

    override fun describeContents() = 0

    companion object {

        private val SAFE_PARSER: (DataSnapshot, preferences: SharedPreferences?) -> Application? = { snapshot, preferences ->
            snapshot.getValue(Application::class.java)?.apply {
                key = snapshot.key
                preferences?.let { fetchFavoriteState(it) }
            }
        }

        fun parse(dataSnapshot: DataSnapshot, preferences: SharedPreferences? = null) = SAFE_PARSER(dataSnapshot, preferences)

    }

}
