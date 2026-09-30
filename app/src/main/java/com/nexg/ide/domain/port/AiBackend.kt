package com.nexg.ide.domain.port

import com.nexg.ide.domain.model.AiEvent
import com.nexg.ide.domain.model.AiRequest
import com.nexg.ide.domain.model.BackendHealth
import kotlinx.coroutines.flow.Flow

/**
 * The AI provider seam (PLAN.MD 4.9, resolves decision C2).
 *
 * `GeminiBackend` is the Tier 1 implementation; a local `OpenCodeBackend`
 * and a `CloudProxyBackend` can be added without touching a single UI call
 * site, which is the entire point of the interface. UI → Application →
 * Domain, and no detail of the transport (HTTP, SSE, JSON) leaks past this
 * boundary in either direction.
 */
interface AiBackend {
    val id: String

    /** Whether [complete] performs network I/O (Phase 1 gating even before
     *  the model is called: prompting a backend that needs the network without
     *  any network is a worse lie than saying so up front). */
    val requiresNetwork: Boolean

    suspend fun health(): BackendHealth

    /**
     * Streams one completion as [AiEvent]s.
     *
     * Contract: the flow must terminate on its own — a [AiEvent.Done] (with a
     * non-null finish reason), a [AiEvent.Error], or by being cancelled. It
     * must never hang. An implementation that is not configured returns a
     * single `Error` without touching the network, so a caller cannot
     * distinguish "not configured" from "network is down" by accident.
     */
    suspend fun complete(request: AiRequest): Flow<AiEvent>
}