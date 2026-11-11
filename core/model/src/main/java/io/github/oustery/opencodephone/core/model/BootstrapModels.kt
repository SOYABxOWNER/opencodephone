package io.github.oustery.opencodephone.core.model

/**
 * Phases of the one-time environment bootstrap.
 */
enum class BootstrapPhase {
    NOT_STARTED,
    DOWNLOADING_ROOTFS,
    EXTRACTING_ROOTFS,
    INSTALLING_TOOLS,
    INSTALLING_OPENCODE,
    CONFIGURING,
    READY,
    FAILED,
}

data class BootstrapState(
    val phase: BootstrapPhase = BootstrapPhase.NOT_STARTED,
    val label: String = "",
    /** 0..1 for determinate phases, null for indeterminate. */
    val progress: Float? = null,
    val message: String = "",
    val error: String? = null,
    /** Set on failure: the phase that was running when it broke. */
    val failedPhase: BootstrapPhase? = null,
    /** Recent log lines for on-screen diagnostics (newest last). */
    val log: List<String> = emptyList(),
)

/**
 * Device ABIs the app supports, mapped to Debian and opencode targets.
 */
enum class DeviceAbi(
    val androidAbi: String,
    val debianArch: String,
    /** npm target for the opencode CLI package, null when unsupported. */
    val opencodeTarget: String?,
) {
    ARM64("arm64-v8a", "arm64", "linux-arm64"),
    ARM32("armeabi-v7a", "arm", null),
    X86_64("x86_64", "amd64", "linux-x64");

    companion object {
        /** Resolve from the primary ABI reported by the device, null if unsupported. */
        fun fromPrimaryAbi(primary: String): DeviceAbi? =
            entries.firstOrNull { it.androidAbi == primary }
    }
}

/**
 * Pinned external component versions and digests.
 *
 * Rationale is recorded in docs/adr/ADR-003 and docs/research/01-opencode-platform.md.
 */
object Pins {
    const val DEBIAN_SUITE: String = "trixie"
    const val DOCKER_REPO: String = "library/debian"

    /**
     * Per-architecture manifest digests for debian:trixie (docker.io/library/debian),
     * fetched from the registry index at development time (2026-11-08).
     * Pinning a digest makes every download reproducible and verifiable:
     * the layer blob digest inside the manifest is itself the checksum.
     */
    val ROOTFS_MANIFEST_DIGESTS: Map<String, String> = mapOf(
        "amd64" to "sha256:bfc36aecdece0387418759dba474aa856c026ca8087da3606b41b90ab0509c99",
        "arm64" to "sha256:e529717ccc8118a0b9d38525f2b4e78cf150dbe470ad60d733c7c0023cabc3b0",
        "arm" to "sha256:4220a655ef92cf9f4bea07e6dc36b55193268feb1176b54e955e05027cf18af2",
    )

    /** OpenCode V1 CLI version matching the HTTP client, installed into the environment at bootstrap. */
    const val OPENCODE_VERSION: String = "1.19.5"
    const val OPENCODE_NPM_PACKAGE_PREFIX: String = "opencode-"

    /** Android-compatible static PRoot (Termux fork; see tools/build_proot.py). */
    const val PROOT_VERSION: String = "termux-52d65a4dc140-android2"

    /**
     * Pre-resolved toolset tarballs (git, curl, ripgrep, less, procps, jq,
     * fd-find, tree, zip, unzip, python3 and their dependency closure)
     * published as assets of a dedicated toolset release. Downloaded and
     * extracted app-side so bootstrap never has to run apt/dpkg inside proot
     * (Android 12+ kills high-CPU child processes — see ADR-008).
     */
    const val TOOLSET_URL_PREFIX: String =
        "https://github.com/oustery/opencodephone/releases/download/toolset-v1/toolset-"
    const val TOOLSET_URL_SUFFIX: String = ".tar.gz"

    /** sha256 of each toolset tarball, keyed by Debian architecture. */
    val TOOLSET_SHA256: Map<String, String> = mapOf(
        "arm64" to "d965e982a147854ec1ed22690437d9657bbf501c8668432589b76fc627cbdc14",
        "arm" to "e30509886e141bbc2dd05042a3b4d8f065d1b118e03262adf7f4851cb3f22acc",
        "amd64" to "7a498476f70314436ed824ecdc84c294e3194d2d1dac556b3fd560afb7374106",
    )
}
