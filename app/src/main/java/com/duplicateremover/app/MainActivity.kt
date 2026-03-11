package com.duplicateremover.app

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.duplicateremover.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class MediaFile(
    val id: Long,
    val path: String,
    val name: String,
    val size: Long,
    val dateAdded: Long,
    val mimeType: String,
    var hash: String = "",
    var isSelected: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private val storageLogTag = "StorageDebug"

    private lateinit var binding: ActivityMainBinding
    private lateinit var duplicateAdapter: DuplicateGroupAdapter
    private val duplicateGroups = mutableListOf<List<MediaFile>>()
    private val allMediaFiles = mutableListOf<MediaFile>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] == true ||
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true -> {
                showScanScreen()
                startScanning()
            }
            else -> {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStorageStats()
        calculateActualDuplicates()
    }

    private fun checkAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = android.net.Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        setupRecyclerView()
        setupClickListeners()
        updateStorageStats()
        showHomeScreen()
        checkAllFilesPermission()
    }

    private fun setupRecyclerView() {
        duplicateAdapter = DuplicateGroupAdapter { filesToDelete ->
            deleteFiles(filesToDelete)
        }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = duplicateAdapter
        }
    }

    private fun setupClickListeners() {
        // Storage Card
        binding.storageCard.setOnClickListener {
            val intent = Intent(this, CategoriesActivity::class.java)
            startActivity(intent)
        }

        binding.settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // All Files
        binding.categoryAllFiles.setOnClickListener {
            val intent = Intent(this, AllFilesScanActivity::class.java)
            startActivity(intent)
        }

        // Photos
        binding.categoryPhotos.setOnClickListener {
            val intent = Intent(this, PhotoScanActivity::class.java)
            startActivity(intent)
        }
        
        // Videos
        binding.categoryVideos.setOnClickListener {
            val intent = Intent(this, VideoScanActivity::class.java)
            startActivity(intent)
        }
        
        // Documents
        binding.categoryDocs.setOnClickListener {
            val intent = Intent(this, DocScanActivity::class.java)
            startActivity(intent)
        }
        
        // Audios
        binding.categoryAudios.setOnClickListener {
            val intent = Intent(this, AudioScanActivity::class.java)
            startActivity(intent)
        }
        
        // Contacts
        binding.categoryContacts.setOnClickListener {
            val intent = Intent(this, ContactScanActivity::class.java)
            startActivity(intent)
        }

        // Back button
        binding.backButton.setOnClickListener {
            showHomeScreen()
        }

        // Delete button
        binding.deleteButton.setOnClickListener {
            val selectedFiles = duplicateAdapter.getSelectedFiles()
            if (selectedFiles.isNotEmpty()) {
                showDeleteConfirmation(selectedFiles)
            } else {
                Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show()
            }
        }

        binding.selectAllButton.setOnClickListener {
            duplicateAdapter.selectAllExceptFirst()
        }
    }

    private fun startScanActivity(category: String) {
        val intent = Intent(this, ScanActivity::class.java).apply {
            putExtra("CATEGORY", category)
        }
        startActivity(intent)
    }

    private fun showHomeScreen() {
        binding.homeScrollView.visibility = View.VISIBLE
        binding.scanResultsContainer.visibility = View.GONE
        binding.statusText.text = "Tap Scan to find duplicates"
    }

    private fun showScanScreen() {
        binding.homeScrollView.visibility = View.GONE
        binding.scanResultsContainer.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        updateStorageStats()
        calculateActualDuplicates()
    }

    private fun updateStorageStats() {
        val stats = StorageUtils.getStorageStats(this)
        val usedString = StorageUtils.formatSize(stats.usedBytes)
        val totalString = StorageUtils.formatSize(stats.totalBytes)
        val freeString = StorageUtils.formatSize(stats.availableBytes)

        try {
            val dataStat = StatFs(Environment.getDataDirectory().path)
            val dataTotal = dataStat.blockCountLong * dataStat.blockSizeLong
            val dataAvail = dataStat.availableBlocksLong * dataStat.blockSizeLong
            val dataUsed = (dataTotal - dataAvail).coerceAtLeast(0L)

            Log.d(
                storageLogTag,
                "UI(total=${stats.totalBytes}, used=${stats.usedBytes}, free=${stats.availableBytes}) " +
                    "StatFs(dataTotal=$dataTotal, dataUsed=$dataUsed, dataFree=$dataAvail)"
            )
        } catch (e: Exception) {
            Log.d(storageLogTag, "StatFs debug failed: ${e.message}")
        }

        val displayPercentage = stats.usedPercentage

        // Smooth animations for text changes
        animateTextChange(binding.storageUsedText, usedString)
        animateTextChange(binding.freeSpaceText, freeString)
        binding.storageTotalText.text = "of $totalString total"
        
        // Circular and Linear progress animations are handled by their custom views
        binding.storageProgress.setProgress(displayPercentage)
        binding.linearProgress.setProgress(displayPercentage)
        binding.storagePercentText.text = "$displayPercentage%"
    }

    private fun animateTextChange(textView: android.widget.TextView, newText: String) {
        if (textView.text == newText) return
        textView.animate().alpha(0f).setDuration(200).withEndAction {
            textView.text = newText
            textView.animate().alpha(1f).setDuration(200).start()
        }.start()
    }


    private fun calculateActualDuplicates() {
        lifecycleScope.launch(Dispatchers.IO) {
            var totalCount = 0
            var totalSize = 0L

            // Scan Photos
            val photos = queryFiles(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            val photoDupes = findDuplicatesForFiles(photos)
            totalCount += photoDupes.sumOf { it.size - 1 }
            totalSize += photoDupes.sumOf { group -> group.drop(1).sumOf { it.size } }

            // Scan Videos
            val videos = queryFiles(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            val videoDupes = findDuplicatesForFiles(videos)
            totalCount += videoDupes.sumOf { it.size - 1 }
            totalSize += videoDupes.sumOf { group -> group.drop(1).sumOf { it.size } }

            withContext(Dispatchers.Main) {
                binding.duplicatesCountText.text = "$totalCount files"
                binding.canSaveText.text = StorageUtils.formatSize(totalSize)
            }
        }
    }

    private fun queryFiles(uri: android.net.Uri): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                files.add(MediaFile(
                    cursor.getLong(idCol),
                    cursor.getString(pathCol),
                    cursor.getString(nameCol),
                    cursor.getLong(sizeCol),
                    cursor.getLong(dateCol),
                    cursor.getString(mimeCol) ?: ""
                ))
            }
        }
        return files
    }

    private fun findDuplicatesForFiles(files: List<MediaFile>): List<List<MediaFile>> {
        val sizeGroups = files.groupBy { it.size }.filter { it.value.size > 1 }
        val duplicates = mutableListOf<List<MediaFile>>()
        sizeGroups.forEach { (_, group) ->
            group.forEach { it.hash = StorageUtils.calculateFileHash(it.path) }
            val hashGroups = group.groupBy { it.hash }.filter { it.value.size > 1 }
            hashGroups.forEach { duplicates.add(it.value) }
        }
        return duplicates
    }


    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        when {
            permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            } -> {
                showScanScreen()
                startScanning()
            }
            else -> permissionLauncher.launch(permissions)
        }
    }

    private fun startScanning() {
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Scanning files..."
        binding.recyclerView.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        duplicateAdapter.submitList(emptyList())

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                allMediaFiles.clear()
                scanMediaFiles()
                findDuplicates()

                withContext(Dispatchers.Main) {
                    displayResults()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.statusText.text = "Error: ${e.message}"
                    binding.emptyView.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun scanMediaFiles() {
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
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val path = cursor.getString(pathColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val date = cursor.getLong(dateColumn)
                val mime = cursor.getString(mimeColumn) ?: "image/jpeg"

                allMediaFiles.add(MediaFile(id, path, name, size, date, mime))
            }
        }

        // Scan videos too
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection.map { it.replace("images", "video") }.toTypedArray(),
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val path = cursor.getString(pathColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val date = cursor.getLong(dateColumn)
                val mime = cursor.getString(mimeColumn) ?: "video/mp4"

                allMediaFiles.add(MediaFile(id, path, name, size, date, mime))
            }
        }
    }

    private fun findDuplicates() {
        // Group by size first (quick filter)
        val sizeGroups = allMediaFiles.groupBy { it.size }.filter { it.value.size > 1 }
        
        duplicateGroups.clear()

        sizeGroups.forEach { (_, files) ->
            // Calculate hash for each file
            files.forEach { file ->
                if (file.hash.isEmpty()) {
                    file.hash = calculateFileHash(file.path)
                }
            }

            // Group by hash
            val hashGroups = files.groupBy { it.hash }.filter { it.value.size > 1 }
            
            hashGroups.forEach { (_, duplicates) ->
                // Sort by date, keep oldest first
                val sorted = duplicates.sortedBy { it.dateAdded }
                duplicateGroups.add(sorted)
            }
        }
    }

    private fun calculateFileHash(filePath: String): String {
        return try {
            val file = File(filePath)
            if (!file.exists()) return ""
            
            // For large files, just hash first and last 1MB for speed
            val digest = MessageDigest.getInstance("MD5")
            
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                val maxRead = 1024 * 1024 // 1MB
                
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

    private suspend fun displayResults() {
        withContext(Dispatchers.Main) {
            if (duplicateGroups.isEmpty()) {
                binding.statusText.text = "No duplicates found!"
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
                binding.duplicatesCountText.text = "0 files"
                binding.canSaveText.text = "0 MB"
            } else {
                val totalDuplicates = duplicateGroups.sumOf { it.size - 1 }
                val spaceWasted = duplicateGroups.sumOf { group ->
                    group.drop(1).sumOf { it.size }
                }
                
                binding.statusText.text = "Found ${duplicateGroups.size} groups, $totalDuplicates duplicates, ${formatSize(spaceWasted)} wasted"
                binding.recyclerView.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
                
                binding.duplicatesCountText.text = "$totalDuplicates files"
                binding.canSaveText.text = formatSize(spaceWasted)
                
                duplicateAdapter.submitList(duplicateGroups.toList())
            }
        }
    }

    private fun formatSize(size: Long): String = StorageUtils.formatSize(size)

    private fun showDeleteConfirmation(files: List<MediaFile>) {
        val totalSize = files.sumOf { it.size }
        AlertDialog.Builder(this)
            .setTitle("Delete Files")
            .setMessage("Delete ${files.size} files (${formatSize(totalSize)})?\nOriginal files will be kept.")
            .setPositiveButton("Delete") { _, _ ->
                deleteFiles(files)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteFiles(files: List<MediaFile>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var deleted = 0
            var failed = 0
            
            files.forEach { file ->
                try {
                    val f = File(file.path)
                    if (f.exists() && f.delete()) {
                        deleted++
                        // Remove from media store
                        contentResolver.delete(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            "${MediaStore.Images.Media._ID} = ?",
                            arrayOf(file.id.toString())
                        )
                    } else {
                        failed++
                    }
                } catch (e: Exception) {
                    failed++
                }
            }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Deleted: $deleted, Failed: $failed", Toast.LENGTH_LONG).show()
                if (deleted > 0) {
                    startScanning() // Refresh
                }
            }
        }
    }
}
