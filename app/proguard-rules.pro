# Add project specific ProGuard rules here.
# You can control what gets obfuscated or shrunk by adding rules here.

-keepattributes SourceFile,LineNumberTable
-keepattributes JavascriptInterface

# Keep JavascriptInterface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
