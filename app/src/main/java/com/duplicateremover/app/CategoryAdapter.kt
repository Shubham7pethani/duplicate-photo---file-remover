package com.duplicateremover.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.duplicateremover.app.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val items: List<CategoriesActivity.CategoryInfo>,
    private val onScanClick: (CategoriesActivity.CategoryInfo) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            catTitle.text = item.title
            catSubtitle.text = "${item.count} ${item.typeName} | ${StorageUtils.formatSize(item.totalSize)}"
            
            if (item.iconResId != null) {
                categoryIcon.setImageResource(item.iconResId)
                categoryIcon.visibility = View.VISIBLE
                dotView.visibility = View.GONE
            } else {
                categoryIcon.visibility = View.GONE
                dotView.visibility = View.VISIBLE
                dotView.backgroundTintList = ColorStateList.valueOf(Color.parseColor(item.colorHex))
            }
            
            scanBtn.setOnClickListener { onScanClick(item) }
        }
    }

    override fun getItemCount() = items.size
}
