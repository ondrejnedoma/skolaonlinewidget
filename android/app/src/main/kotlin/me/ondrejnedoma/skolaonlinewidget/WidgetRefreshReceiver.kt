package me.ondrejnedoma.skolaonlinewidget

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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

class WidgetRefreshReceiver : BroadcastReceiver() {
    
    // Helper functions for week calculation
    private fun getMondayOfWeek(date: Calendar): Calendar {
        val result = date.clone() as Calendar
        val dayOfWeek = result.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        result.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
        result.set(Calendar.HOUR_OF_DAY, 0)
        result.set(Calendar.MINUTE, 0)
        result.set(Calendar.SECOND, 0)
        result.set(Calendar.MILLISECOND, 0)
        return result
    }
    
    private fun getFridayOfWeek(monday: Calendar): Calendar {
        val result = monday.clone() as Calendar
        result.add(Calendar.DAY_OF_MONTH, 4)
        return result
    }
    
    private fun formatDateForApi(date: Calendar): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        return java.net.URLEncoder.encode(format.format(date.time), "UTF-8")
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshWidget(context)
            } catch (e: Exception) {
                setError(context, "Chyba: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
    
    private fun refreshWidget(context: Context) {
        // Check internet connectivity first - if no internet, keep refreshing state and schedule retry
        if (!hasInternetConnection(context)) {
            scheduleRetry(context)
            return
        }
        
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val widgetPrefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
        
        val refreshToken = prefs.getString("flutter.refresh_token", null)
        if (refreshToken == null) {
            setError(context, "Nepřihlášen")
            return
        }
        
        try {
            // Determine which week to fetch
            val weekNavDirection = widgetPrefs.getString("week_navigation_direction", null)
            val currentWeekOffset = widgetPrefs.getInt("current_week_offset", 0)
            
            val newWeekOffset = when (weekNavDirection) {
                "previous" -> currentWeekOffset - 1
                "next" -> currentWeekOffset + 1
                else -> currentWeekOffset
            }
            
            // Calculate Monday and Friday of the target week
            val now = Calendar.getInstance()
            now.add(Calendar.WEEK_OF_YEAR, newWeekOffset)
            val monday = getMondayOfWeek(now)
            val friday = getFridayOfWeek(monday)
            
            val accessToken = getAccessToken(context, refreshToken)
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
            
            val timetable = getTimetable(accessToken, personId, schoolYearId, monday, friday)
            if (timetable == null) {
                setError(context, "Chyba rozvrhu")
                return
            }
            
            val processedData = processTimetable(timetable, monday)
            
            // Determine which day to show after navigation
            val currentDayIndex = when (weekNavDirection) {
                "previous" -> 4 // Show Friday of previous week
                "next" -> 0 // Show Monday of next week
                else -> 0 // Default to Monday
            }
            
            widgetPrefs.edit()
                .putString("all_days_data", processedData.toString())
                .putString("error", "")
                .putBoolean("is_refreshing", false)
                .putInt("current_week_offset", newWeekOffset)
                .putInt("current_day_index", currentDayIndex)
                .remove("week_navigation_direction")
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
    
    private fun processTimetable(timetableJson: JSONObject, monday: Calendar): JSONArray {
        val days = timetableJson.optJSONArray("days")
        
        // Create a map of existing days from API response
        val existingDays = mutableMapOf<String, JSONObject>()
        
        if (days != null) {
            for (i in 0 until days.length()) {
                val day = days.getJSONObject(i)
                val dateStr = day.optString("date")
                val schedules = day.optJSONArray("schedules")
                
                if (schedules != null) {
                    // Collect all lessons
                    val allLessons = mutableListOf<JSONObject>()
                    for (j in 0 until schedules.length()) {
                        val schedule = schedules.getJSONObject(j)
                        allLessons.add(schedule)
                    }
                    
                    // Sort lessons by begin time
                    val sortedLessons = allLessons.sortedBy { 
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
                    dayData.put("isFreeDay", false)
                    
                    // Extract just the date part (YYYY-MM-DD)
                    val datePart = dateStr.split('T')[0]
                    existingDays[datePart] = dayData
                }
            }
        }
        
        // Ensure all weekdays (Monday to Friday) are present
        val result = JSONArray()
        for (i in 0 until 5) {
            val currentDay = monday.clone() as Calendar
            currentDay.add(Calendar.DAY_OF_MONTH, i)
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(currentDay.time)
            val fullDateStr = "${dateStr}T00:00:00"
            
            if (existingDays.containsKey(dateStr)) {
                result.put(existingDays[dateStr])
            } else {
                // No data for this day - it's a free day
                val dayData = JSONObject()
                dayData.put("date", fullDateStr)
                dayData.put("dateLabel", formatDateLabel(fullDateStr))
                dayData.put("lessons", JSONArray())
                dayData.put("isFreeDay", true)
                result.put(dayData)
            }
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
        val roomAbbrev = if (rooms != null && rooms.length() == 1) {
            rooms.getJSONObject(0).optString("abbrev", "")
        } else ""
        
        val teachers = lesson.optJSONArray("teachers")
        val teacherName = if (teachers != null && teachers.length() == 1) {
            teachers.getJSONObject(0).optString("displayName", "")
        } else ""
        
        val detailHours = lesson.optJSONArray("detailHours")
        val lessonNum = if (detailHours != null && detailHours.length() > 0) {
            if (detailHours.length() == 1) {
                detailHours.getJSONObject(0).optString("name", "")
            } else {
                val firstName = detailHours.getJSONObject(0).optString("name", "")
                val lastName = detailHours.getJSONObject(detailHours.length() - 1).optString("name", "")
                "$firstName-$lastName"
            }
        } else ""
        
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
            
            val weekdays = arrayOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")
            val dayName = weekdays[(calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7]
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            val month = calendar.get(Calendar.MONTH) + 1
            
            "$dayName $day.$month."
        } catch (e: Exception) {
            dateStr
        }
    }
    
    private fun scheduleRetry(context: Context) {
        // Keep the refreshing state and schedule a retry in 5 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val intent = Intent(context, WidgetRefreshReceiver::class.java)
            context.sendBroadcast(intent)
        }, 5000)
    }
    
    private fun hasInternetConnection(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
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
    
    private fun getAccessToken(context: Context, refreshToken: String): String? {
        val url = URL("https://aplikace.skolaonline.cz/solapi/api/connect/token")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        
        val params = "client_id=test_client&grant_type=refresh_token&refresh_token=$refreshToken"
        connection.outputStream.write(params.toByteArray())
        
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            
            // Save the new refresh token if provided
            val newRefreshToken = json.optString("refresh_token")
            if (newRefreshToken.isNotEmpty()) {
                val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                prefs.edit().putString("flutter.refresh_token", newRefreshToken).apply()
            }
            
            return json.optString("access_token")
        }
        return null
    }
    
    private fun getUserInfo(accessToken: String): JSONObject? {
        val url = URL("https://aplikace.skolaonline.cz/solapi/api/v1/user")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            return JSONObject(response)
        }
        return null
    }
    
    private fun getTimetable(accessToken: String, personId: String, schoolYearId: String, monday: Calendar, friday: Calendar): JSONObject? {
        val dateFrom = formatDateForApi(monday)
        val dateTo = formatDateForApi(friday)
        val url = URL("https://aplikace.skolaonline.cz/solapi/api/v1/timeTable?StudentId=$personId&SchoolYearId=$schoolYearId&DateFrom=$dateFrom&DateTo=$dateTo")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            return JSONObject(response)
        }
        return null
    }
}
