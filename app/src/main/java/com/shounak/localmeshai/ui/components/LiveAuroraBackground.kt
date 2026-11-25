package com.shounak.localmeshai.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import com.shounak.localmeshai.features.LocalWorkspaceVisuals
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val AURORA_AGSL = """
    uniform float time;
    uniform float2 size;
    uniform float4 primaryRgb;
    uniform float4 secondaryRgb;
    uniform float4 tertiaryRgb;

    float waveBand(float2 uv, float yCenter, float freqX, float speed, float phase, float thickness) {
        float wave = sin(uv.x * freqX + time * speed + phase) * 0.115
                   + cos(uv.x * (freqX * 1.65) - time * (speed * 0.72) + phase * 1.4) * 0.055;
        float dist = abs(uv.y - (yCenter + wave));
        return exp(-(dist * dist) / (thickness * thickness));
    }

    half4 main(float2 coord) {
        float2 safeSize = max(size, float2(1.0, 1.0));
        float2 uv = coord / safeSize;

        // Vertical aurora curtain rays shimmering gently across the entire width
        float raysA = 0.66 + 0.34 * sin(uv.x * 10.0 + time * 0.55 + uv.y * 2.4);
        float raysB = 0.68 + 0.32 * cos(uv.x * 7.2 - time * 0.42 + uv.y * 2.0);
        float raysC = 0.72 + 0.28 * sin(uv.x * 5.4 + time * 0.31 - uv.y * 1.6);

        // Flowing aurora ribbons distributed gently across upper, middle, and lower screen
        float band1 = waveBand(uv, 0.22, 3.2, 0.40, 0.4, 0.24) * raysA;
        float band2 = waveBand(uv, 0.48, 3.9, -0.34, 2.1, 0.26) * raysB;
        float band3 = waveBand(uv, 0.75, 2.9, 0.28, 4.0, 0.28) * raysC;

        // Subtle whole-screen atmospheric wash + soft central bloom behind hero card
        float2 centerDelta = float2((uv.x - 0.50) * 0.90, (uv.y - 0.46) * 0.78);
        float centralBloom = exp(-dot(centerDelta, centerDelta) * 2.1) * 0.30;
        float screenVeil = 0.13 + 0.05 * sin(uv.y * 3.14159 + time * 0.22);

        // Very gentle top/bottom edge softening without pinching the sides
        float edgeMask = smoothstep(-0.12, 0.10, uv.y) * smoothstep(1.12, 0.90, uv.y);

        float3 color = primaryRgb.rgb * (band1 * 0.54 + centralBloom * 0.52 + screenVeil * 0.42)
                     + secondaryRgb.rgb * (band2 * 0.50 + centralBloom * 0.28 + screenVeil * 0.32)
                     + tertiaryRgb.rgb * (band3 * 0.46 + centralBloom * 0.38 + screenVeil * 0.36);

        // Micro-dither to prevent 8-bit banding on OLED displays
        float dither = (fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;

        float intensity = clamp((band1 * 0.34 + band2 * 0.32 + band3 * 0.28 + centralBloom * 0.45 + screenVeil) * edgeMask, 0.0, 0.62);
        float3 finalRgb = clamp(color * edgeMask + dither, 0.0, 1.0);

        return half4(half3(finalRgb * intensity), half(intensity));
    }
"""

/**
 * Full-bleed live glowing aurora rendered behind empty-state and initialized pre-chat views.
 * Automatically stops animating when hidden (once chatting begins) and honors Reduce Motion.
 */
@Composable
fun LiveAuroraBackground(
    modifier: Modifier = Modifier,
    accentColor: Color? = null
) {
    val reduceMotion = LocalWorkspaceVisuals.current.reduceMotion
    var elapsedSeconds by remember { mutableFloatStateOf(1.6f) }

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            elapsedSeconds = 1.6f
            return@LaunchedEffect
        }
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val deltaSeconds = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    elapsedSeconds += deltaSeconds * 1.65f
                }
                lastFrameNanos = frameNanos
            }
        }
    }

    val primaryColor = accentColor ?: Color(0xFF38BDF8)   // Luminous cyan
    val secondaryColor = Color(0xFF2DD4BF)                // Boreal teal
    val tertiaryColor = Color(0xFF818CF8)                 // Ethereal indigo-violet

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslLiveAuroraCanvas(
            time = elapsedSeconds,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor,
            modifier = modifier.fillMaxSize()
        )
    } else {
        FallbackLiveAuroraCanvas(
            time = elapsedSeconds,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor,
            modifier = modifier.fillMaxSize()
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslLiveAuroraCanvas(
    time: Float,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    modifier: Modifier = Modifier
) {
    val shader = remember {
        runCatching { RuntimeShader(AURORA_AGSL) }.getOrNull()
    }
    if (shader == null) {
        FallbackLiveAuroraCanvas(
            time = time,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor,
            modifier = modifier
        )
        return
    }

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform(
            "primaryRgb",
            primaryColor.red,
            primaryColor.green,
            primaryColor.blue,
            1f
        )
        shader.setFloatUniform(
            "secondaryRgb",
            secondaryColor.red,
            secondaryColor.green,
            secondaryColor.blue,
            1f
        )
        shader.setFloatUniform(
            "tertiaryRgb",
            tertiaryColor.red,
            tertiaryColor.green,
            tertiaryColor.blue,
            1f
        )
        drawRect(brush = ShaderBrush(shader))
    }
}

@Composable
private fun FallbackLiveAuroraCanvas(
    time: Float,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val baseRadius = max(size.width, size.height)

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.07f),
                    secondaryColor.copy(alpha = 0.09f),
                    tertiaryColor.copy(alpha = 0.07f)
                )
            )
        )

        val p1 = Offset(
            x = size.width * (0.48f + 0.24f * sin(time * 0.52f)),
            y = size.height * (0.24f + 0.09f * cos(time * 0.40f))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.24f),
                    primaryColor.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = p1,
                radius = baseRadius * 0.68f
            ),
            center = p1,
            radius = baseRadius * 0.68f,
            blendMode = BlendMode.Screen
        )

        val p2 = Offset(
            x = size.width * (0.52f + 0.26f * cos(time * 0.46f + (PI / 3).toFloat())),
            y = size.height * (0.49f + 0.10f * sin(time * 0.36f))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = 0.22f),
                    secondaryColor.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = p2,
                radius = baseRadius * 0.66f
            ),
            center = p2,
            radius = baseRadius * 0.66f,
            blendMode = BlendMode.Screen
        )

        val p3 = Offset(
            x = size.width * (0.46f + 0.22f * sin(time * 0.38f + 2.1f)),
            y = size.height * (0.74f + 0.09f * cos(time * 0.42f + 1.2f))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    tertiaryColor.copy(alpha = 0.20f),
                    tertiaryColor.copy(alpha = 0.07f),
                    Color.Transparent
                ),
                center = p3,
                radius = baseRadius * 0.66f
            ),
            center = p3,
            radius = baseRadius * 0.66f,
            blendMode = BlendMode.Screen
        )
    }
}
