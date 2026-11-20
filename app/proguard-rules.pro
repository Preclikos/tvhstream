# R8 rules for release builds (minify + obfuscation are enabled in app/build.gradle.kts).
# Most libraries (Media3, Compose, Koin, Firebase, Coil, OkHttp) ship their own consumer rules;
# only app-specific needs live here.

# Keep line numbers so Crashlytics / Play vitals stack traces stay useful after deobfuscation
# (the mapping file is bundled into the AAB and uploaded by the Crashlytics Gradle plugin).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Media3 extension decoders live in local AARs (app/libs) and are instantiated reflectively by
# DefaultRenderersFactory (EXTENSION_RENDERER_MODE_ON). Keep their renderer constructors explicitly
# so playback doesn't silently lose FFmpeg / AV1 / IAMF / MPEG-H support if upstream rules change.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep class androidx.media3.decoder.av1.Libdav1dVideoRenderer { <init>(...); }
-keep class androidx.media3.decoder.iamf.LibiamfAudioRenderer { <init>(...); }
-keep class androidx.media3.decoder.mpegh.MpeghAudioRenderer { <init>(...); }
