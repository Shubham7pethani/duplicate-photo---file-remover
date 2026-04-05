package com.duplicateremover07.app

import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.view.ViewGroup
import androidx.core.view.marginTop
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.duplicateremover07.app.databinding.ActivityContactScanBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactScanActivity : BaseActivity() {

    private lateinit var binding: ActivityContactScanBinding
    private lateinit var duplicateAdapter: DuplicateGroupAdapter
    private val allContacts = mutableListOf<MediaFile>()
    private val duplicateGroups = mutableListOf<List<MediaFile>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityContactScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

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
        val permissions = arrayOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS
        )

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
            Toast.makeText(this, "Permission required to scan contacts", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupToolbar() {
        binding.backButton.setOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        duplicateAdapter = DuplicateGroupAdapter { _ ->
            updateBottomBarStats()
        }
        binding.resultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ContactScanActivity)
            adapter = duplicateAdapter
        }
    }

    private fun setupClickListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.searchButton.setOnClickListener {
            showSearchOverlay()
        }

        binding.closeSearchButton.setOnClickListener {
            hideSearchOverlay()
        }

        binding.deleteButton.setOnClickListener {
            val selectedFiles = duplicateAdapter.getSelectedFiles()
            if (selectedFiles.isNotEmpty()) {
                showDeleteConfirmation(selectedFiles)
            }
        }

        binding.searchEditText.addTextChangedListener { text ->
            val query = text?.toString() ?: ""
            duplicateAdapter.filter(query)
        }
    }

    private fun showSearchOverlay() {
        binding.searchOverlay.visibility = View.VISIBLE
        binding.searchOverlay.alpha = 0f
        binding.searchOverlay.translationY = -100f
        
        binding.searchOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.searchEditText.requestFocus()
                val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.showSoftInput(binding.searchEditText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            .start()
        
        binding.searchButton.animate().alpha(0f).setDuration(200).withEndAction {
            binding.searchButton.visibility = View.GONE
        }.start()
    }

    private fun hideSearchOverlay() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
        
        binding.searchOverlay.animate()
            .alpha(0f)
            .translationY(-100f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.searchOverlay.visibility = View.GONE
                binding.searchEditText.setText("")
            }
            .start()
        
        binding.searchButton.visibility = View.VISIBLE
        binding.searchButton.alpha = 0f
        binding.searchButton.animate().alpha(1f).setDuration(200).start()
    }

    private fun setupAnimation() {
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.people_contact}")
        binding.scanAnimation.setVideoURI(videoUri)
        binding.scanAnimation.setOnPreparedListener { mp ->
            mp.isLooping = true
            binding.scanAnimation.start()
        }
    }

    private fun startScanning() {
        allContacts.clear()
        duplicateGroups.clear()
        
        binding.scanningContainer.visibility = View.VISIBLE
        binding.resultsRecyclerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        binding.scanProgressCircle.setProgress(0)
        binding.fileCountText.text = getString(R.string.contacts_scanned, 0)
        binding.statusText.text = getString(R.string.searching_duplicates)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                scanContacts()
                findDuplicates()

                withContext(Dispatchers.Main) {
                    displayResults()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ContactScanActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private suspend fun scanContacts() {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            
            val total = cursor.count
            var current = 0
            val seen = mutableSetOf<Pair<Long, String>>() // (ID, normalized_number)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Unknown Name"
                val rawNumber = cursor.getString(numCol) ?: ""
                val normalizedNumber = rawNumber.replace("[^0-9+]".toRegex(), "")
                
                if (normalizedNumber.isNotBlank() && seen.add(id to normalizedNumber)) {
                    // Abuse MediaFile for contacts for now (id=id, path=number, name=name)
                    allContacts.add(MediaFile(id, normalizedNumber, name, 0, 0, name))
                }
                
                current++
                val progress = (current * 100) / total
                withContext(Dispatchers.Main) {
                    binding.scanProgressCircle.setProgress(progress)
                    binding.fileCountText.text = getString(R.string.contacts_scanned, current)
                }
                if (current % 10 == 0) delay(1)
            }
        }
    }

    private suspend fun findDuplicates() {
        withContext(Dispatchers.Main) {
            binding.statusText.text = getString(R.string.searching_duplicates)
        }

        // Group by normalized number only
        val groups = allContacts.groupBy { it.path }
            .filter { it.value.size > 1 }
            .map { it.value.distinctBy { contact -> contact.id } } // SAFETY: Only keep unique IDs in a group
            .filter { it.size > 1 } // Only groups that truly contain DIFFERENT contact IDs sharing a number

        duplicateGroups.clear()
        groups.forEach { contacts ->
            val sorted = contacts.sortedBy { it.id }
            sorted.forEachIndexed { index, contact ->
                contact.isSelected = index > 0
            }
            duplicateGroups.add(sorted)
        }
    }

    private fun displayResults() {
        binding.scanningContainer.visibility = View.GONE
        
        if (duplicateGroups.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.resultsRecyclerView.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
            binding.searchButton.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.resultsRecyclerView.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.VISIBLE
            
            // Show search icon if there are enough items
            binding.searchButton.visibility = if (duplicateGroups.size > 2) View.VISIBLE else View.GONE
            binding.searchButton.alpha = 1f
            
            duplicateAdapter.submitList(duplicateGroups.toList())
            updateBottomBarStats()
        }
    }

    private fun updateBottomBarStats() {
        val selectedFiles = duplicateAdapter.getSelectedFiles()
        binding.selectedStatsText.text = getString(R.string.files_selected, selectedFiles.size)
    }

    private fun showDeleteConfirmation(files: List<MediaFile>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete, null)
        val dialog = AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setView(dialogView)
            .create()

        dialogView.findViewById<android.widget.ImageView>(R.id.dialogIcon).setImageResource(R.drawable.people)
        dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = getString(R.string.delete_dialog_title)
        dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text = 
            getString(R.string.delete_dialog_message, files.size, getString(R.string.contacts))
        dialogView.findViewById<android.widget.TextView>(R.id.dialogSubMessage).text = 
            getString(R.string.delete_dialog_warning)
        
        val btnDelete = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDelete)
        btnDelete.text = getString(R.string.delete)

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        btnDelete.setOnClickListener {
            dialog.dismiss()
            deleteContacts(files)
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun deleteContacts(files: List<MediaFile>) {
        // Double check: ensure we are NOT deleting the first contact (original) from any group
        val safeToDelete = files.filter { file ->
            duplicateGroups.none { group -> group.firstOrNull()?.id == file.id }
        }

        if (safeToDelete.isEmpty()) {
            Toast.makeText(this, "No duplicates selected for deletion", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    showDeleteProgress()
                }

                var deleted = 0
                val total = files.size

                safeToDelete.forEachIndexed { index, file ->
                    try {
                        val rows = contentResolver.delete(
                            ContactsContract.RawContacts.CONTENT_URI,
                            ContactsContract.RawContacts.CONTACT_ID + " = ?",
                            arrayOf(file.id.toString())
                        )
                        if (rows > 0) deleted++

                        val progress = ((index + 1) * 100) / safeToDelete.size
                        withContext(Dispatchers.Main) {
                            binding.scanProgressCircle.setProgress(progress)
                            binding.fileCountText.text = "Removed ${index + 1}/${safeToDelete.size}"
                        }
                        delay(50) // Small delay for visual effect
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                withContext(Dispatchers.Main) {
                    binding.scanningContainer.visibility = View.GONE
                    Toast.makeText(this@ContactScanActivity, "Removed $deleted duplicate contacts", Toast.LENGTH_SHORT).show()
                    startScanning()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.scanningContainer.visibility = View.GONE
                    binding.resultsRecyclerView.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.VISIBLE
                    Toast.makeText(this@ContactScanActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDeleteProgress() {
        binding.scanningContainer.visibility = View.VISIBLE
        binding.resultsRecyclerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        
        binding.scanProgressCircle.setProgress(0)
        binding.fileCountText.text = "Starting removal..."
        binding.statusText.text = "Removing duplicate contacts..."
        
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.trash_bin}")
        binding.scanAnimation.setVideoURI(videoUri)
        binding.scanAnimation.start()
    }
}
