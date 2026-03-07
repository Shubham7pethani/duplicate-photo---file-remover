package com.duplicateremover.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.duplicateremover.app.databinding.ItemDuplicateGroupBinding
import com.duplicateremover.app.databinding.ItemPhotoBinding

class DuplicateGroupAdapter(
    private val onDeleteClicked: (List<MediaFile>) -> Unit
) : RecyclerView.Adapter<DuplicateGroupAdapter.GroupViewHolder>() {

    private val groups = mutableListOf<List<MediaFile>>()

    fun submitList(newGroups: List<List<MediaFile>>) {
        groups.clear()
        groups.addAll(newGroups)
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
            
            binding.groupTitle.text = "Group ${adapterPosition + 1}"
            
            val totalSize = group.drop(1).sumOf { it.size }
            val sizeText = when {
                totalSize >= 1024 * 1024 -> "%.1f MB".format(totalSize / (1024.0 * 1024.0))
                totalSize >= 1024 -> "%.1f KB".format(totalSize / 1024.0)
                else -> "$totalSize B"
            }
            
            binding.groupInfo.text = "${group.size - 1} duplicates, $sizeText recoverable"
            
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
            
            // Load image
            Glide.with(context)
                .load(file.path)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .centerCrop()
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
