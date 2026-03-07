package com.duplicateremover.app

import android.app.RecoverableSecurityException
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
import com.duplicateremover.app.databinding.ActivityVideoScanBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class VideoScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoScanBinding
    private lateinit var duplicateAdapter: DuplicateGroupAdapter
    private val allVideos = mutableListOf<MediaFile>()
    private val duplicateGroups = mutableListOf<List<MediaFile>>()
    private var pendingFilesToDelete: List<MediaFile>? = null

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingFilesToDelete?.let { finalizeDeletion(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        setupAnimation()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
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
            Toast.makeText(this, "Permission required to scan videos", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        duplicateAdapter = DuplicateGroupAdapter { _ ->
            updateBottomBarStats()
        }
        binding.resultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@VideoScanActivity)
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
        // Using video.mp4 from res/raw as requested
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.video}")
        binding.scanAnimation.setVideoURI(videoUri)
        binding.scanAnimation.setOnPreparedListener { mp ->
            mp.isLooping = true
            binding.scanAnimation.start()
        }
    }

    private fun startScanning() {
        allVideos.clear()
        duplicateGroups.clear()
        
        binding.scanningContainer.visibility = View.VISIBLE
        binding.resultsRecyclerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        binding.scanProgressCircle.setProgress(0)
        binding.fileCountText.text = "0 Videos Scanned"
        binding.statusText.text = "Searching for duplicates..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                scanVideos()
                findDuplicates()

                withContext(Dispatchers.Main) {
                    displayResults()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VideoScanActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private suspend fun scanVideos() {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE
        )

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            
            val total = cursor.count
            var current = 0
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(pathCol)
                val size = cursor.getLong(sizeCol)
                
                allVideos.add(MediaFile(id, path, "", size, 0, ""))
                
                current++
                val progress = (current * 100) / total
                withContext(Dispatchers.Main) {
                    binding.scanProgressCircle.setProgress(progress)
                    binding.fileCountText.text = "$current Videos Scanned"
                }
                if (current % 5 == 0) delay(1)
            }
        }
    }

    private suspend fun findDuplicates() {
        withContext(Dispatchers.Main) {
            binding.statusText.text = "Comparing videos..."
        }

        val sizeGroups = allVideos.groupBy { it.size }.filter { it.value.size > 1 }
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
        
        binding.selectedStatsText.text = "${selectedFiles.size} videos selected"
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
            "Delete ${files.size} duplicate videos (${formatSize(totalSize)})?\nOriginal videos will be kept safe."

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = files.map { 
                    Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.id.toString())
                }
                val editPendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                pendingFilesToDelete = files
                val intentSenderRequest = IntentSenderRequest.Builder(editPendingIntent.intentSender).build()
                withContext(Dispatchers.Main) {
                    deleteLauncher.launch(intentSenderRequest)
                }
            } else {
                var deleted = 0
                files.forEach { file ->
                    try {
                        val f = File(file.path)
                        if (f.exists() && f.delete()) {
                            deleted++
                            contentResolver.delete(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                "${MediaStore.Video.Media._ID} = ?",
                                arrayOf(file.id.toString())
                            )
                        }
                    } catch (e: Exception) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                            pendingFilesToDelete = listOf(file)
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
            }
        }
    }

    private fun finalizeDeletion(files: List<MediaFile>) {
        Toast.makeText(this, "Deleted ${files.size} videos", Toast.LENGTH_SHORT).show()
        pendingFilesToDelete = null
        startScanning()
    }
}
