package com.shounak.localmeshai.ai

import com.google.ai.edge.litertlm.Engine

/**
 * LiteRT rejects close() before initialize() succeeds, and after a successful close().
 * Its handle remains initialized if native deletion throws, so real shutdown failures
 * still propagate and the manager retains ownership for another cleanup attempt.
 */
internal fun Engine.closeIfInitialized() {
    if (isInitialized()) close()
}
