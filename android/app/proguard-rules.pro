# PDFBox-Android reflects over font and encoding classes it loads from its bundled resources.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**

# PDFBox is a port of a desktop library and references AWT/Java classes absent on Android.
# They sit on code paths this app never reaches (printing, image encoders).
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.**

# ML Kit loads its bundled model implementation dynamically.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# kotlinx.serialization generates serializers referenced only by name.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.gohil.bookkeeper.core.rules.** {
    *** Companion;
}
-keepclasseswithmembers class com.gohil.bookkeeper.core.rules.** {
    kotlinx.serialization.KSerializer serializer(...);
}
