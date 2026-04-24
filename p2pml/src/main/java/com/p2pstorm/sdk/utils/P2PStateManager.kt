package com.p2pstorm.sdk.utils

import com.p2pstorm.sdk.logger.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class P2PStateManager {
    private var isEngineDisabled = false
    private val mutex = Mutex()

    // Circuit breaker
    private var consecutiveFailures = 0
    private var circuitBreakerTripped = false
    private var circuitBreakerTrippedAt = 0L
    private val maxConsecutiveFailures = 5
    private val circuitBreakerCooldownMs = 60_000L // 1 minute cooldown

    suspend fun isEngineDisabled(): Boolean =
        mutex.withLock {
            if (circuitBreakerTripped) {
                val elapsed = System.currentTimeMillis() - circuitBreakerTrippedAt
                if (elapsed > circuitBreakerCooldownMs) {
                    // Try to recover
                    circuitBreakerTripped = false
                    consecutiveFailures = 0
                    Logger.d(TAG, "Circuit breaker reset after cooldown")
                }
            }
            isEngineDisabled || circuitBreakerTripped
        }

    suspend fun recordP2PSuccess() =
        mutex.withLock {
            consecutiveFailures = 0
        }

    suspend fun recordP2PFailure() =
        mutex.withLock {
            consecutiveFailures++
            if (consecutiveFailures >= maxConsecutiveFailures && !circuitBreakerTripped) {
                circuitBreakerTripped = true
                circuitBreakerTrippedAt = System.currentTimeMillis()
                Logger.w(TAG, "Circuit breaker tripped after $consecutiveFailures consecutive P2P failures. Cooldown ${circuitBreakerCooldownMs / 1000}s")
            }
        }

    suspend fun changeP2PEngineStatus(isP2PDisabled: Boolean) =
        mutex.withLock {
            if (isP2PDisabled == isEngineDisabled) return@withLock
            isEngineDisabled = isP2PDisabled
        }

    suspend fun reset() =
        mutex.withLock {
            isEngineDisabled = false
            consecutiveFailures = 0
            circuitBreakerTripped = false
        }

    companion object {
        private const val TAG = "P2PStateManager"
    }
}
