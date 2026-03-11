package com.duplicateremover.app

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.duplicateremover.app.databinding.ActivityOthersScanBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class OthersScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOthersScanBinding
    private lateinit var duplicateAdapter: DuplicateGroupAdapter
    private val allFiles = mutableListOf<MediaFile>()
    private val duplicateGroups = mutableListOf<List<MediaFile>>()
    private var pendingFilesToDelete: List<MediaFile>? = null

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                startScanning()
            } else {
                Toast.makeText(this, "All files access is required to scan more files", Toast.LENGTH_SHORT).show()
                startScanning()
            }
        } else {
            startScanning()
        }
    }

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingFilesToDelete?.let { finalizeDeletion(it) }
        } else {
            pendingFilesToDelete = null
            Toast.makeText(this, "Delete cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOthersScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        setupAnimation()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showAllFilesAccessDialog()
                return
            }
        }

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missingPermissions = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startScanning()
        } else {
            requestPermissions(missingPermissions.toTypedArray(), 100)
        }
    }

    private fun showAllFilesAccessDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        AlertDialog.Builder(this)
            .setTitle("Allow All Files Access")
            .setMessage("To scan all other files on your phone (Android 11+), please allow All Files Access.")
            .setPositiveButton("Allow") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            }
            .setNegativeButton("Skip") { _, _ ->
                startScanning()
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            startScanning()
        } else {
            Toast.makeText(this, "Permission required to scan files", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupToolbar() {
        binding.backButton.setOnClickListener {
            onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        duplicateAdapter = DuplicateGroupAdapter { _ ->
            updateBottomBarStats()
        }
        binding.resultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@OthersScanActivity)
            adapter = duplicateAdapter
        }
    }

    private fun setupClickListeners() {
        binding.deleteButton.setOnClickListener {
            val selectedFiles = duplicateAdapter.getSelectedFiles()
            if (selectedFiles.isNotEmpty()) {
                showDeleteConfirmation(selectedFiles)
            }
        }
    }

    private fun setupAnimation() {
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.others}")
        binding.scanAnimation.setVideoURI(videoUri)
        binding.scanAnimation.setOnPreparedListener { mp ->
            mp.isLooping = true
            binding.scanAnimation.start()
        }
    }

    private fun startScanning() {
        allFiles.clear()
        duplicateGroups.clear()
        
        binding.scanningContainer.visibility = View.VISIBLE
        binding.resultsRecyclerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        binding.scanProgressCircle.setProgress(0)
        binding.fileCountText.text = "0 Files Scanned"
        binding.statusText.text = "Searching for duplicates..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                scanOthers()
                findDuplicates()

                withContext(Dispatchers.Main) {
                    displayResults()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OthersScanActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private suspend fun scanOthers() {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        // Exclude common media types that have their own screens
        val selection = "(${MediaStore.Files.FileColumns.MIME_TYPE} IS NULL OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} NOT IN (" +
                "'image/jpeg', 'image/png', 'image/gif', 'image/webp', " +
                "'video/mp4', 'video/3gp', 'video/mkv', 'video/webm', " +
                "'audio/mpeg', 'audio/wav', 'audio/ogg', 'audio/aac', 'audio/m4a', " +
                "'application/pdf', 'application/msword', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', " +
                "'application/vnd.ms-excel', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', " +
                "'text/plain', 'application/vnd.ms-powerpoint', 'application/vnd.openxmlformats-officedocument.presentationml.presentation'" +
                ")) AND " +
                "(${MediaStore.Files.FileColumns.MIME_TYPE} IS NULL OR ${MediaStore.Files.FileColumns.MIME_TYPE} != 'application/vnd.android.package-archive') AND " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) NOT LIKE '%.apk' AND " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) NOT LIKE '%.apks' AND " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) NOT LIKE '%.xapk'"

        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            
            val total = cursor.count
            var current = 0
            
            if (total == 0) {
                withContext(Dispatchers.Main) {
                    binding.fileCountText.text = "No Other Files Found"
                }
                return
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(pathCol)
                val size = cursor.getLong(sizeCol)
                val name = cursor.getString(nameCol) ?: ""
                
                if (size > 0 && File(path).exists()) {
                    allFiles.add(MediaFile(id, path, name, size, 0, ""))
                }
                
                current++
                val progress = (current * 100) / total
                withContext(Dispatchers.Main) {
                    binding.scanProgressCircle.setProgress(progress)
                    binding.fileCountText.text = "$current Files Scanned"
                }
                if (current % 20 == 0) delay(1)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            scanManualNonMediaFiles()
        }
    }

    private suspend fun scanManualNonMediaFiles() {
        val excludeExtensions = setOf(
            "jpg", "jpeg", "png", "gif", "webp",
            "mp4", "3gp", "mkv", "webm",
            "mp3", "wav", "ogg", "aac", "m4a",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt",
            "apk", "apks", "xapk"
        )

        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        )

        val seenPaths = allFiles.map { it.path }.toHashSet()
        var scanned = allFiles.size
        val maxFilesToAdd = 5000

        for (root in roots) {
            if (allFiles.size >= maxFilesToAdd) break
            if (!root.exists() || !root.isDirectory) continue
            scanned = scanFolderRecursive(root, 0, 6, excludeExtensions, seenPaths, scanned, maxFilesToAdd)
        }
    }

    private suspend fun scanFolderRecursive(
        dir: File,
        depth: Int,
        maxDepth: Int,
        excludeExtensions: Set<String>,
        seenPaths: MutableSet<String>,
        scannedCount: Int,
        maxFilesToAdd: Int
    ): Int {
        if (depth > maxDepth) return scannedCount
        if (!dir.exists() || !dir.isDirectory) return scannedCount

        var scanned = scannedCount
        val children = dir.listFiles() ?: return scanned

        for (child in children) {
            if (allFiles.size >= maxFilesToAdd) break

            if (child.isDirectory) {
                scanned = scanFolderRecursive(child, depth + 1, maxDepth, excludeExtensions, seenPaths, scanned, maxFilesToAdd)
                continue
            }

            if (!child.isFile) continue
            if (child.length() <= 0L) continue

            val ext = child.extension.lowercase()
            if (ext.isNotEmpty() && excludeExtensions.contains(ext)) continue

            val path = child.absolutePath
            if (seenPaths.contains(path)) continue
            seenPaths.add(path)

            allFiles.add(
                MediaFile(
                    id = -1L,
                    path = path,
                    name = child.name,
                    size = child.length(),
                    dateAdded = child.lastModified() / 1000,
                    mimeType = ""
                )
            )

            scanned++
            if (scanned % 50 == 0) {
                withContext(Dispatchers.Main) {
                    binding.fileCountText.text = "$scanned Files Scanned"
                }
                delay(1)
            }
        }

        return scanned
    }

    private suspend fun findDuplicates() {
        withContext(Dispatchers.Main) {
            binding.statusText.text = "Comparing files..."
        }

        val sizeGroups = allFiles.groupBy { it.size }.filter { it.value.size > 1 }
        duplicateGroups.clear()

        sizeGroups.forEach { (_, files) ->
            files.forEach { file ->
                if (file.hash.isEmpty()) {
                    file.hash = calculateFileHash(file.path)
                }
            }

            val hashGroups = files.groupBy { it.hash }.filter { it.value.size > 1 }
            hashGroups.forEach { (_, duplicates) ->
                val sorted = duplicates.sortedBy { it.dateAdded }
                sorted.forEachIndexed { index, mediaFile ->
                    mediaFile.isSelected = index > 0
                }
                duplicateGroups.add(sorted)
            }
        }
    }

    private fun calculateFileHash(filePath: String): String {
        return try {
            val file = File(filePath)
            if (!file.exists()) return ""
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                val maxRead = 1024 * 1024
                while (input.read(buffer).also { bytesRead = it } != -1 && totalRead < maxRead) {
                    digest.update(buffer, 0, bytesRead)
                    totalRead += bytesRead
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "" }
    }

    private fun displayResults() {
        binding.scanningContainer.visibility = View.GONE
        
        if (duplicateGroups.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.resultsRecyclerView.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.resultsRecyclerView.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.VISIBLE
            
            duplicateAdapter.submitList(duplicateGroups.toList())
            updateBottomBarStats()
        }
    }

    private fun updateBottomBarStats() {
        val selectedFiles = duplicateAdapter.getSelectedFiles()
        val totalSize = selectedFiles.sumOf { it.size }
        
        binding.selectedStatsText.text = "${selectedFiles.size} files selected"
        binding.totalSizeText.text = "Total: ${StorageUtils.formatSize(totalSize)}"
    }

    private fun showDeleteConfirmation(files: List<MediaFile>) {
        val totalSize = files.sumOf { it.size }
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete, null)
        val dialog = AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setView(dialogView)
            .create()

        dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Delete Duplicates?"
        dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text = 
            "Delete ${files.size} duplicate files (${StorageUtils.formatSize(totalSize)})"
        dialogView.findViewById<android.widget.TextView>(R.id.dialogSubMessage).text = 
            "Original files will be kept safe."

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDelete).setOnClickListener {
            dialog.dismiss()
            deleteFiles(files)
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun deleteFiles(files: List<MediaFile>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    showDeleteProgress()
                }

                val baseUri = MediaStore.Files.getContentUri("external")
                val total = files.size

                val contentFiles = files.filter { it.path.startsWith("content://") || it.id > 0 }
                val rawPathFiles = files.filter { !it.path.startsWith("content://") && it.id <= 0 }

                // Delete raw-path files directly (these can come from manual scan)
                if (rawPathFiles.isNotEmpty()) {
                    for ((index, file) in rawPathFiles.withIndex()) {
                        try {
                            File(file.path).delete()

                            val progress = ((index + 1) * 100) / total
                            withContext(Dispatchers.Main) {
                                binding.scanProgressCircle.setProgress(progress)
                                binding.statusText.text = "Deleting duplicates..."
                                binding.fileCountText.text = "Deleted ${index + 1}/$total"
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                // For MediaStore-backed items on Android 11+, match other screens by using createDeleteRequest
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && contentFiles.isNotEmpty()) {
                    val uris = contentFiles.map {
                        if (it.path.startsWith("content://")) {
                            Uri.parse(it.path)
                        } else {
                            ContentUris.withAppendedId(baseUri, it.id)
                        }
                    }
                    val editPendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                    pendingFilesToDelete = contentFiles
                    val intentSenderRequest = IntentSenderRequest.Builder(editPendingIntent.intentSender).build()
                    withContext(Dispatchers.Main) {
                        deleteLauncher.launch(intentSenderRequest)
                    }

                    // If we deleted raw files already, update UI groups for them right away
                    if (rawPathFiles.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            finalizeDeletion(rawPathFiles)
                        }
                    }
                    return@launch
                }
                
                for ((index, file) in files.withIndex()) {
                    try {
                        if (file.path.startsWith("content://")) {
                            contentResolver.delete(Uri.parse(file.path), null, null)
                        } else if (file.id > 0) {
                            val itemUri = ContentUris.withAppendedId(baseUri, file.id)
                            contentResolver.delete(itemUri, null, null)
                        } else {
                            File(file.path).delete()
                        }
                        
                        val progress = ((index + 1) * 100) / total
                        withContext(Dispatchers.Main) {
                            binding.scanProgressCircle.setProgress(progress)
                            binding.statusText.text = "Deleting duplicates..."
                            binding.fileCountText.text = "Deleted ${index + 1}/$total"
                        }
                    } catch (e: Exception) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                            pendingFilesToDelete = files.drop(index)
                            val intentSenderRequest = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                            withContext(Dispatchers.Main) {
                                deleteLauncher.launch(intentSenderRequest)
                            }
                            return@launch
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    finalizeDeletion(files)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.scanningContainer.visibility = View.GONE
                    binding.resultsRecyclerView.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.VISIBLE
                    pendingFilesToDelete = null
                    Toast.makeText(this@OthersScanActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDeleteProgress() {
        binding.scanningContainer.visibility = View.VISIBLE
        binding.resultsRecyclerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        
        binding.scanTitleText.text = "Deleting Files"
        binding.scanProgressCircle.setProgress(0)
        binding.fileCountText.text = "Starting deletion..."
        binding.statusText.text = "Removing duplicates..."
        
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.trash_bin}")
        binding.scanAnimation.setVideoURI(videoUri)
        binding.scanAnimation.start()
    }

    private fun finalizeDeletion(deletedFiles: List<MediaFile>) {
        val deletedIds = deletedFiles.mapNotNull { if (it.id > 0) it.id else null }.toSet()
        val deletedPaths = deletedFiles.mapNotNull { if (it.id <= 0) it.path else null }.toSet()

        val updatedGroups = duplicateGroups.map { group ->
            group.filter { file ->
                when {
                    file.id > 0 -> file.id !in deletedIds
                    else -> file.path !in deletedPaths
                }
            }
        }.filter { it.size > 1 }

        duplicateGroups.clear()
        duplicateGroups.addAll(updatedGroups)
        
        binding.scanningContainer.visibility = View.GONE
        pendingFilesToDelete = null
        
        if (duplicateGroups.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.resultsRecyclerView.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.resultsRecyclerView.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.VISIBLE
            duplicateAdapter.submitList(duplicateGroups.toList())
            updateBottomBarStats()
        }
        
        Toast.makeText(this, "Deleted ${deletedFiles.size} files", Toast.LENGTH_SHORT).show()
    }
}
