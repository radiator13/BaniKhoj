package app.banikhoj

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ROW_1 = listOf("ੳ", "ਅ", "ੲ", "ਸ", "ਹ", "ਕ", "ਖ", "ਗ", "ਘ", "ਙ")
private val ROW_2 = listOf("ਚ", "ਛ", "ਜ", "ਝ", "ਞ", "ਟ", "ਠ", "ਡ", "ਢ", "ਣ")
private val ROW_3 = listOf("ਤ", "ਥ", "ਦ", "ਧ", "ਨ", "ਪ", "ਫ", "ਬ", "ਭ", "ਮ")
private val ROW_4 = listOf("ਯ", "ਰ", "ਲ", "ਵ", "ੜ", "ਸ਼", "ਖ਼", "ਫ਼", "ਲ਼", "ਸ਼")
private val ROW_5 = listOf("ਾ", "ਿ", "ੀ", "ੁ", "ੂ", "ੇ", "ੈ", "ੋ", "ੌ", "ੰ")
private val ROW_6 = listOf("ਂ", "ੱ", "੍", "਼", "।", "॥")

@Composable
fun GurmukhiKeyboard(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    fun tap() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
            KeyRow(ROW_1) { tap(); onKey(it) }
            KeyRow(ROW_2) { tap(); onKey(it) }
            KeyRow(ROW_3) { tap(); onKey(it) }
            KeyRow(ROW_4) { tap(); onKey(it) }
            KeyRow(ROW_5) { tap(); onKey(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ROW_6.forEach { k ->
                    KeyCap(k, Modifier.weight(1f)) { tap(); onKey(k) }
                }
                WideKey("space", Modifier.weight(2.4f)) { tap(); onKey(" ") }
                BackspaceKey(Modifier.weight(2.4f), onClick = { tap(); onBackspace() })
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun KeyRow(keys: List<String>, onKey: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        keys.forEach { k ->
            KeyCap(k, Modifier.weight(1f).aspectRatio(1.25f)) { onKey(k) }
        }
    }
}

@Composable
private fun KeyCap(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 1.dp,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun WideKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 1.dp,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Vector backspace glyph — some device fonts lack U+232B and render it blank. */
@Composable
private fun BackspaceKey(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 1.dp,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            Modifier.padding(vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Canvas(Modifier.size(width = 26.dp, height = 17.dp)) {
                val w = size.width
                val h = size.height
                val stroke = Stroke(width = 1.7.dp.toPx(), join = StrokeJoin.Round)
                val body = Path().apply {
                    moveTo(w * 0.34f, h * 0.06f)
                    lineTo(w, h * 0.06f)
                    lineTo(w, h * 0.94f)
                    lineTo(w * 0.34f, h * 0.94f)
                    lineTo(0f, h * 0.5f)
                    close()
                }
                drawPath(body, tint, style = stroke)
                val cx = w * 0.64f
                val cy = h * 0.5f
                val r = h * 0.18f
                drawLine(tint, Offset(cx - r, cy - r), Offset(cx + r, cy + r), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(cx - r, cy + r), Offset(cx + r, cy - r), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}
