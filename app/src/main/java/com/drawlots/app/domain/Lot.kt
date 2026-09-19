package com.drawlots.app.domain

import java.util.UUID

/** 一个签最多可以设置的数量。 */
const val MAX_QUANTITY: Int = 999

enum class LotKind { TEXT, IMAGE }

/**
 * 签池里的一种签。[quantity] 表示这种签在池子里有几支。
 *
 * 抽签过程中不会修改 [quantity]，已经抽走的支数记录在 [DrawSession] 里，
 * 这样“放回/不放回”都只是在改变“已抽走支数”。
 */
data class Lot(
    val id: String = newLotId(),
    val kind: LotKind,
    val text: String = "",
    val imagePath: String? = null,
    val quantity: Int = 1,
) {
    /** 展示用的名字：文字签用文字，图片签用备注文字，都没有时用占位名。 */
    val title: String
        get() = when {
            text.isNotBlank() -> text
            kind == LotKind.IMAGE -> "图片签"
            else -> "（空签）"
        }
}

fun newLotId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

/**
 * 把输入框里的文字转成 (内容, 数量) 列表。
 *
 * [onePerLine] 为 true 时每个非空行作为一个签（方便一次粘贴一长串名单），
 * 否则整段文字（保留换行）作为一个签。
 */
fun buildTextLotEntries(
    text: String,
    quantity: Int,
    onePerLine: Boolean,
): List<Pair<String, Int>> {
    if (onePerLine) {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it to quantity }
    }
    val whole = text.trim()
    return if (whole.isEmpty()) emptyList() else listOf(whole to quantity)
}

/** 抓完一支签之后，这支签是否放回签池。 */
enum class DrawMode(val label: String) {
    /** 放回：每次都在完整的签池里抽。 */
    WITH_REPLACEMENT("放回"),

    /** 不放回：抽走的签不再参与后面的抽取。 */
    WITHOUT_REPLACEMENT("不放回");

    val explanation: String
        get() = when (this) {
            WITH_REPLACEMENT -> "每次抽签都在完整的签池里抽，同一支签可能被重复抽到。"
            WITHOUT_REPLACEMENT -> "抽走的签不再放回，每个签最多被抽到它的数量那么多次。"
        }
}

/**
 * 一次抽签结果。[seq] 是抽出的顺序（从 1 开始）。
 *
 * 结果里保存了签的内容快照，所以即使之后签池被修改或删除，历史结果依然能正常显示。
 * [drawnBy] 是联机模式下的抽签人名称（房主应用请求时写入，单机模式为空串）；
 * [note] 是给这一条结果加的手写备注，例如「小明抽到」。
 */
data class DrawRecord(
    val seq: Int,
    val lotId: String,
    val kind: LotKind,
    val text: String,
    val imagePath: String?,
    val consumedCopy: Boolean,
    val mode: DrawMode,
    val atMillis: Long,
    val note: String = "",
    val drawnBy: String = "",
) {
    val title: String
        get() = when {
            text.isNotBlank() -> text
            kind == LotKind.IMAGE -> "图片签"
            else -> "（空签）"
        }
}

/** 可持久化的完整状态。 */
data class PoolSnapshot(
    val lots: List<Lot> = emptyList(),
    val results: List<DrawRecord> = emptyList(),
    val mode: DrawMode = DrawMode.WITHOUT_REPLACEMENT,
)
