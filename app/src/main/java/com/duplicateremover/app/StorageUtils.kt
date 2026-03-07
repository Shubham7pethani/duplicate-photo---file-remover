package com.duplicateremover.app

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object StorageUtils {

    fun getStorageStats(context: Context): StorageStats {
        val dataStat = StatFs(Environment.getDataDirectory().path)
        val rawTotalBytes = dataStat.blockCountLong * dataStat.blockSizeLong
        val availableBytes = dataStat.availableBlocksLong * dataStat.blockSizeLong

        // Marketed size (e.g., 128GB)
        val marketedTotalBytes = getMarketedStorageSize(rawTotalBytes)

        // The system settings calculation is essentially: Marketed Total - OS reported available
        // This includes reclaimable cache as "used", which matches the 72.2GB screenshot.
        val usedBytes = (marketedTotalBytes - availableBytes).coerceAtLeast(0L)

        return StorageStats(
            totalBytes = marketedTotalBytes,
            usedBytes = usedBytes,
            availableBytes = availableBytes
        )
    }

    fun getMarketedStorageSize(totalBytes: Long): Long {
        // Settings/marketing uses decimal GB (1 GB = 1,000,000,000 bytes)
        // If we use 1024-based GiB here, we end up showing 137.4 GB for "128GB" devices.
        val gb = totalBytes / (1000.0 * 1000.0 * 1000.0)
        return when {
            gb <= 8 -> 8L * 1000 * 1000 * 1000
            gb <= 16 -> 16L * 1000 * 1000 * 1000
            gb <= 32 -> 32L * 1000 * 1000 * 1000
            gb <= 64 -> 64L * 1000 * 1000 * 1000
            gb <= 128 -> 128L * 1000 * 1000 * 1000
            gb <= 256 -> 256L * 1000 * 1000 * 1000
            gb <= 512 -> 512L * 1000 * 1000 * 1000
            else -> 128L * 1000 * 1000 * 1000
        }
    }

    fun formatSize(size: Long): String {
        val doubleSize = size.toDouble()
        return when {
            size >= 1000L * 1000 * 1000 -> "%.1f GB".format(doubleSize / (1000 * 1000 * 1000))
            size >= 1000L * 1000 -> "%.1f MB".format(doubleSize / (1000 * 1000))
            size >= 1000L -> "%.1f KB".format(doubleSize / 1000)
            else -> "$size B"
        }
    }

    fun calculateFileHash(filePath: String): String {
        return try {
            val file = File(filePath)
            if (!file.exists()) return ""
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                val maxRead = 1024 * 1024 // 1MB for speed
                while (input.read(buffer).also { bytesRead = it } != -1 && totalRead < maxRead) {
                    digest.update(buffer, 0, bytesRead)
                    totalRead += bytesRead
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
    fun getRamStats(context: Context): RamStats {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        
        val totalRam = mi.totalMem
        val availRam = mi.availMem
        val usedRam = totalRam - availRam
        
        return RamStats(totalRam, usedRam, availRam)
    }
}

data class StorageStats(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long
) {
    val usedPercentage: Int
        get() = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
}

data class RamStats(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long
) {
    val usedPercentage: Int
        get() = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
}
