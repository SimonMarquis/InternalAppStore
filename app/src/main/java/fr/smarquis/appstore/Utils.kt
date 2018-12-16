package fr.smarquis.appstore

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.*
import android.provider.Settings
import android.text.Html
import android.text.Spanned
import android.text.format.DateUtils
import android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
import android.text.format.DateUtils.SECOND_IN_MILLIS
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.set
import androidx.core.text.toSpannable
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.RecyclerView


class Utils {

    companion object {

        fun isAtLeast(versionCode: Int): Boolean {
            return SDK_INT >= versionCode
        }

        fun isApplicationInstalled(context: Context, packageName: String): Boolean {
            return try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

        fun applicationVersionName(context: Context, packageName: String): String? {
            return try {
                context.packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

        fun applicationVersionCode(context: Context, packageName: String): Long {
            return try {
                PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(packageName, 0))
            } catch (e: PackageManager.NameNotFoundException) {
                0
            }
        }

        fun applicationLastUpdateTime(context: Context, packageName: String): Long? {
            return try {
                context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

        fun getLaunchIntent(context: Context, packageName: String): Intent? {
            return context.packageManager.getLaunchIntentForPackage(packageName)
        }

        fun getDetailsIntent(packageName: String): Intent {
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }

        fun getDeleteIntent(packageName: String): Intent {
            return Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
        }

        fun getMarketIntent(packageName: String): Intent {
            return Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("market").authority("details").appendQueryParameter("id", packageName).build())
        }

        fun notificationSettingsIntent(context: Context, channelId: String? = null, openChannel: Boolean = true): Intent {
            return when {
                SDK_INT >= O -> {
                    val action = if (channelId != null && openChannel) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    Intent(action).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                    }
                }
                SDK_INT > N_MR1 -> {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                }
                SDK_INT >= LOLLIPOP -> {
                    Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
                        putExtra("app_package", context.packageName)
                        putExtra("app_uid", context.applicationInfo.uid)
                    }
                }
                else -> {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        addCategory(Intent.CATEGORY_DEFAULT)
                        data = Uri.parse("package:" + context.packageName)
                    }
                }
            }
        }

        fun matchesFilter(text: CharSequence?, constraint: String?): Boolean =
                when {
                    constraint.isNullOrBlank() -> true // Always accept empty constraint
                    text.isNullOrBlank() -> false // Always deny empty text
                    else -> text.indexOf(constraint, ignoreCase = true) != -1 // Search first occurrence
                }

        fun highlightFilter(text: CharSequence?, constraint: String?): CharSequence? {
            if (constraint.isNullOrBlank() || text.isNullOrBlank()) {
                return text
            }
            var indexOf = text.indexOf(constraint, ignoreCase = true)
            if (indexOf == -1) {
                return text
            }
            val spannable = text.toSpannable()
            do {
                spannable[indexOf, indexOf + constraint.length] = StyleSpan(Typeface.BOLD)
                spannable[indexOf, indexOf + constraint.length] = BackgroundColorSpan(Color.YELLOW)
                // more ? text size ?
                indexOf = text.indexOf(constraint, indexOf + 1, ignoreCase = true)
            } while (indexOf != -1)

            return buildSpannedString {
                append(spannable)
            }
        }

        fun highlight(viewHolder: RecyclerView.ViewHolder) {
            viewHolder.setIsRecyclable(false)
            viewHolder.itemView.apply {
                isPressed = true
                // Animate RippleDrawable
                if (Utils.isAtLeast(LOLLIPOP)) {
                    postDelayed({
                        drawableHotspotChanged(width / 2F, height / 2F)
                        isPressed = false
                        viewHolder.setIsRecyclable(true)
                    }, resources.getInteger(android.R.integer.config_mediumAnimTime) * 2.toLong())
                } else {
                    doOnNextLayout {
                        isPressed = false
                        viewHolder.setIsRecyclable(true)
                    }
                    requestLayout()
                }
            }
        }

        fun parseHtml(description: String?): Spanned? {
            return if (description == null || description.isBlank()) {
                null
            } else {
                description.replace("\n", "<br>").let {
                    if (Utils.isAtLeast(N)) {
                        Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY)
                    } else {
                        @Suppress("DEPRECATION")
                        Html.fromHtml(it)
                    }
                }
            }
        }

        fun relativeTimeSpan(time: Long?, now: Long = System.currentTimeMillis(), minResolution: Long = SECOND_IN_MILLIS, flags: Int = FORMAT_ABBREV_RELATIVE): CharSequence? {
            return DateUtils.getRelativeTimeSpanString(time ?: now, now, minResolution, flags)
        }
    }
}
