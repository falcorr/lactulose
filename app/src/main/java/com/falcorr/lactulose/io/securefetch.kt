// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// securefetch.kt
// HTTPS GET restricted to an allowlist of exact host names. Redirects are followed manually so a redirect
// can never lead outside the allowlist, and every response is size-capped.
package com.falcorr.lactulose.io

import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection


// Connection and read timeouts, the redirect limit, and the default response size cap.
private const val TIMEOUT_MS = 15_000

// Longest a whole response may take to arrive, so a trickling server can't hold a download open forever.
private const val TOTAL_TIMEOUT_MS = 120_000L
private const val MAX_REDIRECTS = 3
const val DEFAULT_MAX_BYTES = 2_000_000L

// Requests present as a typical Android Chrome browser, at the user's request (20/10/2026); BoM's site refuses
// requests with no user agent at all.
private const val USER_AGENT =
  "Mozilla/5.0 (Linux; Android 14; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"


// Result of a fetch: value on success, otherwise an error describing what failed.
class FetchResult<T>(val ok: Boolean, val value: T? = null, val error: String = "")


class SecureFetch(private val allowedHosts: Set<String>)
{
  // Fetches url as UTF-8 text of at most maxBytes.
  fun get(url: String, maxBytes: Long = DEFAULT_MAX_BYTES, headers: Map<String, String> = emptyMap()):
    FetchResult<String> = stream(url, maxBytes, headers) { String(it.readBytes(), Charsets.UTF_8) }


  // Opens url and hands its body to reader, which must consume it before returning; reading past maxBytes
  // throws, failing the fetch. Fails without connecting if the URL isn't HTTPS or its host isn't allowed.
  // headers are sent only to url's own host, never to a host a redirect leads to.
  fun <T> stream(url: String, maxBytes: Long, headers: Map<String, String> = emptyMap(), reader: (InputStream) -> T):
    FetchResult<T>
  {
    val firstHost = try { URL(url).host } catch (e: Exception) { "" }
    var target = url
    var connection: HttpsURLConnection? = null
    var code = 0

    for (attempt in 0..MAX_REDIRECTS)
    {
      val parsed = try { URL(target) } catch (e: Exception) { return FetchResult(false, error = "Bad URL " + target) }

      if (parsed.protocol != "https") return FetchResult(false, error = "Refused non-HTTPS URL " + target)
      if (parsed.host !in allowedHosts) return FetchResult(false, error = "Refused host " + parsed.host)
      try
      {
        connection = parsed.openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (parsed.host == firstHost) for ((name, value) in headers) connection.setRequestProperty(name, value)
        code = connection.responseCode
        if (code in 300..399)
        {
          target = URL(parsed, connection.getHeaderField("Location") ?: "").toString()
          continue
        }
        if (code != HttpURLConnection.HTTP_OK) return FetchResult(false, error = "HTTP " + code + " from " + parsed.host)
        return connection.inputStream.use { FetchResult(true, reader(CappedStream(it, maxBytes, parsed.host, System.currentTimeMillis() + TOTAL_TIMEOUT_MS))) }
      }
      catch (e: Exception)
      {
        return FetchResult(false, error = "Fetching from " + parsed.host + " failed: " + e.message)
      }
      finally
      {
        connection?.disconnect()
      }
    }
    return FetchResult(false, error = "Too many redirects from " + url)
  }
}


// Input stream that throws once more than maxBytes have been read, so a huge response can't exhaust memory, or
// once reading runs past deadlineMs.
private class CappedStream(input: InputStream, private val maxBytes: Long, private val host: String,
  private val deadlineMs: Long) : FilterInputStream(input)
{
  // Bytes read so far.
  private var count = 0L


  override fun read(): Int
  {
    val value = super.read()

    if (value >= 0) check(1)
    return value
  }


  override fun read(buffer: ByteArray, offset: Int, length: Int): Int
  {
    val read = super.read(buffer, offset, length)

    if (read > 0) check(read)
    return read
  }


  // Adds read bytes to the count and fails if the cap is passed.
  private fun check(read: Int)
  {
    count += read
    if (count > maxBytes) throw java.io.IOException("Response from " + host + " larger than " + maxBytes + " bytes")
    if (System.currentTimeMillis() > deadlineMs) throw java.io.IOException("Response from " + host + " too slow")
  }
}
