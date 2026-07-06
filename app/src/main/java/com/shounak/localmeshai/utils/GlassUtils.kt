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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
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

@Composable
fun rememberLiquidGlassQuality(): LiquidGlassQuality {
    val context = LocalContext.current
    return remember(context) {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager
        when {
            activityManager.isLowRamDevice || powerManager.isPowerSaveMode ->
                LiquidGlassQuality.Fallback
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                LiquidGlassQuality.Full
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                LiquidGlassQuality.Balanced
            else -> LiquidGlassQuality.Fallback
        }
    }
}

@Composable
fun Modifier.fluidReveal(
    delayMillis: Int = 0,
    initialScale: Float = 0.96f,
    initialYOffset: Dp = 14.dp
): Modifier {
    val density = LocalDensity.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(delayMillis, initialScale, initialYOffset) {
        progress.snapTo(0f)
        if (delayMillis > 0) delay(delayMillis.toLong())
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.68f,
                stiffness = Spring.StiffnessMedium
            )
        )
    }
    val offsetPx = with(density) { initialYOffset.toPx() }
    return this.graphicsLayer {
        alpha = progress.value
        scaleX = initialScale + (1f - initialScale) * progress.value
        scaleY = initialScale + (1f - initialScale) * progress.value
        translationY = (1f - progress.value) * offsetPx
    }
}

@Composable
fun Modifier.animatedGlassHalo(
    enabled: Boolean = true,
    alpha: Float = 0.085f,
    durationMillis: Int = 3_800
): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "glass_halo")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glass_halo_sweep"
    )
    return this.drawWithContent {
        drawContent()
        val center = Offset(
            x = size.width * (0.18f + 0.64f * sweep),
            y = size.height * (0.10f + 0.72f * (1f - sweep))
        )
        val radius = max(size.width, size.height) * 0.82f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = alpha),
                    Color(0xFF7DDCFF).copy(alpha = alpha * 0.72f),
                    Color(0xFFFF7AC8).copy(alpha = alpha * 0.34f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            center = center,
            radius = radius,
            blendMode = BlendMode.Screen
        )
    }
}

@Composable
fun LiquidGlassBackdrop(
    hazeState: HazeState,
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_bg")

    val blob1X by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(4_400),
            repeatMode = RepeatMode.Reverse
        ), label = "blob1_x"
    )
    val blob1Y by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.68f,
        animationSpec = infiniteRepeatable(
            animation = tween(4_050),
            repeatMode = RepeatMode.Reverse
        ), label = "blob1_y"
    )

    val blob2X by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(5_200),
            repeatMode = RepeatMode.Reverse
        ), label = "blob2_x"
    )
    val blob2Y by infiniteTransition.animateFloat(
        initialValue = 0.78f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(4_650),
            repeatMode = RepeatMode.Reverse
        ), label = "blob2_y"
    )

    val blob3X by infiniteTransition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(5_900),
            repeatMode = RepeatMode.Reverse
        ), label = "blob3_x"
    )
    val blob3Y by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(5_250),
            repeatMode = RepeatMode.Reverse
        ), label = "blob3_y"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
        ) {
            val width = size.width
            val height = size.height

            if (isDark) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF09051C),
                            Color(0xFF18083D),
                            Color(0xFF091838)
                        )
                    )
                )
            } else {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF8F4FF),
                            Color(0xFFE9E5FF),
                            Color(0xFFDCEFFF)
                        )
                    )
                )
            }

            val blob1Color = if (isDark) {
                Color(0xFF00C8FF).copy(alpha = 0.34f)
            } else {
                Color(0xFF00AEEF).copy(alpha = 0.30f)
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blob1Color, Color.Transparent),
                    center = Offset(width * blob1X, height * blob1Y),
                    radius = width * 0.86f
                ),
                center = Offset(width * blob1X, height * blob1Y),
                radius = width * 0.86f
            )

            val blob2Color = if (isDark) {
                Color(0xFFFF2DAA).copy(alpha = 0.31f)
            } else {
                Color(0xFFFF63B7).copy(alpha = 0.28f)
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blob2Color, Color.Transparent),
                    center = Offset(width * blob2X, height * blob2Y),
                    radius = width * 0.92f
                ),
                center = Offset(width * blob2X, height * blob2Y),
                radius = width * 0.92f
            )

            val blob3Color = if (isDark) {
                Color(0xFF8E4DFF).copy(alpha = 0.42f)
            } else {
                Color(0xFF9B6CFF).copy(alpha = 0.32f)
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blob3Color, Color.Transparent),
                    center = Offset(width * blob3X, height * blob3Y),
                    radius = width
                ),
                center = Offset(width * blob3X, height * blob3Y),
                radius = width
            )

            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF5E7CFF).copy(alpha = if (isDark) 0.16f else 0.10f),
                        Color.Transparent
                    ),
                    start = Offset(0f, height),
                    end = Offset(width, 0f)
                ),
                blendMode = BlendMode.Screen
            )
        }

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
    val isDark = isSystemInDarkTheme()
    val quality = rememberLiquidGlassQuality()
    val effectiveBlur = when (quality) {
        LiquidGlassQuality.Full -> blurRadius
        LiquidGlassQuality.Balanced -> blurRadius * 0.72f
        LiquidGlassQuality.Fallback -> 0.dp
    }
    val effectiveTint = when (quality) {
        LiquidGlassQuality.Full -> tintColor
        LiquidGlassQuality.Balanced -> tintColor.copy(
            alpha = max(tintColor.alpha, 0.12f)
        )
        LiquidGlassQuality.Fallback -> if (isDark) {
            Color(0xFF25133E).copy(alpha = 0.90f)
        } else {
            Color.White.copy(alpha = 0.82f)
        }
    }
    val fallbackTint = if (isDark) {
        HazeTint(Color(0xFF25133E).copy(alpha = 0.94f))
    } else {
        HazeTint(Color.White.copy(alpha = 0.90f))
    }
    val clipped = this.clip(shape)
    val blurred = if (quality == LiquidGlassQuality.Fallback) {
        clipped.background(effectiveTint, shape)
    } else {
        clipped.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                blurRadius = effectiveBlur,
                tints = listOf(
                    HazeTint(
                        Brush.linearGradient(
                            listOf(
                                effectiveTint.copy(alpha = effectiveTint.alpha * 0.75f),
                                Color.White.copy(alpha = if (isDark) 0.055f else 0.16f),
                                effectiveTint.copy(alpha = effectiveTint.alpha * 0.45f)
                            )
                        )
                    )
                ),
                backgroundColor = Color.Transparent,
                noiseFactor = when (quality) {
                    LiquidGlassQuality.Full -> 0.055f
                    LiquidGlassQuality.Balanced -> 0.032f
                    LiquidGlassQuality.Fallback -> 0f
                },
                fallbackTint = fallbackTint
            )
        )
    }

    return blurred.drawWithContent {
        drawContent()

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = borderAlpha * 0.75f),
                    Color.Transparent,
                    Color(0xFF80D8FF).copy(alpha = borderAlpha * 0.22f),
                    Color.Transparent
                ),
                start = Offset(-size.width * 0.20f, -size.height * 0.10f),
                end = Offset(size.width * 1.10f, size.height * 0.85f)
            ),
            blendMode = BlendMode.Screen
        )

        drawOutline(
            outline = shape.createOutline(size, layoutDirection, this),
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = borderAlpha * 1.55f),
                    Color.White.copy(alpha = borderAlpha * 0.20f),
                    Color(0xFF8DCBFF).copy(alpha = borderAlpha * 0.85f),
                    Color.White.copy(alpha = borderAlpha * 0.35f)
                )
            ),
            style = Stroke(width = 0.8.dp.toPx())
        )
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
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "liquid_button_scale"
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
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.48f
                translationY = pressGlow * density * 1.4f
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
    val scope = rememberCoroutineScope()

    return this
        .graphicsLayer {
            this.rotationX = rotationX.value
            this.rotationY = rotationY.value
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
            }
        }
}

// ── Legacy/Static Glassmorphic Modifier retained as fallback ─────────────────────────────
@Composable
fun Modifier.glassmorphic(
    shape: androidx.compose.foundation.shape.CornerBasedShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    borderWidth: Dp = 1.dp
): Modifier {
    val isDark = isSystemInDarkTheme()
    val baseColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.4f)
    val borderColors = if (isDark) {
        listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.03f))
    } else {
        listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.12f))
    }

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
    val isDark = isSystemInDarkTheme()
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
            tintColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.68f),
            borderAlpha = 0.52f
        )
    } else {
        modifier.background(
            brush = Brush.linearGradient(
                listOf(
                    if (isDark) Color(0xF228174B) else Color(0xF7FFFFFF),
                    if (isDark) Color(0xF2181037) else Color(0xF2EEF4FF),
                    if (isDark) Color(0xF21A2852) else Color(0xF2E6F3FF)
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
                    Color.White.copy(alpha = if (isDark) 0.48f else 0.78f),
                    Color.White.copy(alpha = 0.10f),
                    Color(0xFF7FCBFF).copy(alpha = 0.42f)
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
    val isDark = isSystemInDarkTheme()
    var causticTime by remember { mutableFloatStateOf(0f) }
    val causticShader = remember(quality) {
        if (quality == LiquidGlassQuality.Full &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            runCatching { createCausticShader() }.getOrNull()
        } else {
            null
        }
    }

    LaunchedEffect(animatedCaustics, causticShader) {
        if (animatedCaustics && causticShader != null) {
            val startedAt = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    causticTime = (now - startedAt) / 1_000_000_000f
                }
            }
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
                .then(
                    if (quality == LiquidGlassQuality.Fallback) {
                        Modifier.background(
                            if (isDark) {
                                Color(0xE625153E)
                            } else {
                                Color.White.copy(alpha = 0.84f)
                            }
                        )
                    } else {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                blurRadius = if (quality == LiquidGlassQuality.Full) {
                                    blurRadius
                                } else {
                                    blurRadius * 0.72f
                                },
                                tints = listOf(
                                    HazeTint(
                                        Brush.linearGradient(
                                            listOf(
                                                tintColor,
                                                Color.White.copy(alpha = 0.05f),
                                                Color(0xFF769CFF).copy(alpha = 0.06f)
                                            )
                                        )
                                    )
                                ),
                                backgroundColor = Color.Transparent,
                                noiseFactor = if (quality == LiquidGlassQuality.Full) {
                                    0.06f
                                } else {
                                    0.035f
                                },
                                fallbackTint = HazeTint(
                                    if (isDark) Color(0xEE25153E) else Color.White
                                )
                            )
                        )
                    }
                )
        )

        Canvas(modifier = Modifier.matchParentSize()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && causticShader != null) {
                drawCausticShader(
                    shader = causticShader,
                    time = causticTime,
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
                            Color.White.copy(alpha = borderAlpha * 1.6f),
                            Color.White.copy(alpha = borderAlpha * 0.18f),
                            Color(0xFF82D4FF).copy(alpha = borderAlpha),
                            Color.White.copy(alpha = borderAlpha * 0.45f)
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
