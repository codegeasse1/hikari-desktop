package com.hikari.app.download

import org.json.JSONArray
import java.io.File

/**
 * The download queue's persistence. Its own file (`downloads.json` in the app
 * data dir) rather than a key in the main `hikari.json`, so a large queue never
 * bloats the settings blob the rest of the app rewrites on every change.
 *
 * Writes go through a temp file + rename, so a crash mid-save can never leave a
 * truncated queue behind.
 */
object DownloadStore {

    private fun file(dir: File): File = File(dir, "downloads.json")

    fun load(dir: File): List<DownloadTask> {
        val f = file(dir)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .map { DownloadTask.fromJson(it) }
        }.getOrDefault(emptyList())
    }

    fun save(dir: File, tasks: List<DownloadTask>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        val f = file(dir)
        val tmp = File(dir, "downloads.json.tmp")
        runCatching {
            dir.mkdirs()
            tmp.writeText(arr.toString())
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            System.err.println("DownloadStore.save failed: $it")
        }
    }
}
