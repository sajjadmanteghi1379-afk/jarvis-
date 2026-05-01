package com.jarvis.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CalendarActionHandler(private val context: Context) {

    companion object {
        fun addEvent(context: Context, title: String, startTime: Long, endTime: Long): CalendarInsertResult {
            return CalendarActionHandler(context).addCalendarEvent(title, startTime, endTime)
        }
    }

    fun addCalendarEvent(title: String, startTime: Long, endTime: Long, location: String = ""): CalendarInsertResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            return CalendarInsertResult(false, "Calendar write permission not granted.")
        }
        if (title.isBlank()) return CalendarInsertResult(false, "Event title is empty.")
        if (endTime <= startTime) return CalendarInsertResult(false, "Event end time must be after start time.")

        return try {
            val calendarId = findWritableCalendarId()
                ?: return CalendarInsertResult(false, "No writable calendar found on this device.")
            val timezone = TimeZone.getDefault().id
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title.trim())
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.EVENT_TIMEZONE, timezone)
                put(CalendarContract.Events.DESCRIPTION, "Added by Jarvis")
                if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location.trim())
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                CalendarInsertResult(false, "Calendar provider returned no event URI.")
            } else {
                CalendarInsertResult(true, "Event added.", uri.toString())
            }
        } catch (e: SecurityException) {
            CalendarInsertResult(false, "Calendar permission denied: ${e.message}")
        } catch (e: Exception) {
            CalendarInsertResult(false, "Calendar insert failed: ${e.message}")
        }
    }

    fun parseCommand(command: String, defaultDurationMinutes: Int = 60): ParsedCalendarEvent? {
        return parseDraft(command, defaultDurationMinutes).asParsedEvent()
    }

    fun parseDraft(command: String, defaultDurationMinutes: Int = 60): CalendarEventDraft {
        val lower = command.lowercase(Locale.getDefault())
        val date = extractDate(lower)
        val time = extractTime(lower)
        val duration = extractDurationMinutes(lower) ?: defaultDurationMinutes
        val title = extractTitle(command).ifBlank { null }
        val location = extractLocation(command)
        return CalendarEventDraft(
            title = title,
            date = date,
            time = time,
            durationMinutes = duration,
            location = location
        )
    }

    private fun CalendarEventDraft.asParsedEvent(): ParsedCalendarEvent? {
        val dateValue = date ?: Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val timeValue = time ?: return null

        dateValue.set(Calendar.HOUR_OF_DAY, timeValue.first)
        dateValue.set(Calendar.MINUTE, timeValue.second)
        dateValue.set(Calendar.SECOND, 0)
        dateValue.set(Calendar.MILLISECOND, 0)
        if (dateValue.timeInMillis < System.currentTimeMillis() && date == null) {
            dateValue.add(Calendar.DAY_OF_YEAR, 1)
        }

        return ParsedCalendarEvent(
            title = title ?: return null,
            startTime = dateValue.timeInMillis,
            endTime = dateValue.timeInMillis + durationMinutes * 60_000L,
            location = location.orEmpty()
        )
    }

    fun mergeDraft(existing: CalendarEventDraft?, answer: String, defaultDurationMinutes: Int = 60): CalendarEventDraft {
        val next = parseDraft(answer, defaultDurationMinutes)
        return CalendarEventDraft(
            title = next.title ?: existing?.title,
            date = next.date ?: existing?.date,
            time = next.time ?: existing?.time,
            durationMinutes = if (next.durationMinutes != defaultDurationMinutes) next.durationMinutes else existing?.durationMinutes ?: defaultDurationMinutes,
            location = next.location ?: existing?.location
        )
    }

    fun toParsedEvent(draft: CalendarEventDraft): ParsedCalendarEvent? = draft.asParsedEvent()

    fun missingFields(draft: CalendarEventDraft): List<String> {
        val missing = mutableListOf<String>()
        if (draft.title.isNullOrBlank()) missing.add("title")
        if (draft.date == null) missing.add("date")
        if (draft.time == null) missing.add("time")
        return missing
    }

    fun followUpQuestion(draft: CalendarEventDraft): String {
        val missing = missingFields(draft)
        return when {
            missing.contains("title") && missing.contains("date") && missing.contains("time") ->
                "What is the appointment title, date, and time, sir?"
            missing.contains("title") && missing.contains("date") ->
                "What should I call it, and what day is it for, sir?"
            missing.contains("title") && missing.contains("time") ->
                "What should I call it, and what time is it, sir?"
            missing.contains("date") && missing.contains("time") ->
                "What day and time should I put it on the calendar, sir?"
            missing.contains("title") -> "What should I call the appointment, sir?"
            missing.contains("date") -> "What day is it for, sir?"
            missing.contains("time") -> "What time is it, sir?"
            else -> "Please confirm the appointment details, sir."
        }
    }

    private fun findWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null,
            null,
            "${CalendarContract.Calendars._ID} ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun extractDate(lower: String): Calendar? {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when {
            lower.contains("today") -> return cal
            lower.contains("tomorrow") -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal
            }
        }

        val days = mapOf(
            "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY,
            "sunday" to Calendar.SUNDAY
        )
        for ((name, value) in days) {
            if (lower.contains(name)) {
                var delta = value - cal.get(Calendar.DAY_OF_WEEK)
                if (delta <= 0) delta += 7
                cal.add(Calendar.DAY_OF_YEAR, delta)
                return cal
            }
        }
        return null
    }

    private fun extractTime(lower: String): Pair<Int, Int>? {
        Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)")
            .find(lower)?.let { match ->
                var hour = match.groupValues[1].toIntOrNull() ?: return null
                val minute = match.groupValues[2].toIntOrNull() ?: 0
                val amPm = match.groupValues[3].replace(".", "").lowercase(Locale.getDefault())
                if (amPm == "pm" && hour < 12) hour += 12
                if (amPm == "am" && hour == 12) hour = 0
                return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
            }
        Regex("\\b(?:at\\s+)?(\\d{1,2}):(\\d{2})\\b").find(lower)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
        }
        Regex("\\bat\\s+(\\d{1,2})\\b").find(lower)?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            if (hour in 1..7) hour += 12
            return hour.coerceIn(0, 23) to 0
        }
        return when {
            lower.contains("noon") -> 12 to 0
            lower.contains("morning") -> 9 to 0
            lower.contains("afternoon") -> 15 to 0
            lower.contains("evening") -> 19 to 0
            lower.contains("night") -> 21 to 0
            else -> null
        }
    }

    private fun extractDurationMinutes(lower: String): Int? {
        Regex("(\\d{1,2})\\s*(hour|hours)").find(lower)?.let {
            return (it.groupValues[1].toIntOrNull() ?: return null) * 60
        }
        Regex("(\\d{1,3})\\s*(minute|minutes|min)").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun extractTitle(command: String): String {
        return command
            .replace(Regex("(?i)^(hey jarvis,?\\s+)?"), "")
            .replace(Regex("(?i)^(set|add|create|schedule|book)\\s+(an?\\s+)?"), "")
            .replace(Regex("(?i)\\b(event|appointment|meeting)\\b"), "")
            .replace(Regex("(?i)\\b(in|to|on|into)\\s+(my\\s+)?calend[ae]r\\b"), "")
            .replace(Regex("(?i)\\bcalend[ae]r\\b"), "")
            .replace(Regex("(?i)\\b(for\\s+)?(tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"), "")
            .replace(Regex("(?i)\\b(at\\s+)?\\d{1,2}(:\\d{2})?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)\\b"), "")
            .replace(Regex("(?i)\\b(at\\s+)?\\d{1,2}:\\d{2}\\b"), "")
            .replace(Regex("(?i)\\bat\\s+\\d{1,2}\\b"), "")
            .replace(Regex("(?i)\\b(morning|afternoon|evening|night|noon)\\b"), "")
            .replace(Regex("(?i)\\bfor\\s+\\d{1,3}\\s*(minutes?|mins?|hours?)\\b"), "")
            .replace(Regex("(?i)\\bat\\s+[^,.;]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun extractLocation(command: String): String? {
        val lower = command.lowercase(Locale.getDefault())
        if (Regex("\\bat\\s+\\d").containsMatchIn(lower)) return null
        val match = Regex("(?i)\\bat\\s+([^,.;]+)$").find(command) ?: return null
        val value = match.groupValues[1].trim()
        if (value.isBlank()) return null
        val blocked = setOf("morning", "afternoon", "evening", "night", "noon")
        if (blocked.contains(value.lowercase(Locale.getDefault()))) return null
        return value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    fun formatEventTime(startTime: Long): String {
        return SimpleDateFormat("EEE MMM d 'at' h:mm a", Locale.getDefault()).format(Date(startTime))
    }
}

data class ParsedCalendarEvent(
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val location: String = ""
)

data class CalendarEventDraft(
    val title: String? = null,
    val date: Calendar? = null,
    val time: Pair<Int, Int>? = null,
    val durationMinutes: Int = 60,
    val location: String? = null
)

data class CalendarInsertResult(
    val success: Boolean,
    val message: String,
    val eventUri: String? = null
)
