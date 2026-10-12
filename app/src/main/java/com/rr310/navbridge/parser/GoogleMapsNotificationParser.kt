package com.rr310.navbridge.parser

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.rr310.navbridge.protocol.TvsProtocol.Pictogram
import java.util.Locale
import java.util.regex.Pattern

data class ParsedNavigation(
    val distanceToManeuverMeters: Int,
    val remainingDistanceMeters: Int,
    val remainingTimeSeconds: Int,
    val pictogram: Pictogram,
    val streetName: String,
    val rawTitle: String,
    val isArrived: Boolean = false,
    val isRerouting: Boolean = false
)

object GoogleMapsNotificationParser {

    private val DISTANCE_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(m|km|mi|ft)", Pattern.CASE_INSENSITIVE)

    @Volatile
    private var lastDetectedPictogram: Pictogram = Pictogram.STRAIGHT

    fun parse(sbn: StatusBarNotification): ParsedNavigation? {
        if (sbn.packageName != "com.google.android.apps.maps") return null

        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        val title = normalizeText(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val text = normalizeText(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val subText = normalizeText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        val bigText = normalizeText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        val titleBig = normalizeText(extras.getCharSequence("android.title.big")?.toString())
        val tickerText = normalizeText(notification.tickerText?.toString())

        if (title.isEmpty() && text.isEmpty() && bigText.isEmpty() && tickerText.isEmpty()) return null

        val fullContent = "$title $text $subText $bigText $titleBig $tickerText".lowercase(Locale.ROOT)

        // Detect arrival
        if (fullContent.contains("arrived") || fullContent.contains("destination") || fullContent.contains("you have reached") || fullContent.contains("you've arrived")) {
            lastDetectedPictogram = Pictogram.ARRIVED
            return ParsedNavigation(
                distanceToManeuverMeters = 0,
                remainingDistanceMeters = 0,
                remainingTimeSeconds = 0,
                pictogram = Pictogram.ARRIVED,
                streetName = "Arrived",
                rawTitle = if (title.isNotEmpty()) title else "Arrived",
                isArrived = true
            )
        }

        // Parse distance to upcoming maneuver across all fields
        val distToManeuver = parseDistanceMeters(title).takeIf { it > 0 }
            ?: parseDistanceMeters(text).takeIf { it > 0 }
            ?: parseDistanceMeters(bigText).takeIf { it > 0 }
            ?: parseDistanceMeters(tickerText).takeIf { it > 0 }
            ?: parseDistanceMeters(subText).takeIf { it > 0 }
            ?: 0

        // Parse ETA and total remaining distance
        val remainingDistance = parseDistanceMeters(subText).takeIf { it > 0 }
            ?: parseDistanceMeters(bigText).takeIf { it > 0 }
            ?: (distToManeuver * 2)

        val remainingSeconds = parseEtaSeconds(subText).takeIf { it > 0 }
            ?: parseEtaSeconds(bigText).takeIf { it > 0 }
            ?: 600

        // Detect pictogram or retain last detected pictogram for ongoing maneuver distance updates
        val detected = detectPictogram(fullContent)
        val picto = if (detected != Pictogram.OFF) {
            lastDetectedPictogram = detected
            detected
        } else {
            // Keep active maneuver pictogram during distance updates (e.g. 500m -> 400m -> 300m)
            if (lastDetectedPictogram != Pictogram.OFF) lastDetectedPictogram else Pictogram.STRAIGHT
        }

        // Extract clean street name (max 14 chars for cluster)
        val cleanStreet = extractStreetName(title, text, bigText)

        val isRerouting = fullContent.contains("rerouting")

        return ParsedNavigation(
            distanceToManeuverMeters = distToManeuver,
            remainingDistanceMeters = remainingDistance,
            remainingTimeSeconds = remainingSeconds,
            pictogram = picto,
            streetName = cleanStreet,
            rawTitle = title,
            isRerouting = isRerouting
        )
    }

    fun resetParserState() {
        lastDetectedPictogram = Pictogram.STRAIGHT
    }

    private fun normalizeText(input: String?): String {
        if (input == null) return ""
        return input.replace("\u00A0", " ")
            .replace("\u202F", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseDistanceMeters(input: String): Int {
        if (input.isEmpty()) return 0
        val matcher = DISTANCE_PATTERN.matcher(input)
        if (matcher.find()) {
            val numStr = matcher.group(1)?.replace(",", ".") ?: return 0
            val unit = matcher.group(2)?.lowercase(Locale.ROOT) ?: "m"
            val value = numStr.toDoubleOrNull() ?: return 0

            return when (unit) {
                "km" -> (value * 1000).toInt()
                "m" -> value.toInt()
                "mi" -> (value * 1609.34).toInt()
                "ft" -> (value * 0.3048).toInt()
                else -> value.toInt()
            }
        }
        return 0
    }

    private fun parseEtaSeconds(input: String): Int {
        if (input.isEmpty()) return 0
        var totalSec = 0
        if (input.contains("hr") || input.contains("hour")) {
            val hrMatcher = Pattern.compile("(\\d+)\\s*(?:hr|hour|hours)", Pattern.CASE_INSENSITIVE).matcher(input)
            if (hrMatcher.find()) {
                totalSec += (hrMatcher.group(1)?.toIntOrNull() ?: 0) * 3600
            }
        }
        val minMatcher = Pattern.compile("(\\d+)\\s*(?:min|mins|minute|minutes)", Pattern.CASE_INSENSITIVE).matcher(input)
        if (minMatcher.find()) {
            totalSec += (minMatcher.group(1)?.toIntOrNull() ?: 0) * 60
        }
        return totalSec
    }

    private fun detectPictogram(text: String): Pictogram {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("u-turn") || lower.contains("make a u turn") || lower.contains("uturn") || lower.contains("turn around") -> Pictogram.U_TURN
            lower.contains("sharp right") -> Pictogram.SHARP_RIGHT
            lower.contains("sharp left") -> Pictogram.SHARP_LEFT
            lower.contains("slight right") || lower.contains("bear right") || lower.contains("fork right") -> Pictogram.SLIGHT_RIGHT
            lower.contains("slight left") || lower.contains("bear left") || lower.contains("fork left") -> Pictogram.SLIGHT_LEFT
            lower.contains("turn right") || lower.contains("take a right") || lower.contains("keep right") || lower.contains("right") -> Pictogram.TURN_RIGHT
            lower.contains("turn left") || lower.contains("take a left") || lower.contains("keep left") || lower.contains("left") -> Pictogram.TURN_LEFT
            lower.contains("roundabout") || lower.contains("rotary") -> Pictogram.ROUNDABOUT
            lower.contains("destination") || lower.contains("arrived") || lower.contains("reached") -> Pictogram.ARRIVED
            lower.contains("continue") || lower.contains("straight") || lower.contains("head north") || lower.contains("head south") || lower.contains("head east") || lower.contains("head west") -> Pictogram.STRAIGHT
            else -> Pictogram.OFF
        }
    }

    private fun extractStreetName(title: String, text: String, bigText: String): String {
        val candidates = listOf(title, text, bigText)
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue

            for (prefix in listOf("onto ", "towards ", "toward ", "on ")) {
                val index = candidate.indexOf(prefix, ignoreCase = true)
                if (index != -1 && index + prefix.length < candidate.length) {
                    val street = candidate.substring(index + prefix.length).trim()
                    if (street.isNotEmpty() && !street.contains("min", ignoreCase = true)) {
                        return sanitizeStreetName(street)
                    }
                }
            }
        }

        if (text.isNotEmpty() && !text.contains("min", ignoreCase = true)) {
            return sanitizeStreetName(text)
        }

        val cleanedTitle = title.replace(Regex("In\\s+\\d+.*?,\\s*", RegexOption.IGNORE_CASE), "").trim()
        return sanitizeStreetName(cleanedTitle)
    }

    private fun sanitizeStreetName(raw: String): String {
        val cleaned = raw.replace(Regex("^In\\s+\\d+.*?meters?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^In\\s+\\d+.*?feet\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        return if (cleaned.length > 14) cleaned.substring(0, 14).trim() else cleaned
    }
}
