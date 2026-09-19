package com.drawlots.app.room

import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.PoolCodec
import com.drawlots.app.domain.PoolSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 联机协议（纯 Kotlin，不依赖 Android）。
 *
 * 分帧：`[4 字节大端长度 N][1 字节帧类型][N-1 字节帧体]`
 * - `0x01` JSON 帧：帧体是 UTF-8 的 JSON 文本（一条 [RoomMessage]）
 * - `0x02` 二进制帧：帧体是 `[4 字节大端 headerLen][header JSON][原始字节]`，用于图片分片
 *
 * 这个文件是协议的唯一 owner：房主与成员、局域网与蓝牙都走同一套编解码。
 */
object RoomLimits {
    const val PROTOCOL_VERSION = 1

    /** 单条 JSON 帧上限（状态全量广播也走它）。 */
    const val MAX_JSON_FRAME_BYTES = 1 shl 20

    /** 二进制分片负载上限；蓝牙会按 MTU 再往下调。 */
    const val MAX_BINARY_CHUNK_BYTES = 4096

    const val MAX_NAME_LENGTH = 12
    const val MAX_IMAGE_BYTES = 5 shl 20
    const val HEARTBEAT_INTERVAL_MS = 5_000L
    const val MEMBER_TIMEOUT_MS = 15_000L
    const val REQUEST_TIMEOUT_MS = 5_000L
    const val LAN_IMAGE_TIMEOUT_MS = 10_000L
    const val BLUETOOTH_IMAGE_TIMEOUT_MS = 60_000L
}

object RoomErrors {
    const val VERSION_MISMATCH = "version_mismatch"
    const val NOT_ALLOWED = "not_allowed"
    const val BAD_REQUEST = "bad_request"
    const val NOT_FOUND = "not_found"
    const val TOO_LARGE = "too_large"
    const val BUSY = "busy"
}

/** 连接目标：二维码 / 房间码解析出来的东西。 */
sealed interface RoomTarget {
    data class Lan(val host: String, val port: Int) : RoomTarget
    data class Bluetooth(val serviceUuid: String) : RoomTarget
}

/** 成员可以发给房主的操作；房主按当前放权开关决定接受还是回 `not_allowed`。 */
sealed interface RoomAction {
    data class Draw(val count: Int) : RoomAction
    data class SetNote(val seq: Int, val note: String) : RoomAction
    data object Undo : RoomAction
    data class PutBack(val seq: Int) : RoomAction
    data object ResetPool : RoomAction
    data class AddLot(val lot: Lot) : RoomAction
    data class UpdateLot(val lot: Lot) : RoomAction
    data class RemoveLot(val id: String) : RoomAction
    data class SetQuantity(val id: String, val quantity: Int) : RoomAction
    data object ClearPool : RoomAction

    /** 放回/不放回是房间共享规则，只有房主能改（默认成员不能切模式）。 */
    data class SetMode(val mode: DrawMode) : RoomAction
}

/** 协议消息。 */
sealed interface RoomMessage {
    data class Hello(
        val protocolVersion: Int,
        val deviceId: String,
        val name: String,
    ) : RoomMessage

    data class Welcome(
        val roomName: String,
        val hostName: String,
        val allowRecover: Boolean,
        val allowEdit: Boolean,
        val revision: Long,
        val members: List<String>,
        val state: JSONObject,
    ) : RoomMessage

    data class RoomState(
        val revision: Long,
        val allowRecover: Boolean,
        val allowEdit: Boolean,
        val members: List<String>,
        val state: JSONObject,
    ) : RoomMessage

    data class Request(val id: String, val action: RoomAction) : RoomMessage

    data class Ack(val id: String, val revision: Long) : RoomMessage

    data class Failure(val id: String?, val code: String, val message: String) : RoomMessage

    data object Resync : RoomMessage

    data class ImageRequest(val id: String, val imageKey: String) : RoomMessage

    data class ImageBegin(val id: String, val imageKey: String, val size: Int) : RoomMessage

    data class ImageEnd(val id: String) : RoomMessage

    data class Ping(val ts: Long) : RoomMessage

    data class Pong(val ts: Long) : RoomMessage

    data class Bye(val reason: String) : RoomMessage
}

/** 一帧。二进制帧的 [bytes] 用内容比较，不依赖 data class 的默认相等。 */
sealed interface RoomFrame {
    data class Json(val message: RoomMessage) : RoomFrame

    class Binary(val id: String, val offset: Int, val bytes: ByteArray) : RoomFrame {
        override fun equals(other: Any?): Boolean =
            other is Binary && other.id == id && other.offset == offset && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = (id.hashCode() * 31 + offset) * 31 + bytes.contentHashCode()

        override fun toString(): String = "Binary(id=$id, offset=$offset, size=${bytes.size})"
    }
}

object RoomFraming {
    const val TYPE_JSON: Byte = 0x01
    const val TYPE_BINARY: Byte = 0x02

    fun encode(frame: RoomFrame): ByteArray = when (frame) {
        is RoomFrame.Json -> {
            val payload = RoomMessages.encode(frame.message).toByteArray(Charsets.UTF_8)
            frameOf(TYPE_JSON, payload)
        }

        is RoomFrame.Binary -> {
            val header = JSONObject()
                .put("id", frame.id)
                .put("offset", frame.offset)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val payload = ByteArray(4 + header.size + frame.bytes.size)
            writeInt(payload, 0, header.size)
            header.copyInto(payload, 4)
            frame.bytes.copyInto(payload, 4 + header.size)
            frameOf(TYPE_BINARY, payload)
        }
    }

    private fun frameOf(type: Byte, payload: ByteArray): ByteArray {
        val length = 1 + payload.size
        val out = ByteArray(4 + length)
        writeInt(out, 0, length)
        out[4] = type
        payload.copyInto(out, 5)
        return out
    }

    internal fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    internal fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)
}

/**
 * 增量解码器：TCP 会粘包/半包，所以按字节流喂进来，吐出完整帧。
 *
 * [error] 一旦被设置就说明对端发了非法数据（超长帧、长度为 0、坏 JSON），传输层应当断开该连接；
 * 未知的消息类型不算错误，只是被跳过（向前兼容）。
 */
class RoomFrameDecoder(
    private val maxFrameBytes: Int = RoomLimits.MAX_JSON_FRAME_BYTES,
) {
    private var buffer = ByteArray(0)

    var error: String? = null
        private set

    fun feed(chunk: ByteArray, length: Int = chunk.size): List<RoomFrame> {
        if (error != null) return emptyList()
        if (length <= 0) return emptyList()
        buffer += chunk.copyOf(length)

        val frames = ArrayList<RoomFrame>()
        var offset = 0
        while (buffer.size - offset >= 5) {
            val frameLength = RoomFraming.readInt(buffer, offset)
            if (frameLength < 2 || frameLength > maxFrameBytes) {
                error = "非法帧长度 $frameLength"
                buffer = ByteArray(0)
                return frames
            }
            val total = 4 + frameLength
            if (buffer.size - offset < total) break

            val type = buffer[offset + 4]
            val payload = buffer.copyOfRange(offset + 5, offset + total)
            decodeFrame(type, payload)?.let { frames += it }
            offset += total
        }
        buffer = if (offset >= buffer.size) ByteArray(0) else buffer.copyOfRange(offset, buffer.size)
        return frames
    }

    private fun decodeFrame(type: Byte, payload: ByteArray): RoomFrame? = when (type) {
        RoomFraming.TYPE_JSON -> {
            val text = String(payload, Charsets.UTF_8)
            RoomMessages.decode(text)?.let { RoomFrame.Json(it) }
            // 未知类型或坏 JSON：跳过该帧（向前兼容），不判为致命错误
        }

        RoomFraming.TYPE_BINARY -> {
            if (payload.size < 4) {
                null
            } else {
                val headerLength = RoomFraming.readInt(payload, 0)
                if (headerLength < 0 || 4 + headerLength > payload.size) {
                    null
                } else {
                    val header = runCatching {
                        JSONObject(String(payload, 4, headerLength, Charsets.UTF_8))
                    }.getOrNull()
                    val id = header?.optString("id").orEmpty()
                    if (header == null || id.isEmpty()) {
                        null
                    } else {
                        val data = payload.copyOfRange(4 + headerLength, payload.size)
                        RoomFrame.Binary(id = id, offset = header.optInt("offset", 0), bytes = data)
                    }
                }
            }
        }

        else -> null
    }
}

/** 消息 ↔ JSON。字段名就是协议字段名，测试里直接比对编码结果。 */
object RoomMessages {

    private const val TYPE = "type"

    fun encode(message: RoomMessage): String = encodeToJson(message).toString()

    fun encodeToJson(message: RoomMessage): JSONObject = when (message) {
        is RoomMessage.Hello -> JSONObject()
            .put(TYPE, "hello")
            .put("protocolVersion", message.protocolVersion)
            .put("deviceId", message.deviceId)
            .put("name", message.name)

        is RoomMessage.Welcome -> JSONObject()
            .put(TYPE, "welcome")
            .put("roomName", message.roomName)
            .put("hostName", message.hostName)
            .put("allowRecover", message.allowRecover)
            .put("allowEdit", message.allowEdit)
            .put("revision", message.revision)
            .put("members", JSONArray(message.members))
            .put("state", message.state)

        is RoomMessage.RoomState -> JSONObject()
            .put(TYPE, "state")
            .put("revision", message.revision)
            .put("allowRecover", message.allowRecover)
            .put("allowEdit", message.allowEdit)
            .put("members", JSONArray(message.members))
            .put("state", message.state)

        is RoomMessage.Request -> JSONObject()
            .put(TYPE, "request")
            .put("id", message.id)
            .put("action", encodeAction(message.action))

        is RoomMessage.Ack -> JSONObject()
            .put(TYPE, "ack")
            .put("id", message.id)
            .put("revision", message.revision)

        is RoomMessage.Failure -> JSONObject()
            .put(TYPE, "error")
            .put("id", message.id ?: JSONObject.NULL)
            .put("code", message.code)
            .put("message", message.message)

        RoomMessage.Resync -> JSONObject().put(TYPE, "resync")

        is RoomMessage.ImageRequest -> JSONObject()
            .put(TYPE, "imageRequest")
            .put("id", message.id)
            .put("imageKey", message.imageKey)

        is RoomMessage.ImageBegin -> JSONObject()
            .put(TYPE, "imageBegin")
            .put("id", message.id)
            .put("imageKey", message.imageKey)
            .put("size", message.size)

        is RoomMessage.ImageEnd -> JSONObject()
            .put(TYPE, "imageEnd")
            .put("id", message.id)

        is RoomMessage.Ping -> JSONObject().put(TYPE, "ping").put("ts", message.ts)

        is RoomMessage.Pong -> JSONObject().put(TYPE, "pong").put("ts", message.ts)

        is RoomMessage.Bye -> JSONObject().put(TYPE, "bye").put("reason", message.reason)
    }

    /** 解码一条消息；未知类型或字段缺失都返回 null（由调用方跳过）。 */
    fun decode(text: String): RoomMessage? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return decodeFromJson(json)
    }

    fun decodeFromJson(json: JSONObject): RoomMessage? = when (json.optString(TYPE)) {
        "hello" -> RoomMessage.Hello(
            protocolVersion = json.optInt("protocolVersion", 0),
            deviceId = json.optString("deviceId"),
            name = json.optString("name"),
        )

        "welcome" -> RoomMessage.Welcome(
            roomName = json.optString("roomName"),
            hostName = json.optString("hostName"),
            allowRecover = json.optBoolean("allowRecover", false),
            allowEdit = json.optBoolean("allowEdit", false),
            revision = json.optLong("revision", 0L),
            members = json.optJSONArray("members").toStringList(),
            state = json.optJSONObject("state") ?: JSONObject(),
        )

        "state" -> RoomMessage.RoomState(
            revision = json.optLong("revision", 0L),
            allowRecover = json.optBoolean("allowRecover", false),
            allowEdit = json.optBoolean("allowEdit", false),
            members = json.optJSONArray("members").toStringList(),
            state = json.optJSONObject("state") ?: JSONObject(),
        )

        "request" -> {
            val id = json.optString("id")
            val action = json.optJSONObject("action")?.let { decodeAction(it) }
            if (id.isEmpty() || action == null) null else RoomMessage.Request(id, action)
        }

        "ack" -> RoomMessage.Ack(
            id = json.optString("id"),
            revision = json.optLong("revision", 0L),
        )

        "error" -> RoomMessage.Failure(
            id = if (json.isNull("id")) null else json.optString("id"),
            code = json.optString("code"),
            message = json.optString("message"),
        )

        "resync" -> RoomMessage.Resync

        "imageRequest" -> RoomMessage.ImageRequest(
            id = json.optString("id"),
            imageKey = json.optString("imageKey"),
        )

        "imageBegin" -> RoomMessage.ImageBegin(
            id = json.optString("id"),
            imageKey = json.optString("imageKey"),
            size = json.optInt("size", 0),
        )

        "imageEnd" -> RoomMessage.ImageEnd(id = json.optString("id"))

        "ping" -> RoomMessage.Ping(ts = json.optLong("ts", 0L))

        "pong" -> RoomMessage.Pong(ts = json.optLong("ts", 0L))

        "bye" -> RoomMessage.Bye(reason = json.optString("reason"))

        else -> null
    }

    fun encodeAction(action: RoomAction): JSONObject = when (action) {
        is RoomAction.Draw -> JSONObject().put("kind", "draw").put("count", action.count)
        is RoomAction.SetNote -> JSONObject()
            .put("kind", "setNote")
            .put("seq", action.seq)
            .put("note", action.note)

        RoomAction.Undo -> JSONObject().put("kind", "undo")
        is RoomAction.PutBack -> JSONObject().put("kind", "putBack").put("seq", action.seq)
        RoomAction.ResetPool -> JSONObject().put("kind", "resetPool")
        is RoomAction.SetMode -> JSONObject().put("kind", "setMode").put("mode", action.mode.name)
        is RoomAction.AddLot -> JSONObject()
            .put("kind", "addLot")
            .put("lot", PoolCodec.encodeLot(action.lot))

        is RoomAction.UpdateLot -> JSONObject()
            .put("kind", "updateLot")
            .put("lot", PoolCodec.encodeLot(action.lot))

        is RoomAction.RemoveLot -> JSONObject().put("kind", "removeLot").put("id", action.id)
        is RoomAction.SetQuantity -> JSONObject()
            .put("kind", "setQuantity")
            .put("id", action.id)
            .put("quantity", action.quantity)

        RoomAction.ClearPool -> JSONObject().put("kind", "clearPool")
    }

    fun decodeAction(json: JSONObject): RoomAction? = when (json.optString("kind")) {
        "draw" -> RoomAction.Draw(count = json.optInt("count", 1))
        "setNote" -> RoomAction.SetNote(seq = json.optInt("seq", -1), note = json.optString("note"))
        "undo" -> RoomAction.Undo
        "putBack" -> RoomAction.PutBack(seq = json.optInt("seq", -1))
        "resetPool" -> RoomAction.ResetPool
        "setMode" -> runCatching { DrawMode.valueOf(json.optString("mode")) }
            .getOrNull()
            ?.let { RoomAction.SetMode(it) }
        "addLot" -> json.optJSONObject("lot")?.let { PoolCodec.decodeLot(it) }?.let { RoomAction.AddLot(it) }
        "updateLot" -> json.optJSONObject("lot")?.let { PoolCodec.decodeLot(it) }?.let { RoomAction.UpdateLot(it) }
        "removeLot" -> json.optString("id").takeIf { it.isNotEmpty() }?.let { RoomAction.RemoveLot(it) }
        "setQuantity" -> json.optString("id").takeIf { it.isNotEmpty() }?.let {
            RoomAction.SetQuantity(id = it, quantity = json.optInt("quantity", 1))
        }

        "clearPool" -> RoomAction.ClearPool
        else -> null
    }

    fun encodeState(state: PoolSnapshot): JSONObject = PoolCodec.encodeToJson(state)

    fun decodeState(state: JSONObject): PoolSnapshot = PoolCodec.decodeFromJson(state)

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (index in 0 until length()) {
            val item = optString(index)
            if (item.isNotEmpty()) out += item
        }
        return out
    }
}
