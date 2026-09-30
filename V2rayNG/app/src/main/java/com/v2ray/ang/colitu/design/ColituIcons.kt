package com.v2ray.ang.colitu.design

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import kotlin.math.cos
import kotlin.math.sin

/**
 * Line and glyph icons drawn on a 24-unit grid in the spirit of the SF Symbols
 * the iOS app uses. Kept in code so the APK does not carry an icon font.
 */
object ColituIcons {
    private fun icon(name: String, block: IconScope.() -> Unit): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        IconScope(builder).block()
        return builder.build()
    }

    class IconScope(private val builder: ImageVector.Builder) {
        fun stroke(width: Float = 1.9f, path: PathBuilder.() -> Unit) {
            val nodes = PathBuilder().apply(path).nodes
            builder.addPath(
                pathData = nodes,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

        fun fill(path: PathBuilder.() -> Unit) {
            val nodes = PathBuilder().apply(path).nodes
            builder.addPath(pathData = nodes, fill = SolidColor(Color.Black))
        }
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, false, false, cx + r, cy)
        arcTo(r, r, 0f, false, false, cx - r, cy)
        close()
    }

    private fun PathBuilder.shield() {
        moveTo(12f, 2.5f)
        lineTo(20f, 5.5f)
        verticalLineTo(11f)
        curveTo(20f, 16f, 16.5f, 19.8f, 12f, 21.5f)
        curveTo(7.5f, 19.8f, 4f, 16f, 4f, 11f)
        verticalLineTo(5.5f)
        close()
    }

    val House = icon("house") {
        fill {
            moveTo(2.6f, 11.2f)
            lineTo(12f, 3.2f)
            lineTo(21.4f, 11.2f)
            lineTo(20f, 12.6f)
            lineTo(19.5f, 12.2f)
            verticalLineTo(20f)
            curveTo(19.5f, 20.6f, 19.1f, 21f, 18.5f, 21f)
            horizontalLineTo(14.5f)
            verticalLineTo(15f)
            horizontalLineTo(9.5f)
            verticalLineTo(21f)
            horizontalLineTo(5.5f)
            curveTo(4.9f, 21f, 4.5f, 20.6f, 4.5f, 20f)
            verticalLineTo(12.2f)
            lineTo(4f, 12.6f)
            close()
        }
    }

    val Globe = icon("globe") {
        stroke {
            circle(12f, 12f, 9f)
            moveTo(12f, 3f)
            arcTo(4.4f, 9f, 0f, false, false, 12f, 21f)
            arcTo(4.4f, 9f, 0f, false, false, 12f, 3f)
            moveTo(3f, 12f)
            lineTo(21f, 12f)
            moveTo(4.6f, 7.5f)
            lineTo(19.4f, 7.5f)
            moveTo(4.6f, 16.5f)
            lineTo(19.4f, 16.5f)
        }
    }

    val Gift = icon("gift") {
        fill {
            moveTo(3f, 8f)
            horizontalLineTo(11.1f)
            verticalLineTo(12f)
            horizontalLineTo(3f)
            close()
            moveTo(12.9f, 8f)
            horizontalLineTo(21f)
            verticalLineTo(12f)
            horizontalLineTo(12.9f)
            close()
            moveTo(4.4f, 13.2f)
            horizontalLineTo(11.1f)
            verticalLineTo(21f)
            horizontalLineTo(5.4f)
            curveTo(4.8f, 21f, 4.4f, 20.6f, 4.4f, 20f)
            close()
            moveTo(12.9f, 13.2f)
            horizontalLineTo(19.6f)
            verticalLineTo(20f)
            curveTo(19.6f, 20.6f, 19.2f, 21f, 18.6f, 21f)
            horizontalLineTo(12.9f)
            close()
        }
        stroke(1.7f) {
            moveTo(12f, 7.6f)
            curveTo(10.8f, 4.2f, 8.4f, 3.2f, 7.3f, 4.6f)
            curveTo(6.3f, 5.9f, 7.8f, 7.6f, 12f, 7.6f)
            moveTo(12f, 7.6f)
            curveTo(13.2f, 4.2f, 15.6f, 3.2f, 16.7f, 4.6f)
            curveTo(17.7f, 5.9f, 16.2f, 7.6f, 12f, 7.6f)
        }
    }

    val Person = icon("person") {
        fill {
            circle(12f, 7.8f, 4.3f)
            moveTo(3.8f, 20.2f)
            curveTo(3.8f, 15.6f, 7.5f, 13.6f, 12f, 13.6f)
            curveTo(16.5f, 13.6f, 20.2f, 15.6f, 20.2f, 20.2f)
            curveTo(20.2f, 20.7f, 19.8f, 21f, 19.3f, 21f)
            horizontalLineTo(4.7f)
            curveTo(4.2f, 21f, 3.8f, 20.7f, 3.8f, 20.2f)
            close()
        }
    }

    val Shield = icon("shield") { fill { shield() } }

    val ShieldCheck = icon("shield-check") {
        stroke { shield() }
        stroke(2.1f) {
            moveTo(8.4f, 12f)
            lineTo(11f, 14.6f)
            lineTo(15.8f, 9.4f)
        }
    }

    val ShieldHalf = icon("shield-half") {
        stroke { shield() }
        fill {
            moveTo(12f, 2.5f)
            lineTo(4f, 5.5f)
            verticalLineTo(11f)
            curveTo(4f, 16f, 7.5f, 19.8f, 12f, 21.5f)
            close()
        }
    }

    val LockShield = icon("lock-shield") {
        stroke { shield() }
        fill {
            moveTo(9f, 11.5f)
            horizontalLineTo(15f)
            verticalLineTo(16.5f)
            horizontalLineTo(9f)
            close()
        }
        stroke(1.6f) {
            moveTo(10.2f, 11.5f)
            verticalLineTo(10f)
            arcTo(1.8f, 1.8f, 0f, false, true, 13.8f, 10f)
            verticalLineTo(11.5f)
        }
    }

    val Star = icon("star") {
        fill {
            for (i in 0 until 10) {
                val angle = Math.toRadians(-90.0 + i * 36.0)
                val radius = if (i % 2 == 0) 9.6 else 4.2
                val x = (12 + radius * cos(angle)).toFloat()
                val y = (12.6 + radius * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    val Bolt = icon("bolt") {
        fill {
            moveTo(13.6f, 2f)
            lineTo(4.6f, 13.6f)
            horizontalLineTo(11.2f)
            lineTo(10.2f, 22f)
            lineTo(19.4f, 10f)
            horizontalLineTo(12.8f)
            close()
        }
    }

    val Power = icon("power") {
        stroke(2.1f) {
            moveTo(16.3f, 7.4f)
            arcTo(7.4f, 7.4f, 0f, true, true, 7.7f, 7.4f)
            moveTo(12f, 3.4f)
            verticalLineTo(12f)
        }
    }

    val ArrowUp = icon("arrow-up") {
        stroke(2f) {
            moveTo(12f, 20f)
            verticalLineTo(4.5f)
            moveTo(6f, 10.5f)
            lineTo(12f, 4.5f)
            lineTo(18f, 10.5f)
        }
    }

    val ArrowDown = icon("arrow-down") {
        stroke(2f) {
            moveTo(12f, 4f)
            verticalLineTo(19.5f)
            moveTo(6f, 13.5f)
            lineTo(12f, 19.5f)
            lineTo(18f, 13.5f)
        }
    }

    val ArrowRight = icon("arrow-right") {
        stroke(2f) {
            moveTo(4f, 12f)
            horizontalLineTo(19.5f)
            moveTo(13.5f, 6f)
            lineTo(19.5f, 12f)
            lineTo(13.5f, 18f)
        }
    }

    val ArrowUpRight = icon("arrow-up-right") {
        stroke(2f) {
            moveTo(7f, 17f)
            lineTo(17f, 7f)
            moveTo(9f, 7f)
            horizontalLineTo(17f)
            verticalLineTo(15f)
        }
    }

    val ArrowUpRightSquare = icon("arrow-up-right-square") {
        stroke {
            moveTo(13.5f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(10.5f)
            moveTo(20f, 4f)
            lineTo(11f, 13f)
            moveTo(17.5f, 14f)
            verticalLineTo(19f)
            curveTo(17.5f, 19.6f, 17.1f, 20f, 16.5f, 20f)
            horizontalLineTo(5f)
            curveTo(4.4f, 20f, 4f, 19.6f, 4f, 19f)
            verticalLineTo(7.5f)
            curveTo(4f, 6.9f, 4.4f, 6.5f, 5f, 6.5f)
            horizontalLineTo(10f)
        }
    }

    val ChevronRight = icon("chevron-right") {
        stroke(2.2f) {
            moveTo(9f, 5f)
            lineTo(16f, 12f)
            lineTo(9f, 19f)
        }
    }

    val ChevronLeft = icon("chevron-left") {
        stroke(2.2f) {
            moveTo(15f, 5f)
            lineTo(8f, 12f)
            lineTo(15f, 19f)
        }
    }

    val CheckCircle = icon("check-circle") {
        stroke { circle(12f, 12f, 9f) }
        stroke(2.1f) {
            moveTo(8f, 12.4f)
            lineTo(10.9f, 15.2f)
            lineTo(16.2f, 9.2f)
        }
    }

    val SealCheck = icon("seal-check") {
        stroke {
            for (i in 0 until 24) {
                val angle = Math.toRadians(i * 15.0)
                val radius = if (i % 2 == 0) 9.6 else 8.2
                val x = (12 + radius * cos(angle)).toFloat()
                val y = (12 + radius * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        stroke(2.1f) {
            moveTo(8f, 12.4f)
            lineTo(10.9f, 15.2f)
            lineTo(16.2f, 9.2f)
        }
    }

    val XOctagon = icon("x-octagon") {
        stroke {
            for (i in 0 until 8) {
                val angle = Math.toRadians(22.5 + i * 45.0)
                val x = (12 + 9.4 * cos(angle)).toFloat()
                val y = (12 + 9.4 * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        stroke(2f) {
            moveTo(9f, 9f)
            lineTo(15f, 15f)
            moveTo(15f, 9f)
            lineTo(9f, 15f)
        }
    }

    val Info = icon("info") {
        stroke { circle(12f, 12f, 9f) }
        stroke(2f) {
            moveTo(12f, 11f)
            verticalLineTo(16.5f)
        }
        fill { circle(12f, 7.8f, 1.2f) }
    }

    val Question = icon("question") {
        stroke { circle(12f, 12f, 9f) }
        stroke(2f) {
            moveTo(9.4f, 9.6f)
            curveTo(9.4f, 6.4f, 14.6f, 6.4f, 14.6f, 9.6f)
            curveTo(14.6f, 11.8f, 12f, 11.9f, 12f, 14.2f)
        }
        fill { circle(12f, 17.2f, 1.2f) }
    }

    val Warning = icon("warning") {
        stroke {
            moveTo(12f, 3.6f)
            lineTo(21.4f, 19.8f)
            horizontalLineTo(2.6f)
            close()
        }
        stroke(2f) {
            moveTo(12f, 9.6f)
            verticalLineTo(14.2f)
        }
        fill { circle(12f, 17f, 1.1f) }
    }

    val Search = icon("search") {
        stroke(2f) {
            circle(10.5f, 10.5f, 6.5f)
            moveTo(15.4f, 15.4f)
            lineTo(20.5f, 20.5f)
        }
    }

    val XMark = icon("x-mark") {
        stroke(2f) {
            moveTo(7f, 7f)
            lineTo(17f, 17f)
            moveTo(17f, 7f)
            lineTo(7f, 17f)
        }
    }

    val XCircleOutline = icon("x-circle-outline") {
        stroke { circle(12f, 12f, 9f) }
        stroke(1.9f) {
            moveTo(9.2f, 9.2f)
            lineTo(14.8f, 14.8f)
            moveTo(14.8f, 9.2f)
            lineTo(9.2f, 14.8f)
        }
    }

    val Mail = icon("mail") {
        stroke {
            moveTo(4.5f, 5.5f)
            horizontalLineTo(19.5f)
            curveTo(20.3f, 5.5f, 21f, 6.2f, 21f, 7f)
            verticalLineTo(17f)
            curveTo(21f, 17.8f, 20.3f, 18.5f, 19.5f, 18.5f)
            horizontalLineTo(4.5f)
            curveTo(3.7f, 18.5f, 3f, 17.8f, 3f, 17f)
            verticalLineTo(7f)
            curveTo(3f, 6.2f, 3.7f, 5.5f, 4.5f, 5.5f)
            close()
            moveTo(3.6f, 6.6f)
            lineTo(12f, 12.8f)
            lineTo(20.4f, 6.6f)
        }
    }

    val Lock = icon("lock") {
        stroke {
            moveTo(6f, 10.5f)
            horizontalLineTo(18f)
            curveTo(18.8f, 10.5f, 19.5f, 11.2f, 19.5f, 12f)
            verticalLineTo(19.5f)
            curveTo(19.5f, 20.3f, 18.8f, 21f, 18f, 21f)
            horizontalLineTo(6f)
            curveTo(5.2f, 21f, 4.5f, 20.3f, 4.5f, 19.5f)
            verticalLineTo(12f)
            curveTo(4.5f, 11.2f, 5.2f, 10.5f, 6f, 10.5f)
            close()
            moveTo(8f, 10.5f)
            verticalLineTo(7.5f)
            arcTo(4f, 4f, 0f, false, true, 16f, 7.5f)
            verticalLineTo(10.5f)
        }
    }

    val Eye = icon("eye") {
        stroke {
            moveTo(2.5f, 12f)
            curveTo(4.8f, 7.3f, 8.2f, 5f, 12f, 5f)
            curveTo(15.8f, 5f, 19.2f, 7.3f, 21.5f, 12f)
            curveTo(19.2f, 16.7f, 15.8f, 19f, 12f, 19f)
            curveTo(8.2f, 19f, 4.8f, 16.7f, 2.5f, 12f)
            close()
            circle(12f, 12f, 3.2f)
        }
    }

    val EyeSlash = icon("eye-slash") {
        stroke {
            moveTo(2.5f, 12f)
            curveTo(4.8f, 7.3f, 8.2f, 5f, 12f, 5f)
            curveTo(15.8f, 5f, 19.2f, 7.3f, 21.5f, 12f)
            curveTo(19.2f, 16.7f, 15.8f, 19f, 12f, 19f)
            curveTo(8.2f, 19f, 4.8f, 16.7f, 2.5f, 12f)
            close()
            circle(12f, 12f, 3.2f)
            moveTo(4f, 3.5f)
            lineTo(20f, 20.5f)
        }
    }

    val Plus = icon("plus") {
        stroke(2.2f) {
            moveTo(12f, 5f)
            verticalLineTo(19f)
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
    }

    val Minus = icon("minus") {
        stroke(2.2f) {
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
    }

    val Check = icon("check") {
        stroke(2.4f) {
            moveTo(5f, 12.5f)
            lineTo(10f, 17.5f)
            lineTo(19f, 7f)
        }
    }

    val Antenna = icon("antenna") {
        fill { circle(12f, 12f, 1.8f) }
        stroke {
            moveTo(8.6f, 8.6f)
            arcTo(4.8f, 4.8f, 0f, false, false, 8.6f, 15.4f)
            moveTo(15.4f, 8.6f)
            arcTo(4.8f, 4.8f, 0f, false, true, 15.4f, 15.4f)
            moveTo(5.6f, 5.6f)
            arcTo(9f, 9f, 0f, false, false, 5.6f, 18.4f)
            moveTo(18.4f, 5.6f)
            arcTo(9f, 9f, 0f, false, true, 18.4f, 18.4f)
        }
    }

    val PlayCircle = icon("play-circle") {
        stroke { circle(12f, 12f, 9f) }
        fill {
            moveTo(10f, 8.3f)
            lineTo(16f, 12f)
            lineTo(10f, 15.7f)
            close()
        }
    }

    val Doc = icon("doc") {
        stroke {
            moveTo(6.5f, 3f)
            horizontalLineTo(13.5f)
            lineTo(18.5f, 8f)
            verticalLineTo(19.5f)
            curveTo(18.5f, 20.3f, 17.8f, 21f, 17f, 21f)
            horizontalLineTo(6.5f)
            curveTo(5.7f, 21f, 5f, 20.3f, 5f, 19.5f)
            verticalLineTo(4.5f)
            curveTo(5f, 3.7f, 5.7f, 3f, 6.5f, 3f)
            close()
            moveTo(13.5f, 3f)
            verticalLineTo(8f)
            horizontalLineTo(18.5f)
            moveTo(8.5f, 12.5f)
            horizontalLineTo(15f)
            moveTo(8.5f, 16.5f)
            horizontalLineTo(15f)
        }
    }

    val SignOut = icon("sign-out") {
        stroke {
            moveTo(13.5f, 4f)
            horizontalLineTo(6.5f)
            curveTo(5.7f, 4f, 5f, 4.7f, 5f, 5.5f)
            verticalLineTo(18.5f)
            curveTo(5f, 19.3f, 5.7f, 20f, 6.5f, 20f)
            horizontalLineTo(13.5f)
            moveTo(10f, 12f)
            horizontalLineTo(20.5f)
            moveTo(16.5f, 8f)
            lineTo(20.5f, 12f)
            lineTo(16.5f, 16f)
        }
    }

    val Phone = icon("phone") {
        stroke {
            moveTo(8.5f, 2.5f)
            horizontalLineTo(15.5f)
            curveTo(16.6f, 2.5f, 17.5f, 3.4f, 17.5f, 4.5f)
            verticalLineTo(19.5f)
            curveTo(17.5f, 20.6f, 16.6f, 21.5f, 15.5f, 21.5f)
            horizontalLineTo(8.5f)
            curveTo(7.4f, 21.5f, 6.5f, 20.6f, 6.5f, 19.5f)
            verticalLineTo(4.5f)
            curveTo(6.5f, 3.4f, 7.4f, 2.5f, 8.5f, 2.5f)
            close()
            moveTo(10.5f, 18.4f)
            horizontalLineTo(13.5f)
        }
    }

    val Laptop = icon("laptop") {
        stroke {
            moveTo(5.5f, 5f)
            horizontalLineTo(18.5f)
            curveTo(19.1f, 5f, 19.5f, 5.4f, 19.5f, 6f)
            verticalLineTo(15.5f)
            horizontalLineTo(4.5f)
            verticalLineTo(6f)
            curveTo(4.5f, 5.4f, 4.9f, 5f, 5.5f, 5f)
            close()
            moveTo(2.5f, 18.5f)
            horizontalLineTo(21.5f)
        }
    }

    val Desktop = icon("desktop") {
        stroke {
            moveTo(4f, 4f)
            horizontalLineTo(20f)
            curveTo(20.6f, 4f, 21f, 4.4f, 21f, 5f)
            verticalLineTo(15f)
            curveTo(21f, 15.6f, 20.6f, 16f, 20f, 16f)
            horizontalLineTo(4f)
            curveTo(3.4f, 16f, 3f, 15.6f, 3f, 15f)
            verticalLineTo(5f)
            curveTo(3f, 4.4f, 3.4f, 4f, 4f, 4f)
            close()
            moveTo(12f, 16f)
            verticalLineTo(20f)
            moveTo(8f, 20.5f)
            horizontalLineTo(16f)
        }
    }

    val Ellipsis = icon("ellipsis") {
        fill {
            circle(5.5f, 12f, 1.7f)
            circle(12f, 12f, 1.7f)
            circle(18.5f, 12f, 1.7f)
        }
    }

    val Chat = icon("chat") {
        stroke {
            moveTo(4.5f, 5f)
            horizontalLineTo(19.5f)
            curveTo(20.3f, 5f, 21f, 5.7f, 21f, 6.5f)
            verticalLineTo(15.5f)
            curveTo(21f, 16.3f, 20.3f, 17f, 19.5f, 17f)
            horizontalLineTo(10f)
            lineTo(5f, 21f)
            verticalLineTo(17f)
            horizontalLineTo(4.5f)
            curveTo(3.7f, 17f, 3f, 16.3f, 3f, 15.5f)
            verticalLineTo(6.5f)
            curveTo(3f, 5.7f, 3.7f, 5f, 4.5f, 5f)
            close()
            moveTo(8f, 10f)
            horizontalLineTo(16f)
            moveTo(8f, 13f)
            horizontalLineTo(13f)
        }
    }

    val Paperclip = icon("paperclip") {
        stroke(1.8f) {
            moveTo(20.5f, 11.5f)
            lineTo(12.3f, 19.7f)
            arcTo(5f, 5f, 0f, false, true, 5.2f, 12.6f)
            lineTo(13.8f, 4f)
            arcTo(3.3f, 3.3f, 0f, false, true, 18.5f, 8.7f)
            lineTo(9.9f, 17.3f)
            arcTo(1.7f, 1.7f, 0f, false, true, 7.5f, 14.9f)
            lineTo(15.4f, 7f)
        }
    }

    val Send = icon("send") {
        stroke(2f) {
            moveTo(21f, 3f)
            lineTo(10.5f, 13.5f)
            moveTo(21f, 3f)
            lineTo(14.5f, 21f)
            lineTo(10.5f, 13.5f)
            lineTo(3f, 9.5f)
            close()
        }
    }

    val Code = icon("code") {
        stroke(2f) {
            moveTo(8f, 7f)
            lineTo(3f, 12f)
            lineTo(8f, 17f)
            moveTo(16f, 7f)
            lineTo(21f, 12f)
            lineTo(16f, 17f)
            moveTo(13.5f, 4.5f)
            lineTo(10.5f, 19.5f)
        }
    }

    val Book = icon("book") {
        stroke {
            moveTo(4f, 5.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 3f)
            horizontalLineTo(20f)
            verticalLineTo(18f)
            horizontalLineTo(6.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 4f, 20.5f)
            close()
            moveTo(4f, 20.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 6.5f, 23f)
            horizontalLineTo(20f)
            verticalLineTo(18f)
        }
    }

    val Refresh = icon("refresh") {
        stroke(2f) {
            moveTo(19.5f, 12f)
            arcTo(7.5f, 7.5f, 0f, true, true, 16.8f, 6.3f)
            moveTo(17.5f, 2.8f)
            verticalLineTo(6.8f)
            horizontalLineTo(13.5f)
        }
    }
}

/** Tinted icon; the vector's own colour is ignored. */
@Composable
fun ColituIcon(
    icon: ImageVector,
    tint: Color,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier
            .size(size)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    )
}
