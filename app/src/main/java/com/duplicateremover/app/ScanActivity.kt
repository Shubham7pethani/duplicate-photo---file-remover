package com.duplicateremover.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.duplicateremover.app.databinding.ActivityScanBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private var category: String? = null
    private val scannedFiles = mutableListOf<MediaFile>()
    private val duplicateGroups = mutableListOf<List<MediaFile>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        category = intent.getStringExtra("CATEGORY")
        binding.categoryTitle.text = "Scanning $category"

        setupToolbar()
        setupAnimation()
        startScanning()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupAnimation() {
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.photo}")
        binding.scanAnimation.setVideoURI(videoUri)
        binding.scanAnimation.setOnPreparedListener { mp ->
            mp.isLooping = true
            binding.scanAnimation.start()
        }
    }

    private fun startScanning() {
        lifecycleScope.launch(Dispatchers.IO) {
            val files = when (category) {
                "Photos" -> scanPhotos()
                "Videos" -> scanVideos()
                "Audios" -> scanAudios()
                "Docs" -> scanDocs()
                "All Files" -> scanAll()
                else -> emptyList()
            }

            findDuplicates(files)

            withContext(Dispatchers.Main) {
                binding.statusText.text = "Scan Complete!"
                delay(1000)
                // Return results to MainActivity or show ResultActivity
                val intent = Intent(this@ScanActivity, MainActivity::class.java).apply {
                    putExtra("SCAN_COMPLETED", true)
                    putExtra("CATEGORY", category)
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private suspend fun scanPhotos(): List<MediaFile> {
        val photos = mutableListOf<MediaFile>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE
        )

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            
            val total = cursor.count
            var current = 0
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(pathCol)
                val size = cursor.getLong(sizeCol)
                photos.add(MediaFile(id, path, "", size, 0, ""))
                
                current++
                val progress = (current * 100) / total
                withContext(Dispatchers.Main) {
                    binding.scanProgressCircle.setProgress(progress)
                    binding.fileCountText.text = "$current Photos Scanned"
                }
                delay(10) // Small delay for animation feel
            }
        }
        return photos
    }

    // Placeholder for other scan methods
    private fun scanVideos(): List<MediaFile> = emptyList()
    private fun scanAudios(): List<MediaFile> = emptyList()
    private fun scanDocs(): List<MediaFile> = emptyList()
    private fun scanAll(): List<MediaFile> = emptyList()

    private suspend fun findDuplicates(files: List<MediaFile>) {
        val sizeGroups = files.groupBy { it.size }.filter { it.value.size > 1 }
        var processed = 0
        val total = sizeGroups.values.sumOf { it.size }

        sizeGroups.forEach { (_, group) ->
            group.forEach { file ->
                file.hash = calculateFileHash(file.path)
                processed++
                // Update secondary progress if needed
            }
        }
    }

    private fun calculateFileHash(path: String): String {
        return try {
            val file = File(path)
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { it.readBytes() } // Simplified for now
            ""
        } catch (e: Exception) { "" }
    }
}
