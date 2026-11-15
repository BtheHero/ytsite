# Rules for NewPipeExtractor (Rhino JS engine)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Rules for kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.framestack.android.data.**$$serializer { *; }
-keepclassmembers class com.framestack.android.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.framestack.android.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
