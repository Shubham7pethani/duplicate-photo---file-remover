# WorkManager protection (for background scanning)
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Glide protection (for image loading)
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# View Binding protection
-keep class com.duplicateremover07.app.databinding.** { *; }

# Keep all Activity and Activity classes
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep the data class used for media files
-keep class com.duplicateremover07.app.MediaFile { *; }
-keep class com.duplicateremover07.app.SimpleFile { *; }

# Keep the worker class specifically
-keep class com.duplicateremover07.app.DuplicateDetectionWorker { *; }

# Activity specific protection
-keep class com.duplicateremover07.app.DocScanActivity { *; }
-keep class com.duplicateremover07.app.ApkScanActivity { *; }
-keep class com.duplicateremover07.app.OthersScanActivity { *; }
-keep class com.duplicateremover07.app.AllFilesScanActivity { *; }

# General Android protection
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses
