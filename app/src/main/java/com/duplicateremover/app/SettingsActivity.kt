package com.duplicateremover.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.duplicateremover.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateAllFilesAccessStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.allFilesAccessCard.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            } else {
                Toast.makeText(this, "Not required on this Android version", Toast.LENGTH_SHORT).show()
            }
        }

        binding.privacyPolicyCard.setOnClickListener {
            Toast.makeText(this, "Privacy policy screen coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.aboutAppCard.setOnClickListener {
            showAboutDialog()
        }

        binding.rateAppCard.setOnClickListener {
            openPlayStore()
        }

        binding.shareAppCard.setOnClickListener {
            shareApp()
        }

        binding.contactSupportCard.setOnClickListener {
            contactSupport()
        }

        updateAllFilesAccessStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAllFilesAccessStatus()
    }

    private fun updateAllFilesAccessStatus() {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        binding.allFilesAccessStatus.text = if (allowed) {
            "Status: Allowed"
        } else {
            "Status: Not allowed"
        }
    }

    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }

        val message = buildString {
            append("App: ")
            append(getString(R.string.app_name))
            if (versionName.isNotBlank()) {
                append("\nVersion: ")
                append(versionName)
            }
            append("\nDeveloper: ")
            append(getString(R.string.developer_name))
        }

        AlertDialog.Builder(this)
            .setTitle("About")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openPlayStore() {
        val marketUri = Uri.parse("market://details?id=$packageName")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")

        val marketIntent = Intent(Intent.ACTION_VIEW, marketUri)
        val webIntent = Intent(Intent.ACTION_VIEW, webUri)

        try {
            startActivity(marketIntent)
        } catch (_: Exception) {
            try {
                startActivity(webIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open Play Store", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareApp() {
        val link = getString(R.string.share_app_link, packageName)
        val text = getString(R.string.share_app_text, link)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    private fun contactSupport() {
        val supportEmail = getString(R.string.support_email)
        val subject = "Support: ${getString(R.string.app_name)}"

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(supportEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }
}
