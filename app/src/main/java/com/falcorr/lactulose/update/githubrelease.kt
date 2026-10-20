// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// githubrelease.kt
// Reads the latest GitHub release of falcorr/lactulose: its version and the API address of its APK, which the app
// downloads itself (user, 20/10/2026). A private repository needs a read-only access token, which is sent only to
// GitHub's API host, never to the file host GitHub redirects the download to.
package com.falcorr.lactulose.update

import com.falcorr.lactulose.io.FetchResult
import com.falcorr.lactulose.io.SecureFetch
import org.json.JSONObject


// Repository holding release builds, GitHub's API host, and the hosts GitHub redirects release downloads to.
const val GITHUB_REPO = "falcorr/lactulose"
val GITHUB_HOSTS = setOf("api.github.com")
val GITHUB_DOWNLOAD_HOSTS = setOf("api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

// Largest APK the app will download.
const val MAX_APK_BYTES = 20_000_000L


// A release: its version name without the leading "v" (e.g. "0.8.0") and the APK asset's API address.
class LatestRelease(val versionName: String, val apkUrl: String)


class GithubRelease(private val fetch: SecureFetch, private val token: String)
{
  // Returns the latest release's version and first .apk asset, or the error.
  fun latest(): FetchResult<LatestRelease>
  {
    val headers = HashMap<String, String>()
    val release: FetchResult<String>
    var json: JSONObject
    var apkUrl = ""

    headers["Accept"] = "application/vnd.github+json"
    if (token.isNotEmpty()) headers["Authorization"] = "Bearer " + token
    release = fetch.get("https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest", headers = headers)
    if (!release.ok) return FetchResult(false, error = "GitHub release check failed: " + release.error)
    try
    {
      json = JSONObject(release.value!!)
      val assets = json.getJSONArray("assets")

      for (i in 0 until assets.length())
      {
        if (assets.getJSONObject(i).getString("name").lowercase().endsWith(".apk"))
        {
          apkUrl = assets.getJSONObject(i).getString("url")
          break
        }
      }
    }
    catch (e: Exception)
    {
      return FetchResult(false, error = "GitHub release unreadable: " + e.message)
    }
    if (apkUrl.isEmpty() || !apkUrl.startsWith("https://api.github.com/repos/" + GITHUB_REPO + "/releases/assets/"))
    {
      return FetchResult(false, error = "Latest GitHub release has no .apk file")
    }
    return FetchResult(true, LatestRelease(json.optString("tag_name").removePrefix("v"), apkUrl))
  }


  // Downloads release's APK into file, replacing it. The token goes only to the API host; GitHub then redirects
  // to its file host.
  fun download(release: LatestRelease, file: java.io.File): FetchResult<Unit>
  {
    val headers = HashMap<String, String>()

    headers["Accept"] = "application/octet-stream"
    if (token.isNotEmpty()) headers["Authorization"] = "Bearer " + token
    return SecureFetch(GITHUB_DOWNLOAD_HOSTS).stream(release.apkUrl, MAX_APK_BYTES, headers) { input ->
      file.outputStream().use { input.copyTo(it) }
      Unit
    }
  }
}


// Compares dotted version names numerically, e.g. "0.10.0" > "0.9.1"; returns <0, 0 or >0 like compareTo.
fun compareVersions(a: String, b: String): Int
{
  val left = a.split('.').map { it.toIntOrNull() ?: 0 }
  val right = b.split('.').map { it.toIntOrNull() ?: 0 }

  for (i in 0 until maxOf(left.size, right.size))
  {
    val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }

    if (diff != 0) return diff
  }
  return 0
}
