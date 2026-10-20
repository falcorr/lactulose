// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// apkupdater.kt
// Finds the newest copy of this app: the latest GitHub release of falcorr/lactulose, or any file named
// lactulose*.apk in folders the user granted once (Android doesn't allow granting Download itself or an SD
// card's root, so these are subfolders such as Download/lactulose, internal storage first). The app never
// installs anything itself, so neither it nor a browser needs permission to install apps (user, 20/10/2026): a
// release is downloaded by the app into the update folder, checked, and then opened from the Files app, so
// Android's own package installer installs it, refusing an update not signed with this app's key.
package com.falcorr.lactulose.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import com.falcorr.lactulose.io.FetchResult
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


  // Returns the first granted folder the app may write to, or null.
  fun writableFolder(): Uri?
  {
    val writable = context.contentResolver.persistedUriPermissions.filter { it.isWritePermission }.map { it.uri }

    return folders().firstOrNull { it in writable }
  }


  // Downloads release into the first writable update folder as lactulose-<version>.apk, after checking it is
  // this app, newer than the installed version, and signed with the same key. Returns the file name, or the
  // error. Run off the main thread.
  fun downloadToFolder(release: LatestRelease): FetchResult<String>
  {
    val tree = writableFolder() ?: return FetchResult(false, error = "no writable folder")
    val file = File(context.cacheDir, "download.apk")
    val name = "lactulose-" + release.versionName + ".apk"
    var target: Uri?

    try
    {
      val fetched = GithubRelease(SecureFetch(GITHUB_HOSTS), githubToken).download(release, file)

      if (!fetched.ok) return FetchResult(false, error = fetched.error)
      val problem = checkApk(file)

      if (problem.isNotEmpty()) return FetchResult(false, error = problem)
      target = apkFiles(tree).firstOrNull { it.lastPathSegment?.substringAfterLast('/')?.lowercase() == name }
      if (target == null)
      {
        target = DocumentsContract.createDocument(context.contentResolver,
          DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
          "application/vnd.android.package-archive", name)
      }
      if (target == null) return FetchResult(false, error = "couldn't create " + name)
      context.contentResolver.openOutputStream(target, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
        ?: return FetchResult(false, error = "couldn't write " + name)
      return FetchResult(true, target.lastPathSegment?.substringAfterLast('/') ?: name)
    }
    catch (e: Exception)
    {
      return FetchResult(false, error = e.toString())
    }
    finally
    {
      file.delete()
    }
  }


  // Returns why file isn't a valid update (another app, not newer, or a different signing key), or "" when it is.
  // When Android can't read the file's signers the installer's own check still applies.
  private fun checkApk(file: File): String
  {
    val info = context.packageManager.getPackageArchiveInfo(file.path,
      PackageManager.GET_SIGNING_CERTIFICATES or @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES)
      ?: return "downloaded file isn't an app"
    val installed = context.packageManager.getPackageInfo(context.packageName,
      PackageManager.GET_SIGNING_CERTIFICATES or @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES)
    val theirs = signers(info)
    val ours = signers(installed)

    if (info.packageName != context.packageName) return "downloaded file is another app"
    if (info.longVersionCode <= installed.longVersionCode) return "downloaded file isn't newer"
    if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs != ours) return "downloaded file has the wrong signature"
    return ""
  }


  // Returns the signing certificates of info as hex strings, or an empty set when Android didn't supply them.
  @Suppress("DEPRECATION")
  private fun signers(info: android.content.pm.PackageInfo): Set<String> =
    (info.signingInfo?.apkContentsSigners ?: info.signatures ?: emptyArray()).map { it.toCharsString() }.toSet()


  // Granted folder tree URIs, internal storage first.
  fun folders(): List<Uri> =
    (prefs.getString(KEY_FOLDERS, "") ?: "").split('\n').filter { it.isNotEmpty() }.map { Uri.parse(it) }


  // Keeps read and write access to a folder the user picked (read only if write wasn't granted), internal storage
  // ahead of removable storage.
  fun addFolder(tree: Uri)
  {
    val all = (folders() + tree).distinct().sortedBy { if (it.toString().contains("primary")) 0 else 1 }

    try
    {
      context.contentResolver.takePersistableUriPermission(tree,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    catch (e: SecurityException)
    {
      context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    prefs.edit().putString(KEY_FOLDERS, all.joinToString("\n")).apply()
  }


  // Forgets all granted folders so they can be picked again.
  fun clearFolders()
  {
    for (uri in folders())
    {
      try { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
      catch (e: Exception) {}
      try { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
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
