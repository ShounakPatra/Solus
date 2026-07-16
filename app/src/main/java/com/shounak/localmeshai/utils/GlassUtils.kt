package com.shounak.localmeshai.utils

import android.app.ActivityManager
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

// ── Custom AGSL Dispersion Shader for Android 13+ ──────────────────────────────────────
const val DISPERSION_AGSL = """
    uniform shader content;
    uniform float2 size;
    uniform float  refractionStrength;
    uniform float  dispersionAmount;

    half4 main(float2 coord) {
        float2 center  = size * 0.5;
        float2 delta   = coord - center;
        float  dist    = length(delta);
        float  maxDist = length(center);

        // 0.0 = dead center, 1.0 = corner
        float edgeFactor = clamp(dist / maxDist, 0.0, 1.0);

        // Convex-lens refraction — bends content toward centre
        float2 refDir    = dist > 0.001 ? delta / dist : float2(0.0);
        float2 refracted = refDir * (dist * dist / maxDist) * refractionStrength;

        // Dispersion: RGB channels split at different angles
        // Strongest near edges — physically correct
        float disp = dispersionAmount * (0.2 + edgeFactor * 0.8);

        float2 coordR = coord - refracted * (1.0 + disp);
        float2 coordG = coord - refracted;
        float2 coordB = coord - refracted * (1.0 - disp);

        half r = content.eval(coordR).r;
        half g = content.eval(coordG).g;
        half b = content.eval(coordB).b;
        half a = content.eval(coord  ).a;

        // Fresnel rim: edges catch light like real glass
        half rim = half(pow(edgeFactor, 2.5) * 0.14);

        return half4(r + rim, g + rim, b + rim, a);
    }
"""

private const val CAUSTIC_AGSL = """
    uniform float time;
    uniform float2 size;
    uniform float intensity;

    half4 main(float2 coord) {
        float2 uv = coord / size;
        float a = sin(uv.x * 8.0 + time * 0.55) + cos(uv.y * 7.0 - time * 0.42);
        float b = sin((uv.x + uv.y) * 10.0 - time * 0.36);
        float c = pow(clamp((a + b) * 0.22 + 0.5, 0.0, 1.0), 5.0);
        return half4(
            half(c * intensity * 0.75),
            half(c * intensity),
            half(c * intensity * 1.35),
            half(c * intensity * 0.7)
        );
    }
"""

enum class LiquidGlassQuality {
    Full,
    Balanced,
    Fallback
}

@Volatile
private var cachedLiquidGlassQuality: LiquidGlassQuality? = null

private fun resolveLiquidGlassQuality(context: Context): LiquidGlassQuality {
    cachedLiquidGlassQuality?.let { return it }
    return synchronized(LiquidGlassQuality::class.java) {
        cachedLiquidGlassQuality ?: run {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val totalRamGb = DeviceUtils.getTotalRamGB(context)
            val hasHighEndGpuProfile = DeviceUtils.supportsLiteRtLmGpu()
            when {
                activityManager.isLowRamDevice || powerManager.isPowerSaveMode ->
                    LiquidGlassQuality.Fallback
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    hasHighEndGpuProfile && totalRamGb >= 14.0 ->
                    LiquidGlassQuality.Full
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    hasHighEndGpuProfile && totalRamGb >= 9.0 ->
                    LiquidGlassQuality.Balanced
                else -> LiquidGlassQuality.Fallback
            }.also { cachedLiquidGlassQuality = it }
        }
    }
}

@Composable
fun rememberLiquidGlassQuality(): LiquidGlassQuality {
    val context = LocalContext.current
    return remember(context.applicationContext) {
        resolveLiquidGlassQuality(context.applicationContext)
    }
}

@Composable
fun Modifier.fluidReveal(
    delayMillis: Int = 0,
    initialScale: Float = 0.96f,
    initialYOffset: Dp = 14.dp,
    initialXOffset: Dp = 0.dp,
    initialRotationZ: Float = 0f
): Modifier {
    val quality = rememberLiquidGlassQuality()
    val density = LocalDensity.current
    var hasRevealed by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (hasRevealed) 1f else 0f) }
    LaunchedEffect(delayMillis, initialScale, initialYOffset, initialXOffset, initialRotationZ) {
        if (hasRevealed) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        val effectiveDelay = if (quality == LiquidGlassQuality.Fallback) {
            delayMillis.coerceAtMost(80)
        } else {
            delayMillis
        }
        if (effectiveDelay > 0) delay(effectiveDelay.toLong())
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = if (quality == LiquidGlassQuality.Fallback) 0.68f else 0.72f,
                stiffness = if (quality == LiquidGlassQuality.Fallback) 520f else 430f,
                visibilityThreshold = 0.001f
            )
        )
        hasRevealed = true
    }
    val movementScale = if (quality == LiquidGlassQuality.Fallback) 0.45f else 1f
    val effectiveInitialScale = if (quality == LiquidGlassQuality.Fallback) {
        initialScale.coerceAtLeast(0.985f)
    } else {
        initialScale
    }
    val offsetYPx = with(density) { initialYOffset.toPx() } * movementScale
    val offsetXPx = with(density) { initialXOffset.toPx() } * movementScale
    val effectiveRotation = if (quality == LiquidGlassQuality.Fallback) 0f else initialRotationZ
    return this.graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        scaleX = effectiveInitialScale + (1f - effectiveInitialScale) * progress.value
        scaleY = effectiveInitialScale + (1f - effectiveInitialScale) * progress.value
        translationY = (1f - progress.value) * offsetYPx
        translationX = (1f - progress.value) * offsetXPx
        rotationZ = (1f - progress.value) * effectiveRotation
    }
}

@Composable
fun Modifier.animatedGlassHalo(
    enabled: Boolean = true,
    alpha: Float = 0.085f,
    durationMillis: Int = 3_800
): Modifier {
    if (!enabled) return this
    val accent = MaterialTheme.colorScheme.primary
    return this.drawWithCache {
        val center = Offset(
            x = size.width * 0.34f,
            y = size.height * 0.24f
        )
        val radius = max(size.width, size.height) * 0.82f
        val haloBrush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = alpha),
                accent.copy(alpha = alpha * 0.68f),
                accent.copy(alpha = alpha * 0.18f),
                Color.Transparent
            ),
            center = center,
            radius = radius
        )
        onDrawWithContent {
            drawContent()
            drawCircle(
                brush = haloBrush,
                center = center,
                radius = radius,
                blendMode = BlendMode.Screen
            )
        }
    }
}

@Composable
fun LiquidGlassBackdrop(
    hazeState: HazeState,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    // A full-window Haze source allocates an off-screen texture. Some mobile GPUs
    // cap that texture below the physical display width, producing a dark strip at
    // the edge; it can also flash black while the capture surface is initialised.
    // Draw the deliberate low-saturation backdrop directly so every frame covers
    // the complete window with the same deterministic pixels.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(state = hazeState)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colors.background,
                        colors.surfaceContainerLowest,
                        colors.surfaceContainerLow
                    )
                )
            )
    ) {
        content()
    }
}

@Composable
fun LiquidGlassBox(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    cornerRadius: Dp = 16.dp,
    refractionHeight: Dp = 20.dp,
    dispersion: Float = 0.5f,
    blurRadius: Dp = 15.dp,
    animatedCaustics: Boolean = false,
    content: @Composable () -> Unit
) {
    GlassDispersionCard(
        modifier = modifier,
        hazeState = hazeState,
        cornerRadius = cornerRadius,
        blurRadius = blurRadius,
        refractionStrength = (refractionHeight.value / 180f).coerceIn(0.05f, 0.18f),
        dispersionAmount = (dispersion * 0.024f).coerceIn(0f, 0.032f),
        contentPadding = PaddingValues(0.dp),
        animatedCaustics = animatedCaustics
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
fun Modifier.glassEffect(
    hazeState: HazeState,
    shape: Shape = RoundedCornerShape(24.dp),
    blurRadius: Dp = 20.dp,
    tintColor: Color = Color.White.copy(alpha = 0.10f),
    borderAlpha: Float = 0.25f,
): Modifier {
    val colors = MaterialTheme.colorScheme
    val isSurfaceLevel = tintColor == colors.surfaceContainerLowest ||
        tintColor == colors.surfaceContainerLow ||
        tintColor == colors.surfaceContainer ||
        tintColor == colors.surfaceContainerHigh ||
        tintColor == colors.surfaceContainerHighest
    val effectiveTint = if (isSurfaceLevel) {
        tintColor
    } else {
        tintColor
            .copy(alpha = (tintColor.alpha * 0.38f).coerceAtMost(0.14f))
            .compositeOver(colors.surfaceContainer)
    }
    // List items use cached translucent paint instead of a live background blur.
    // A haze pass for every visible row is one of the most expensive operations
    // Compose can perform while the rows are moving.
    val blurred = this
        .hazeEffect(state = hazeState)
        .clip(shape)
        .background(effectiveTint, shape)

    return blurred.drawWithCache {
        val surfaceBrush = Brush.linearGradient(
            colors = listOf(
                colors.surfaceContainerHigh.copy(alpha = borderAlpha * 0.34f),
                Color.Transparent,
                colors.primary.copy(alpha = borderAlpha * 0.10f),
                Color.Transparent
            ),
            start = Offset(-size.width * 0.20f, -size.height * 0.10f),
            end = Offset(size.width * 1.10f, size.height * 0.85f)
        )
        val outline = shape.createOutline(size, layoutDirection, this)
        val outlineBrush = Brush.linearGradient(
            listOf(
                colors.outline.copy(alpha = borderAlpha * 1.15f),
                colors.outlineVariant.copy(alpha = borderAlpha * 0.72f),
                colors.primary.copy(alpha = borderAlpha * 0.42f),
                colors.outlineVariant.copy(alpha = borderAlpha * 0.62f)
            )
        )
        val outlineStroke = Stroke(width = 0.8.dp.toPx())
        onDrawWithContent {
            drawContent()
            drawRect(brush = surfaceBrush)
            drawOutline(
                outline = outline,
                brush = outlineBrush,
                style = outlineStroke
            )
        }
    }
}

@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    tintColor: Color = Color.White.copy(alpha = 0.10f),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val animatedScaleX by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = if (isPressed) 0.92f else 0.76f,
            stiffness = if (isPressed) 650f else 480f,
            visibilityThreshold = 0.001f
        ),
        label = "liquid_button_scale_x"
    )
    val animatedScaleY by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = spring(
            dampingRatio = if (isPressed) 0.94f else 0.72f,
            stiffness = if (isPressed) 720f else 440f,
            visibilityThreshold = 0.001f
        ),
        label = "liquid_button_scale_y"
    )
    val pressGlow by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "liquid_button_glow"
    )

    Row(
        modifier = modifier
            .animatedGlassHalo(
                enabled = enabled && isPressed,
                alpha = 0.14f,
                durationMillis = 920
            )
            .graphicsLayer {
                scaleX = animatedScaleX
                scaleY = animatedScaleY
                alpha = if (enabled) 1f else 0.48f
                translationY = pressGlow * density * 1.8f
            }
            .glassEffect(
                hazeState = hazeState,
                shape = shape,
                blurRadius = 20.dp,
                tintColor = tintColor,
                borderAlpha = 0.34f + pressGlow * 0.28f
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun Modifier.jellyOnTouch(sensitivity: Float = 2.4f): Modifier {
    val rotationX = remember { Animatable(0f) }
    val rotationY = remember { Animatable(0f) }
    val pressScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    return this
        .graphicsLayer {
            this.rotationX = rotationX.value
            this.rotationY = rotationY.value
            scaleX = pressScale.value
            scaleY = pressScale.value
            cameraDistance = 18f * density
        }
        .pointerInput(sensitivity) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val x = ((down.position.x - centerX) / centerX).coerceIn(-1f, 1f)
                val y = ((down.position.y - centerY) / centerY).coerceIn(-1f, 1f)
                scope.launch {
                    pressScale.animateTo(
                        0.965f,
                        spring(dampingRatio = 0.88f, stiffness = 760f)
                    )
                }
                scope.launch {
                    rotationY.animateTo(
                        x * sensitivity,
                        spring(dampingRatio = 0.55f, stiffness = 520f)
                    )
                }
                scope.launch {
                    rotationX.animateTo(
                        -y * sensitivity,
                        spring(dampingRatio = 0.55f, stiffness = 520f)
                    )
                }
                waitForUpOrCancellation()
                scope.launch {
                    rotationX.animateTo(
                        0f,
                        spring(dampingRatio = 0.65f, stiffness = 680f)
                    )
                }
                scope.launch {
                    rotationY.animateTo(
                        0f,
                        spring(dampingRatio = 0.65f, stiffness = 680f)
                    )
                }
                scope.launch {
                    pressScale.animateTo(
                        1f,
                        spring(dampingRatio = 0.56f, stiffness = 520f)
                    )
                }
            }
        }
}

// ── Legacy/Static Glassmorphic Modifier retained as fallback ─────────────────────────────
@Composable
fun Modifier.glassmorphic(
    shape: androidx.compose.foundation.shape.CornerBasedShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    borderWidth: Dp = 1.dp
): Modifier {
    val colors = MaterialTheme.colorScheme
    val baseColor = colors.surfaceContainer
    val borderColors = listOf(
        colors.outline.copy(alpha = 0.48f),
        colors.outlineVariant.copy(alpha = 0.72f)
    )

    return this.then(
        Modifier
            .background(
                color = baseColor,
                shape = shape
            )
            .border(
                width = borderWidth,
                brush = Brush.linearGradient(borderColors),
                shape = shape
            )
    )
}

@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    var keepPopupVisible by remember { mutableStateOf(expanded) }
    val visibilityState = remember { MutableTransitionState(false) }

    LaunchedEffect(expanded) {
        if (expanded) {
            keepPopupVisible = true
            visibilityState.targetState = true
        } else {
            visibilityState.targetState = false
            keepPopupVisible = false
        }
    }

    val glassModifier = if (hazeState != null) {
        modifier.glassEffect(
            hazeState = hazeState,
            shape = shape,
            blurRadius = 28.dp,
            tintColor = colors.surfaceContainerHigh,
            borderAlpha = 0.38f
        )
    } else {
        modifier.background(
            brush = Brush.linearGradient(
                listOf(
                    colors.surfaceContainerHigh,
                    colors.surfaceContainer,
                    colors.surfaceContainerHigh
                )
            ),
            shape = shape
        )
    }
    if (!keepPopupVisible) return

    DropdownMenu(
        expanded = keepPopupVisible,
        onDismissRequest = onDismissRequest,
        modifier = glassModifier,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        properties = PopupProperties(focusable = true),
        border = BorderStroke(
            0.8.dp,
            Brush.linearGradient(
                listOf(
                    colors.outline.copy(alpha = 0.58f),
                    colors.outlineVariant.copy(alpha = 0.72f),
                    colors.primary.copy(alpha = 0.34f)
                )
            )
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibilityState,
            enter = scaleIn(
                initialScale = 0.78f,
                transformOrigin = TransformOrigin(0.14f, 0f),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ),
            exit = scaleOut(
                targetScale = 0.88f,
                transformOrigin = TransformOrigin(0.14f, 0f),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeOut(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        ) {
            Column(content = content)
        }
    }
}

// ── Glass Dispersion Card using AGSL Shaders (Android 13+) ───────────────────────────────
@Composable
fun GlassDispersionCard(
    hazeState:          HazeState,
    modifier:           Modifier = Modifier,
    refractionStrength: Float    = 0.12f,
    dispersionAmount:   Float    = 0.020f,
    blurRadius:         Dp       = 22.dp,
    cornerRadius:       Dp       = 24.dp,
    tintColor:          Color    = Color.White.copy(alpha = 0.10f),
    borderAlpha:        Float    = 0.25f,
    contentPadding:     PaddingValues = PaddingValues(16.dp),
    animatedCaustics:   Boolean  = false,
    content: @Composable BoxScope.() -> Unit = {}
) {
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val quality = rememberLiquidGlassQuality()
    val colors = MaterialTheme.colorScheme
    val elevatedTint = tintColor
        .copy(alpha = (tintColor.alpha * 0.35f).coerceAtMost(0.14f))
        .compositeOver(colors.surfaceContainerHigh)
    val causticShader = remember(quality) {
        if (quality == LiquidGlassQuality.Full &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            runCatching { createCausticShader() }.getOrNull()
        } else {
            null
        }
    }

    val dispersionEffect: RenderEffect? =
        if (quality == LiquidGlassQuality.Full &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            cardSize != IntSize.Zero
        ) {
            remember(cardSize, refractionStrength, dispersionAmount) {
                runCatching {
                    createDispersionRenderEffect(
                        cardSize = cardSize,
                        refractionStrength = refractionStrength,
                        dispersionAmount = dispersionAmount
                    )
                }.getOrNull()
            }
        } else null

    Box(
        modifier = modifier
            .animatedGlassHalo(
                enabled = animatedCaustics,
                alpha = if (quality == LiquidGlassQuality.Full) 0.07f else 0.045f,
                durationMillis = 4_600
            )
            .onSizeChanged { cardSize = it }
            .clip(RoundedCornerShape(cornerRadius))
    ) {

        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    if (dispersionEffect != null) {
                        renderEffect = dispersionEffect.asComposeRenderEffect()
                    }
                }
                // Keep card elevation independent from asynchronous background
                // capture. The cached tint is visually consistent and avoids a
                // black intermediate frame on GPU/driver combinations where live
                // blur setup is delayed.
                .background(elevatedTint)
        )

        Canvas(modifier = Modifier.matchParentSize()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && causticShader != null) {
                drawCausticShader(
                    shader = causticShader,
                    time = 0.35f,
                    animatedCaustics = animatedCaustics
                )
            } else {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.16f, 0f),
                        radius = max(size.width, size.height) * 0.64f
                    ),
                    center = Offset(size.width * 0.16f, 0f),
                    radius = max(size.width, size.height) * 0.64f,
                    blendMode = BlendMode.Screen
                )
            }
        }

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .align(Alignment.TopCenter)
        ) {
            val highlightHeight = size.height * 0.38f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(borderAlpha), Color.Transparent),
                    endY = highlightHeight
                ),
                size = Size(size.width, highlightHeight)
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = 0.8.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            colors.outline.copy(alpha = borderAlpha * 1.10f),
                            colors.outlineVariant.copy(alpha = borderAlpha * 0.72f),
                            colors.primary.copy(alpha = borderAlpha * 0.48f),
                            colors.outlineVariant.copy(alpha = borderAlpha * 0.62f)
                        )
                    ),
                    shape = RoundedCornerShape(cornerRadius)
                )
        )

        Box(
            modifier = Modifier
                .padding(contentPadding),
            content  = content
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createCausticShader(): RuntimeShader = RuntimeShader(CAUSTIC_AGSL)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createDispersionRenderEffect(
    cardSize: IntSize,
    refractionStrength: Float,
    dispersionAmount: Float
): RenderEffect {
    val shader = RuntimeShader(DISPERSION_AGSL)
    shader.setFloatUniform(
        "size",
        cardSize.width.toFloat(),
        cardSize.height.toFloat()
    )
    shader.setFloatUniform("refractionStrength", refractionStrength)
    shader.setFloatUniform("dispersionAmount", dispersionAmount)
    return RenderEffect.createRuntimeShaderEffect(shader, "content")
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawCausticShader(
    shader: RuntimeShader,
    time: Float,
    animatedCaustics: Boolean
) {
    shader.setFloatUniform("time", time)
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform(
        "intensity",
        if (animatedCaustics) 0.11f else 0.045f
    )
    drawRect(
        brush = ShaderBrush(shader),
        blendMode = BlendMode.Screen
    )
}
