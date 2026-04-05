package com.duplicateremover07.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.duplicateremover07.app.databinding.ActivityLanguageBinding
import java.util.Locale

class LanguageActivity : BaseActivity() {

    private lateinit var binding: ActivityLanguageBinding
    private val languages = arrayOf(
        "English", "Hindi", "Gujarati", "Marathi", "Tamil", "Telugu", "Kannada", "Malayalam", "Bengali", "Punjabi",
        "Spanish", "French", "German", "Russian", "Japanese", "Chinese", "Arabic",
        "Portuguese", "Italian", "Korean", "Turkish", "Dutch", "Vietnamese", "Indonesian", "Thai", "Polish", "Greek",
        "Urdu", "Persian", "Hebrew", "Swedish", "Norwegian", "Danish", "Finnish", "Romanian", "Hungarian", "Czech",
        "Slovak", "Ukrainian", "Bulgarian", "Croatian", "Serbian", "Malay", "Filipino", "Burmese", "Khmer", "Lao"
    )
    private val languageCodes = arrayOf(
        "en", "hi", "gu", "mr", "ta", "te", "kn", "ml", "bn", "pa",
        "es", "fr", "de", "ru", "ja", "zh", "ar",
        "pt", "it", "ko", "tr", "nl", "vi", "id", "th", "pl", "el",
        "ur", "fa", "he", "sv", "no", "da", "fi", "ro", "hu", "cs",
        "sk", "uk", "bg", "hr", "sr", "ms", "tl", "my", "km", "lo"
    )
    private val languageFlags = arrayOf(
        "🇺🇸", "🇮🇳", "🇮🇳", "🇮🇳", "🇮🇳", "🇮🇳", "🇮🇳", "🇮🇳", "🇮🇳", "🇮🇳",
        "🇪🇸", "🇫🇷", "🇩🇪", "🇷🇺", "🇯🇵", "🇨🇳", "🇸🇦",
        "🇵🇹", "🇮🇹", "🇰🇷", "🇹🇷", "🇳🇱", "🇻🇳", "🇮🇩", "🇹🇭", "🇵🇱", "🇬🇷",
        "🇵🇰", "🇮🇷", "🇮🇱", "🇸🇪", "🇳🇴", "🇩🇰", "🇫🇮", "🇷🇴", "🇭🇺", "🇨🇿",
        "🇸🇰", "🇺🇦", "🇧🇬", "🇭🇷", "🇷🇸", "🇲🇾", "🇵🇭", "🇲🇲", "🇰🇭", "🇱🇦"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLanguageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.backButton.setOnClickListener { finish() }

        setupLanguageList()
    }

    private fun setupLanguageList() {
        val currentLangCode = getSharedPreferences("Settings", Context.MODE_PRIVATE).getString("My_Lang", "en")
        
        languages.forEachIndexed { index, langName ->
            val langCode = languageCodes[index]
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_language, binding.languageContainer, false)
            
            val nameText = itemView.findViewById<TextView>(R.id.langName)
            val flagText = itemView.findViewById<TextView>(R.id.langFlag)
            val checkIcon = itemView.findViewById<ImageView>(R.id.checkIcon)
            
            nameText.text = langName
            flagText.text = languageFlags[index]
            checkIcon.visibility = if (langCode == currentLangCode) View.VISIBLE else View.GONE
            
            itemView.setOnClickListener {
                LocaleHelper.setLocale(this, langCode)
                // Restart app or return to main/settings
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            
            binding.languageContainer.addView(itemView)
        }
    }
}
