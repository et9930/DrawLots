package com.drawlots.app.domain

/**
 * 把抽签结果整理成分享/复制用的文本：按当前位次编号，署名放在括号里，手写备注跟在破折号后。
 *
 * 例：`1. 张三（小明抽到）—— 备注内容`
 *
 * 纯函数，方便单独测试（不依赖 Android）。
 */
fun formatResultsText(results: List<DrawRecord>, mode: DrawMode): String {
    if (results.isEmpty()) return "还没有抽签结果"
    return buildString {
        appendLine("抽签结果（共 ${results.size} 个 · ${mode.label}）")
        results.forEachIndexed { index, record ->
            append(index + 1)
            append(". ")
            append(record.title)
            if (record.drawnBy.isNotBlank()) {
                append("（")
                append(record.drawnBy)
                append("抽到）")
            }
            if (record.note.isNotBlank()) {
                if (record.drawnBy.isNotBlank()) {
                    append("—— ")
                    append(record.note)
                } else {
                    append("（")
                    append(record.note)
                    append("）")
                }
            }
            appendLine()
        }
    }.trim()
}
