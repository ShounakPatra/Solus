package com.shounak.localmeshai
import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import com.shounak.localmeshai.utils.AppCacheCleaner
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
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BottomNavHeight = 84.dp
private val BottomNavVerticalPadding = 10.dp

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    private var isFirstStart = true

    override fun onStart() {
        super.onStart()
        com.shounak.localmeshai.utils.RepositoryFileDownloads.resumePending(this)
        isFirstStart = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            ModelRuntimeCoordinator.shutdownScope.launch {
                ModelRuntimeCoordinator.releaseAll()
                AppCacheCleaner.clearAppCache(applicationContext)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        com.shounak.localmeshai.utils.CrashReportManager.install(this)
        enableEdgeToEdge()
        preferHighestRefreshRate()
        requestNotificationPermissionIfNeeded()
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                decor.post {
                    com.shounak.localmeshai.data.StartupCoordinator.getInstance(applicationContext).onFirstFrameRendered()
                }
                return true
            }
        })
        setContent {
            val appSettingsData by mainViewModel.appSettingsData.collectAsState()

            LocalMeshAITheme(darkTheme = appSettingsData.enableDarkMode) {
                com.shounak.localmeshai.features.WorkspaceAppearance {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(mainViewModel)
                }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(com.shounak.localmeshai.utils.AppUpdateManager.EXTRA_OPEN_SETTINGS_ABOUT, false)) {
            // Do not trust unverified incoming intent extras for download URLs or version data.
            // Trigger independent verification against the official repository release API.
            mainViewModel.checkForUpdates(silent = false)
            mainViewModel.openSettings(scrollToAbout = true)
            intent.removeExtra(com.shounak.localmeshai.utils.AppUpdateManager.EXTRA_OPEN_SETTINGS_ABOUT)
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
    val reduceMotion = com.shounak.localmeshai.features.LocalWorkspaceVisuals.current.reduceMotion
    val items = remember { listOf(Screen.Chat, Screen.Models) }
    var selectedIndex by androidx.compose.runtime.saveable.rememberSaveable {
        mutableIntStateOf(0)
    }
    // Keep indicator animation outside Scaffold's subcomposed bar and page lifetime.
    val navigationPosition by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap()
            else tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "main_navigation_position"
    )
    // A destination never samples a cached capture from the outgoing tab.
    val hazeState = remember(selectedIndex) { HazeState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val tabSwipeThresholdPx = with(density) { 64.dp.toPx() }
    val isKeyboardOpen = WindowInsets.isImeVisible
    val prefs = remember { context.getSharedPreferences("solus_prefs", android.content.Context.MODE_PRIVATE) }
    var showOnboarding by remember {
        mutableStateOf(!prefs.getBoolean("onboarding_completed", false))
    }

    if (showOnboarding) {
        com.shounak.localmeshai.ui.components.OnboardingOverlay(
            isVisible = showOnboarding,
            hazeState = hazeState,
            onGoToModels = {
                prefs.edit().putBoolean("onboarding_completed", true).apply()
                showOnboarding = false
                selectedIndex = 1
            }
        )
    }

    val pendingCrashReport by mainViewModel.pendingCrashPrompt.collectAsState()
    pendingCrashReport?.let { report ->
        com.shounak.localmeshai.ui.components.CrashPromptDialog(
            report = report,
            hazeState = hazeState,
            onSend = {
                mainViewModel.sendPendingCrashReport(context)
            },
            onCancel = {
                mainViewModel.dismissPendingCrashPrompt()
            }
        )
    }

    val settingsDialogVisible by mainViewModel.settingsDialogVisible.collectAsState()
    val scrollToAboutRequested by mainViewModel.scrollToAboutRequested.collectAsState()
    val appSettingsData by mainViewModel.appSettingsData.collectAsState()

    // Keep the resting dock inset independent of the bar's visibility animation.
    // Chat combines this with the animated IME inset using max, so closing the
    // keyboard never briefly drops the composer into the navigation bar's space.
    val bottomContentInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        if (appSettingsData.enableAutoHideBottomBar) 0.dp
        else BottomNavHeight + BottomNavVerticalPadding * 2

    var autoHideWasEnabledWhenSettingsOpened by remember { mutableStateOf(false) }
    var autoHideTurnedOnInSettings by remember { mutableStateOf(false) }

    LaunchedEffect(settingsDialogVisible) {
        if (settingsDialogVisible) {
            autoHideWasEnabledWhenSettingsOpened = appSettingsData.enableAutoHideBottomBar
            autoHideTurnedOnInSettings = false
        }
    }

    if (settingsDialogVisible) {
        com.shounak.localmeshai.ui.screens.SettingsDialog(
            mainViewModel = mainViewModel,
            // Settings must not sample the underlying Chat/Models capture.
            hazeState = remember { HazeState() },
            scrollToAbout = scrollToAboutRequested,
            onDismissRequest = {
                val shouldGoToChat = (autoHideTurnedOnInSettings || !autoHideWasEnabledWhenSettingsOpened) &&
                    appSettingsData.enableAutoHideBottomBar
                if (shouldGoToChat) {
                    selectedIndex = 0
                }
                mainViewModel.dismissSettings()
            },
            onAutoHideToggleChanged = { enabled ->
                autoHideTurnedOnInSettings = enabled
            }
        )
    }

    LiquidGlassBackdrop(hazeState = hazeState) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isKeyboardOpen && !appSettingsData.enableAutoHideBottomBar,
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
                            .padding(horizontal = 20.dp, vertical = BottomNavVerticalPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        LiquidBottomNav(
                            items = items,
                            selectedIndex = selectedIndex,
                            selectionPosition = navigationPosition,
                            hazeState = hazeState,
                            onSelect = { index -> selectedIndex = index }
                        )
                    }
                }
            }
        ) { innerPadding ->
            // Animate only the destination over an opaque background. The outgoing
            // page and its blur capture are disposed immediately, avoiding colour bleed.
            key(selectedIndex) {
                val pageSlide = remember(reduceMotion) {
                    Animatable(if (reduceMotion) 0f else if (selectedIndex == 1) 0.08f else -0.08f)
                }
                LaunchedEffect(reduceMotion) {
                    if (reduceMotion) pageSlide.snapTo(0f)
                    else pageSlide.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                        .background(MaterialTheme.colorScheme.background)
                        .clipToBounds()
                        // Bottom space belongs to floating controls; let each screen
                        // consume the full IME inset when the keyboard opens.
                        .consumeWindowInsets(PaddingValues(top = innerPadding.calculateTopPadding()))
                        .pointerInput(selectedIndex, isKeyboardOpen, tabSwipeThresholdPx) {
                            if (isKeyboardOpen) return@pointerInput
                            var accumulatedDragX = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { accumulatedDragX = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    accumulatedDragX += dragAmount
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
                ) {
                    val page = selectedIndex
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = pageSlide.value * size.width }
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        when (page) {
                            0 -> ChatScreen(mainViewModel = mainViewModel, hazeState = hazeState,
                                bottomContentInset = bottomContentInset)
                            1 -> Column(modifier = Modifier.fillMaxSize()) {
                                LiquidTopBar(
                                    title = Screen.Models.title,
                                    hazeState = hazeState
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    ModelManagerScreen(
                                        mainViewModel = mainViewModel,
                                        hazeState = hazeState,
                                        bottomContentInset = bottomContentInset,
                                        onGoToChat = { selectedIndex = 0 }
                                    )
                                }
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
    selectionPosition: Float,
    hazeState: HazeState,
    onSelect: (Int) -> Unit
) {
    val reduceMotion = com.shounak.localmeshai.features.LocalWorkspaceVisuals.current.reduceMotion
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
    val animatedIndicatorOffsetPx = (selectionPosition * stepPx + indicatorInsetPx)
        .coerceIn(0f, maxIndicatorOffsetPx)
    val indicatorScale by animateFloatAsState(
        targetValue = if (isIndicatorDragging) 1.045f else 1f,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bottom_nav_indicator_scale"
    )
    val indicatorOffsetPx = if (isIndicatorDragging) targetIndicatorOffsetPx else animatedIndicatorOffsetPx
    val switchPulse = if (reduceMotion) 0f else tabSwitchBounce.value

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

    val navShape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .height(BottomNavHeight)
            .fluidReveal(delayMillis = 120, initialYOffset = 18.dp)
            .clip(navShape)
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
            }
    ) {
        LiquidGlassBox(
            modifier = Modifier.matchParentSize(),
            hazeState = hazeState,
            cornerRadius = 22.dp,
            refractionHeight = 30.dp,
            dispersion = 0.82f,
            blurRadius = 0.dp,
            animatedCaustics = true
        ) {}
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
                    blurRadius = 0.dp,
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
                .animatedGlassHalo(shape = RoundedCornerShape(16.dp), alpha = 0.05f, durationMillis = 4_700),
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
