# Reguly gotowe pod wlaczenie R8 (isMinifyEnabled = true w build.gradle.kts).
# Wlacz je dopiero po sprawdzeniu wydania na prawdziwym telefonie - R8 usuwa
# kod, do ktorego siega sie przez refleksje, a to widac dopiero w czasie dzialania.

# kotlinx.serialization dobiera serializatory po nazwie klasy
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
