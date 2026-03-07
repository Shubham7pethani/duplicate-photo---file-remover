package com.duplicateremover.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.duplicateremover.app.databinding.ActivityCategoriesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoriesBinding
    private val categories = mutableListOf<CategoryInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadData()
        setupVideoBackground()
    }

    private fun setupUI() {
        binding.backBtn.setOnClickListener { finish() }
        binding.allScanBtn.setOnClickListener {
            startActivity(Intent(this, AllFilesScanActivity::class.java))
        }

        binding.categoryRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupVideoBackground() {
        try {
            val videoPath = "android.resource://$packageName/${R.raw.finder}"
            binding.allScanVideo.setVideoURI(Uri.parse(videoPath))
            binding.allScanVideo.setOnPreparedListener { mp ->
                mp.isLooping = true
                binding.allScanVideo.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.allScanVideo.start()
    }

    private fun loadData() {
        val storageStats = StorageUtils.getStorageStats(this)
        val ramStats = StorageUtils.getRamStats(this)

        binding.storageUsedText.text = StorageUtils.formatSize(storageStats.usedBytes)
        binding.storageTotalText.text = "of ${StorageUtils.formatSize(storageStats.totalBytes)} total"
        binding.storagePercentText.text = "${storageStats.usedPercentage}%"
        binding.storageProgress.setProgress(storageStats.usedPercentage)
        
        binding.ramMainText.text = "${StorageUtils.formatSize(ramStats.usedBytes)}/${StorageUtils.formatSize(ramStats.totalBytes)}"

        lifecycleScope.launch(Dispatchers.IO) {
            val breakdown = calculateBreakdown()
            withContext(Dispatchers.Main) {
                updateList(breakdown)
            }
        }
    }

    private fun calculateBreakdown(): List<CategoryInfo> {
        val result = mutableListOf<CategoryInfo>()

        // Photos
        result.add(getMediaInfo(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Photos", "Images", "#FF7D45", R.drawable.photographer))
        
        // Videos
        result.add(getMediaInfo(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Videos", "Videos", "#C55BFF", R.drawable.video_editor))
        
        // Audios
        result.add(getMediaInfo(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audios", "Audio", "#FFCC5B", R.drawable.audio))

        // Documents (Querying Files with common doc mime types)
        val docInfo = getFileInfo("application/pdf", "text/plain", "application/msword", 
            title = "Documents", typeName = "Docs", color = "#45D3FF", iconResId = R.drawable.file)
        result.add(docInfo)

        // APKs
        val apkInfo = getFileInfo("application/vnd.android.package-archive", 
            title = "APK's", typeName = "APK's", color = "#45FFF5", iconResId = R.drawable.apk)
        result.add(apkInfo)

        // Calculate 'Others' as the remaining used space
        val totalUsedSize = StorageUtils.getStorageStats(this).usedBytes
        val categorizedSize = result.sumOf { it.totalSize }
        val othersSize = (totalUsedSize - categorizedSize).coerceAtLeast(0L)
        result.add(CategoryInfo("Others", "Files", 0, othersSize, "#45FF56", R.drawable.more))

        return result
    }

    private fun getFileInfo(vararg mimeTypes: String, title: String, typeName: String, color: String, iconResId: Int? = null): CategoryInfo {
        var count = 0
        var size = 0L
        val selection = mimeTypes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} = ?" }
        val selectionArgs = mimeTypes
        
        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns.SIZE),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            count = cursor.count
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            while (cursor.moveToNext()) {
                size += cursor.getLong(sizeCol)
            }
        }
        return CategoryInfo(title, typeName, count, size, color, iconResId)
    }

    private fun getMediaInfo(uri: android.net.Uri, title: String, typeName: String, color: String, iconResId: Int? = null): CategoryInfo {
        var count = 0
        var size = 0L
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            count = cursor.count
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                size += cursor.getLong(sizeCol)
            }
        }
        return CategoryInfo(title, typeName, count, size, color, iconResId)
    }

    private fun updateList(breakdown: List<CategoryInfo>) {
        val adapter = CategoryAdapter(breakdown) { item ->
            val intent = when(item.title) {
                "Photos" -> Intent(this, PhotoScanActivity::class.java)
                "Videos" -> Intent(this, VideoScanActivity::class.java)
                "Documents" -> Intent(this, DocScanActivity::class.java)
                "Audios" -> Intent(this, AudioScanActivity::class.java)
                "APK's" -> Intent(this, ScanActivity::class.java).putExtra("CATEGORY", "APK's")
                else -> Intent(this, ScanActivity::class.java).putExtra("CATEGORY", item.title)
            }
            startActivity(intent)
        }
        binding.categoryRecyclerView.adapter = adapter
    }

    data class CategoryInfo(
        val title: String,
        val typeName: String,
        val count: Int,
        val totalSize: Long,
        val colorHex: String,
        val iconResId: Int? = null
    )
}
