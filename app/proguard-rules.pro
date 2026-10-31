# R8 rules for the release build.
#
# Cuentivo talks to Firestore with plain Map<String, Any?> documents and reads them field by field,
# so there are no model classes for R8 to strip the names off. These rules cover the reflection the
# libraries themselves do.

# Room generates implementations at build time; keep its runtime annotations intact.
-keep class androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(...); }

# Firebase/Firestore reflect over their own internal model classes.
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# WorkManager instantiates workers by class name from the database.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Kotlin coroutines internals reached reflectively.
-dontwarn kotlinx.coroutines.**
