package com.dramaflow.app.rewards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardsSessionCoordinatorTest {
    @Test
    fun `并发失效会话只启动一次续签并通知全部等待者`() {
        val key = uniqueKey("single-flight")
        var bootstrapCount = 0
        val readyTokens = mutableListOf<String>()

        RewardsSessionCoordinator.recover(
            key = key,
            failedToken = "expired-token",
            startBootstrap = { bootstrapCount += 1 },
            onReady = { readyTokens += "first:$it" },
            onFailure = { throw AssertionError(it) },
        )
        RewardsSessionCoordinator.recover(
            key = key,
            failedToken = "expired-token",
            startBootstrap = { bootstrapCount += 1 },
            onReady = { readyTokens += "second:$it" },
            onFailure = { throw AssertionError(it) },
        )

        assertEquals(1, bootstrapCount)
        RewardsSessionCoordinator.publishToken(key, "renewed-token")
        assertEquals(listOf("first:renewed-token", "second:renewed-token"), readyTokens)
    }

    @Test
    fun `延迟旧401复用新令牌而不覆盖或二次续签`() {
        val key = uniqueKey("stale-401")
        RewardsSessionCoordinator.publishToken(key, "newer-token")
        var bootstrapCount = 0
        var replayToken: String? = null

        RewardsSessionCoordinator.recover(
            key = key,
            failedToken = "older-token",
            startBootstrap = { bootstrapCount += 1 },
            onReady = { replayToken = it },
            onFailure = { throw AssertionError(it) },
        )

        assertEquals(0, bootstrapCount)
        assertEquals("newer-token", replayToken)
        assertEquals("newer-token", RewardsSessionCoordinator.tokenFor(key))
    }

    @Test
    fun `令牌发布通知归因等待者且移除后不再重复通知`() {
        val key = uniqueKey("attribution")
        val received = mutableListOf<String>()
        val listener: (String) -> Unit = { received += it }

        RewardsSessionCoordinator.addTokenListener(key, listener)
        RewardsSessionCoordinator.publishToken(key, "first-token")
        RewardsSessionCoordinator.removeTokenListener(key, listener)
        RewardsSessionCoordinator.publishToken(key, "second-token")

        assertEquals(listOf("first-token"), received)
    }

    @Test
    fun `HTTP模拟保留原事件并且认证恢复后仅重放一次`() {
        val body = "{\"user_id\":\"u1\",\"event_id\":\"stable-event\"}"
        val http = SimulatedProtectedHttp(sessionInvalidResponses = 2)

        val completed = http.post(body, "old-token", authReplayRemaining = 1) { "new-token" }

        assertFalse(completed)
        assertEquals(listOf(body, body), http.bodies)
        assertEquals(listOf("old-token", "new-token"), http.tokens)
        assertEquals(1, http.authenticationReplays)
        assertTrue(RewardsSessionRecoveryPolicy.isSessionInvalid(401, 1904, null, null))
        assertFalse(RewardsSessionRecoveryPolicy.canReplayAfterSessionRecovery(0))
    }

    private fun uniqueKey(prefix: String) = RewardsSessionCoordinator.Key(
        baseUrl = "https://rewards.test",
        userId = "$prefix-${System.nanoTime()}",
    )

    /** 纯 HTTP 行为模拟：不依赖 Android、OkHttp 或真实网络。 */
    private class SimulatedProtectedHttp(private var sessionInvalidResponses: Int) {
        val bodies = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        var authenticationReplays = 0
            private set

        fun post(
            body: String,
            token: String,
            authReplayRemaining: Int,
            renewSession: () -> String,
        ): Boolean {
            bodies += body
            tokens += token
            if (sessionInvalidResponses-- <= 0) return true
            if (!RewardsSessionRecoveryPolicy.canReplayAfterSessionRecovery(authReplayRemaining)) return false
            authenticationReplays += 1
            return post(body, renewSession(), authReplayRemaining - 1, renewSession)
        }
    }
}
