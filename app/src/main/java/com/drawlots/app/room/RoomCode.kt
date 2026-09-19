package com.drawlots.app.room

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

/**
 * 房间码与二维码内容。
 *
 * 房间码用 Crockford Base32 的子集（去掉 I/L/O/U，避免和 1/0 看错），6 位，
 * 手输时再做一次容错归一化：大写、去分隔符、I/L→1、O→0。
 *
 * 二维码内容形如：
 * - 局域网：`drawlots://v1?t=lan&h=192.168.1.5&p=47001&c=K7M2QX&r=周五抽签`
 * - 蓝牙：  `drawlots://v1?t=bt&sid=<service-uuid>&c=K7M2QX&r=周五抽签`
 */
data class RoomInvite(
    val target: RoomTarget,
    val roomCode: String,
    val roomName: String,
)

object RoomCode {

    /** Crockford Base32：0-9 + A-Z 去掉 I/L/O/U。 */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 6

    private const val SCHEME = "drawlots"
    private const val AUTHORITY = "v1"

    fun generate(random: Random = Random.Default): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** 校验一个码是否本身就是合法房间码（不做归一化）。 */
    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { ALPHABET.indexOf(it) >= 0 }

    /** 手输容错：大写、丢掉无关字符并做 I/L→1、O→0 映射；长度不对返回 null。 */
    fun normalize(raw: String): String? {
        val cleaned = buildString {
            for (ch in raw.trim().uppercase()) {
                when (ch) {
                    'I', 'L' -> append('1')
                    'O' -> append('0')
                    else -> if (ALPHABET.indexOf(ch) >= 0) append(ch)
                }
            }
        }
        return cleaned.takeIf { isValid(it) }
    }

    fun encodeInvite(invite: RoomInvite): String {
        val target = when (val value = invite.target) {
            is RoomTarget.Lan -> "t=lan&h=${encode(value.host)}&p=${value.port}"
            is RoomTarget.Bluetooth -> "t=bt&sid=${encode(value.serviceUuid)}"
        }
        return "$SCHEME://$AUTHORITY?$target&c=${encode(invite.roomCode.trim().uppercase())}" +
            "&r=${encode(invite.roomName)}"
    }

    /** 解析二维码内容；不是本协议的二维码、缺字段或端口非法都返回 null。 */
    fun decodeInvite(uri: String?): RoomInvite? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { URI(uri.trim()) }.getOrNull() ?: return null
        if (!SCHEME.equals(parsed.scheme, ignoreCase = true)) return null
        if (parsed.host != AUTHORITY) return null

        val params = parseQuery(parsed.rawQuery)
        val target = when (params["t"]?.lowercase()) {
            "lan" -> {
                val host = params["h"]?.takeIf { it.isNotBlank() } ?: return null
                val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                RoomTarget.Lan(host = host, port = port)
            }

            "bt" -> {
                val uuid = params["sid"]?.takeIf { it.isNotBlank() } ?: return null
                RoomTarget.Bluetooth(serviceUuid = uuid)
            }

            else -> return null
        }

        val code = normalize(params["c"].orEmpty()) ?: return null
        return RoomInvite(
            target = target,
            roomCode = code,
            roomName = params["r"].orEmpty().ifBlank { "抽签房间" },
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            if (index <= 0) continue
            val key = pair.substring(0, index)
            val value = runCatching { URLDecoder.decode(pair.substring(index + 1), "UTF-8") }
                .getOrDefault("")
            out[key] = value
        }
        return out
    }

    private fun encode(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault("")
}
