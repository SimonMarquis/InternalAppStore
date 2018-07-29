package fr.smarquis.appstore

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.Keep

@Keep
data class Link(val name: String? = null,
                val uri: String? = null
) : Parcelable {

    private constructor(p: Parcel) : this(
            name = p.readString(),
            uri = p.readString()
    )

    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeString(name)
        p.writeString(uri)
    }

    override fun describeContents() = 0

    companion object {
        @Suppress("unused")
        @JvmField
        val CREATOR = object : Parcelable.Creator<Link> {
            override fun createFromParcel(parcel: Parcel) = Link(parcel)

            override fun newArray(size: Int) = arrayOfNulls<Link?>(size)
        }
    }
}