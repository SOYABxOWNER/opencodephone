# OpenCodePhone ProGuard rules.
# kotlinx-serialization, OkHttp and commons-compress ship their own consumer rules;
# only project-specific keeps live here.

# Keep bootstrap entry points referenced from resources/service declarations.
-keep class io.github.oustery.opencodephone.EnvService { *; }

# commons-compress optional dependencies are not used on Android.
-dontwarn org.apache.commons.compress.utils.CountingInputStream
-dontwarn org.tukaani.xz.XZOutputStream
-dontwarn org.tukaani.xz.XZInputStream
