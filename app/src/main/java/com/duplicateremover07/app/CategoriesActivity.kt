package com.duplicateremover07.app

import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.duplicateremover07.app.databinding.ActivityCategoriesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoriesActivity : BaseActivity() {

    private lateinit var binding: ActivityCategoriesBinding
    private val categories = mutableListOf<CategoryInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.onAttach(this)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

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
        binding.storageTotalText.text = getString(R.string.of_total, StorageUtils.formatSize(storageStats.totalBytes))
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
        result.add(getMediaInfo(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, getString(R.string.photos), getString(R.string.photos), "#FF7D45", R.drawable.photographer))
        
        // Videos
        result.add(getMediaInfo(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, getString(R.string.videos), getString(R.string.videos), "#C55BFF", R.drawable.video_editor))
        
        // Audios
        result.add(getMediaInfo(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, getString(R.string.audio), getString(R.string.audio), "#FFCC5B", R.drawable.audio))

        // Documents
        val docInfo = getFileInfo("application/pdf", "text/plain", "application/msword", 
            title = getString(R.string.documents), typeName = getString(R.string.documents), color = "#45D3FF", iconResId = R.drawable.file)
        result.add(docInfo)

        // Contacts
        val contactInfo = getContactsInfo()
        result.add(contactInfo)

        // Calculate 'Others' as the remaining used space
        val totalUsedSize = StorageUtils.getStorageStats(this).usedBytes
        val categorizedSize = result.filter { it.title != getString(R.string.contacts) }.sumOf { it.totalSize }
        val othersSize = (totalUsedSize - categorizedSize).coerceAtLeast(0L)
        result.add(CategoryInfo(getString(R.string.others), getString(R.string.others), 0, othersSize, "#45FF56", R.drawable.more))

        return result
    }

    private fun getContactsInfo(): CategoryInfo {
        var count = 0
        try {
            contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                null, null, null, null
            )?.use { cursor ->
                count = cursor.count
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return CategoryInfo(getString(R.string.contacts), getString(R.string.contacts), count, 0L, "#FFEB3B", R.drawable.people)
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
            val intent = when {
                item.title == getString(R.string.photos) -> Intent(this, PhotoScanActivity::class.java)
                item.title == getString(R.string.videos) -> Intent(this, VideoScanActivity::class.java)
                item.title == getString(R.string.documents) -> Intent(this, DocScanActivity::class.java)
                item.title == getString(R.string.audio) -> Intent(this, AudioScanActivity::class.java)
                item.title == getString(R.string.contacts) -> Intent(this, ContactScanActivity::class.java)
                item.title == getString(R.string.others) -> Intent(this, OthersScanActivity::class.java)
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

