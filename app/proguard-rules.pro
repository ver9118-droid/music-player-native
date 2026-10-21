# Media3 / ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.session.** { *; }

# Kotlin 메타데이터
-dontwarn kotlinx.coroutines.**
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# org.json 은 안드로이드 기본 제공 (조용히 무시)
-dontwarn org.json.**
