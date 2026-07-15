package com.shounak.localmeshai.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.shounak.localmeshai.utils.ModelAnswerFormatter
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState

/**
 * Shows formulas in a draggable horizontal viewport. Common arithmetic uses
 * selectable native text; advanced structures retain native LaTeX rendering
 * and expose a dedicated copy action with the same readable result.
 *
 * The copy button uses [LiquidGlassButton] with the same circular glass style
 * as [MessageToolButton] in ChatScreen to keep the UI consistent.
 */
@Composable
fun ModelMathCard(
    latex: String,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val normalizedLatex = remember(latex) { ModelAnswerFormatter.normalizeEscapedModelText(latex) }
    val readableText = remember(normalizedLatex) { ModelAnswerFormatter.formatMath(normalizedLatex) }
    val usesNativeRenderer = remember(normalizedLatex) {
        ModelAnswerFormatter.requiresNativeMathRenderer(normalizedLatex)
    }
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = readableText }
            .glassEffect(
                hazeState = hazeState,
                shape = shape,
                blurRadius = 10.dp,
                tintColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                borderAlpha = 0.28f
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Copy button row ────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp, top = 4.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                // Same circular glass style as MessageToolButton in ChatScreen:
                // size(34dp), RoundedCornerShape(17dp), tintColor alpha 0.18f, icon 16dp
                LiquidGlassButton(
                    onClick = { clipboardManager.setText(AnnotatedString(readableText)) },
                    hazeState = hazeState,
                    modifier = Modifier.size(34.dp),
                    shape = RoundedCornerShape(17.dp),
                    tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy equation",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Math content ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(start = 14.dp, end = 18.dp, bottom = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                SelectionContainer {
                    if (usesNativeRenderer) {
                        Latex(
                            latex = normalizedLatex,
                            config = LatexConfig(fontSize = 19.sp, theme = LatexTheme.auto())
                        )
                    } else {
                        androidx.compose.material3.Text(
                            text = readableText,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
