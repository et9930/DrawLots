package com.drawlots.app.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把签池状态序列化成 JSON。
 *
 * 这个类是**存档 schema 的唯一 owner**：既用于本地持久化（`PoolStore`），
 * 也用于联机房间的状态负载（`room/RoomProtocol` 直接嵌套 [encodeToJson] 的对象，
 * 不再二次编码成字符串，避免出现第二份 schema）。
 *
 * 用 org.json（Android 平台自带；单元测试里用 maven 上的同名实现）。
 */
object PoolCodec {

    /** 3：results[] 增加 drawnBy（联机署名）。 */
    private const val VERSION = 3
    private const val KEY_VERSION = "version"
    private const val KEY_MODE = "mode"
    private const val KEY_LOTS = "lots"
    private const val KEY_RESULTS = "results"

    private const val LOT_ID = "id"
    private const val LOT_KIND = "kind"
    private const val LOT_TEXT = "text"
    private const val LOT_IMAGE = "image"
    private const val LOT_QUANTITY = "qty"

    private const val REC_SEQ = "seq"
    private const val REC_LOT = "lotId"
    private const val REC_KIND = "kind"
    private const val REC_TEXT = "text"
    private const val REC_IMAGE = "image"
    private const val REC_CONSUMED = "consumed"
    private const val REC_MODE = "mode"
    private const val REC_AT = "at"
    private const val REC_NOTE = "note"
    private const val REC_DRAWN_BY = "drawnBy"

    fun encode(snapshot: PoolSnapshot): String = encodeToJson(snapshot).toString()

    fun decode(json: String?): PoolSnapshot {
        if (json.isNullOrBlank()) return PoolSnapshot()
        return try {
            decodeFromJson(JSONObject(json))
        } catch (e: Exception) {
            // 数据损坏时宁可当空签池，也不要崩溃。
            PoolSnapshot()
        }
    }

    /** 供协议嵌套使用的对象版本；字段与 [encode] 完全一致。 */
    fun encodeToJson(snapshot: PoolSnapshot): JSONObject {
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)
        root.put(KEY_MODE, snapshot.mode.name)

        val lots = JSONArray()
        snapshot.lots.forEach { lot -> lots.put(encodeLot(lot)) }
        root.put(KEY_LOTS, lots)

        val results = JSONArray()
        snapshot.results.forEach { record ->
            results.put(
                JSONObject()
                    .put(REC_SEQ, record.seq)
                    .put(REC_LOT, record.lotId)
                    .put(REC_KIND, record.kind.name)
                    .put(REC_TEXT, record.text)
                    .put(REC_IMAGE, record.imagePath ?: JSONObject.NULL)
                    .put(REC_CONSUMED, record.consumedCopy)
                    .put(REC_MODE, record.mode.name)
                    .put(REC_AT, record.atMillis)
                    .put(REC_NOTE, record.note)
                    .put(REC_DRAWN_BY, record.drawnBy)
            )
        }
        root.put(KEY_RESULTS, results)

        return root
    }

    /** 从（可能来自网络的、不可信的）JSON 对象还原状态；任何字段异常都退化成空签池。 */
    fun decodeFromJson(root: JSONObject): PoolSnapshot = try {
        PoolSnapshot(
            lots = root.optJSONArray(KEY_LOTS).mapObjects { it.toLot() },
            results = root.optJSONArray(KEY_RESULTS).mapObjects { it.toRecord() },
            mode = root.optString(KEY_MODE).toMode(),
        )
    } catch (e: Exception) {
        PoolSnapshot()
    }

    /** 单支签的编码；联机协议里的「加签/改签」动作也用它，保证 schema 只有一个 owner。 */
    fun encodeLot(lot: Lot): JSONObject = JSONObject()
        .put(LOT_ID, lot.id)
        .put(LOT_KIND, lot.kind.name)
        .put(LOT_TEXT, lot.text)
        .put(LOT_IMAGE, lot.imagePath ?: JSONObject.NULL)
        .put(LOT_QUANTITY, lot.quantity)

    /** 单支签的解码；任何异常都退化成 null（调用方丢弃该条）。 */
    fun decodeLot(json: JSONObject): Lot? = try {
        json.toLot()
    } catch (e: Exception) {
        null
    }

    private fun JSONObject.toLot(): Lot? {
        val id = optString(LOT_ID).takeIf { it.isNotBlank() } ?: return null
        return Lot(
            id = id,
            kind = optString(LOT_KIND).toKind(),
            text = optString(LOT_TEXT),
            imagePath = optStringOrNull(LOT_IMAGE),
            quantity = optInt(LOT_QUANTITY, 1).coerceIn(1, MAX_QUANTITY),
        )
    }

    private fun JSONObject.toRecord(): DrawRecord? {
        val lotId = optString(REC_LOT).takeIf { it.isNotBlank() } ?: return null
        return DrawRecord(
            seq = optInt(REC_SEQ, 1),
            lotId = lotId,
            kind = optString(REC_KIND).toKind(),
            text = optString(REC_TEXT),
            imagePath = optStringOrNull(REC_IMAGE),
            consumedCopy = optBoolean(REC_CONSUMED, false),
            mode = optString(REC_MODE).toMode(),
            atMillis = optLong(REC_AT, 0L),
            // 旧版本存档里没有 note / drawnBy 字段，读出来就是空串
            note = optString(REC_NOTE),
            drawnBy = optString(REC_DRAWN_BY),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            transform(item)?.let { out += it }
        }
        return out
    }

    private fun String.toMode(): DrawMode =
        runCatching { DrawMode.valueOf(this) }.getOrDefault(DrawMode.WITHOUT_REPLACEMENT)

    private fun String.toKind(): LotKind =
        runCatching { LotKind.valueOf(this) }.getOrDefault(LotKind.TEXT)
}
