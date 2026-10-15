package com.shihab.diplay.core

/**
 * Single source of truth for update / release endpoints.
 *
 * Consolidated in P2 from scattered constants previously duplicated across
 * [com.shihab.diplay.DiPlayAtomSource], [com.shihab.diplay.DiPlayGiteeReleases]
 * and [com.shihab.diplay.DiPlayUpdateClient]. Values are unchanged — only centralised.
 */
internal object UpdateEndpoints {
    /** Official GitHub repo (`owner/name`) for DiPlay release metadata and assets. */
    const val GITHUB_REPO = "chansLam/DiPlay-CS75Plus-2020"

    /** China-accessible Gitee mirror repo (`owner/name`). */
    const val GITEE_REPO = "JingRuirui/DiPlay-Legacy-Android"

    const val GITHUB_RELEASE_TAG_URL = "https://github.com/$GITHUB_REPO/releases/tag"
    const val GITHUB_RELEASES_ATOM = "https://github.com/$GITHUB_REPO/releases.atom"
    const val GITHUB_RELEASE_DOWNLOAD = "https://github.com/$GITHUB_REPO/releases/download"
    const val GITHUB_API_LATEST = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    const val GITEE_API = "https://gitee.com/api/v5/repos/$GITEE_REPO"
    const val GITEE_DOWNLOADS = "https://gitee.com/$GITEE_REPO/releases/download/"

    /**
     * Domestic GitHub release-asset relays, tried in order by the download fallback,
     * with the canonical source last. SHA-256 still guards integrity.
     */
    fun githubDownloadCandidates(assetUrl: String): List<String> {
        if (!assetUrl.startsWith("$GITHUB_RELEASE_DOWNLOAD/")) return listOf(assetUrl)
        return listOf(
            "https://ghproxy.net/$assetUrl",
            "https://ghfast.top/$assetUrl",
            "https://gh-proxy.com/$assetUrl",
            "https://mirror.ghproxy.com/$assetUrl",
            assetUrl,
        ).distinct()
    }
}
