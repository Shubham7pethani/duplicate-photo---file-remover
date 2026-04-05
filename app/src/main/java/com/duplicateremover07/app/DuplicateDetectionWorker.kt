package com.duplicateremover07.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DuplicateDetectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL_ID = "duplicate_alerts"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        val totalDuplicates = scanForDuplicates()

        if (totalDuplicates > 0) {
            showNotification(totalDuplicates)
        }

        return Result.success()
    }

    private data class SimpleFile(val path: String, val size: Long)

    private fun scanForDuplicates(): Int {
        var count = 0

        // 1. Photos
        count += findDuplicatesForUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)

        // 2. Videos
        count += findDuplicatesForUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

        // 3. Audio
        count += findDuplicatesForUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

        // 4. Docs/Others (via MediaStore.Files)
        val filesUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val otherSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} AND " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} AND " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}"
        count += findDuplicatesForUri(filesUri, otherSelection)

        // 5. Contacts
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val contactIdsPerNumber = mutableMapOf<String, MutableSet<Long>>()
            applicationContext.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val numCol = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val number = (cursor.getString(numCol) ?: "").replace("[^0-9+]".toRegex(), "")
                    if (number.length >= 10) { // Safety check for valid numbers
                        contactIdsPerNumber.getOrPut(number) { mutableSetOf() }.add(id)
                    }
                }
            }
            count += contactIdsPerNumber.values.sumOf { (it.size - 1).coerceAtLeast(0) }
        }

        return count
    }

    private fun findDuplicatesForUri(uri: android.net.Uri, selection: String? = null): Int {
        val files = mutableListOf<SimpleFile>()
        applicationContext.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA),
            selection, null, null
        )?.use { cursor ->
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val size = cursor.getLong(sizeCol)
                val path = cursor.getString(pathCol) ?: ""
                val name = if (path.isNotEmpty()) java.io.File(path).name else ""
                val mimeType = (if (path.isNotEmpty()) {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    when (ext) {
                        "jpg", "jpeg", "png", "gif", "webp" -> "image/$ext"
                        "mp4", "mkv", "3gp", "webm" -> "video/$ext"
                        "mp3", "wav", "ogg", "aac", "m4a" -> "audio/$ext"
                        "pdf" -> "application/pdf"
                        "apk" -> "application/vnd.android.package-archive"
                        else -> ""
                    }
                } else "").lowercase()

                // Only include specific user-friendly categories
                val isImage = mimeType.startsWith("image/")
                val isVideo = mimeType.startsWith("video/")
                val isAudio = mimeType.startsWith("audio/")
                val isDoc = mimeType == "application/pdf" || name.endsWith(".pdf", true) || 
                           name.endsWith(".doc", true) || name.endsWith(".docx", true) || 
                           name.endsWith(".xls", true) || name.endsWith(".xlsx", true)
                val isApk = mimeType == "application/vnd.android.package-archive" || name.endsWith(".apk", true)

                // Strictly exclude system/hidden folders and non-media files
                val isSystemFile = path.contains("/Android/", true) || 
                                 path.contains("/PSP/", true) ||
                                 path.contains("/SYSTEM/", true) ||
                                 path.contains("/. ", true) ||
                                 name.startsWith(".")

                if (size > 0 && path.isNotEmpty() && java.io.File(path).exists() && 
                    !isSystemFile && (isImage || isVideo || isAudio || isDoc || isApk)) {
                    files.add(SimpleFile(path, size))
                }
            }
        }

        var dupesCount = 0
        // Exact same grouping logic as MainActivity
        val sizeGroups = files.groupBy { it.size }.filter { it.value.size > 1 }
        sizeGroups.forEach { (_, group) ->
            // Use same StorageUtils.calculateFileHash as MainActivity
            val hashGroups = group.groupBy { StorageUtils.calculateFileHash(it.path) }
                .filter { it.key.isNotEmpty() && it.value.size > 1 }
            hashGroups.forEach { (_, duplicates) ->
                dupesCount += (duplicates.size - 1)
            }
        }
        return dupesCount
    }

    private fun showNotification(count: Int) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Duplicate Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when duplicates are detected"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val bigText = "We found $count duplicates on your phone. Tap to review and clean up to save space."

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$count duplicates found")
            .setContentText("Tap to clean and free up space")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
