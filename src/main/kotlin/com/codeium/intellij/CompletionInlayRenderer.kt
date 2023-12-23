/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import kotlin.math.ceil

enum class InlayType {
  INLINE,
  BLOCK,
  AFTER_LINE,
}

class CompletionInlayRenderer(
    private val lines: List<String>,
    private val inlayType: InlayType,
    private val offset: Int,
) : EditorCustomElementRenderer {
  override fun calcWidthInPixels(inlay: Inlay<*>): Int {
    val fontMetrics =
        inlay.editor.contentComponent.getFontMetrics(
            inlay.editor.colorsScheme.getFont(EditorFontType.ITALIC))
    val longestLine = lines.maxByOrNull { it.length }!!
    return fontMetrics.stringWidth(longestLine)
  }

  override fun calcHeightInPixels(inlay: Inlay<*>): Int {
    val lineHeight = inlay.editor.lineHeight.toDouble()
    return ceil(lines.size * lineHeight).toInt()
  }

  /**
   * Draw the inlay completion element in the editor.
   *
   * @param inlay The inlay element to draw.
   * @param g The graphics context to draw on.
   * @param targetRegion The region to draw in.
   * @param textAttributes The text attributes to use for drawing.
   */
  override fun paint(
      inlay: Inlay<*>,
      g: Graphics,
      targetRegion: Rectangle,
      textAttributes: TextAttributes
  ) {
    val g2 = g.create() as Graphics2D
    GraphicsUtil.setupAAPainting(g2 as Graphics)

    lines.forEachIndexed(
        fun(index, line) {
          val fontMetrics =
              inlay.editor.contentComponent.getFontMetrics(
                  inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN))
          val lineHeight = inlay.editor.lineHeight.toDouble()
          val font: Font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
          val fontBaseline =
              ceil(font.createGlyphVector(fontMetrics.fontRenderContext, "A").visualBounds.height)
          val linePadding = (lineHeight - fontBaseline) / 2.0
          val offsetX = targetRegion.x.toFloat()
          val offsetY = (targetRegion.y + fontBaseline + linePadding + index * lineHeight).toFloat()
          g2.color = JBColor.GRAY
          g2.font = font
          g2.drawString(line, offsetX, offsetY)
        })
    g2.dispose()
  }

  fun getInlayType(): InlayType {
    return inlayType
  }

  fun getOffset(): Int {
    return offset
  }
}
