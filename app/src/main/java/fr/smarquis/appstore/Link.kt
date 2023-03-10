package fr.smarquis.appstore

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
data class Link(
    val name: String? = null,
    val uri: String? = null,
) : Parcelable
