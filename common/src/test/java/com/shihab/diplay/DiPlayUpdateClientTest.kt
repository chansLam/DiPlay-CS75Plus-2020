package com.shihab.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiPlayUpdateClientTest {
    private val name = "DiPlay-v1.0.0-release.apk"
    private val prefix = "https://github.com/chansLam/DiPlay-CS75Plus-2020/releases/download/v1.0.0/"

    @Test fun legacyHttpOnlyAllowsTls12NeverSsl3() {
        val spec = DiPlayUpdateClient.legacyTlsSpec()
        assertTrue(spec.isTls)
        assertEquals(listOf(okhttp3.TlsVersion.TLS_1_2), spec.tlsVersions())
        assertFalse(spec.tlsVersions()!!.contains(okhttp3.TlsVersion.SSL_3_0))
    }

    @Test fun retriesOnlyWhenVendorForcesDisabledSslv3() {
        val disabled = javax.net.ssl.SSLHandshakeException(
            "No enabled protocols; SSLv3 is no longer supported and was filtered from the list")
        assertTrue(DiPlayUpdateClient.isObsoleteSslv3Failure(disabled))
        assertTrue(DiPlayUpdateClient.isObsoleteSslv3Failure(
            java.io.IOException("wrapped", disabled)))
        assertFalse(DiPlayUpdateClient.isObsoleteSslv3Failure(
            javax.net.ssl.SSLHandshakeException("Certificate chain validation failed")))
        assertFalse(DiPlayUpdateClient.isObsoleteSslv3Failure(
            java.net.SocketTimeoutException("Read timed out")))
    }

    @Test fun githubApkPrefersDomesticRelayWithDirectFallback() {
        val url = prefix + name
        assertEquals(
            listOf(
                "https://ghproxy.net/$url",
                "https://ghfast.top/$url",
                "https://gh-proxy.com/$url",
                "https://mirror.ghproxy.com/$url",
                url,
            ),
            DiPlayUpdateClient.downloadCandidates(url),
        )
        assertEquals(listOf("https://example.com/test.apk"), DiPlayUpdateClient.downloadCandidates("https://example.com/test.apk"))
    }

    @Test fun acceptsOnlyHigherStableVersion() {
        assertTrue(DiPlayUpdateClient.newerVersion("v0.2.34", "0.2.33"))
        assertTrue(DiPlayUpdateClient.newerVersion("v1.0.0", "0.9.99"))
        assertTrue(DiPlayUpdateClient.newerVersion("v0.2.40.1", "0.2.40.0"))
        assertTrue(DiPlayUpdateClient.newerVersion("v0.2.40.1-cs75plus", "0.2.40.0"))
        assertFalse(DiPlayUpdateClient.newerVersion("v0.2.33", "0.2.33"))
        assertFalse(DiPlayUpdateClient.newerVersion("v0.2.32", "0.2.33"))
        assertFalse(DiPlayUpdateClient.newerVersion("v0.2.34-beta", "0.2.33"))
        assertFalse(DiPlayUpdateClient.newerVersion("vabc", "0.2.33"))
    }

    @Test fun discoversReleaseOnlyWithMatchingApkAndChecksumAssets() {
        val release = DiPlayUpdateClient.parseLatest(json(), "0.2.46")
        assertEquals("v1.0.0", release?.tag)
        assertEquals(name, release?.apkName)
        assertEquals(prefix + name, release?.apkUrl)
        assertNull(DiPlayUpdateClient.parseLatest(json(), "1.0.0"))
        assertNull(DiPlayUpdateClient.parseLatest(json(prerelease = true), "0.2.46"))
    }

    @Test fun rejectsMissingOrExternalReleaseAssets() {
        try {
            DiPlayUpdateClient.parseLatest(json(shaName = "different.sha256"), "0.2.46")
            throw AssertionError("Missing checksum should fail")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("SHA-256"))
        }
        try {
            DiPlayUpdateClient.parseLatest(json(apkUrl = "https://malicious.example/$name"), "0.2.46")
            throw AssertionError("External asset should fail")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("APK"))
        }
    }

    @Test fun checksumAcceptsOnlyValidDigestForExpectedApk() {
        val hash = "a".repeat(64)
        assertEquals(hash, DiPlayUpdateClient.parseChecksum("$hash  $name\n", name))
        assertEquals(hash, DiPlayUpdateClient.parseChecksum(hash, name))
        for (bad in listOf("abcd  $name", "$hash  different.apk", "a".repeat(65))) {
            try {
                DiPlayUpdateClient.parseChecksum(bad, name)
                throw AssertionError("Expected rejection of invalid digest: $bad")
            } catch (_: java.io.IOException) { }
        }
    }

    private fun json(
        prerelease: Boolean = false,
        shaName: String = "$name.sha256",
        apkUrl: String = prefix + name,
    ): String = org.json.JSONObject().apply {
        put("tag_name", "v1.0.0")
        put("prerelease", prerelease)
        put("draft", false)
        put("body", "A stable update")
        put("assets", org.json.JSONArray().apply {
            put(org.json.JSONObject().put("name", name).put("size", 1024)
                .put("browser_download_url", apkUrl))
            put(org.json.JSONObject().put("name", shaName).put("size", 100)
                .put("browser_download_url", prefix + shaName))
        })
    }.toString()
}
