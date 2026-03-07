package com.duplicateremover.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.duplicateremover.app.databinding.ActivityContactScanBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactScanBinding
    private lateinit var duplicateAdapter: DuplicateGroupAdapter
    private val allContacts = mutableListOf<MediaFile>()
    private val duplicateGroups = mutableListOf<List<MediaFile>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactScanBinding.inflate(layoutInflater)
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
        binding.toolbar.setNavigationOnClickListener {
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
        binding.deleteButton.setOnClickListener {
            val selectedFiles = duplicateAdapter.getSelectedFiles()
            if (selectedFiles.isNotEmpty()) {
                showDeleteConfirmation(selectedFiles)
            }
        }
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
        binding.fileCountText.text = "0 Contacts Scanned"
        binding.statusText.text = "Searching for duplicates..."

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
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val number = cursor.getString(numCol).replace("\\s".toRegex(), "")
                
                // Abuse MediaFile for contacts for now (id=id, path=number, name=name)
                allContacts.add(MediaFile(id, number, name, 0, 0, name))
                
                current++
                val progress = (current * 100) / total
                withContext(Dispatchers.Main) {
                    binding.scanProgressCircle.setProgress(progress)
                    binding.fileCountText.text = "$current Contacts Scanned"
                }
                if (current % 10 == 0) delay(1)
            }
        }
    }

    private suspend fun findDuplicates() {
        withContext(Dispatchers.Main) {
            binding.statusText.text = "Comparing contacts..."
        }

        // Group by name + number
        val groups = allContacts.groupBy { "${it.path}_${it.name}" }.filter { it.value.size > 1 }
        duplicateGroups.clear()

        groups.forEach { (_, contacts) ->
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
        binding.selectedStatsText.text = "${selectedFiles.size} contacts selected"
    }

    private fun showDeleteConfirmation(files: List<MediaFile>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete, null)
        val dialog = AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setView(dialogView)
            .create()

        dialogView.findViewById<android.widget.ImageView>(R.id.dialogIcon).setImageResource(android.R.drawable.ic_menu_my_calendar)
        dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Remove Duplicates?"
        dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text = 
            "Remove ${files.size} duplicate contacts?\nOriginal contacts will be kept safe."
        
        val btnDelete = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDelete)
        btnDelete.text = "Remove"

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
        lifecycleScope.launch(Dispatchers.IO) {
            var deleted = 0
            files.forEach { file ->
                try {
                    val rows = contentResolver.delete(
                        ContactsContract.RawContacts.CONTENT_URI,
                        ContactsContract.RawContacts.CONTACT_ID + " = ?",
                        arrayOf(file.id.toString())
                    )
                    if (rows > 0) deleted++
                } catch (e: Exception) {}
            }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ContactScanActivity, "Removed $deleted duplicate contacts", Toast.LENGTH_SHORT).show()
                startScanning()
            }
        }
    }
}
