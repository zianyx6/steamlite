package org.steamlite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hand drawn 24×24 line icons. Kept as Canvas primitives (no icon dependency,
 * no vector resources) so the APK stays small and the strokes stay consistent.
 */
enum class Glyph {
    Store, Heart, Chat, User, Search, Filter,
    ChevronRight, ChevronDown, Back, More, Send, Emoji,
    Refresh, Alert, Info, Close, Copy, Bell, Shield, Logout,
    Users, Check, Plus, Mark, Image, Phone, Pin, Sun, Moon, Qr, Download
}

@Composable
fun SteamIcon(
    glyph: Glyph,
    dimension: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    strokeScale: Float = 1.7f,
    filled: Boolean = false
) {
    Canvas(Modifier.size(dimension)) {
        val u = this.size.width / 24f
        val sw = strokeScale * u
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), sw, StrokeCap.Round)
        fun dot(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, Offset(x * u, y * u))
        fun circle(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, Offset(x * u, y * u), style = stroke)
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(tint, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u, r * u), style = stroke)

        when (glyph) {
            Glyph.Download -> {
                line(12f, 3f, 12f, 15f)
                line(7f, 10f, 12f, 15f)
                line(12f, 15f, 17f, 10f)
                line(4f, 17f, 4f, 21f)
                line(4f, 21f, 20f, 21f)
                line(20f, 21f, 20f, 17f)
            }
            Glyph.Sun -> {
                circle(12f, 12f, 4f)
                for (step in 0..7) {
                    val angle = step * Math.PI / 4
                    val x = kotlin.math.cos(angle).toFloat()
                    val y = kotlin.math.sin(angle).toFloat()
                    line(12 + 7 * x, 12 + 7 * y, 12 + 10 * x, 12 + 10 * y)
                }
            }
            Glyph.Moon -> {
                val moon = Path().apply {
                    moveTo(15 * u, 3 * u)
                    cubicTo(3 * u, 1 * u, 0f, 17 * u, 10 * u, 21 * u)
                    cubicTo(15 * u, 23 * u, 21 * u, 19 * u, 21 * u, 14 * u)
                    cubicTo(13 * u, 17 * u, 8 * u, 8 * u, 15 * u, 3 * u)
                    close()
                }
                drawPath(moon, tint, style = stroke)
            }
            Glyph.Qr -> {
                rect(3f, 3f, 7f, 7f, 1f)
                rect(14f, 3f, 7f, 7f, 1f)
                rect(3f, 14f, 7f, 7f, 1f)
                dot(6.5f, 6.5f, 1f)
                dot(17.5f, 6.5f, 1f)
                dot(6.5f, 17.5f, 1f)
                line(14f, 14f, 14f, 21f)
                line(18f, 14f, 21f, 14f)
                line(18f, 18f, 21f, 18f)
                line(21f, 18f, 21f, 21f)
            }
            Glyph.Pin -> {
                line(8f, 3f, 16f, 3f); line(9f, 3f, 9f, 10f); line(15f, 3f, 15f, 10f)
                line(9f, 10f, 6f, 14f); line(6f, 14f, 18f, 14f); line(18f, 14f, 15f, 10f)
                line(12f, 14f, 12f, 21f)
            }
            Glyph.Image -> {
                rect(3f, 3f, 18f, 18f, 3f); circle(8f, 8f, 1.5f)
                line(4f, 17f, 9f, 12f); line(9f, 12f, 13f, 16f); line(13f, 16f, 17f, 11f); line(17f, 11f, 21f, 15f)
            }
            Glyph.Phone -> {
                val p = Path().apply {
                    moveTo(6f*u, 3f*u); lineTo(3f*u, 5f*u)
                    cubicTo(2f*u, 13f*u, 11f*u, 22f*u, 19f*u, 21f*u)
                    lineTo(21f*u, 18f*u); lineTo(16f*u, 14f*u); lineTo(13f*u, 16f*u)
                    cubicTo(10f*u, 15f*u, 9f*u, 14f*u, 8f*u, 11f*u)
                    lineTo(10f*u, 8f*u); close()
                }
                drawPath(p, tint, style = stroke)
            }
            Glyph.Store -> {
                rect(3.5f, 3.5f, 7f, 7f, 2.2f); rect(13.5f, 3.5f, 7f, 7f, 2.2f)
                rect(3.5f, 13.5f, 7f, 7f, 2.2f); rect(13.5f, 13.5f, 7f, 7f, 2.2f)
            }
            Glyph.Heart -> {
                val p = Path().apply {
                    moveTo(12f * u, 20.2f * u)
                    cubicTo(3.8f * u, 14.7f * u, 3.4f * u, 8.9f * u, 7.2f * u, 6.7f * u)
                    cubicTo(9.5f * u, 5.5f * u, 11.2f * u, 6.6f * u, 12f * u, 8.3f * u)
                    cubicTo(12.8f * u, 6.6f * u, 14.5f * u, 5.5f * u, 16.8f * u, 6.7f * u)
                    cubicTo(20.6f * u, 8.9f * u, 20.2f * u, 14.7f * u, 12f * u, 20.2f * u)
                    close()
                }
                if (filled) drawPath(p, tint) else drawPath(p, tint, style = stroke)
            }
            Glyph.Chat -> {
                val p = Path().apply {
                    moveTo(20.5f * u, 12.2f * u)
                    cubicTo(20.5f * u, 16.1f * u, 16.7f * u, 19.2f * u, 12f * u, 19.2f * u)
                    cubicTo(11.1f * u, 19.2f * u, 10.1f * u, 19.1f * u, 9.3f * u, 18.8f * u)
                    lineTo(4.2f * u, 20.9f * u)
                    lineTo(5.6f * u, 17f * u)
                    cubicTo(4.3f * u, 15.7f * u, 3.5f * u, 14f * u, 3.5f * u, 12.2f * u)
                    cubicTo(3.5f * u, 8.3f * u, 7.3f * u, 5.2f * u, 12f * u, 5.2f * u)
                    cubicTo(16.7f * u, 5.2f * u, 20.5f * u, 8.3f * u, 20.5f * u, 12.2f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
            }
            Glyph.User -> {
                circle(12f, 8.4f, 3.6f)
                val p = Path().apply {
                    moveTo(4.8f * u, 20f * u)
                    cubicTo(5.6f * u, 16.5f * u, 8.5f * u, 14.3f * u, 12f * u, 14.3f * u)
                    cubicTo(15.5f * u, 14.3f * u, 18.4f * u, 16.5f * u, 19.2f * u, 20f * u)
                }
                drawPath(p, tint, style = stroke)
            }
            Glyph.Search -> { circle(11f, 11f, 6.6f); line(15.9f, 15.9f, 20.4f, 20.4f) }
            Glyph.Filter -> {
                line(4f, 8f, 14f, 8f); line(19f, 8f, 20f, 8f)
                line(4f, 16f, 7f, 16f); line(12f, 16f, 20f, 16f)
                circle(16.5f, 8f, 2.3f); circle(9.5f, 16f, 2.3f)
            }
            Glyph.ChevronRight -> { line(9.5f, 5.5f, 16f, 12f); line(16f, 12f, 9.5f, 18.5f) }
            Glyph.ChevronDown -> { line(6.5f, 9.5f, 12f, 15f); line(12f, 15f, 17.5f, 9.5f) }
            Glyph.Back -> { line(14.5f, 5f, 7.5f, 12f); line(7.5f, 12f, 14.5f, 19f) }
            Glyph.More -> { dot(5.4f, 12f, 1.5f); dot(12f, 12f, 1.5f); dot(18.6f, 12f, 1.5f) }
            Glyph.Send -> {
                line(12f, 19.5f, 12f, 5.2f)
                line(6.2f, 10.6f, 12f, 4.8f); line(17.8f, 10.6f, 12f, 4.8f)
            }
            Glyph.Emoji -> {
                circle(12f, 12f, 8.4f)
                dot(9.3f, 9.7f, 0.9f); dot(14.7f, 9.7f, 0.9f)
                val p = Path().apply {
                    moveTo(8.6f * u, 13.6f * u)
                    cubicTo(9.7f * u, 15.8f * u, 14.3f * u, 15.8f * u, 15.4f * u, 13.6f * u)
                }
                drawPath(p, tint, style = stroke)
            }
            Glyph.Refresh -> {
                drawArc(
                    color = tint, startAngle = 30f, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(3.6f * u, 3.6f * u), size = Size(16.8f * u, 16.8f * u), style = stroke
                )
                line(19.3f, 3.4f, 19.3f, 7.9f); line(19.3f, 7.9f, 14.8f, 7.9f)
            }
            Glyph.Alert -> {
                val p = Path().apply {
                    moveTo(12f * u, 4.6f * u); lineTo(21f * u, 19f * u); lineTo(3f * u, 19f * u); close()
                }
                drawPath(p, tint, style = stroke)
                line(12f, 10f, 12f, 14f); dot(12f, 16.6f, 0.9f)
            }
            Glyph.Info -> { circle(12f, 12f, 8.4f); line(12f, 11f, 12f, 16.4f); dot(12f, 7.9f, 0.9f) }
            Glyph.Close -> { line(6.4f, 6.4f, 17.6f, 17.6f); line(17.6f, 6.4f, 6.4f, 17.6f) }
            Glyph.Copy -> {
                rect(9f, 9f, 11f, 11f, 2.6f)
                val p = Path().apply {
                    moveTo(15.5f * u, 6.4f * u)
                    cubicTo(15.5f * u, 5.2f * u, 14.5f * u, 4.2f * u, 13.3f * u, 4.2f * u)
                    lineTo(6.6f * u, 4.2f * u)
                    cubicTo(5.4f * u, 4.2f * u, 4.4f * u, 5.2f * u, 4.4f * u, 6.4f * u)
                    lineTo(4.4f * u, 13.1f * u)
                    cubicTo(4.4f * u, 14.3f * u, 5.4f * u, 15.3f * u, 6.6f * u, 15.3f * u)
                }
                drawPath(p, tint, style = stroke)
            }
            Glyph.Bell -> {
                val p = Path().apply {
                    moveTo(18.2f * u, 9.4f * u)
                    cubicTo(18.2f * u, 5.98f * u, 15.42f * u, 3.2f * u, 12f * u, 3.2f * u)
                    cubicTo(8.58f * u, 3.2f * u, 5.8f * u, 5.98f * u, 5.8f * u, 9.4f * u)
                    cubicTo(5.8f * u, 14.6f * u, 3.7f * u, 15.7f * u, 3.7f * u, 15.7f * u)
                    lineTo(20.3f * u, 15.7f * u)
                    cubicTo(20.3f * u, 15.7f * u, 18.2f * u, 14.6f * u, 18.2f * u, 9.4f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
                val mouth = Path().apply {
                    moveTo(10.2f * u, 19.2f * u)
                    cubicTo(11f * u, 20.5f * u, 13f * u, 20.5f * u, 13.8f * u, 19.2f * u)
                }
                drawPath(mouth, tint, style = stroke)
            }
            Glyph.Shield -> {
                val p = Path().apply {
                    moveTo(12f * u, 3.3f * u); lineTo(19.3f * u, 6f * u); lineTo(19.3f * u, 12f * u)
                    cubicTo(19.3f * u, 16.4f * u, 16.2f * u, 19.5f * u, 12f * u, 20.7f * u)
                    cubicTo(7.8f * u, 19.5f * u, 4.7f * u, 16.4f * u, 4.7f * u, 12f * u)
                    lineTo(4.7f * u, 6f * u); close()
                }
                drawPath(p, tint, style = stroke)
                val check = Path().apply {
                    moveTo(9.2f * u, 12.1f * u); lineTo(11.2f * u, 14.1f * u); lineTo(14.8f * u, 10.3f * u)
                }
                drawPath(check, tint, style = stroke)
            }
            Glyph.Logout -> {
                val p = Path().apply {
                    moveTo(10.2f * u, 5f * u); lineTo(6.4f * u, 5f * u)
                    cubicTo(5.1f * u, 5f * u, 4f * u, 6.1f * u, 4f * u, 7.4f * u)
                    lineTo(4f * u, 16.6f * u)
                    cubicTo(4f * u, 17.9f * u, 5.1f * u, 19f * u, 6.4f * u, 19f * u)
                    lineTo(10.2f * u, 19f * u)
                }
                drawPath(p, tint, style = stroke)
                line(9.6f, 12f, 19.2f, 12f)
                line(15.4f, 8.2f, 19.2f, 12f); line(19.2f, 12f, 15.4f, 15.8f)
            }
            Glyph.Users -> {
                circle(9.4f, 8.8f, 3.2f)
                circle(16.4f, 9.4f, 2.4f)
                val a = Path().apply {
                    moveTo(3.4f * u, 19.4f * u)
                    cubicTo(4.1f * u, 16.2f * u, 6.6f * u, 14.2f * u, 9.6f * u, 14.2f * u)
                    cubicTo(12.6f * u, 14.2f * u, 15.1f * u, 16.2f * u, 15.8f * u, 19.4f * u)
                }
                drawPath(a, tint, style = stroke)
                val b = Path().apply {
                    moveTo(16.6f * u, 14.9f * u)
                    cubicTo(18.3f * u, 15.5f * u, 19.5f * u, 17.1f * u, 19.9f * u, 19.4f * u)
                }
                drawPath(b, tint, style = stroke)
            }
            Glyph.Check -> { line(5f, 12.6f, 9.8f, 17.4f); line(9.8f, 17.4f, 19f, 7.4f) }
            Glyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Glyph.Mark -> {
                drawCircle(tint, 9f * u, Offset(12f * u, 12f * u), style = Stroke(width = 1.9f * u))
                dot(15.4f, 8.9f, 2.5f)
                drawLine(tint, Offset(5.4f * u, 15.1f * u), Offset(10.6f * u, 17f * u), 1.9f * u, StrokeCap.Round)
            }
        }
    }
}

/** Brand tile in the top bar. */
@Composable
fun BrandMark(dimension: Dp = 23.dp) {
    Box(
        Modifier
            .size(dimension)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2E74C4), Color(0xFF64BEF2)))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "S",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            style = TextStyle(letterSpacing = (-0.2).sp)
        )
    }
}
