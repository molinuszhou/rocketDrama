package com.dramaflow.app.presentation

/**
 * Protects a requested PSSDK resume position while its first lifecycle callbacks still report 0.
 * The provider emits `onShortPlayPlayed` before its optional start-time seek has fully settled.
 */
internal data class SdkResumeCheckpoint(
    val episodeIndex: Int,
    val positionSeconds: Int,
) {
    init {
        require(episodeIndex >= 0)
        require(positionSeconds > 0)
    }

    fun matches(episodeIndex: Int): Boolean = this.episodeIndex == episodeIndex

    /** Do not visually regress to zero while the provider is applying its requested seek. */
    fun displayedProgress(providerProgressSeconds: Int): Int = maxOf(positionSeconds, providerProgressSeconds.coerceAtLeast(0))

    /** Persistence may resume only after the provider has actually reached the requested offset. */
    fun isConfirmedBy(providerProgressSeconds: Int): Boolean = providerProgressSeconds >= positionSeconds
}
