package com.example.skolaonlinewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import java.text.SimpleDateFormat
import java.util.*

class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshWidget(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
    
    private fun refreshWidget(context: Context) {
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        
        val refreshToken = prefs.getString("flutter.refresh_token", null)
        if (refreshToken == null) {
            setError(context, "Nepřihlášen")
            return
        }
        
        try {
            val accessToken = getAccessToken(refreshToken)
            if (accessToken == null) {
                setError(context, "Chyba přihlášení")
                return
            }
            
            val userInfo = getUserInfo(accessToken)
            if (userInfo == null) {
                setError(context, "Chyba načítání")
                return
            }
            
            val personId = userInfo.optString("personID")
            val schoolYearId = userInfo.optString("schoolYearId")
            
            if (personId.isEmpty() || schoolYearId.isEmpty()) {
                setError(context, "Chybí data")
                return
            }
            
            val timetable = getTimetable(accessToken, personId, schoolYearId)
            if (timetable == null) {
                setError(context, "Chyba rozvrhu")
                return
            }
            
            val processedData = processTimetable(timetable)
            val todayIndex = findTodayIndex(processedData)
            
            val widgetPrefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
            widgetPrefs.edit()
                .putString("all_days_data", processedData.toString())
                .putInt("current_day_index", todayIndex)
                .putString("error", "")
                .putBoolean("is_refreshing", false)
                .apply()
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, ScheduleWidgetProvider::class.java)
            )
            for (id in widgetIds) {
                ScheduleWidgetProvider.updateAppWidget(context, appWidgetManager, id)
            }
            
        } catch (e: Exception) {
            setError(context, "Chyba: ${e.message}")
        }
    }
    
    private fun processTimetable(timetableJson: JSONObject): JSONArray {
        val days = timetableJson.optJSONArray("days") ?: return JSONArray()
        val result = JSONArray()
        
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            val dateStr = day.optString("date")
            val schedules = day.optJSONArray("schedules") ?: continue
            
            // Deduplicate lessons - prefer SUPLOVANI over SUPLOVANA
            val lessonsBySlot = mutableMapOf<String, JSONObject>()
            for (j in 0 until schedules.length()) {
                val schedule = schedules.getJSONObject(j)
                val lessonFrom = schedule.optString("lessonIdFrom", "")
                val lessonTo = schedule.optString("lessonIdTo", "")
                val hourType = schedule.optJSONObject("hourType")
                val hourTypeId = hourType?.optString("id", "") ?: ""
                
                val slotKey = "$lessonFrom-$lessonTo"
                if (lessonsBySlot.containsKey(slotKey)) {
                    val existing = lessonsBySlot[slotKey]!!
                    val existingTypeId = existing.optJSONObject("hourType")?.optString("id", "") ?: ""
                    if (hourTypeId == "SUPLOVANI" && existingTypeId == "SUPLOVANA") {
                        lessonsBySlot[slotKey] = schedule
                    }
                } else {
                    lessonsBySlot[slotKey] = schedule
                }
            }
            
            // Sort lessons by begin time
            val sortedLessons = lessonsBySlot.values.sortedBy { 
                it.optString("beginTime", "")
            }
            
            // Format lessons
            val formattedLessons = JSONArray()
            for (lesson in sortedLessons) {
                val formatted = formatLesson(lesson)
                formattedLessons.put(formatted)
            }
            
            val dayData = JSONObject()
            dayData.put("date", dateStr)
            dayData.put("dateLabel", formatDateLabel(dateStr))
            dayData.put("lessons", formattedLessons)
            result.put(dayData)
        }
        
        return result
    }
    
    private fun formatLesson(lesson: JSONObject): JSONObject {
        val hourType = lesson.optJSONObject("hourType")
        val hourTypeId = hourType?.optString("id", "") ?: ""
        
        val subject = lesson.optJSONObject("subject")
        val subjectName = if (hourTypeId == "SKOLNI_AKCE") {
            lesson.optString("title", "Školní akce")
        } else {
            subject?.optString("name", "?") ?: "?"
        }
        
        val rooms = lesson.optJSONArray("rooms")
        val roomAbbrev = if (rooms != null && rooms.length() > 0) {
            rooms.getJSONObject(0).optString("abbrev", "")
        } else ""
        
        val teachers = lesson.optJSONArray("teachers")
        val teacherName = if (teachers != null && teachers.length() > 0) {
            teachers.getJSONObject(0).optString("displayName", "")
        } else ""
        
        val lessonFrom = lesson.optString("lessonIdFrom", "")
        val lessonTo = lesson.optString("lessonIdTo", "")
        val lessonNum = if (lessonFrom == lessonTo) lessonFrom else "$lessonFrom-$lessonTo"
        
        val result = JSONObject()
        result.put("lessonNum", lessonNum)
        result.put("timeStart", formatTime(lesson.optString("beginTime")))
        result.put("timeEnd", formatTime(lesson.optString("endTime")))
        result.put("subject", subjectName)
        result.put("room", roomAbbrev)
        result.put("teacher", teacherName)
        result.put("isSupl", hourTypeId == "SUPLOVANI")
        result.put("isCancelled", hourTypeId == "SUPLOVANA")
        result.put("isEvent", hourTypeId == "SKOLNI_AKCE")
        
        return result
    }
    
    private fun formatTime(dateTimeStr: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = inputFormat.parse(dateTimeStr)
            val outputFormat = SimpleDateFormat("HH:mm", Locale.US)
            outputFormat.format(date!!)
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun formatDateLabel(dateStr: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = inputFormat.parse(dateStr)!!
            val calendar = Calendar.getInstance()
            calendar.time = date
            
            val today = Calendar.getInstance()
            today.set(Calendar.HOUR_OF_DAY, 0)
            today.set(Calendar.MINUTE, 0)
            today.set(Calendar.SECOND, 0)
            today.set(Calendar.MILLISECOND, 0)
            
            val dateOnly = Calendar.getInstance()
            dateOnly.time = date
            dateOnly.set(Calendar.HOUR_OF_DAY, 0)
            dateOnly.set(Calendar.MINUTE, 0)
            dateOnly.set(Calendar.SECOND, 0)
            dateOnly.set(Calendar.MILLISECOND, 0)
            
            val weekdays = arrayOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")
            val dayName = weekdays[(calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7]
            
            val diffDays = ((dateOnly.timeInMillis - today.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
            
            when (diffDays) {
                0 -> "Dnes ($dayName)"
                1 -> "Zítra ($dayName)"
                -1 -> "Včera ($dayName)"
                else -> "$dayName ${calendar.get(Calendar.DAY_OF_MONTH)}.${calendar.get(Calendar.MONTH) + 1}."
            }
        } catch (e: Exception) {
            dateStr
        }
    }
    
    private fun findTodayIndex(daysArray: JSONArray): Int {
        val todayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = todayFormat.format(Date())
        
        for (i in 0 until daysArray.length()) {
            val day = daysArray.getJSONObject(i)
            val dateStr = day.optString("date", "")
            if (dateStr.startsWith(today)) {
                return i
            }
        }
        return 0
    }
    
    private fun setError(context: Context, error: String) {
        val prefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("error", error)
            .putBoolean("is_refreshing", false)
            .apply()
        
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, ScheduleWidgetProvider::class.java)
        )
        for (id in widgetIds) {
            ScheduleWidgetProvider.updateAppWidget(context, appWidgetManager, id)
        }
    }
    
    private fun getAccessToken(refreshToken: String): String? {
        val url = URL("https://aplikace.skolaonline.cz/solapi/api/connect/token")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        
        val params = "client_id=test_client&grant_type=refresh_token&refresh_token=$refreshToken"
        connection.outputStream.write(params.toByteArray())
        
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            return json.optString("access_token")
        }
        return null
    }
    
    private fun getUserInfo(accessToken: String): JSONObject? {
        val url = URL("https://aplikace.skolaonline.cz/solapi/api/v1/user")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            return JSONObject(response)
        }
        return null
    }
    
    private fun getTimetable(accessToken: String, personId: String, schoolYearId: String): JSONObject? {
        val url = URL("https://aplikace.skolaonline.cz/solapi/api/v1/timeTable?StudentId=$personId&SchoolYearId=$schoolYearId")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            return JSONObject(response)
        }
        return null
    }
}
