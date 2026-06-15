package com.richardluo.globalIconPack.iconPack

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.StrictMode
import androidx.core.net.toUri
import com.richardluo.globalIconPack.BuildConfig
import com.richardluo.globalIconPack.iconPack.source.IconPackInfoSerializer
import com.richardluo.globalIconPack.iconPack.source.loadIconPack
import com.richardluo.globalIconPack.ui.MyApplication
import com.richardluo.globalIconPack.utils.SingletonManager.get
import com.richardluo.globalIconPack.utils.getOrNull
import com.richardluo.globalIconPack.utils.log
import com.richardluo.globalIconPack.utils.unflattenFromString

class IconPackProvider : ContentProvider() {
  companion object {
    const val AUTHORITIES = "${BuildConfig.APPLICATION_ID}.IconPackProvider"
    val ICON = "content://$AUTHORITIES/ICON".toUri()
    val FALLBACK = "content://$AUTHORITIES/FALLBACK".toUri()
    val ALL_ICONS = "content://$AUTHORITIES/ALL_ICONS".toUri()
    val IS_PACK_MODIFIED = "content://$AUTHORITIES/IS_PACK_MODIFIED".toUri()
    val PACK_INFO = "content://$AUTHORITIES/PACK_INFO".toUri()
  }

  private val iconPackDB: IconPackDB? by get {
    runCatching { IconPackDB(MyApplication.context) }.getOrNull { log(it) }
  }

  // Process-local cache: pack -> serialized IconPackInfo bytes
  // Multiple processes (launcher, system, etc.) all benefit, but the parse happens
  // only once per process lifetime on the first query for a given pack.
  private val packInfoCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

  private fun getPackInfoBytes(pack: String): ByteArray? =
    packInfoCache.computeIfAbsent(pack) { p ->
      val ctx = MyApplication.context
      val res =
        runCatching { ctx.packageManager.getResourcesForApplication(p) }.getOrNull { log(it) }
          ?: return@computeIfAbsent null
      val info = loadIconPack(res, p)
      runCatching { IconPackInfoSerializer.toBytes(info) }.getOrNull { log(it) }
    }

  override fun onCreate() = true

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? {
    selectionArgs ?: return null
    if (uri != PACK_INFO) {
      val db = iconPackDB ?: return null
      return strictModeAllowThreadDiskReads {
        runCatching { queryDb(uri, db, selectionArgs) }.getOrNull { log(it) }
      }
    }
    return strictModeAllowThreadDiskReads {
      runCatching {
          if (selectionArgs.isEmpty()) null
          else {
            val data = getPackInfoBytes(selectionArgs[0])
            if (data == null) null
            else MatrixCursor(arrayOf("data")).apply { addRow(arrayOf<Any>(data)) }
          }
        }
        .getOrNull { log(it) }
    }
  }

  private fun queryDb(uri: Uri, db: IconPackDB, selectionArgs: Array<out String>): Cursor? =
    when (uri) {
      FALLBACK ->
        if (selectionArgs.isNotEmpty()) db.getFallbackSettings(selectionArgs[0]) else null
      ICON ->
        if (selectionArgs.size >= 3) {
          db.getIcon(
            selectionArgs[0],
            selectionArgs.drop(2).mapNotNull { unflattenFromString(it) },
            selectionArgs[1].toBoolean(),
          )
        } else null
      ALL_ICONS ->
        if (selectionArgs.isNotEmpty()) db.getAllIconsForPack(selectionArgs[0]) else null
      IS_PACK_MODIFIED ->
        if (selectionArgs.isEmpty()) null
        else
          MatrixCursor(arrayOf("modified")).apply {
            addRow(arrayOf<Any>(if (db.isPackModified(selectionArgs[0])) 1 else 0))
          }
      else -> null
    }

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0
}

private inline fun <T> strictModeAllowThreadDiskReads(crossinline block: () -> T): T {
  val oldPolicy = StrictMode.allowThreadDiskReads()
  val result = block()
  StrictMode.setThreadPolicy(oldPolicy)
  return result
}
