package org.febyher.chat

import java.awt.Font

/**
 * 聊天相关 UI 通用工具（字体等）
 * 供 ChatPanel、AurodActionPanel 等复用
 */
object ChatUiUtils {

    @JvmStatic
    fun getChineseFont(style: Int = Font.PLAIN, size: Int): Font {
        val fontNames = arrayOf(
            "Microsoft YaHei",
            "SimHei",
            "SimSun",
            "Noto Sans CJK SC",
            "Source Han Sans SC",
            "WenQuanYi Micro Hei",
            "PingFang SC",
            "Heiti SC",
            "STHeiti"
        )
        for (fontName in fontNames) {
            val font = Font(fontName, style, size)
            if (font.family != "Dialog") {
                return font
            }
        }
        return Font(Font.DIALOG, style, size)
    }
}
