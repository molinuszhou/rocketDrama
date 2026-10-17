package com.dramaflow.app.rewards

/**
 * Process-local coordination for one Rewards service user. It deliberately has no Android or
 * networking dependency so every [RewardsApiClient] instance can use the same renewal flight.
 */
internal object RewardsSessionCoordinator {
    data class Key(val baseUrl: String, val userId: String)

    private data class RecoveryWaiter(
        val onReady: (String) -> Unit,
        val onFailure: (String) -> Unit,
    )

    private data class State(
        var token: String? = null,
        var region: String? = null,
        var recovering: Boolean = false,
        val waiters: MutableList<RecoveryWaiter> = mutableListOf(),
        val tokenListeners: MutableSet<(String) -> Unit> = linkedSetOf(),
    )

    private val lock = Any()
    private val states = mutableMapOf<Key, State>()

    fun tokenFor(key: Key): String? = synchronized(lock) {
        states[key]?.token?.takeIf { it.isNotBlank() }
    }

    /** Persisted tokens are only adopted when this process has not already received a newer one. */
    fun adoptTokenIfAbsent(key: Key, token: String?) {
        val validToken = token?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            val state = states.getOrPut(key, ::State)
            if (state.token.isNullOrBlank()) state.token = validToken
        }
    }

    fun cacheRegion(key: Key, region: String?) {
        region?.trim()?.takeIf { it.isNotBlank() }?.let { validRegion ->
            synchronized(lock) { states.getOrPut(key, ::State).region = validRegion }
        }
    }

    fun cachedRegion(key: Key): String? = synchronized(lock) { states[key]?.region }

    /**
     * Starts at most one asynchronous bootstrap. A 401 for an older token immediately reuses the
     * token already published by another client instead of starting a stale second renewal.
     */
    fun recover(
        key: Key,
        failedToken: String?,
        startBootstrap: () -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        var readyToken: String? = null
        var shouldStart = false
        synchronized(lock) {
            val state = states.getOrPut(key, ::State)
            val currentToken = state.token?.takeIf { it.isNotBlank() }
            if (currentToken != null && currentToken != failedToken) {
                readyToken = currentToken
            } else {
                readyToken = null
                state.waiters += RecoveryWaiter(onReady, onFailure)
                if (!state.recovering) {
                    state.recovering = true
                    shouldStart = true
                }
            }
        }
        if (readyToken != null) {
            onReady(readyToken)
        } else if (shouldStart) {
            runCatching(startBootstrap).onFailure { error ->
                failRecovery(key, "会话续签启动失败：${error.message ?: "未知错误"}")
            }
        }
    }

    /** Publishes a newly signed token to all waiters and pending raw-attribution owners. */
    fun publishToken(key: Key, token: String) {
        if (token.isBlank()) return
        val waiters: List<RecoveryWaiter>
        val listeners: List<(String) -> Unit>
        synchronized(lock) {
            val state = states.getOrPut(key, ::State)
            state.token = token
            state.recovering = false
            waiters = state.waiters.toList()
            state.waiters.clear()
            listeners = state.tokenListeners.toList()
        }
        waiters.forEach { it.onReady(token) }
        listeners.forEach { it(token) }
    }

    fun failRecovery(key: Key, message: String) {
        val waiters = synchronized(lock) {
            val state = states.getOrPut(key, ::State)
            state.recovering = false
            state.waiters.toList().also { state.waiters.clear() }
        }
        waiters.forEach { it.onFailure(message) }
    }

    /** Listeners receive only future publications; callers can separately inspect [tokenFor]. */
    fun addTokenListener(key: Key, listener: (String) -> Unit) {
        synchronized(lock) { states.getOrPut(key, ::State).tokenListeners += listener }
    }

    fun removeTokenListener(key: Key, listener: (String) -> Unit) {
        synchronized(lock) { states[key]?.tokenListeners?.remove(listener) }
    }
}

/** Pure rules shared by the client and unit-level HTTP simulations. */
internal object RewardsSessionRecoveryPolicy {
    fun isSessionInvalid(statusCode: Int, apiCode: Int?, apiCodeText: String?, message: String?): Boolean =
        statusCode == 401 ||
            apiCode == 1904 ||
            apiCodeText.equals("session_invalid", ignoreCase = true) ||
            message?.contains("session_invalid", ignoreCase = true) == true

    fun canReplayAfterSessionRecovery(authReplayRemaining: Int): Boolean = authReplayRemaining > 0
}
