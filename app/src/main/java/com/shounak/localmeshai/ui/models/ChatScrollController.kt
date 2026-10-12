package com.shounak.localmeshai.ui.models

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
    private val scope: CoroutineScope
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
                listState.animateScrollToItem(0)
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
                    listState.animateScrollToItem(anchorIndex)
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
                    listState.animateScrollToItem(anchorIndex)
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
            val isAtBottom = !listState.canScrollForward
            Pair(anchor, isAtBottom)
        }
        .conflate()
        .collect { (anchorIndex, _) ->
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
                        listState.scrollToItem(anchorIndex)
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
    return remember(listState) {
        ChatScrollController(listState, scope)
    }
}
