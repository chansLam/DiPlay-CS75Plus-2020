# Application class initialization happens before MultiDexApplication.attachBaseContext.
-keep class com.shihab.diplay.DiPlayApplication { *; }
-keep class com.shihab.diplay.DiPlayApplication$* { *; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keep class androidx.multidex.** { *; }
