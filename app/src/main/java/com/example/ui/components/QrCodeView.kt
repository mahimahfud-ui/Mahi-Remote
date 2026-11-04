package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkCharcoalBg
import kotlin.math.abs

@Composable
fun QrCodeView(
    payload: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp
) {
    // Generate a deterministic 21x21 QR-like matrix with standard finder patterns
    val matrix = remember(payload) {
        generateQrMatrix(payload)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkCharcoalBg)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size - 32.dp)) {
            val n = 25
            val cellSize = this.size.width / n

            // Draw matrix cells
            for (r in 0 until n) {
                for (c in 0 until n) {
                    if (matrix[r][c]) {
                        drawRect(
                            color = CyanAccent,
                            topLeft = Offset(c * cellSize, r * cellSize),
                            size = Size(cellSize * 0.95f, cellSize * 0.95f)
                        )
                    }
                }
            }
        }
    }
}

private fun generateQrMatrix(data: String): Array<BooleanArray> {
    val n = 25
    val m = Array(n) { BooleanArray(n) }

    // Finder pattern helper
    fun drawFinder(top: Int, left: Int) {
        for (r in 0..6) {
            for (c in 0..6) {
                val isOuter = r == 0 || r == 6 || c == 0 || c == 6
                val isInner = r in 2..4 && c in 2..4
                m[top + r][left + c] = isOuter || isInner
            }
        }
    }

    drawFinder(0, 0)
    drawFinder(0, n - 7)
    drawFinder(n - 7, 0)

    // Timing patterns
    for (i in 8 until n - 8) {
        m[6][i] = (i % 2 == 0)
        m[i][6] = (i % 2 == 0)
    }

    // Deterministic payload hashing into cells
    val hash = abs(data.hashCode())
    var bitPos = 0
    val bytes = data.toByteArray()

    for (r in 0 until n) {
        for (c in 0 until n) {
            // Skip finder zones
            val inFinder1 = r < 8 && c < 8
            val inFinder2 = r < 8 && c >= n - 8
            val inFinder3 = r >= n - 8 && c < 8
            val inTiming = r == 6 || c == 6

            if (!inFinder1 && !inFinder2 && !inFinder3 && !inTiming) {
                val byteIndex = (bitPos / 8) % bytes.size
                val bitIndex = bitPos % 8
                val bitVal = (bytes[byteIndex].toInt() shr bitIndex) and 1
                val mask = ((r + c) * hash) % 3 == 0
                m[r][c] = (bitVal == 1) xor mask
                bitPos++
            }
        }
    }

    return m
}
