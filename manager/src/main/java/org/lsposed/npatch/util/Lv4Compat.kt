package org.lsposed.npatch.util

/**
 * Apps that ship their OWN seccomp-bpf sandbox (Gecko / Chromium engines) conflict with
 * lv4's SvcBypass seccomp filter: NPatch's trusted-thread re-executes trapped syscalls, but
 * the app's own restrictive filter denies them (EPERM), crashing the app shortly after inject.
 *
 * lv3 (PM hook + native openat PLT hook + Java IO redirect) has no seccomp and works for these
 * apps — they read their apk through libc, which the PLT hook already covers. lv4 only adds
 * coverage for direct syscalls that bypass libc, which browsers don't do.
 *
 * This is a hint list for known offenders (mostly browsers). It is intentionally NOT
 * exhaustive — the manage page also offers "re-sign with lv3" on EVERY lv4 app so the user can
 * downgrade any app they discover to be incompatible.
 */
object Lv4Compat {

    // Exact package names known to embed a Gecko/Chromium sandbox.
    private val knownIncompatible = setOf(
        // Chrome / Chromium
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx",          // Edge
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.gx",
        "com.opera.mini.native",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.yandex.browser",
        "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser", // Samsung Internet
        "org.bromite.bromite",
        "org.cromite.cromite",
        "org.chromium.chrome",
        // Gecko / Firefox family
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "org.mozilla.rocket",
        "org.torproject.torbrowser",
        "org.torproject.torbrowser_alpha",
        "us.spotco.fennec_dos",         // Mull
        "io.github.forkmaintainers.iceraven",
    )

    // Package prefixes so browser forks / channels are covered without listing each one.
    private val incompatiblePrefixes = listOf(
        "org.mozilla.",
        "com.opera.",
    )

    fun isIncompatibleWithLv4(packageName: String): Boolean {
        if (packageName in knownIncompatible) return true
        return incompatiblePrefixes.any { packageName.startsWith(it) }
    }
}
