// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// githubrelease.kt
// Reads the latest GitHub release of falcorr/lactulose: its version and the web address of its APK, which the
// phone's browser downloads so Android's own package installer can install it. A private repository needs a
// read-only access token, which is sent only to GitHub's API host.
package com.falcorr.lactulose.update

import com.falcorr.lactulose.io.FetchResult
import com.falcorr.lactulose.io.SecureFetch
import org.json.JSONObject


// Repository holding release builds, and GitHub's API host (the only one the app itself contacts there).
const val GITHUB_REPO = "falcorr/lactulose"
val GITHUB_HOSTS = setOf("api.github.com")


// A release: its version name without the leading "v" (e.g. "0.8.0") and the APK's download address.
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
          apkUrl = assets.getJSONObject(i).getString("browser_download_url")
          break
        }
      }
    }
    catch (e: Exception)
    {
      return FetchResult(false, error = "GitHub release unreadable: " + e.message)
    }
    if (apkUrl.isEmpty() || !apkUrl.startsWith("https://github.com/" + GITHUB_REPO + "/releases/download/"))
    {
      return FetchResult(false, error = "Latest GitHub release has no .apk file")
    }
    return FetchResult(true, LatestRelease(json.optString("tag_name").removePrefix("v"), apkUrl))
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
