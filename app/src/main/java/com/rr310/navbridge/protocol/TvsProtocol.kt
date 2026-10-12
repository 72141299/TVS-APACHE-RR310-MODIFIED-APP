package com.rr310.navbridge.protocol

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Reverse-engineered TVS SmartXonnect protocol for TVS Apache RR 310 (N597 / U796).
 */
object TvsProtocol {

    // Primary BLE GATT Service & Characteristics
    val SERVICE_UUID: UUID = UUID.fromString("5456534D-5647-5341-5342-454E544F5251") // TVSMVG SASBENTORQ
    val CHAR_NOTIFY_UUID: UUID = UUID.fromString("00005354-0000-1000-8000-00805f9b34fb") // 0x5354 (ST)
    val CHAR_WRITE_UUID: UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb")  // 0x5352 (SR)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val START_BYTE_NAV: Byte = 0x5A.toByte()
    const val DATA_ID_NAV: Byte = 0x4E.toByte() // ASCII 'N'
    const val START_BYTE_TEXT: Byte = 0x5B.toByte() // ASCII '['
    const val DATA_ID_TEXT: Byte = 0x53.toByte() // ASCII 'S'
    const val XOR_KEY: Byte = 0xEA.toByte()

    /**
     * Exact TVS Apache RR 310 TFT Cluster Pictogram Codes:
     * 0  = OFF / Clear Overlay
     * 1  = STRAIGHT
     * 2  = SLIGHT_RIGHT
     * 3  = TURN_RIGHT
     * 4  = SHARP_RIGHT
     * 5  = SLIGHT_LEFT
     * 6  = TURN_LEFT
     * 7  = SHARP_LEFT
     * 8  = U_TURN
     * 9  = U_TURN_LEFT
     * 10 = ROUNDABOUT
     * 11 = ROUNDABOUT_LEFT
     * 12 = KEEP_RIGHT
     * 13 = KEEP_LEFT
     * 14 = ARRIVED (Destination Reached / Flag)
     */
    enum class Pictogram(val code: Byte) {
        OFF(0),
        STRAIGHT(1),
        SLIGHT_RIGHT(2),
        TURN_RIGHT(3),
        SHARP_RIGHT(4),
        SLIGHT_LEFT(5),
        TURN_LEFT(6),
        SHARP_LEFT(7),
        U_TURN(8),
        U_TURN_LEFT(9),
        ROUNDABOUT(10),
        ROUNDABOUT_LEFT(11),
        KEEP_RIGHT(12),
        KEEP_LEFT(13),
        ARRIVED(14)
    }

    /**
     * Builds the 20-byte turn-by-turn navigation frame.
     *
     * Bytes 2..8 (distance/time) are XOR encrypted with 0xEA.
     * Bytes 9..17 (pictogram code, unit, etc.) are plain unencrypted bytes.
     */
    fun buildNavigationPacket(
        distanceMeters: Int,
        remainingDistanceMeters: Int,
        remainingTimeSeconds: Int,
        pictogram: Pictogram,
        isRerouting: Boolean = false,
        isMetric: Boolean = true
    ): ByteArray {
        val raw = ByteArray(20)

        raw[0] = START_BYTE_NAV // 0x5A
        raw[1] = DATA_ID_NAV    // 0x4E ('N')

        // Distance to upcoming maneuver (clamp to 65535 meters)
        val distClamped = distanceMeters.coerceIn(0, 0xFFFF)
        raw[2] = ((distClamped shr 8) and 0xFF).toByte()
        raw[3] = (distClamped and 0xFF).toByte()

        // Remaining time in seconds (clamp to 65535 sec)
        val timeClamped = remainingTimeSeconds.coerceIn(0, 0xFFFF)
        raw[4] = ((timeClamped shr 8) and 0xFF).toByte()
        raw[5] = (timeClamped and 0xFF).toByte()

        // Remaining journey distance (3 bytes, clamp to 16,777,215 meters)
        val remDistClamped = remainingDistanceMeters.coerceIn(0, 0xFFFFFF)
        raw[6] = ((remDistClamped shr 16) and 0xFF).toByte()
        raw[7] = ((remDistClamped shr 8) and 0xFF).toByte()
        raw[8] = (remDistClamped and 0xFF).toByte()

        // XOR encrypt only distance/time bytes 2..8
        for (i in 2..8) {
            raw[i] = (raw[i].toInt() xor XOR_KEY.toInt()).toByte()
        }

        // Unencrypted pictogram code and metadata flags (bytes 9..17)
        raw[9] = pictogram.code
        raw[10] = 1.toByte() // String chunk count
        raw[11] = if (isRerouting) 1.toByte() else 0.toByte()
        raw[12] = if (isMetric) 0.toByte() else 1.toByte()
        raw[13] = 0.toByte()
        raw[14] = 0.toByte()
        raw[15] = 0.toByte()
        raw[16] = 0.toByte()
        raw[17] = 0.toByte()

        // Checksum calculation on bytes 0..17
        var sum = 0
        for (i in 0..17) {
            sum += (raw[i].toInt() and 0xFF)
        }
        val checksum = ((255 - (sum % 256)) and 0xFF).toByte()
        raw[18] = checksum
        raw[19] = 0xFF.toByte()

        return raw
    }

    /**
     * Builds a packet that clears/resets the navigation overlay on the cluster.
     */
    fun buildClearNavigationPacket(): ByteArray {
        return buildNavigationPacket(
            distanceMeters = 0,
            remainingDistanceMeters = 0,
            remainingTimeSeconds = 0,
            pictogram = Pictogram.OFF
        )
    }

    /**
     * Builds the 16-byte text packet [0x5B, 0x53, char1..char14].
     * Format: [0x5B] + [0x53] + [14 UTF-8 text bytes padded with spaces 0x20]
     */
    fun buildStreetNamePacket(streetName: String): ByteArray {
        val packet = ByteArray(16)
        packet[0] = START_BYTE_TEXT // 0x5B ('[')
        packet[1] = DATA_ID_TEXT    // 0x53 ('S')

        // Pad text payload bytes 2..15 with space (0x20)
        for (i in 2..15) {
            packet[i] = 0x20.toByte()
        }

        val trimmed = if (streetName.length > 14) streetName.substring(0, 14) else streetName
        val textBytes = trimmed.toByteArray(StandardCharsets.UTF_8)
        val copyLen = textBytes.size.coerceAtMost(14)
        System.arraycopy(textBytes, 0, packet, 2, copyLen)

        return packet
    }
}
