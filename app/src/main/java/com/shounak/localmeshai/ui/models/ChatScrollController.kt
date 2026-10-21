package com.shounak.localmeshai.ui.models

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

class ChatScrollController(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val reduceMotion: () -> Boolean = { false }
) {
    var followLatest by mutableStateOf(true)
        private set

    private var activeScrollJob: Job? = null
    private var activeScrollJobId: Long = 0L
    private var lastSessionKey: String? = null
    private var lastScrollTime = 0L

    fun bindConversation(sessionId: String?, mode: String) {
        val currentKey = "$sessionId:$mode"
        if (lastSessionKey != currentKey) {
            lastSessionKey = currentKey
            followLatest = true
            activeScrollJobId++
            activeScrollJob?.cancel()
            activeScrollJob = null
        }
    }

    suspend fun observeUserInteractions() {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                // User explicitly dragged the list with finger: immediately cancel any active programmatic scroll and pause auto-follow
                activeScrollJobId++
                activeScrollJob?.cancel()
                activeScrollJob = null
                followLatest = false
            }
        }
    }

    suspend fun observeScrollPosition() {
        snapshotFlow { !listState.canScrollForward }
            .collect { isAtBottom ->
                if (activeScrollJob?.isActive == true) return@collect
                if (isAtBottom) {
                    followLatest = true
                }
            }
    }

    fun scrollToTop() {
        followLatest = false
        activeScrollJob?.cancel()
        val currentJobId = ++activeScrollJobId
        activeScrollJob = scope.launch {
            try {
                if (!reduceMotion()) listState.animateScrollToItem(0)
                listState.scrollToItem(0)
            } finally {
                if (activeScrollJobId == currentJobId) {
                    activeScrollJob = null
                }
            }
        }
    }

    fun scrollToBottom(anchorIndex: Int) {
        followLatest = true
        activeScrollJob?.cancel()
        val currentJobId = ++activeScrollJobId
        activeScrollJob = scope.launch {
            if (anchorIndex >= 0) {
                try {
                    if (reduceMotion()) listState.scrollToItem(anchorIndex) else listState.animateScrollToItem(anchorIndex)
                    listState.scrollToItem(anchorIndex)
                } finally {
                    if (activeScrollJobId == currentJobId) {
                        activeScrollJob = null
                    }
                }
            } else {
                if (activeScrollJobId == currentJobId) {
                    activeScrollJob = null
                }
            }
        }
    }

    fun onNewMessageAdded(anchorIndex: Int) {
        if (followLatest && anchorIndex >= 0) {
            activeScrollJob?.cancel()
            val currentJobId = ++activeScrollJobId
            activeScrollJob = scope.launch {
                try {
                    if (reduceMotion()) listState.scrollToItem(anchorIndex) else listState.animateScrollToItem(anchorIndex)
                } finally {
                    if (activeScrollJobId == currentJobId) {
                        activeScrollJob = null
                    }
                }
            }
        }
    }

    suspend fun followStreaming(isStreaming: Boolean, getAnchorIndex: () -> Int) {
        if (!isStreaming) return
        snapshotFlow {
            val anchor = getAnchorIndex()
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            Triple(anchor, last?.offset ?: 0, last?.size ?: 0)
        }
        .conflate()
        .collect { (anchorIndex, _, _) ->
            if (activeScrollJob?.isActive == true) return@collect
            if (anchorIndex > 0 && followLatest) {
                val now = android.os.SystemClock.uptimeMillis()
                val elapsed = now - lastScrollTime
                if (elapsed < 100L) {
                    delay(100L - elapsed)
                }
                if (activeScrollJob?.isActive == true) return@collect
                if (followLatest && anchorIndex > 0) {
                    val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                    if (lastVisibleItem != null && lastVisibleItem.index >= anchorIndex - 2) {
                        val layout = listState.layoutInfo
                        val overflow = (lastVisibleItem.offset + lastVisibleItem.size - layout.viewportEndOffset + layout.afterContentPadding).coerceAtLeast(0)
                        try {
                            if (reduceMotion()) listState.scrollToItem(anchorIndex)
                            else if (overflow > 0) listState.animateScrollBy(overflow.toFloat(), tween(80, easing = LinearEasing))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            // A touch gesture can interrupt the scroll without cancelling this observer.
                            currentCoroutineContext().ensureActive()
                        }
                        lastScrollTime = android.os.SystemClock.uptimeMillis()
                    }
                }
            }
        }
    }

    suspend fun onStreamingEnded(getAnchorIndex: () -> Int) {
        if (activeScrollJob?.isActive == true) return
        if (followLatest) {
            val anchor = getAnchorIndex()
            if (anchor >= 0) {
                kotlinx.coroutines.yield()
                listState.scrollToItem(anchor)
            }
        }
    }
}

@Composable
fun rememberChatScrollController(
    listState: LazyListState,
    scope: CoroutineScope = rememberCoroutineScope()
): ChatScrollController {
    val reduced by rememberUpdatedState(com.shounak.localmeshai.features.LocalWorkspaceVisuals.current.reduceMotion)
    return remember(listState, scope) {
        ChatScrollController(listState, scope) { reduced }
    }
}
