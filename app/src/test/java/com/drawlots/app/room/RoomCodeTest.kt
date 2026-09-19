package com.drawlots.app.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoomCodeTest {

    @Test
    fun generatedCodesUseOnlyTheAlphabetAndHaveFixedLength() {
        val random = Random(7)
        repeat(200) {
            val code = RoomCode.generate(random)
            assertEquals(RoomCode.LENGTH, code.length)
            assertTrue("非法字符：$code", RoomCode.isValid(code))
        }
    }

    @Test
    fun isValidRejectsLookalikeLettersAndWrongLength() {
        assertTrue(RoomCode.isValid("K7M2QX"))
        assertFalse("O 不在字符集里", RoomCode.isValid("K7M2QO"))
        assertFalse("I 不在字符集里", RoomCode.isValid("K7M2QI"))
        assertFalse(RoomCode.isValid("K7M2Q"))
    }

    @Test
    fun normalizeIsForgivingAboutCaseSeparatorsAndLookalikes() {
        assertEquals("K7M2QX", RoomCode.normalize("k7m2-qx"))
        assertEquals("K7M2QX", RoomCode.normalize("  K7M2 qx  "))
        // I/L → 1，O → 0
        assertEquals("K7M21X", RoomCode.normalize("k7m2lx"))
        assertEquals("K7M20X", RoomCode.normalize("k7m2ox"))
    }

    @Test
    fun normalizeRejectsWrongLength() {
        assertNull(RoomCode.normalize("K7M2"))
        assertNull(RoomCode.normalize("K7M2QXA"))
        assertNull(RoomCode.normalize(""))
    }

    @Test
    fun lanInviteRoundTripsIncludingChineseRoomName() {
        val invite = RoomInvite(
            target = RoomTarget.Lan(host = "192.168.1.5", port = 47001),
            roomCode = "K7M2QX",
            roomName = "周五抽签 🎲",
        )

        val decoded = RoomCode.decodeInvite(RoomCode.encodeInvite(invite))

        assertEquals(invite, decoded)
    }

    @Test
    fun bluetoothInviteRoundTrips() {
        val invite = RoomInvite(
            target = RoomTarget.Bluetooth(serviceUuid = "3f2a1b4c-5d6e-7f80-9a0b-1c2d3e4f5061"),
            roomCode = "AB12CD",
            roomName = "蓝牙房间",
        )

        assertEquals(invite, RoomCode.decodeInvite(RoomCode.encodeInvite(invite)))
    }

    @Test
    fun decodeInviteRejectsGarbageAndIncompleteLinks() {
        assertNull(RoomCode.decodeInvite(null))
        assertNull(RoomCode.decodeInvite(""))
        assertNull(RoomCode.decodeInvite("https://example.com/?c=K7M2QX"))
        assertNull("版本不对", RoomCode.decodeInvite("drawlots://v2?t=lan&h=1.2.3.4&p=47001&c=K7M2QX"))
        assertNull("缺端口", RoomCode.decodeInvite("drawlots://v1?t=lan&h=1.2.3.4&c=K7M2QX"))
        assertNull("缺房间码", RoomCode.decodeInvite("drawlots://v1?t=lan&h=1.2.3.4&p=47001"))
        assertNull("码太短", RoomCode.decodeInvite("drawlots://v1?t=lan&h=1.2.3.4&p=47001&c=K7M"))
        assertNull("未知传输", RoomCode.decodeInvite("drawlots://v1?t=nfc&c=K7M2QX"))
        assertNull("蓝牙缺 UUID", RoomCode.decodeInvite("drawlots://v1?t=bt&c=K7M2QX"))
    }

    @Test
    fun decodeInviteRejectsOutOfRangePorts() {
        assertNull(RoomCode.decodeInvite("drawlots://v1?t=lan&h=1.2.3.4&p=0&c=K7M2QX"))
        assertNull(RoomCode.decodeInvite("drawlots://v1?t=lan&h=1.2.3.4&p=70000&c=K7M2QX"))
        assertNull(RoomCode.decodeInvite("drawlots://v1?t=lan&h=1.2.3.4&p=abc&c=K7M2QX"))
    }

    @Test
    fun decodeInviteNormalizesTheCodeAndDefaultsTheRoomName() {
        val decoded = RoomCode.decodeInvite("drawlots://v1?t=lan&h=10.0.0.2&p=1234&c=k7m2-qx")

        assertEquals("K7M2QX", decoded?.roomCode)
        assertEquals("抽签房间", decoded?.roomName)
    }
}
