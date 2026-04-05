package com.duplicateremover07.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.duplicateremover07.app.databinding.ItemDuplicateGroupBinding
import com.duplicateremover07.app.databinding.ItemPhotoBinding

class DuplicateGroupAdapter(
    private val onDeleteClicked: (List<MediaFile>) -> Unit
) : RecyclerView.Adapter<DuplicateGroupAdapter.GroupViewHolder>() {

    private val groups = mutableListOf<List<MediaFile>>()
    private var originalGroups = listOf<List<MediaFile>>()

    fun submitList(newGroups: List<List<MediaFile>>) {
        originalGroups = newGroups
        groups.clear()
        groups.addAll(newGroups)
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        groups.clear()
        if (query.isEmpty()) {
            groups.addAll(originalGroups)
        } else {
            val lowerQuery = query.lowercase()
            val filtered = originalGroups.filter { group ->
                group.any { contact ->
                    contact.name.lowercase().contains(lowerQuery) || 
                    contact.path.contains(lowerQuery)
                }
            }
            groups.addAll(filtered)
        }
        notifyDataSetChanged()
    }

    fun getSelectedFiles(): List<MediaFile> {
        val selected = mutableListOf<MediaFile>()
        groups.forEach { group ->
            selected.addAll(group.filter { it.isSelected })
        }
        return selected
    }

    fun selectAllExceptFirst() {
        groups.forEach { group ->
            group.forEachIndexed { index, file ->
                file.isSelected = index > 0 // Skip first (original)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemDuplicateGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groups[position])
    }

    override fun getItemCount(): Int = groups.size

    inner class GroupViewHolder(
        private val binding: ItemDuplicateGroupBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(group: List<MediaFile>) {
            val context = binding.root.context
            
            val firstFile = group.firstOrNull()
            val typeTitle = if (firstFile != null) {
                val lowerMime = firstFile.mimeType.lowercase()
                val lowerName = firstFile.name.lowercase()
                when {
                    lowerMime.startsWith("image/") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".gif") || lowerName.endsWith(".webp") -> context.getString(R.string.photos)
                    lowerMime.startsWith("video/") || lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".3gp") || lowerName.endsWith(".webm") -> context.getString(R.string.videos)
                    lowerMime.startsWith("audio/") || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".ogg") || lowerName.endsWith(".aac") || lowerName.endsWith(".m4a") -> context.getString(R.string.audio)
                    lowerMime == "application/pdf" || lowerMime.startsWith("text/") || lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") || lowerName.endsWith(".docx") || lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") || lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx") || lowerName.endsWith(".txt") -> context.getString(R.string.documents)
                    lowerMime == "application/vnd.android.package-archive" || lowerName.endsWith(".apk") || lowerName.endsWith(".apks") || lowerName.endsWith(".xapk") -> context.getString(R.string.apks)
                    firstFile.size == 0L && firstFile.path.isNotBlank() && lowerMime.isNotBlank() && lowerMime == firstFile.name.lowercase() -> context.getString(R.string.contacts)
                    else -> "Files"
                }
            } else context.getString(R.string.group)

            binding.groupTitle.text = "$typeTitle ${context.getString(R.string.group)}"
            
            val totalSize = group.drop(1).sumOf { it.size }
            val sizeText = when {
                totalSize >= 1024 * 1024 -> "%.1f MB".format(totalSize / (1024.0 * 1024.0))
                totalSize >= 1024 -> "%.1f KB".format(totalSize / 1024.0)
                else -> "$totalSize B"
            }
            
            binding.groupInfo.text = "${context.getString(R.string.duplicates_count, group.size - 1)}, $sizeText recoverable"
            
            // Setup horizontal RecyclerView for photos
            val photoAdapter = PhotoAdapter(group) { file, isSelected ->
                file.isSelected = isSelected
            }
            
            binding.photosRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                adapter = photoAdapter
            }
        }
    }
}

class PhotoAdapter(
    private val files: List<MediaFile>,
    private val onSelectionChanged: (MediaFile, Boolean) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(files[position], position == 0)
    }

    override fun getItemCount(): Int = files.size

    inner class PhotoViewHolder(
        private val binding: ItemPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: MediaFile, isOriginal: Boolean) {
            val context = binding.root.context

            // Show file name for all types
            binding.fileNameText.text = file.name
            binding.fileNameText.visibility = View.VISIBLE
            
            // For images, we might want a slightly different scale type or max lines
            val lowerName = file.name.lowercase()
            val lowerMime = file.mimeType.lowercase()
            val isImage = lowerMime.startsWith("image/") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".gif") || lowerName.endsWith(".webp")
            if (isImage) {
                binding.fileNameText.maxLines = 1
                binding.fileNameText.alpha = 0.8f
            } else {
                binding.fileNameText.maxLines = 2
                binding.fileNameText.alpha = 1.0f
            }
            
            val isVideo = lowerMime.startsWith("video/") || lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".3gp") || lowerName.endsWith(".webm")
            val isAudio = lowerMime.startsWith("audio/") || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".ogg") || lowerName.endsWith(".aac") || lowerName.endsWith(".m4a")
            val isApk = lowerMime == "application/vnd.android.package-archive" || lowerName.endsWith(".apk") || lowerName.endsWith(".apks") || lowerName.endsWith(".xapk")
            val isDoc = lowerMime == "application/pdf" || lowerMime.startsWith("text/") || lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") || lowerName.endsWith(".docx") || lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") || lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx") || lowerName.endsWith(".txt")
            val isContact = file.size == 0L && file.path.isNotBlank() && lowerMime.isNotBlank() && lowerMime == file.name.lowercase()

            val (fallbackIcon, crop) = when {
                isImage -> Pair(R.drawable.photographer, true)
                isVideo -> Pair(R.drawable.video_editor, true)
                isAudio -> Pair(R.drawable.audio, false)
                isApk -> Pair(R.drawable.file, false)
                isDoc -> Pair(R.drawable.file, false)
                isContact -> Pair(R.drawable.people, false)
                else -> Pair(R.drawable.file, false)
            }

            val requestOptions = RequestOptions()
                .placeholder(fallbackIcon)
                .error(fallbackIcon)

            Glide.with(context)
                .load(file.path)
                .apply(requestOptions)
                .let { req -> if (crop) req.centerCrop() else req.fitCenter() }
                .into(binding.photoImageView)
            
            // Show/hide original label
            binding.originalLabel.visibility = if (isOriginal) View.VISIBLE else View.GONE
            
            // Update selection UI
            updateSelectionUI(file.isSelected)
            
            // Click to toggle selection (except for original)
            if (!isOriginal) {
                binding.root.setOnClickListener {
                    file.isSelected = !file.isSelected
                    updateSelectionUI(file.isSelected)
                    onSelectionChanged(file, file.isSelected)
                }
            } else {
                binding.root.isClickable = false
                binding.root.alpha = 1.0f
            }
        }
        
        private fun updateSelectionUI(isSelected: Boolean) {
            binding.checkIcon.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.selectionOverlay.alpha = if (isSelected) 0.4f else 0.0f
        }
    }
}

