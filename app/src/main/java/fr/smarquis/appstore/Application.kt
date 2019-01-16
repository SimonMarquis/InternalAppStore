package fr.smarquis.appstore

import android.content.SharedPreferences
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.Keep
import com.google.firebase.database.DataSnapshot

@Keep
data class Application(
        var key: String? = null,
        val name: String? = null,
        val packageName: String? = null,
        val description: String? = null,
        val image: String? = null,
        val link_1: Link? = null,
        val link_2: Link? = null,
        val link_3: Link? = null,
        val link_4: Link? = null,
        val link_5: Link? = null
) : Parcelable {

    val descriptionToHtml by lazy {
        Utils.parseHtml(description)
    }

    var isFavorite = false

    private constructor(p: Parcel) : this(
            key = p.readString(),
            name = p.readString(),
            packageName = p.readString(),
            description = p.readString(),
            image = p.readString(),
            link_1 = p.readParcelable(Link::class.java.classLoader),
            link_2 = p.readParcelable(Link::class.java.classLoader),
            link_3 = p.readParcelable(Link::class.java.classLoader),
            link_4 = p.readParcelable(Link::class.java.classLoader),
            link_5 = p.readParcelable(Link::class.java.classLoader)
    )

    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeString(key)
        p.writeString(name)
        p.writeString(packageName)
        p.writeString(description)
        p.writeString(image)
        p.writeParcelable(link_1, flags)
        p.writeParcelable(link_2, flags)
        p.writeParcelable(link_3, flags)
        p.writeParcelable(link_4, flags)
        p.writeParcelable(link_5, flags)
    }

    override fun describeContents() = 0

    companion object {
        @Suppress("unused")
        @JvmField
        val CREATOR = object : Parcelable.Creator<Application> {
            override fun createFromParcel(parcel: Parcel) = Application(parcel)

            override fun newArray(size: Int) = arrayOfNulls<Application?>(size)
        }

        private val SAFE_PARSER: (DataSnapshot, preferences: SharedPreferences?) -> Application? = { snapshot, preferences ->
            snapshot.getValue(Application::class.java)?.apply {
                key = snapshot.key
                preferences?.let { fetchFavoriteState(it) }
            }
        }

        fun parse(dataSnapshot: DataSnapshot, preferences: SharedPreferences? = null) = SAFE_PARSER(dataSnapshot, preferences)

    }

}