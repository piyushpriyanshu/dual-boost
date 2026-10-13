package com.dualboost.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Original 5×7 dot-matrix glyphs ('#' = lit dot). Dot is 1 column wide. */
private val GLYPHS: Map<Char, Array<String>> = mapOf(
    '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
    '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    '3' to arrayOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
    '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
    '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
    '6' to arrayOf(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
    '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
    '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
    '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
    '.' to arrayOf(".", ".", ".", ".", ".", ".", "#"),
    '-' to arrayOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
)

/**
 * Renders [text] as a dot-matrix display. Unlit dots are drawn faintly so the grid reads as a panel.
 * [dot] is the pitch between dot centres.
 */
@Composable
fun DotMatrixText(
    text: String,
    lit: Color,
    unlit: Color,
    modifier: Modifier = Modifier,
    dot: Dp = 9.dp,
) {
    val glyphs = text.map { GLYPHS[it] ?: GLYPHS.getValue('-') }
    val columns = glyphs.sumOf { it[0].length } + (glyphs.size - 1).coerceAtLeast(0)
    Canvas(modifier.size(dot * columns, dot * 7)) {
        val pitch = dot.toPx()
        val r = pitch * 0.36f
        var x0 = 0
        for (g in glyphs) {
            for (row in 0 until 7) {
                val line = g[row]
                for (col in line.indices) {
                    val on = line[col] == '#'
                    drawCircle(
                        color = if (on) lit else unlit,
                        radius = if (on) r else r * 0.62f,
                        center = Offset((x0 + col) * pitch + pitch / 2, row * pitch + pitch / 2),
                    )
                }
            }
            x0 += g[0].length + 1
        }
    }
}
