package fr.smarquis.appstore

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.*
import android.provider.Settings
import android.support.v7.widget.RecyclerView
import android.text.Html
import android.text.Spanned


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

        fun applicationVersionCode(context: Context, packageName: String): Int {
            return try {
                context.packageManager.getPackageInfo(packageName, 0).versionCode
            } catch (e: PackageManager.NameNotFoundException) {
                0
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

        fun highlight(viewHolder: RecyclerView.ViewHolder) {
            viewHolder.setIsRecyclable(false)
            viewHolder.itemView.apply {
                isPressed = true
                // Animate RippleDrawable
                if (Utils.isAtLeast(Build.VERSION_CODES.LOLLIPOP)) {
                    postDelayed({
                        drawableHotspotChanged(width / 2F, height / 2F)
                        isPressed = false
                        viewHolder.setIsRecyclable(true)
                    }, 300)
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
    }
}
