package com.duplicateremover.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.duplicateremover.app.databinding.ActivityPrivacyPolicyBinding

class PrivacyPolicyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyPolicyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyPolicyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("mailto:")) {
                    try {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(url))
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@PrivacyPolicyActivity, "No email app found", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && url.startsWith("mailto:")) {
                    try {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(url))
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@PrivacyPolicyActivity, "No email app found", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }
        }
        binding.webView.loadUrl("file:///android_asset/privacy_policy.html")
    }
}
