// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// atomicstore.kt
// Reads and writes one small text file crash-safely: writes go to a temporary file that is synced and then
// renamed over the old one, so a power loss leaves either the old or the new contents, never a mix.
package com.falcorr.lactulose.io

import java.io.File
import java.io.FileOutputStream


// Result of a store operation: text holds file contents on a successful read, error says what went wrong.
class StoreResult(val ok: Boolean, val text: String = "", val error: String = "")


class AtomicStore(private val file: File)
{
  // Temporary sibling used while writing.
  private val temp = File(file.path + ".tmp")


  // Returns the file's contents, or an error result if it doesn't exist or can't be read.
  fun read(): StoreResult
  {
    if (!file.exists()) return StoreResult(false, error = "No cached file at " + file.path)
    return try
    {
      StoreResult(true, file.readText())
    }
    catch (e: Exception)
    {
      StoreResult(false, error = "Reading " + file.path + " failed: " + e.message)
    }
  }


  // Replaces the file's contents with text, returning whether it succeeded and why not.
  fun write(text: String): StoreResult
  {
    return try
    {
      FileOutputStream(temp).use {
        it.write(text.toByteArray())
        it.fd.sync()
      }
      if (!temp.renameTo(file)) return StoreResult(false, error = "Renaming " + temp.path + " to " + file.path + " failed")
      StoreResult(true)
    }
    catch (e: Exception)
    {
      temp.delete()
      StoreResult(false, error = "Writing " + file.path + " failed: " + e.message)
    }
  }
}
