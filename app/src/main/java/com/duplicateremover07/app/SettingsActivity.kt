package com.duplicateremover07.app

import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.duplicateremover07.app.databinding.ActivitySettingsBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import android.content.Context
import android.content.res.Configuration

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        updateNotificationStatus()
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for background alerts", Toast.LENGTH_LONG).show()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateAllFilesAccessStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.onAttach(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.backButton.setOnClickListener { finish() }

        binding.notificationStatusCard.setOnClickListener {
            requestNotificationPermission()
        }

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

        binding.languageCard.setOnClickListener {
            startActivity(Intent(this, LanguageActivity::class.java))
        }

        updateAllFilesAccessStatus()
        updateNotificationStatus()
        updateLanguageStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAllFilesAccessStatus()
        updateNotificationStatus()
        updateLanguageStatus()
    }

    private fun updateNotificationStatus() {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        binding.notificationStatus.text = if (allowed) {
            getString(R.string.status_allowed)
        } else {
            getString(R.string.status_not_allowed)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    Toast.makeText(this, "Notification permission already granted", Toast.LENGTH_SHORT).show()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    AlertDialog.Builder(this)
                        .setTitle("Notifications Required")
                        .setMessage("We need notification permission to alert you when duplicate files are detected in the background.")
                        .setPositiveButton("Allow") { _, _ ->
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("No Thanks", null)
                        .show()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            Toast.makeText(this, "Notification permission already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAllFilesAccessStatus() {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        binding.allFilesAccessStatus.text = if (allowed) {
            getString(R.string.status_allowed)
        } else {
            getString(R.string.status_not_allowed)
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

    private fun setLocale(lang: String) {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration()
        config.setLocale(locale)
        baseContext.resources.updateConfiguration(config, baseContext.resources.displayMetrics)
        
        getSharedPreferences("Settings", Context.MODE_PRIVATE).edit().putString("My_Lang", lang).apply()
    }

    private fun updateLanguageStatus() {
        val langCode = getSharedPreferences("Settings", Context.MODE_PRIVATE).getString("My_Lang", "en")
        val languages = arrayOf(
            "English", "Hindi", "Gujarati", "Marathi", "Tamil", "Telugu", "Kannada", "Malayalam", "Bengali", "Punjabi",
            "Spanish", "French", "German", "Russian", "Japanese", "Chinese", "Arabic",
            "Portuguese", "Italian", "Korean", "Turkish", "Dutch", "Vietnamese", "Indonesian", "Thai", "Polish", "Greek",
            "Urdu", "Persian", "Hebrew", "Swedish", "Norwegian", "Danish", "Finnish", "Romanian", "Hungarian", "Czech",
            "Slovak", "Ukrainian", "Bulgarian", "Croatian", "Serbian", "Malay", "Filipino", "Burmese", "Khmer", "Lao"
        )
        val languageCodes = arrayOf(
            "en", "hi", "gu", "mr", "ta", "te", "kn", "ml", "bn", "pa",
            "es", "fr", "de", "ru", "ja", "zh", "ar",
            "pt", "it", "ko", "tr", "nl", "vi", "id", "th", "pl", "el",
            "ur", "fa", "he", "sv", "no", "da", "fi", "ro", "hu", "cs",
            "sk", "uk", "bg", "hr", "sr", "ms", "tl", "my", "km", "lo"
        )
        val index = languageCodes.indexOf(langCode)
        val langName = if (index != -1) languages[index] else "English"
        binding.currentLanguage.text = langName
    }
}

