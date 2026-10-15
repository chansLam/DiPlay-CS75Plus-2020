package com.shihab.diplay

import org.json.JSONObject
import com.shihab.diplay.host.R
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import okhttp3.OkUrlFactory

import com.shihab.diplay.core.UpdateEndpoints

/** GitHub-only updates with a domestic HTTPS mirror for release assets. */
internal object DiPlayUpdateClient {
    private const val MAX_METADATA = 512 * 1024
    private const val MAX_DIGEST_FILE = 2 * 1024
    private const val MAX_APK_BYTES = 120L * 1024 * 1024
    // Stable tags: v1.0.0 or vX.Y.Z(.W) with optional -cs75plus; reject arbitrary prerelease suffixes.
    private val tagPattern = Regex("^v\\d+(?:\\.\\d+){2,3}(?:-cs75plus)?$")
    private val digestPattern = Regex("^[a-fA-F0-9]{64}$")

    data class Release(
        val tag: String,
        val notes: String,
        val apkName: String,
        val apkUrl: String,
        val checksumUrl: String,
        val declaredSize: Long,
    )

    /** Treat malformed, prerelease and non-increasing version labels as non-updates. */
    fun newerVersion(remote: String, installed: String): Boolean {
        if (!tagPattern.matches(remote)) return false
        val remoteParts = versionParts(remote) ?: return false
        val localParts = versionParts(installed) ?: return false
        val width = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until width) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val localValue = localParts.getOrElse(index) { 0 }
            if (remoteValue > localValue) return true
            if (remoteValue < localValue) return false
        }
        return false
    }

    private fun versionParts(label: String): List<Int>? {
        val core = label.removePrefix("v").substringBefore('-')
        val parts = core.split('.').mapNotNull { it.toIntOrNull() }
        return parts.takeIf { it.size in 3..4 && parts.size == core.split('.').size }
    }

    /** Discover releases only from the canonical GitHub repository. */
    fun latest(installed: String): Release? = latestFromGitHub(installed)

    /** Unauthenticated GitHub API calls may be rate-limited on shared mobile networks. */
    private fun latestFromGitHub(installed: String): Release? = try {
        parseLatest(readText(UpdateEndpoints.GITHUB_API_LATEST, MAX_METADATA), installed)
    } catch (apiFailure: Exception) {
        // GitHub's official Atom feed has no REST API quota.
        try {
            latestFromAtom(installed)
        } catch (feedFailure: Exception) {
            feedFailure.addSuppressed(apiFailure)
            throw feedFailure
        }
    }

    /** Atom fallback when anonymous REST API quota is exhausted. */
    private fun latestFromAtom(installed: String): Release? {
        val feedUrl = UpdateEndpoints.GITHUB_RELEASES_ATOM
        val entry = DiPlayAtomSource.newest(readText(feedUrl, MAX_METADATA), installed)
            ?: return null
        val apk = "DiPlay-${entry.tag}-release.apk"
        val url = "${UpdateEndpoints.GITHUB_RELEASE_DOWNLOAD}/${entry.tag}/$apk"
        val head = connect(url, method = "HEAD")
        val size = try {
            head.getHeaderField("Content-Length")?.toLongOrNull()
                ?: throw IOException("Release APK size unavailable")
        } finally {
            head.disconnect()
        }
        if (size <= 0 || size > MAX_APK_BYTES) throw IOException("Invalid release APK size")
        val release = Release(entry.tag, entry.notes, apk, url, "$url.sha256", size)
        checksum(release) // Verify the expected digest file is actually published.
        return release
    }

    internal fun parseLatest(json: String, installed: String): Release? {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.optString("tag_name")
        if (!newerVersion(tag, installed)) return null
        val apk = "DiPlay-$tag-release.apk"
        val prefix = "${UpdateEndpoints.GITHUB_RELEASE_DOWNLOAD}/$tag/"
        val assets = root.getJSONArray("assets")
        var apkUrl: String? = null
        var shaUrl: String? = null
        var bytes = 0L
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (url != prefix + name) continue
            when (name) {
                apk -> {
                    apkUrl = url
                    bytes = asset.optLong("size")
                }
                "$apk.sha256" -> shaUrl = url
            }
        }
        if (apkUrl == null || shaUrl == null || bytes <= 0 || bytes > MAX_APK_BYTES) {
            throw IOException("Release $tag does not contain a valid APK and SHA-256 asset")
        }
        return Release(tag, root.optString("body").take(6000), apk, apkUrl, shaUrl, bytes)
    }

    /** Prefer domestic relays for canonical GitHub release assets, not release metadata. */
    internal fun downloadCandidates(url: String): List<String> =
        UpdateEndpoints.githubDownloadCandidates(url)

    private fun <T> withDownloadFallback(url: String, action: (String) -> T): T {
        var failure: Exception? = null
        for (candidate in downloadCandidates(url)) {
            try {
                return action(candidate)
            } catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        throw failure ?: IOException("No update download source")
    }

    fun checksum(release: Release): String =
        parseChecksum(withDownloadFallback(release.checksumUrl) { readText(it, MAX_DIGEST_FILE) }, release.apkName)

    internal fun parseChecksum(content: String, apkName: String): String {
        val words = content.trim().split(Regex("\\s+"))
        val hash = words.firstOrNull()?.lowercase()
        if (hash == null || !digestPattern.matches(hash) ||
            (words.size > 1 && words.drop(1).joinToString(" ").trimStart('*') != apkName)
        ) throw IOException("Invalid release SHA-256 checksum file")
        return hash
    }

    fun download(release: Release, directory: File, progress: (Int) -> Unit): File =
        downloadWithDetails(release, directory) { percent, _ -> progress(percent) }

    fun downloadWithDetails(release: Release, directory: File,
                            progress: (Int, Long) -> Unit): File {
        val expected = checksum(release)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create update cache")
        val finished = File(directory, release.apkName)
        val temporary = File(directory, release.apkName + ".part")
        temporary.delete()
        try {
            withDownloadFallback(release.apkUrl) { assetUrl ->
            val connection = connect(assetUrl)
            try {
                val messageDigest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > MAX_APK_BYTES || count > release.declaredSize) {
                                throw IOException("APK exceeds its declared size")
                            }
                            messageDigest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            progress((count * 100L / release.declaredSize).toInt().coerceIn(0, 100), count)
                        }
                    }
                }
                val actual = messageDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (count != release.declaredSize || actual != expected) {
                    throw IOException("Downloaded APK size or SHA-256 does not match the published release")
                }
            } finally {
                connection.disconnect()
            }
            }
            if (finished.exists() && !finished.delete()) throw IOException("Cannot replace cached update")
            if (!temporary.renameTo(finished)) throw IOException("Cannot finalize the downloaded APK")
            return finished
        } finally {
            temporary.delete()
        }
    }

    private fun readText(url: String, limit: Int): String {
        val connection = connect(url)
        try {
            connection.inputStream.use { stream ->
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (data.size() + read > limit) throw IOException("Update metadata is too large")
                    data.write(buffer, 0, read)
                }
                return data.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Android 4.4 vendor ROMs can lack both TLS 1.2 and modern public CA roots. */
    @Volatile private var appContext: android.content.Context? = null

    fun configure(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private data class LegacyTls(val factory: SSLSocketFactory, val trust: X509TrustManager)

    private val legacyTls by lazy {
        val context = appContext ?: throw IOException("Updater trust store not initialized")
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply { load(null) }
        context.resources.openRawResource(R.raw.update_ca_bundle).use { stream ->
            // Android 4.4's generateCertificates() returns empty (or fails) on multi-cert PEMs
            // with '#' comment lines. Parse each PEM block individually and skip bad ones.
            val pem = stream.readBytes().toString(Charsets.UTF_8)
            val blocks = pem.split("-----BEGIN CERTIFICATE-----").drop(1)
            var loaded = 0
            blocks.forEachIndexed { index, block ->
                val end = block.indexOf("-----END CERTIFICATE-----")
                if (end < 0) return@forEachIndexed
                val certPem = "-----BEGIN CERTIFICATE-----" + block.substring(0, end) +
                    "-----END CERTIFICATE-----\n"
                try {
                    val cert = factory.generateCertificate(certPem.byteInputStream())
                    store.setCertificateEntry("update-root-$index", cert)
                    loaded++
                } catch (_: Exception) {
                    // Skip unparseable certificates on old Android; keep the rest.
                }
            }
            if (loaded == 0) throw IOException("Bundled update trust roots are empty")
        }
        val trust = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
        )
        trust.init(store)
        val manager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
        // TLSv1.2 is mandatory for GitHub. Never fall back to SSLv3 or relax
        // hostname / certificate validation for old vendor head units.
        val tlsFactory = javax.net.ssl.SSLContext.getInstance(
            "TLSv1.2", org.conscrypt.Conscrypt.newProvider(),
        ).apply { init(null, arrayOf(manager), null) }.socketFactory
        LegacyTls(tlsFactory, manager)
    }

    /**
     * Some API 19 vendor frameworks feed an SSLv3-only ConnectionSpec to their
     * system HttpsURLConnection even with a modern Conscrypt socket factory.
     * Use a separate API 19-capable HTTP stack with an explicit TLS 1.2 spec.
     * Manual allowlisted redirect handling below remains unchanged.
     */
    internal fun legacyTlsSpec(): ConnectionSpec =
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .build()

    private val legacyHttp by lazy {
        val tls = legacyTls
        @Suppress("DEPRECATION")
        OkUrlFactory(OkHttpClient.Builder()
            .sslSocketFactory(tls.factory, tls.trust)
            .connectionSpecs(listOf(legacyTlsSpec()))
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build())
    }

    internal fun isObsoleteSslv3Failure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { cause ->
            val message = cause.message.orEmpty()
            message.contains("No enabled protocols", ignoreCase = true) &&
                message.contains("SSLv3", ignoreCase = true)
        }

    private fun configureRequest(connection: HttpsURLConnection, method: String) {
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "DiPlay-CS75Plus-2020-Updater")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun connect(url: String, method: String = "GET"): HttpsURLConnection {
        var next = url
        repeat(7) {
            val candidate = URL(next)
            val host = candidate.host.lowercase()
            val approvedHost = host == "api.github.com" || host == "github.com" ||
                host == "release-assets.githubusercontent.com" ||
                host == "objects.githubusercontent.com" || host == "ghproxy.net"
            if (candidate.protocol != "https" || !approvedHost) {
                throw IOException("Untrusted update redirect")
            }
            var connection = if (android.os.Build.VERSION.SDK_INT < 22) {
                legacyHttp.open(candidate) as HttpsURLConnection
            } else {
                candidate.openConnection() as HttpsURLConnection
            }
            configureRequest(connection, method)
            val status = try {
                connection.responseCode
            } catch (failure: IOException) {
                connection.disconnect()
                // OEM Android 5+ frameworks can also expose an SSLv3-only
                // TLS fallback; retry that exact failure with bundled Conscrypt.
                // Do not retry certificate, hostname, or other handshake errors.
                if (android.os.Build.VERSION.SDK_INT < 22 || !isObsoleteSslv3Failure(failure)) {
                    throw failure
                }
                android.util.Log.w("DiPlayUpdate", "System TLS offered only SSLv3; retrying TLS 1.2")
                connection = legacyHttp.open(candidate) as HttpsURLConnection
                configureRequest(connection, method)
                try {
                    connection.responseCode
                } catch (retryFailure: IOException) {
                    connection.disconnect()
                    throw retryFailure
                }
            }
            if (status in listOf(301, 302, 303, 307, 308)) {
                val redirect = connection.getHeaderField("Location")
                    ?: throw IOException("Update redirect has no target")
                next = URL(candidate, redirect).toString()
                connection.disconnect()
            } else {
                if (status != 200) {
                    connection.disconnect()
                    throw IOException("Update server returned HTTP $status")
                }
                return connection
            }
        }
        throw IOException("Too many update redirects")
    }
}
