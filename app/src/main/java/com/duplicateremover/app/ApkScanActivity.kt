package com.duplicateremover.app

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.duplicateremover.app.databinding.ActivityApkScanBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class ApkScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApkScanBinding
    private lateinit var duplicateAdapter: DuplicateGroupAdapter
    private val allApks = mutableListOf<MediaFile>()
    private val duplicateGroups = mutableListOf<List<MediaFile>>()
    private var pendingFilesToDelete: List<MediaFile>? = null

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
        binding = ActivityApkScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        setupAnimation()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            startScanning()
        } else {
            Toast.makeText(this, "Permission required to scan APKs", Toast.LENGTH_SHORT).show()
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
            layoutManager = LinearLayoutManager(this@ApkScanActivity)
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
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.mobile_apps}")
        binding.scanAnimation.setVideoURI(videoUri)
        binding.scanAnimation.setOnPreparedListener { mp ->
            mp.isLooping = true
            binding.scanAnimation.start()
        }
    }

    private fun startScanning() {
        allApks.clear()
        duplicateGroups.clear()
        
        binding.scanningContainer.visibility = View.VISIBLE
        binding.resultsRecyclerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        binding.scanProgressCircle.setProgress(0)
        binding.fileCountText.text = "0 APKs Scanned"
        binding.statusText.text = "Searching for duplicates..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                scanApks()
                findDuplicates()

                withContext(Dispatchers.Main) {
                    displayResults()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ApkScanActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private suspend fun scanApks() {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val selection = "(LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE ? OR ${MediaStore.MediaColumns.MIME_TYPE} = ?)"
        val selectionArgs = arrayOf("%.apk", "application/vnd.android.package-archive")

        val sources = listOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )

        val seenUris = HashSet<String>()
        var scanned = 0
        var expectedTotal = 0

        // Count rows first (best-effort) for progress calculation
        sources.forEach { uri ->
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, selectionArgs, null)
                ?.use { expectedTotal += it.count }
        }

        if (expectedTotal == 0) {
            scanManualFolders()
            return
        }

        sources.forEach { baseUri ->
            contentResolver.query(baseUri, projection, selection, selectionArgs, null)?.use { cursor ->
                scanned = readApkCursorIntoList(cursor, baseUri, scanned, expectedTotal, seenUris)
            }
        }

        // Also do a quick manual check of Downloads folder just in case MediaStore missed it
        scanManualFolders(seenUris, scanned, expectedTotal)
    }

    private suspend fun scanManualFolders(
        seenUris: MutableSet<String> = HashSet(),
        alreadyScanned: Int = 0,
        expectedTotal: Int = 0
    ) {
        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir.exists() && downloadDir.isDirectory) {
            val files = downloadDir.listFiles()
            var scanned = alreadyScanned

            files?.forEach { file ->
                if (!file.isFile) return@forEach
                if (!file.name.lowercase().endsWith(".apk")) return@forEach

                // Try to resolve the file to a MediaStore Downloads row so we can build a real content Uri
                val resolvedUri = resolveDownloadUriForFile(file)
                val uriString = resolvedUri?.toString()
                if (uriString == null || seenUris.contains(uriString)) return@forEach

                seenUris.add(uriString)
                allApks.add(
                    MediaFile(
                        id = ContentUris.parseId(resolvedUri),
                        path = uriString,
                        name = file.name,
                        size = file.length(),
                        dateAdded = file.lastModified() / 1000,
                        mimeType = "application/vnd.android.package-archive"
                    )
                )

                scanned++
                if (expectedTotal > 0) {
                    val progress = (scanned * 100) / expectedTotal
                    withContext(Dispatchers.Main) {
                        binding.scanProgressCircle.setProgress(progress)
                        binding.fileCountText.text = "$scanned APKs Scanned"
                    }
                }
            }
        }
    }

    private suspend fun readApkCursorIntoList(
        cursor: Cursor,
        baseUri: Uri,
        startCount: Int,
        expectedTotal: Int,
        seenUris: MutableSet<String>
    ): Int {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

        var current = startCount
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val size = cursor.getLong(sizeCol)
            val name = cursor.getString(nameCol) ?: ""
            val dateAdded = cursor.getLong(dateCol)
            val mime = cursor.getString(mimeCol) ?: ""

            if (size <= 0L) continue

            val itemUri = ContentUris.withAppendedId(baseUri, id)
            val uriString = itemUri.toString()
            if (seenUris.contains(uriString)) continue
            seenUris.add(uriString)

            allApks.add(MediaFile(id, uriString, name, size, dateAdded, mime))

            current++
            val progress = if (expectedTotal > 0) (current * 100) / expectedTotal else 0
            withContext(Dispatchers.Main) {
                binding.scanProgressCircle.setProgress(progress)
                binding.fileCountText.text = "$current APKs Scanned"
            }
            if (current % 10 == 0) delay(5)
        }
        return current
    }

    private fun resolveDownloadUriForFile(file: File): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.SIZE} = ?"
            val args = arrayOf(file.name, file.length().toString())
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(idCol)
                    return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun findDuplicates() {
        withContext(Dispatchers.Main) {
            binding.statusText.text = "Comparing APKs..."
        }

        val sizeGroups = allApks.groupBy { it.size }.filter { it.value.size > 1 }
        duplicateGroups.clear()

        sizeGroups.forEach { (_, files) ->
            files.forEach { file ->
                if (file.hash.isEmpty()) {
                    file.hash = calculateUriHash(Uri.parse(file.path))
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

    private fun calculateUriHash(uri: Uri): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val input: InputStream = contentResolver.openInputStream(uri) ?: return ""
            input.use { stream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                val maxRead = 1024 * 1024
                while (stream.read(buffer).also { bytesRead = it } != -1 && totalRead < maxRead) {
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
        
        binding.selectedStatsText.text = "${selectedFiles.size} APKs selected"
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
            "Delete ${files.size} duplicate APKs (${StorageUtils.formatSize(totalSize)})"
        dialogView.findViewById<android.widget.TextView>(R.id.dialogSubMessage).text = 
            "Original APKs will be kept safe."

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

                val total = files.size
                
                for ((index, file) in files.withIndex()) {
                    try {
                        val itemUri = Uri.parse(file.path)
                        contentResolver.delete(itemUri, null, null)
                        
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
                    Toast.makeText(this@ApkScanActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDeleteProgress() {
        binding.scanningContainer.visibility = View.VISIBLE
        binding.resultsRecyclerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        
        binding.scanTitleText.text = "Deleting APKs"
        binding.scanProgressCircle.setProgress(0)
        binding.fileCountText.text = "Starting deletion..."
        binding.statusText.text = "Removing duplicates..."
        
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.trash_bin}")
        binding.scanAnimation.setVideoURI(videoUri)
        binding.scanAnimation.start()
    }

    private fun finalizeDeletion(deletedFiles: List<MediaFile>) {
        val deletedIds = deletedFiles.map { it.id }.toSet()
        val updatedGroups = duplicateGroups.map { group ->
            group.filter { it.id !in deletedIds }
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
        
        Toast.makeText(this, "Deleted ${deletedFiles.size} APKs", Toast.LENGTH_SHORT).show()
    }
}
