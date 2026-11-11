# Readium's EPUB pages talk to the app through JavaScript interfaces, called by name from JS.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Readium (de)serializes publication metadata and preferences with kotlinx.serialization and
# reflection on its own model classes; keep them intact.
-keep class org.readium.** { *; }
-dontwarn org.readium.**

# Decoration styles are Parcelable and looked up by class name in Readium's HTML templates.
-keepnames class io.github.friendlyglobal.jacket.reader.UnreadVeil
-keepnames class io.github.friendlyglobal.jacket.reader.Marker
-keepnames class io.github.friendlyglobal.jacket.reader.HereMark

# Optional dependencies referenced by libraries but not shipped.
-dontwarn org.slf4j.**
-dontwarn org.joda.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# WorkManager (used by the cover widget) keeps its jobs in a Room database whose generated
# implementation is created by reflection.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# WorkManager also creates its input mergers by reflection, by class name. Without their
# no-argument constructors the widget's session never starts, and the widget stays on its
# loading layout for good.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# sherpa-onnx (natural voices): its native code reads and fills these Kotlin classes by field and
# method name through JNI, and calls back into Kotlin lambdas.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
# ...and it calls this one's invoke([F)Ljava/lang/Integer; by that exact name and signature.
-keep class io.github.friendlyglobal.jacket.voice.KeepGoing { *; }
