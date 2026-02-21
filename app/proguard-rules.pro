# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Ktor rules
-keep class io.ktor.** { *; }

# Kotlinx Serialization rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKd
-keep,allowoptimization class kotlinx.serialization.** { *; }

# Keep data classes used for JSON parsing (Template safe)
# Automatically keeps ANY class annotated with @Serializable regardless of the package name.
# This prevents crashes when users of this template rename the bundle/package ID.
-keep @kotlinx.serialization.Serializable class * {
    *;
}
