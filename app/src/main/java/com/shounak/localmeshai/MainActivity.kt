package com.shounak.localmeshai
import android.Manifest
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shounak.localmeshai.ui.screens.ChatScreen
import com.shounak.localmeshai.ui.screens.ModelManagerScreen
import com.shounak.localmeshai.ui.theme.LocalMeshAITheme
import com.shounak.localmeshai.ui.viewmodels.MainViewModel
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.GlassDispersionCard
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import com.shounak.localmeshai.utils.LiquidGlassBackdrop
import com.shounak.localmeshai.utils.LiquidGlassBox
import com.shounak.localmeshai.utils.animatedGlassHalo
import com.shounak.localmeshai.utils.fluidReveal
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Bundle the Emoji 16 font so user prompts and model responses render
        // consistently even on phones without a downloadable emoji provider.
        EmojiCompat.init(
            BundledEmojiCompatConfig(this)
                .setReplaceAll(true)
        )
        InitCrashGuard.checkAndRecoverCrash(this)
        lifecycleScope.launch(Dispatchers.IO) {
            LiteRtRuntimeCache.pruneOnStartup(this@MainActivity)
        }
        enableEdgeToEdge()
        preferHighestRefreshRate()
        requestNotificationPermissionIfNeeded()
        setContent {
            LocalMeshAITheme {
                val mainViewModel: MainViewModel = viewModel()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(mainViewModel)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun preferHighestRefreshRate() {
        val bestMode = findBestDisplayMode()
        val bestRefreshRate = bestMode?.refreshRate
            ?: runCatching { windowManager.defaultDisplay.refreshRate }.getOrNull()
            ?: return

        window.attributes = window.attributes.apply {
            preferredRefreshRate = bestRefreshRate
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bestMode != null) {
                preferredDisplayModeId = bestMode.modeId
            }
        }

    }

    @Suppress("DEPRECATION")
    private fun findBestDisplayMode(): Display.Mode? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return windowManager.defaultDisplay.supportedModes.maxWithOrNull { first, second ->
            val refreshCompare = first.refreshRate.compareTo(second.refreshRate)
            if (refreshCompare != 0) {
                refreshCompare
            } else {
                val firstPixels = first.physicalWidth * first.physicalHeight
                val secondPixels = second.physicalWidth * second.physicalHeight
                firstPixels.compareTo(secondPixels)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }
}

sealed class Screen(
    val route: String,
    val label: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    object Chat : Screen("chat", "Chat", "Private Local AI", Icons.Default.AutoAwesome)
    object Models : Screen("models", "Models", "Model Manager", Icons.Default.Memory)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainNavigation(mainViewModel: MainViewModel) {
    val hazeState = remember { HazeState() }
    val items = remember { listOf(Screen.Chat, Screen.Models) }
    var selectedIndex by androidx.compose.runtime.saveable.rememberSaveable {
        mutableIntStateOf(0)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val tabSwipeThresholdPx = with(density) { 64.dp.toPx() }
    val isKeyboardOpen = WindowInsets.isImeVisible
    val (isCompatible, compatibilityMessage) = remember { DeviceUtils.isDeviceCompatible(context) }
    var showRequirementDialog by remember { mutableStateOf(!isCompatible) }

    if (showRequirementDialog) {
        AlertDialog(
            onDismissRequest = { showRequirementDialog = false },
            title = { Text("Phone may not meet requirements") },
            text = {
                Text(
                    "$compatibilityMessage\n\n" +
                    "Local AI models run on the phone GPU and require:\n" +
                    "\u2022 MediaTek Dimensity 7000 series or higher\n" +
                    "\u2022 Qualcomm Snapdragon 6 Gen series or higher\n" +
                    "\u2022 Samsung Exynos 1380 or higher\n" +
                    "\u2022 8 GB nominal RAM\n\n" +
                    "You can still browse and download models, but local inference may not work on this device."
                )
            },
            confirmButton = {
                Button(onClick = { (context as? android.app.Activity)?.finish() }) {
                    Text("Close app")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRequirementDialog = false }) {
                    Text("Continue anyway")
                }
            }
        )
    }

    LiquidGlassBackdrop(hazeState = hazeState) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isKeyboardOpen,
                    enter = androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(180)
                    ) + androidx.compose.animation.slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f)
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(120)
                    ) + androidx.compose.animation.slideOutVertically(
                        targetOffsetY = { it / 2 },
                        animationSpec = spring(dampingRatio = 0.92f, stiffness = 620f)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LiquidBottomNav(
                            items = items,
                            selectedIndex = selectedIndex,
                            hazeState = hazeState,
                            onSelect = { index -> selectedIndex = index }
                        )
                    }
                }
            }
        ) { innerPadding ->
            // Directional tab transition (mirrors both ways):
            // Chat → Models: content enters from the right
            // Models → Chat: content enters from the left (same feel, mirrored)
            // Avoids a horizontal pager, which can leave a fractional-pixel edge strip.
            AnimatedContent(
                targetState = selectedIndex,
                transitionSpec = {
                    val forward = targetState > initialState
                    val enterSpring = spring<IntOffset>(
                        dampingRatio = 0.86f,
                        stiffness = 420f
                    )
                    val exitSpring = spring<IntOffset>(
                        dampingRatio = 0.92f,
                        stiffness = 520f
                    )
                    val enterOffset: (fullWidth: Int) -> Int = { fullWidth ->
                        val delta = (fullWidth * 0.14f).toInt().coerceAtLeast(1)
                        if (forward) delta else -delta
                    }
                    val exitOffset: (fullWidth: Int) -> Int = { fullWidth ->
                        val delta = (fullWidth * 0.10f).toInt().coerceAtLeast(1)
                        if (forward) -delta else delta
                    }
                    (
                        slideInHorizontally(
                            animationSpec = enterSpring,
                            initialOffsetX = enterOffset
                        ) + fadeIn(animationSpec = tween(180)) + scaleIn(
                            initialScale = 0.985f,
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = 420f
                            )
                        )
                    ).togetherWith(
                        slideOutHorizontally(
                            animationSpec = exitSpring,
                            targetOffsetX = exitOffset
                        ) + fadeOut(animationSpec = tween(140)) + scaleOut(
                            targetScale = 0.985f,
                            animationSpec = spring(
                                dampingRatio = 0.92f,
                                stiffness = 520f
                            )
                        )
                    ).using(SizeTransform(clip = false))
                },
                label = "main_tab_content",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .pointerInput(selectedIndex, isKeyboardOpen, tabSwipeThresholdPx) {
                        if (isKeyboardOpen) return@pointerInput
                        var accumulatedDragX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { accumulatedDragX = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                accumulatedDragX += dragAmount
                                change.consume()
                            },
                            onDragCancel = { accumulatedDragX = 0f },
                            onDragEnd = {
                                val targetIndex = when {
                                    accumulatedDragX <= -tabSwipeThresholdPx ->
                                        (selectedIndex + 1).coerceAtMost(items.lastIndex)
                                    accumulatedDragX >= tabSwipeThresholdPx ->
                                        (selectedIndex - 1).coerceAtLeast(0)
                                    else -> selectedIndex
                                }
                                if (targetIndex != selectedIndex) {
                                    selectedIndex = targetIndex
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                accumulatedDragX = 0f
                            }
                        )
                    }
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when (page) {
                        0 -> ChatScreen(mainViewModel = mainViewModel, hazeState = hazeState)
                        1 -> Column(modifier = Modifier.fillMaxSize()) {
                            LiquidTopBar(
                                title = Screen.Models.title,
                                hazeState = hazeState
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                ModelManagerScreen(
                                    mainViewModel = mainViewModel,
                                    hazeState = hazeState
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidBottomNav(
    items: List<Screen>,
    selectedIndex: Int,
    hazeState: HazeState,
    onSelect: (Int) -> Unit
) {
    val density = LocalDensity.current
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var isIndicatorDragging by remember { mutableStateOf(false) }
    var previousSelectedIndex by remember { mutableIntStateOf(selectedIndex) }
    val tabSwitchBounce = remember { Animatable(0f) }
    val contentPaddingPx = with(density) { 10.dp.toPx() }
    val itemGapPx = with(density) { 8.dp.toPx() }
    val contentWidthPx = (containerWidthPx - contentPaddingPx * 2f).coerceAtLeast(0f)
    val itemWidthPx = if (items.isNotEmpty()) {
        (contentWidthPx - itemGapPx * (items.size - 1)) / items.size
    } else {
        0f
    }
    val indicatorInsetPx = with(density) { 4.dp.toPx() }
    val indicatorWidthPx = (itemWidthPx - indicatorInsetPx * 2f).coerceAtLeast(0f)
    val indicatorWidthDp = with(density) { indicatorWidthPx.toDp() }
    val yOffsetPx = with(density) { 2.dp.roundToPx() }
    val switchLiftPx = with(density) { 2.4.dp.toPx() }
    val maxIndicatorOffsetPx = (contentWidthPx - indicatorWidthPx).coerceAtLeast(0f)
    val stepPx = itemWidthPx + itemGapPx
    val baseIndicatorOffsetPx = if (itemWidthPx > 0f) {
        (selectedIndex * stepPx + indicatorInsetPx)
            .coerceIn(0f, maxIndicatorOffsetPx)
    } else {
        0f
    }
    val targetIndicatorOffsetPx = if (itemWidthPx > 0f) {
        (baseIndicatorOffsetPx + dragOffsetPx)
            .coerceIn(0f, (contentWidthPx - indicatorWidthPx).coerceAtLeast(0f))
    } else {
        0f
    }
    val animatedIndicatorOffsetPx by animateFloatAsState(
        targetValue = targetIndicatorOffsetPx,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bottom_nav_indicator_offset"
    )
    val indicatorScale by animateFloatAsState(
        targetValue = if (isIndicatorDragging) 1.045f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bottom_nav_indicator_scale"
    )
    val indicatorOffsetPx = if (isIndicatorDragging) targetIndicatorOffsetPx else animatedIndicatorOffsetPx
    val switchPulse = tabSwitchBounce.value

    LaunchedEffect(selectedIndex) {
        if (!isIndicatorDragging) {
            dragOffsetPx = 0f
        }
        if (previousSelectedIndex != selectedIndex) {
            previousSelectedIndex = selectedIndex
            tabSwitchBounce.snapTo(0f)
            tabSwitchBounce.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            tabSwitchBounce.animateTo(
                0f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    LiquidGlassBox(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .height(84.dp)
            .fluidReveal(delayMillis = 120, initialYOffset = 18.dp)
            .animatedGlassHalo(alpha = 0.055f, durationMillis = 5_200)
            .onSizeChanged { containerWidthPx = it.width.toFloat() }
            .pointerInput(items.size, selectedIndex, containerWidthPx) {
                var dragStartedOnIndicator = false
                detectDragGestures(
                    onDragStart = { offset ->
                        val indicatorLeft = contentPaddingPx + baseIndicatorOffsetPx
                        val indicatorRight = indicatorLeft + indicatorWidthPx
                        dragStartedOnIndicator = itemWidthPx > 0f && offset.x in indicatorLeft..indicatorRight
                        isIndicatorDragging = dragStartedOnIndicator
                    },
                    onDragEnd = {
                        if (dragStartedOnIndicator && itemWidthPx > 0f) {
                            val finalOffsetPx = (baseIndicatorOffsetPx + dragOffsetPx)
                                .coerceIn(0f, maxIndicatorOffsetPx)
                            val rawIndex = ((finalOffsetPx + indicatorWidthPx / 2f) / stepPx)
                                .roundToInt()
                                .coerceIn(0, items.lastIndex)
                            dragOffsetPx = 0f
                            isIndicatorDragging = false
                            onSelect(rawIndex)
                        } else {
                            dragOffsetPx = 0f
                            isIndicatorDragging = false
                        }
                    },
                    onDragCancel = {
                        dragOffsetPx = 0f
                        isIndicatorDragging = false
                    },
                    onDrag = { change, dragAmount ->
                        if (dragStartedOnIndicator) {
                            change.consume()
                            dragOffsetPx += dragAmount.x
                        }
                    }
                )
            },
        hazeState = hazeState,
        cornerRadius = 22.dp,
        refractionHeight = 30.dp,
        dispersion = 0.82f,
        blurRadius = 34.dp,
        animatedCaustics = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            if (containerWidthPx > 0f) {
                GlassDispersionCard(
                    hazeState = hazeState,
                    modifier = Modifier
                        .offset { IntOffset(indicatorOffsetPx.roundToInt(), yOffsetPx) }
                        .width(indicatorWidthDp)
                        .height(64.dp)
                        .graphicsLayer {
                            scaleX = indicatorScale + switchPulse * 0.09f
                            scaleY = (indicatorScale - switchPulse * 0.035f).coerceAtLeast(0.92f)
                            translationY = -switchPulse * switchLiftPx
                        },
                    cornerRadius = 20.dp,
                    blurRadius = 32.dp,
                    refractionStrength = 0.17f,
                    dispersionAmount = 0.032f,
                    tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    borderAlpha = 0.52f,
                    contentPadding = PaddingValues(0.dp),
                    animatedCaustics = true
                )
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, screen ->
                    LiquidTabItem(
                        screen = screen,
                        selected = selectedIndex == index,
                        switchTrigger = selectedIndex,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            dragOffsetPx = 0f
                            onSelect(index)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LiquidTopBar(
    title: String,
    hazeState: HazeState
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassBox(
            modifier = Modifier
                .widthIn(max = 430.dp)
                .fillMaxWidth()
                .height(56.dp)
                .fluidReveal(initialYOffset = 10.dp)
                .animatedGlassHalo(alpha = 0.05f, durationMillis = 4_700),
            hazeState = hazeState,
            cornerRadius = 16.dp,
            refractionHeight = 24.dp,
            dispersion = 0.88f,
            blurRadius = 30.dp,
            animatedCaustics = true
        ) {
            androidx.compose.animation.Crossfade(
                targetState = title,
                animationSpec = androidx.compose.animation.core.tween(200),
                label = "top_bar_title_crossfade"
            ) { visibleTitle ->
                Text(
                    text = visibleTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }
    }
}

@Composable
private fun LiquidTabItem(
    screen: Screen,
    selected: Boolean,
    switchTrigger: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var lastSwitchTrigger by remember { mutableIntStateOf(switchTrigger) }
    val selectedBounce = remember { Animatable(0f) }
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.91f
            selected -> 1f
            else -> 0.96f
        },
        animationSpec = spring(
            dampingRatio = if (isPressed) 0.90f else 0.58f,
            stiffness = if (isPressed) 760f else 470f
        ),
        label = "liquid_tab_scale"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        },
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 560f),
        label = "liquid_tab_content_color"
    )
    val bounce = selectedBounce.value
    val rotationDirection = if (screen.route == Screen.Chat.route) -1f else 1f

    LaunchedEffect(switchTrigger, selected) {
        val changed = lastSwitchTrigger != switchTrigger
        lastSwitchTrigger = switchTrigger
        if (changed && selected) {
            selectedBounce.snapTo(0f)
            selectedBounce.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            selectedBounce.animateTo(
                0f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = -bounce * density * 4.5f
                    rotationZ = bounce * rotationDirection * 4.2f +
                        if (isPressed) rotationDirection * -1.2f else 0f
                }
                .widthIn(min = 96.dp, max = 126.dp)
                .height(64.dp)
                .padding(top = 6.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = screen.icon,
                contentDescription = screen.label,
                tint = contentColor,
                modifier = Modifier.size(if (selected) 24.dp else 22.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = screen.label,
                color = contentColor,
                style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}
