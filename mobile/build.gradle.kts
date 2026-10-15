import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import java.security.KeyStore
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
}

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }
    ?: rootProject.file(".private/runtime-assets").takeIf { it.isDirectory }?.canonicalFile
    ?: rootProject.file("auth-assets").takeIf { it.isDirectory }?.canonicalFile

android {
    namespace = "com.shihab.diplay"
    testBuildType = providers.gradleProperty("legacyInstrumentationBuildType").getOrElse("debug")
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 19
        // Sideload SoftAP build for KitKat head units — not distributed via Play.
        targetSdk = 19
        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-config.pro")
        testInstrumentationRunner = "com.shihab.diplay.T3LegacyInstrumentation"
        versionCode = 100
        versionName = "1.0.0"
        ndk {
            // Drop conscrypt/other prebuilt ABIs that OS_S311-WT cannot load.
            abiFilters += listOf("armeabi-v7a")
        }
    }

    lint {
        // Play's floor does not apply to this car-only sideload APK.
        disable += "ExpiredTargetSdkVersion"
    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            val localKeystore = rootProject.file("release-signing.jks").takeIf { it.isFile }
                ?: rootProject.file("release-signing.keystore").takeIf { it.isFile }
            storeFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                .orNull?.let { file(it) } ?: localKeystore ?: file("missing-release-keystore.jks")
            // Release passwords/alias must come from the environment — never hard-code defaults.
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orElse("").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orElse("").get()
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orElse("").get()
        }
    }

    buildTypes {
        release {
            // R8 shrink to a single dex. The CS75 Plus head unit (API 19 / Dalvik 4.4) crashes at
            // startup inside MultiDex.install when a secondary dex exists (verified by v1.0.4 /
            // v1.0.1-diagnostic: no files dir, no crash-handler log, instant exit). Shrinking keeps
            // us under the 64K-method / 1-dex threshold so multidex never engages. No obfuscation.
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

val authenticationProbeRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// AGP 9.3 shrinks test L8 separately, shadowing target-app methods on native multidex Android.
// Keep a complete compatibility library in the test APK only; production remains unchanged.
afterEvaluate {
    tasks.withType<L8DexDesugarLibTask>().configureEach {
        if (name.endsWith("AndroidTest")) {
            keepRulesConfigurations.add("-keep class j$.** { *; }")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation("androidx.multidex:multidex:2.0.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    authenticationProbeRuntime(libs.bouncycastle)
    authenticationProbeRuntime("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

abstract class VerifyDiPlaySigningTask : DefaultTask() {
    @get:org.gradle.api.tasks.Internal abstract val keyStoreFile: RegularFileProperty
    @get:Input abstract val keyStorePassword: Property<String>
    @get:Input abstract val keyAlias: Property<String>
    @get:Input abstract val keyPassword: Property<String>

    @TaskAction fun verify() {
        val keyFile = keyStoreFile.orNull?.asFile
        check(keyFile?.isFile == true && keyStorePassword.get().isNotBlank() &&
            keyAlias.get().isNotBlank() && keyPassword.get().isNotBlank()) {
            "Release requires the original DiPlay signing keystore and explicit ANDROID_KEY* variables. Debug signing is forbidden."
        }
        val secret = keyStorePassword.get().toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        try {
            keyFile!!.inputStream().use { store.load(it, secret) }
        } catch (error: Exception) {
            throw GradleException("Release signing keystore cannot be opened", error)
        } finally {
            secret.fill(0.toChar())
        }
        val alias = keyAlias.get()
        check(store.isKeyEntry(alias)) { "Release signing alias is not a private key entry" }
        val privateKey = runCatching { store.getKey(alias, keyPassword.get().toCharArray()) }.getOrNull()
        check(privateKey is java.security.PrivateKey) { "Release signing private key cannot be unlocked with ANDROID_KEY_PASSWORD" }
        val cert = store.getCertificate(alias) ?: throw GradleException("Release signing certificate not found")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // CS75 Plus open-source release signing certificate (SHA-256).
        // Regenerated 2026-10-15 (first public release rebuild, new keystore).
        // Old APKs cannot update in place; install the new release fresh.
        val allowed = setOf(
            "0203ca02438eeafa96e530d7dda21d96a41f2706e3f6becfda9eafdbe1060176",
        )
        check(allowed.any { fingerprint.equals(it, true) }) {
            "Release certificate does not match the CS75 Plus release signing key."
        }
    }
}
val verifyReleaseSigningIdentity by tasks.registering(VerifyDiPlaySigningTask::class) {
    group = "verification"
    description = "Validate the original DiPlay release certificate before any release APK is signed."
    keyStoreFile.set(layout.file(providers.provider { android.signingConfigs.getByName("release").storeFile!! }))
    keyStorePassword.set(providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orElse(""))
    keyAlias.set(providers.environmentVariable("ANDROID_KEY_ALIAS").orElse(""))
    keyPassword.set(providers.environmentVariable("ANDROID_KEY_PASSWORD").orElse(""))
}
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    dependsOn(verifyReleaseSigningIdentity)
}
// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val requireStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
val verifyStandaloneAuthentication by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Validate standalone key/certificate matching and challenge signatures before packaging."
    dependsOn(requireStandaloneAuthentication, ":shared:bundleLibRuntimeToJarDebug")
    classpath(authenticationProbeRuntime,
        project(":shared").layout.buildDirectory.file(
            "intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"))
    mainClass.set("com.shihab.diplay.mfi.LocalMfiProbe")
    localAuthenticationAssets?.let { args(it.resolve("offline-mfi").absolutePath) }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}

tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a signed standalone APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, verifyReleaseSigningIdentity, "assembleRelease")
}
