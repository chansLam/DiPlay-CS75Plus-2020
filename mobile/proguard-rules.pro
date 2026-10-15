# R8: shrink only — no obfuscation, no code reordering.
# The CS75 Plus 2020 head unit (Android 4.4 / Dalvik) fails to load a multidex secondary dex at
# startup (crashes inside Application.attachBaseContext, before the crash handler runs). Shrinking
# is enabled to cut dex size dramatically (aiming for a single dex to remove multidex). We
# deliberately avoid obfuscation to keep reflection and dynamic class loading safe.
-dontobfuscate
-dontoptimize

# --- Conscrypt TLS platform adapters reference hidden Android classes (runtime-only) ---
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**

# --- Android components (also auto-kept from the manifest by AGP, kept explicitly too) ---
-keep class com.shihab.diplay.DiPlayApplication { *; }
-keep class com.shihab.diplay.DiPlayActivity { *; }

# --- Kotlin default constructor marker (required by generated accessors) ---
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }

# --- Parcelable / Serializable models (AirPlay & CarPlay state) ---
-keep class * implements android.os.Parcelable { *; }
-keepclassmembers class * implements java.io.Serializable { *; }

# --- Native (JNI) method holders: keep native methods so loadLibrary'd classes stay intact ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Reflection points in app code ---
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- BouncyCastle: keep the WHOLE library. CarPlay/AirPlay auth (LocalMfiAuthenticationClient)
#      instantiates BouncyCastleProvider and resolves algorithms (NONEwithECDSA, EC KeyFactory,
#      X.509 CertificateFactory) by name; the provider's algorithm registry is reached via
#      reflection/internal mapping, so subpackage-only keep rules still break authentication.
#      This pushes the method count back up (multidex re-engages), but R8-compressed dexes with
#      no VerifyError have proven loadable on this head unit, so a multi-dex build is acceptable. ---
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- CarPlay / AirPlay authentication & MFI protocol classes (kept to preserve handshake) ---
-keep class com.shihab.diplay.airplay.AirPlayCrypto { *; }
-keep class com.shihab.diplay.airplay.PairSetup { *; }
-keep class com.shihab.diplay.airplay.PairVerify { *; }
-keep class com.shihab.diplay.mfi.** { *; }

# --- ENTIRE app code: shrink (with -dontoptimize/-dontobfuscate) still drops app classes reachable
#      only via reflection, dynamic lookup or framework callbacks (settings items, floating-ball tap
#      handlers, shell exec helpers). Keep every com.shihab.diplay class so all features survive. ---
-keep class com.shihab.diplay.** { *; }

# --- Android components declared in the manifest (auto-kept by AGP, kept explicitly to be safe
#      against ActivityNotFoundException when startActivity resolves a shrunken component) ---
-keep class com.shihab.diplay.**Activity { *; }
-keep class com.shihab.diplay.**Service { *; }
-keep class com.shihab.diplay.**Receiver { *; }
-keep class com.shihab.diplay.**Provider { *; }

# --- jackpal Android-Terminal-Emulator (emulatorview + ShellTermSession + TermExec):
#      rendering/session classes are referenced by DevTerminalActivity and their JNI/native
#      entry points are resolved dynamically, so keep the whole package. ---
-keep class jackpal.androidterm.** { *; }
-keepclassmembers class jackpal.androidterm.** { *; }

# --- Reflection / serialization attributes required by runtime lookups ---
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
