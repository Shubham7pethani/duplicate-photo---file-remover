package com.duplicateremover.app

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
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
import com.duplicateremover.app.databinding.ActivityAllFilesScanBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class AllFilesScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllFilesScanBinding
    private lateinit var duplicateAdapter: DuplicateGroupAdapter
    private val allFiles = mutableListOf<MediaFile>()
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
        binding = ActivityAllFilesScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure UI starts in scanning state
        binding.scanningContainer.visibility = View.VISIBLE
        binding.resultsRecyclerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.emptyView.visibility = View.GONE

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
            layoutManager = LinearLayoutManager(this@AllFilesScanActivity)
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
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.allfiles}")
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
                scanAllFiles()
                findDuplicates()

                withContext(Dispatchers.Main) {
                    displayResults()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AllFilesScanActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private suspend fun scanAllFiles() {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            
            val total = cursor.count
            var current = 0
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(pathCol)
                val size = cursor.getLong(sizeCol)
                
                if (size > 0) {
                    allFiles.add(MediaFile(id, path, "", size, 0, ""))
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
    }

    private suspend fun findDuplicates() {
        withContext(Dispatchers.Main) {
            binding.statusText.text = "Comparing all files..."
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
                val maxRead = 1024 * 1024 // Scan 1MB for speed
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
        binding.totalSizeText.text = "Total: ${formatSize(totalSize)}"
    }

    private fun formatSize(size: Long): String {
        return when {
            size >= 1024 * 1024 * 1024 -> "%.2f GB".format(size.toDouble() / (1024 * 1024 * 1024))
            size >= 1024 * 1024 -> "%.2f MB".format(size.toDouble() / (1024 * 1024))
            size >= 1024 -> "%.2f KB".format(size.toDouble() / 1024)
            else -> "$size B"
        }
    }

    private fun showDeleteConfirmation(files: List<MediaFile>) {
        val totalSize = files.sumOf { it.size }
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete, null)
        val dialog = AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setView(dialogView)
            .create()

        dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Delete Duplicates?"
        dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text = 
            "Delete ${files.size} duplicate files (${formatSize(totalSize)})"
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
                
                for ((index, file) in files.withIndex()) {
                    try {
                        val itemUri = ContentUris.withAppendedId(baseUri, file.id)
                        contentResolver.delete(itemUri, null, null)
                        
                        val progress = ((index + 1) * 100) / total
                        withContext(Dispatchers.Main) {
                            binding.scanProgressCircle.setProgress(progress)
                            binding.statusText.text = "Deleting duplicates..."
                            binding.fileCountText.text = "Deleted ${index + 1}/$total"
                        }
                    } catch (e: Exception) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                            pendingFilesToDelete = files.drop(index) // Save remaining files
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
                    Toast.makeText(this@AllFilesScanActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
        val deletedIds = deletedFiles.map { it.id }.toSet()
        
        // Remove deleted files from the groups
        val updatedGroups = duplicateGroups.map { group ->
            group.filter { it.id !in deletedIds }
        }.filter { it.size > 1 } // Only keep groups that still have duplicates

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
