-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Media3 / ExoPlayer reflection-loaded extractors and renderers
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.common.** { *; }
-dontwarn androidx.media3.**

-dontwarn javax.lang.model.**
