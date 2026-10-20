// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// apkupdater.kt
// Finds the newest copy of this app: the latest GitHub release of falcorr/lactulose, or any file named
// lactulose*.apk in folders the user granted once (Android doesn't allow granting Download itself or an SD
// card's root, so these are subfolders such as Download/lactulose, internal storage first). The app never
// installs anything itself, so it needs no permission to install apps (user, 20/10/2026): a release is handed to
// the browser to download, and a folder copy is opened from the Files app; either way Android's own package
// installer installs it, refusing an update not signed with this app's key.
package com.falcorr.lactulose.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.falcorr.lactulose.io.SecureFetch
import java.io.File


// Preference keys holding granted folder URIs (newline-separated, internal storage first) and the GitHub token.
private const val KEY_FOLDERS = "updateFolders"
private const val KEY_TOKEN = "githubToken"


// A found update: where it is, its version code and version name, and its file name.
class FoundApk(val uri: Uri, val versionCode: Long, val versionName: String, val fileName: String = "")


class ApkUpdater(private val context: Context)
{
  // Small preference file holding only the granted folders.
  private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)


  // GitHub access token for a private repository, or "".
  var githubToken: String
    get() = prefs.getString(KEY_TOKEN, "") ?: ""
    set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()


  // Returns the latest GitHub release if it is newer than the installed version, else null, with any error
  // message. Run off the main thread.
  fun findNewerOnGithub(): Pair<LatestRelease?, String>
  {
    val result = GithubRelease(SecureFetch(GITHUB_HOSTS), githubToken).latest()

    if (!result.ok) return Pair(null, result.error)
    if (compareVersions(result.value!!.versionName, installedVersionName()) <= 0) return Pair(null, "")
    return Pair(result.value, "")
  }


  // Granted folder tree URIs, internal storage first.
  fun folders(): List<Uri> =
    (prefs.getString(KEY_FOLDERS, "") ?: "").split('\n').filter { it.isNotEmpty() }.map { Uri.parse(it) }


  // Keeps read access to a folder the user picked, internal storage ahead of removable storage.
  fun addFolder(tree: Uri)
  {
    val all = (folders() + tree).distinct().sortedBy { if (it.toString().contains("primary")) 0 else 1 }

    context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    prefs.edit().putString(KEY_FOLDERS, all.joinToString("\n")).apply()
  }


  // Forgets all granted folders so they can be picked again.
  fun clearFolders()
  {
    for (uri in folders())
    {
      try { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
      catch (e: Exception) {}
    }
    prefs.edit().remove(KEY_FOLDERS).apply()
  }


  // Returns the newest lactulose*.apk across the granted folders that is this app and newer than the installed
  // version, or null. Each candidate is copied to the app cache briefly to read its version. Run off the main
  // thread.
  fun findNewer(): FoundApk?
  {
    val installed = installedVersionCode()
    val probe = File(context.cacheDir, "probe.apk")
    var best: FoundApk? = null

    for (tree in folders())
    {
      for (uri in apkFiles(tree))
      {
        try
        {
          context.contentResolver.openInputStream(uri)?.use { input -> probe.outputStream().use { input.copyTo(it) } }
          val info = context.packageManager.getPackageArchiveInfo(probe.path, 0) ?: continue
          val code = info.longVersionCode

          if (info.packageName == context.packageName && code > installed && code > (best?.versionCode ?: 0L))
          {
            best = FoundApk(uri, code, info.versionName ?: "", uri.lastPathSegment?.substringAfterLast('/') ?: "")
          }
        }
        catch (e: Exception)
        {
          continue
        }
        finally
        {
          probe.delete()
        }
      }
    }
    return best
  }


  // Lists document URIs of files directly in tree whose names start with "lactulose" and end in ".apk".
  private fun apkFiles(tree: Uri): List<Uri>
  {
    val found = ArrayList<Uri>()
    val children = try
    {
      DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }
    catch (e: Exception)
    {
      return found
    }
    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    try
    {
      context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
        while (cursor.moveToNext())
        {
          val name = cursor.getString(1).lowercase()

          if (name.startsWith("lactulose") && name.endsWith(".apk"))
          {
            found.add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
          }
        }
      }
    }
    catch (e: Exception)
    {
      // A folder that is gone (for example an ejected SD card) is skipped.
    }
    return found
  }


  // Version code and name of the installed app, and when it was last installed or updated (epoch ms).
  fun installedVersionCode(): Long =
    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode


  fun installedVersionName(): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""


  fun lastUpdatedMs(): Long =
    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
}
