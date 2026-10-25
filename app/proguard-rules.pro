# ── 앱 코드는 이름·줄번호를 그대로 유지 ──────────────────────────
# R8 을 켜도 오류 스택에 org.music.infiniteplayer.* 클래스 이름과
# 파일:줄 이 그대로 남아 원인을 읽을 수 있다.
-keep class org.music.infiniteplayer.** { *; }
-keepnames class org.music.infiniteplayer.**
-keepattributes SourceFile,LineNumberTable

# ── Media3 / ExoPlayer ────────────────────────────────────────
-dontwarn androidx.media3.**
-keep class androidx.media3.session.** { *; }

# ── Kotlin 메타데이터 ─────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ── org.json 은 안드로이드 기본 제공 (조용히 무시) ────────────────
-dontwarn org.json.**
