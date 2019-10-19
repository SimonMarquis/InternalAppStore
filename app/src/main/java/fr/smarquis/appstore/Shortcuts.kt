package fr.smarquis.appstore

import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.Context.SHORTCUT_SERVICE
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.*
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.TaskStackBuilder
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import fr.smarquis.appstore.Shortcuts.Type.DYNAMIC
import fr.smarquis.appstore.Shortcuts.Type.PINNED
import java.lang.Boolean.TRUE
import kotlin.math.max

/**
 * Manage shortcuts to VersionsActivity through PersistableBundle on api >= 26 but as flat Bundle otherwise
 * - STATIC (unsupported)
 * - PINNED (all api levels, and widget picker on api >= 26)
 * - DYNAMIC (only on api >= 25)
 */
class Shortcuts private constructor(_context: Context) {

    enum class Type(private val prefix: String) {
        STATIC("static"),
        PINNED("pinned"),
        DYNAMIC("dynamic");

        fun idOf(application: Application) = "${prefix}_${application.key.orEmpty()}"
    }

    val context: Context = _context.applicationContext

    val glide by lazy { Glide.with(context) }

    val options by lazy {
        val launcherLargeIconSize = (context.getSystemService(ACTIVITY_SERVICE) as ActivityManager).launcherLargeIconSize
        val appIconSize = context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
        RequestOptions()
                .override(max(launcherLargeIconSize, appIconSize))
                .fallback(R.drawable.item_application_icon_placeholder)
                .centerCrop()
    }

    companion object : SingletonHolder<Shortcuts, Context>(::Shortcuts) {

        private const val TAG = "SHORTCUTS"

        private val EXTRAS = VersionsActivity::class.java.`package`
        private val EXTRA_SHORTCUT_APPLICATION = "$EXTRAS.EXTRA_SHORTCUT_APPLICATION"

        private fun has(versionCode: Int) = if (SDK_INT >= versionCode) TRUE else null

    }

    @RequiresApi(N_MR1)
    private fun manager() = context.getSystemService(SHORTCUT_SERVICE) as ShortcutManager

    private fun shortcutInfoCompat(type: Type, application: Application, bitmap: Bitmap): ShortcutInfoCompat {
        val label = application.name.orEmpty()
        return ShortcutInfoCompat.Builder(context, type.idOf(application))
                .setShortLabel(label)
                .setLongLabel(label)
                .setIntents(intents(application))
                .setIcon(IconCompat.createWithBitmap(bitmap))
                .setDisabledMessage(context.getString(R.string.shortcut_application_removed))
                .build()
    }

    @RequiresApi(N_MR1)
    private fun shortcutInfo(type: Type, application: Application, bitmap: Bitmap, rank: Int? = null): ShortcutInfo {
        val label = application.name.orEmpty()
        return ShortcutInfo.Builder(context, type.idOf(application))
                .setShortLabel(label)
                .setLongLabel(label)
                .setIntents(intents(application))
                .setIcon(Icon.createWithBitmap(bitmap))
                .setDisabledMessage(context.getString(R.string.shortcut_application_removed))
                .apply { rank?.let { setRank(max(0, it)) } }
                .build()
    }

    @TargetApi(LOLLIPOP)
    private fun intents(application: Application): Array<out Intent> {
        val intent = Intent(context, VersionsActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            when {
                SDK_INT >= O -> putExtra(EXTRA_SHORTCUT_APPLICATION, application.toPersistableBundle())
                else -> putExtras(application.toFlatBundle(EXTRA_SHORTCUT_APPLICATION))
            }
        }
        return TaskStackBuilder.create(context).addNextIntentWithParentStack(intent).intents
    }

    fun extract(intent: Intent): Application? {
        return when {
            SDK_INT >= O -> Application.fromPersistableBundle(intent.getParcelableExtra(EXTRA_SHORTCUT_APPLICATION))
            else -> Application.fromFlatBundle(EXTRA_SHORTCUT_APPLICATION, intent.extras)
        }
    }

    fun request(application: Application) {
        Log.d(TAG, "Requesting `$PINNED` shortcut for `${application.name}`")
        loadImage(application) {
            val shortcut = shortcutInfoCompat(PINNED, application, it)
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
            if (SDK_INT < O) {
                Toast.makeText(context, R.string.shortcut_added_to_home_screen, Toast.LENGTH_LONG).show()
            }
        }
    }

    @TargetApi(N_MR1)
    fun use(application: Application) {
        has(N_MR1) ?: return
        Log.d(TAG, "Reporting usage of shortcut for `${application.name}`")
        with(manager()) {
            loadImage(application) { bitmap ->
                val id = DYNAMIC.idOf(application)
                val shortcuts = dynamicShortcuts.asSequence().filterNotNull().sortedBy { it.rank }.toList()
                val found = shortcuts.find { it.id == id }
                val shortcut = shortcutInfo(DYNAMIC, application, bitmap, found?.rank?.dec())
                if (found == null) {
                    // Add new dynamic shortcut
                    val availableSlots = maxShortcutCountPerActivity - shortcuts.size
                    if (availableSlots < 1) {
                        shortcuts.takeLast(availableSlots.unaryMinus() + 1).map { it.id }.let { lasts ->
                            Log.d(TAG, "Max `$DYNAMIC` shortcuts count reached ($maxShortcutCountPerActivity), removing: `$lasts`")
                            removeDynamicShortcuts(lasts)
                        }
                    }
                    Log.d(TAG, "Adding missing `$DYNAMIC` shortcut for `${application.name}`")
                    addDynamicShortcuts(listOf(shortcut))
                } else {
                    // Increase rank of shortcut
                    Log.d(TAG, "Increasing `$DYNAMIC` shortcut rank for `${application.name}` to ${shortcut.rank}")
                    updateShortcuts(listOf(shortcut))
                    reportShortcutUsed(id)
                }
            }
        }
    }

    @TargetApi(N_MR1)
    fun update(application: Application) {
        has(N_MR1) ?: return
        Log.d(TAG, "Updating shortcuts for `${application.name}`...")
        with(manager()) {
            val pinnedId = PINNED.idOf(application)
            val pinned = pinnedShortcuts.firstOrNull { it.id == pinnedId }
            val dynamicId = DYNAMIC.idOf(application)
            val dynamic = dynamicShortcuts.firstOrNull { it.id == dynamicId }
            // Update only if exists
            pinned ?: dynamic ?: return
            loadImage(application) {
                val shortcuts = mutableListOf<ShortcutInfo>()
                if (pinned != null) {
                    Log.d(TAG, "Updating `$PINNED` shortcut for `${application.name}`")
                    shortcuts.add(shortcutInfoCompat(PINNED, application, it).toShortcutInfo())
                }
                if (dynamic != null) {
                    Log.d(TAG, "Updating `$DYNAMIC` shortcut for `${application.name}`")
                    shortcuts.add(shortcutInfoCompat(DYNAMIC, application, it).toShortcutInfo())
                }
                updateShortcuts(shortcuts)
            }
        }
    }

    @TargetApi(N_MR1)
    fun remove(application: Application) {
        has(N_MR1) ?: return
        Log.d(TAG, "Removing shortcuts for `${application.name}`")
        with(manager()) {
            val dynamic = DYNAMIC.idOf(application)
            val pinned = PINNED.idOf(application)
            removeDynamicShortcuts(listOf(dynamic))
            disableShortcuts(listOf(dynamic, pinned), context.getString(R.string.shortcut_application_removed))
        }
    }

    @TargetApi(N_MR1)
    fun invalidate() {
        has(N_MR1) ?: return
        Log.d(TAG, "Invalidating all shortcuts...")
        with(manager()) {
            removeAllDynamicShortcuts()
            disableShortcuts(pinnedShortcuts.map { it.id }, context.getString(R.string.shortcut_application_removed))
        }
    }

    @Suppress("DEPRECATION")
    private fun loadImage(application: Application, block: (bitmap: Bitmap) -> Unit) {
        glide.asBitmap()
                .apply(options)
                .load(application.findImageReference())
                .into(object : CustomTarget<Bitmap>(options.overrideWidth, options.overrideHeight) {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        block(resource)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
    }

    @RequiresApi(LOLLIPOP)
    private fun Application.Companion.fromPersistableBundle(bundle: PersistableBundle?): Application? {
        return with(bundle ?: return null) {
            Application(
                    key = getString("key"),
                    name = getString("name"),
                    packageName = getString("packageName"),
                    description = getString("description"),
                    image = getString("image"),
                    link_1 = Link.fromPersistableBundle(getPersistableBundle("link_1")),
                    link_2 = Link.fromPersistableBundle(getPersistableBundle("link_2")),
                    link_3 = Link.fromPersistableBundle(getPersistableBundle("link_3")),
                    link_4 = Link.fromPersistableBundle(getPersistableBundle("link_4")),
                    link_5 = Link.fromPersistableBundle(getPersistableBundle("link_5"))
            )
        }
    }

    private fun Application.Companion.fromFlatBundle(prefix: String, bundle: Bundle?): Application? {
        return with(bundle ?: return null) {
            Application(
                    key = getString("$prefix.key"),
                    name = getString("$prefix.name"),
                    packageName = getString("$prefix.packageName"),
                    description = getString("$prefix.description"),
                    image = getString("$prefix.image"),
                    link_1 = Link.fromFlatBundle("$prefix.link_1", this),
                    link_2 = Link.fromFlatBundle("$prefix.link_2", this),
                    link_3 = Link.fromFlatBundle("$prefix.link_3", this),
                    link_4 = Link.fromFlatBundle("$prefix.link_4", this),
                    link_5 = Link.fromFlatBundle("$prefix.link_5", this)
            )
        }
    }


    @RequiresApi(LOLLIPOP)
    private fun Application.toPersistableBundle(): PersistableBundle {
        return PersistableBundle().apply {
            putString("key", key)
            putString("name", name)
            putString("packageName", packageName)
            putString("description", description)
            putString("image", image)
            putPersistableBundle("link_1", link_1?.toPersistableBundle())
            putPersistableBundle("link_2", link_2?.toPersistableBundle())
            putPersistableBundle("link_3", link_3?.toPersistableBundle())
            putPersistableBundle("link_4", link_4?.toPersistableBundle())
            putPersistableBundle("link_5", link_5?.toPersistableBundle())
        }
    }

    private fun Application.toFlatBundle(prefix: String): Bundle {
        return Bundle().apply {
            putString("$prefix.key", key)
            putString("$prefix.name", name)
            putString("$prefix.packageName", packageName)
            putString("$prefix.description", description)
            putString("$prefix.image", image)
            putAll(link_1?.toFlatBundle("$prefix.link_1"))
        }
    }


    @RequiresApi(LOLLIPOP)
    private fun Link.Companion.fromPersistableBundle(bundle: PersistableBundle?): Link? {
        return with(bundle ?: return null) {
            Link(
                    name = getString("name"),
                    uri = getString("uri")
            )
        }
    }

    private fun Link.Companion.fromFlatBundle(prefix: String, bundle: Bundle?): Link? {
        return with(bundle ?: return null) {
            Link(
                    name = getString("$prefix.name"),
                    uri = getString("$prefix.uri")
            )
        }
    }

    @RequiresApi(LOLLIPOP)
    private fun Link.toPersistableBundle(): PersistableBundle {
        return PersistableBundle().apply {
            putString("name", name)
            putString("uri", uri)
        }
    }

    private fun Link.toFlatBundle(prefix: String): Bundle {
        return Bundle().apply {
            putString("$prefix.name", name)
            putString("$prefix.uri", uri)
        }
    }

}