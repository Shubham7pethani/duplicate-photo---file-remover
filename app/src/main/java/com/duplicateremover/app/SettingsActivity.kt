package com.duplicateremover.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.duplicateremover.app.databinding.ActivitySettingsBinding
import java.io.File
import java.io.FileOutputStream

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
            startActivity(Intent(this, PrivacyPolicyActivity::class.java))
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Make dialog background transparent to show our card's rounded corners
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }

        dialogView.findViewById<android.widget.TextView>(R.id.appVersion).text = "Version $versionName"
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.okButton).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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

        try {
            val bitmap = BitmapFactory.decodeResource(resources, R.drawable.sharelogo)
            val cachePath = File(cacheDir, "images")
            cachePath.mkdirs()
            val file = File(cachePath, "share_logo.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.close()

            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

            if (contentUri != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                // Grant permission to all apps that can handle the intent
                val resInfoList = packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                for (resolveInfo in resInfoList) {
                    val packageName = resolveInfo.activityInfo.packageName
                    grantUriPermission(packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                startActivity(Intent.createChooser(intent, "Share with"))
            } else {
                shareTextOnly(text)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            shareTextOnly(text)
        }
    }

    private fun shareTextOnly(text: String) {
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
