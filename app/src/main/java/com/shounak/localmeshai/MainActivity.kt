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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.clipToBounds
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
import kotlin.math.absoluteValue
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
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
    val items = listOf(Screen.Chat, Screen.Models)
    val pagerState = rememberPagerState(pageCount = { items.size })
    val coroutineScope = rememberCoroutineScope()
    val currentScreen = items[pagerState.currentPage]
    val context = androidx.compose.ui.platform.LocalContext.current
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
            containerColor = Color.Transparent,
            topBar = {
                if (currentScreen != Screen.Chat) {
                    LiquidTopBar(
                        title = currentScreen.title,
                        hazeState = hazeState
                    )
                }
            },
            bottomBar = {
                if (!isKeyboardOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LiquidBottomNav(
                            items = items,
                            selectedIndex = pagerState.currentPage,
                            hazeState = hazeState,
                            onSelect = { index ->
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .clipToBounds(),
                // Disable over-scroll glow — it looks wrong with the glass UI
                beyondViewportPageCount = 0,
                userScrollEnabled = true
            ) { page ->
                val pageOffset = (
                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).coerceIn(-1f, 1f)
                val distance = pageOffset.absoluteValue
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .fluidReveal(delayMillis = page * 70, initialYOffset = 10.dp)
                            .graphicsLayer {
                                translationX = pageOffset * size.width * 0.10f
                                scaleX = 1f - distance * 0.04f
                                scaleY = 1f - distance * 0.04f
                                alpha = 1f - distance * 0.12f
                            }
                    ) {
                        when (page) {
                            0 -> ChatScreen(mainViewModel = mainViewModel, hazeState = hazeState)
                            1 -> ModelManagerScreen(mainViewModel = mainViewModel, hazeState = hazeState)
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
    var visualSelectedIndex by remember { mutableIntStateOf(selectedIndex) }
    var isIndicatorDragging by remember { mutableStateOf(false) }
    var previousSelectedIndex by remember { mutableIntStateOf(selectedIndex) }
    val tabSwitchBounce = remember { Animatable(0f) }
    val contentPaddingPx = with(density) { 12.dp.toPx() }
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
        (visualSelectedIndex * stepPx + indicatorInsetPx)
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
            visualSelectedIndex = selectedIndex
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
            .height(88.dp)
            .fluidReveal(delayMillis = 120, initialYOffset = 18.dp)
            .animatedGlassHalo(alpha = 0.055f, durationMillis = 5_200)
            .onSizeChanged { containerWidthPx = it.width.toFloat() }
            .pointerInput(items.size, visualSelectedIndex, containerWidthPx) {
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
                            visualSelectedIndex = rawIndex
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
        cornerRadius = 34.dp,
        refractionHeight = 30.dp,
        dispersion = 0.82f,
        blurRadius = 34.dp,
        animatedCaustics = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp)
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
                    cornerRadius = 30.dp,
                    blurRadius = 32.dp,
                    refractionStrength = 0.17f,
                    dispersionAmount = 0.032f,
                    tintColor = if (isSystemInDarkTheme()) {
                        Color(0xFFC9EEFF).copy(alpha = 0.20f)
                    } else {
                        Color.White.copy(alpha = 0.72f)
                    },
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
                        selected = visualSelectedIndex == index,
                        switchTrigger = selectedIndex,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            visualSelectedIndex = index
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
    val isDark = isSystemInDarkTheme()
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
                .height(58.dp)
                .fluidReveal(initialYOffset = 10.dp)
                .animatedGlassHalo(alpha = 0.05f, durationMillis = 4_700),
            hazeState = hazeState,
            cornerRadius = 29.dp,
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
                    color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.sp
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
    val isDark = isSystemInDarkTheme()
    var lastSwitchTrigger by remember { mutableIntStateOf(switchTrigger) }
    val selectedBounce = remember { Animatable(0f) }
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 0.96f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "liquid_tab_scale"
    )
    val tint = if (selected) {
        if (isDark) Color(0xFFC9EEFF).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.70f)
    } else {
        Color.White.copy(alpha = 0.025f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    }
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
                    scaleX = scale + bounce * 0.08f
                    scaleY = (scale - bounce * 0.025f).coerceAtLeast(0.9f)
                    translationY = -bounce * density * 4.5f
                    rotationZ = bounce * rotationDirection * 4.2f
                }
                .widthIn(min = 96.dp, max = 126.dp)
                .height(64.dp)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = screen.icon,
                contentDescription = screen.label,
                tint = contentColor,
                modifier = Modifier.size(if (selected) 28.dp else 25.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = screen.label,
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}
