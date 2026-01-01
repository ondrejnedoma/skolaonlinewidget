package me.ondrejnedoma.skolaonlinewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.SharedPreferences
import android.content.ComponentName
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

class ScheduleWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "me.ondrejnedoma.skolaonlinewidget.ACTION_REFRESH"
        const val ACTION_PREV_DAY = "me.ondrejnedoma.skolaonlinewidget.ACTION_PREV_DAY"
        const val ACTION_NEXT_DAY = "me.ondrejnedoma.skolaonlinewidget.ACTION_NEXT_DAY"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
            val error = prefs.getString("error", "") ?: ""
            val allDaysData = prefs.getString("all_days_data", "[]") ?: "[]"
            val isRefreshing = prefs.getBoolean("is_refreshing", false)
            val currentDayIndex = prefs.getInt("current_day_index", 0)

            val views = RemoteViews(context.packageName, R.layout.schedule_widget)

            // Set up button intents
            views.setOnClickPendingIntent(R.id.btn_refresh, getPendingIntent(context, ACTION_REFRESH))
            views.setOnClickPendingIntent(R.id.btn_prev_day, getPendingIntent(context, ACTION_PREV_DAY))
            views.setOnClickPendingIntent(R.id.btn_next_day, getPendingIntent(context, ACTION_NEXT_DAY))

            // Show/hide refresh button and progress indicator
            views.setViewVisibility(R.id.btn_refresh, if (isRefreshing) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.refresh_progress, if (isRefreshing) View.VISIBLE else View.GONE)

            if (error.isNotEmpty()) {
                views.setTextViewText(R.id.widget_error, error)
                views.setViewVisibility(R.id.widget_error, View.VISIBLE)
                views.setViewVisibility(R.id.widget_list, View.GONE)
                views.setViewVisibility(R.id.widget_empty, View.GONE)
                views.setTextViewText(R.id.widget_title, "Rozvrh")
            } else {
                views.setViewVisibility(R.id.widget_error, View.GONE)
                
                try {
                    val daysArray = JSONArray(allDaysData)
                    val totalDays = daysArray.length()

                    if (totalDays == 0) {
                        views.setViewVisibility(R.id.widget_list, View.GONE)
                        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                        views.setTextViewText(R.id.widget_title, "?")
                        views.setViewVisibility(R.id.btn_prev_day, View.INVISIBLE)
                        views.setViewVisibility(R.id.btn_next_day, View.INVISIBLE)
                    } else {
                        // Ensure currentDayIndex is within bounds
                        val dayIndex = currentDayIndex.coerceIn(0, totalDays - 1)
                        val currentDay = daysArray.getJSONObject(dayIndex)
                        val dateLabel = currentDay.optString("dateLabel", "?")
                        val lessons = currentDay.optJSONArray("lessons") ?: JSONArray()
                        val isFreeDay = currentDay.optBoolean("isFreeDay", false)

                        // Display formatted date label
                        views.setTextViewText(R.id.widget_title, dateLabel)

                        // Always show chevrons to allow week navigation
                        views.setViewVisibility(R.id.btn_prev_day, View.VISIBLE)
                        views.setViewVisibility(R.id.btn_next_day, View.VISIBLE)

                        if (lessons.length() == 0 && isFreeDay) {
                            // Free day - show celebration emoji
                            views.setViewVisibility(R.id.widget_list, View.GONE)
                            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                            views.setTextViewText(R.id.widget_empty, "🎉\n\nPro tento den nemáte žádnou agendu")
                        } else if (lessons.length() == 0) {
                            views.setViewVisibility(R.id.widget_list, View.GONE)
                            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                        } else {
                            views.setViewVisibility(R.id.widget_empty, View.GONE)
                            views.setViewVisibility(R.id.widget_list, View.VISIBLE)
                            
                            val intent = Intent(context, ScheduleWidgetService::class.java).apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                putExtra("current_day_index", dayIndex)
                                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                            }
                            views.setRemoteAdapter(R.id.widget_list, intent)
                        }
                    }
                } catch (e: Exception) {
                    views.setViewVisibility(R.id.widget_list, View.GONE)
                    views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
        }

        private fun getPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, ScheduleWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        // Trigger refresh on periodic update
        triggerRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> triggerRefresh(context)
            ACTION_PREV_DAY -> navigateDay(context, -1)
            ACTION_NEXT_DAY -> navigateDay(context, 1)
        }
    }

    private fun navigateDay(context: Context, direction: Int) {
        val prefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
        val allDaysData = prefs.getString("all_days_data", "[]") ?: "[]"
        
        try {
            val daysArray = JSONArray(allDaysData)
            val totalDays = daysArray.length()
            
            if (totalDays > 0) {
                val currentIndex = prefs.getInt("current_day_index", 0)
                val newIndex = currentIndex + direction
                
                // Check if we're navigating to previous week (before Monday) or next week (after Friday)
                if (newIndex < 0 || newIndex >= totalDays) {
                    // Request a week change and refresh
                    prefs.edit()
                        .putString("week_navigation_direction", if (direction < 0) "previous" else "next")
                        .putString("refresh_requested", System.currentTimeMillis().toString())
                        .putBoolean("is_refreshing", true)
                        .apply()
                    
                    // Trigger refresh which will fetch new week
                    val intent = Intent(context, WidgetRefreshReceiver::class.java)
                    context.sendBroadcast(intent)
                    
                    // Update widgets to show loading state
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val widgetIds = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, ScheduleWidgetProvider::class.java)
                    )
                    for (id in widgetIds) {
                        updateAppWidget(context, appWidgetManager, id)
                    }
                } else {
                    // Normal navigation within current week
                    prefs.edit()
                        .putInt("current_day_index", newIndex)
                        .apply()
                    
                    // Update all widgets
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val widgetIds = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, ScheduleWidgetProvider::class.java)
                    )
                    for (id in widgetIds) {
                        updateAppWidget(context, appWidgetManager, id)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerRefresh(context: Context) {
        val prefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("refresh_requested", System.currentTimeMillis().toString())
            .putBoolean("is_refreshing", true)
            .putInt("current_day_index", 0)
            .putInt("current_week_offset", 0) // Reset to current week on manual refresh
            .remove("week_navigation_direction")
            .apply()
        
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, ScheduleWidgetProvider::class.java)
        )
        for (id in widgetIds) {
            updateAppWidget(context, appWidgetManager, id)
        }
        
        val intent = Intent(context, WidgetRefreshReceiver::class.java)
        context.sendBroadcast(intent)
    }


    override fun onEnabled(context: Context) {
        // Initialize widget state when first added
        val prefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_refreshing", false)
            .putString("all_days_data", "[]")
            .apply()
    }
    
    override fun onDisabled(context: Context) {}
}

class ScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ScheduleRemoteViewsFactory(applicationContext)
    }
}

class ScheduleRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var lessons: MutableList<LessonItem> = mutableListOf()

    data class LessonItem(
        val lessonNum: String,
        val timeStart: String,
        val timeEnd: String,
        val subject: String,
        val room: String,
        val teacher: String,
        val isSupl: Boolean,
        val isCancelled: Boolean,
        val isEvent: Boolean
    )

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    private fun loadData() {
        lessons.clear()
        val prefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
        val allDaysData = prefs.getString("all_days_data", "[]") ?: "[]"
        val currentDayIndex = prefs.getInt("current_day_index", 0)
        
        try {
            val daysArray = JSONArray(allDaysData)
            if (daysArray.length() == 0) return
            
            val dayIndex = currentDayIndex.coerceIn(0, daysArray.length() - 1)
            val currentDay = daysArray.getJSONObject(dayIndex)
            val lessonsArray = currentDay.optJSONArray("lessons") ?: return

            for (i in 0 until lessonsArray.length()) {
                val obj = lessonsArray.getJSONObject(i)
                lessons.add(LessonItem(
                    lessonNum = obj.optString("lessonNum", ""),
                    timeStart = obj.optString("timeStart", ""),
                    timeEnd = obj.optString("timeEnd", ""),
                    subject = obj.optString("subject", ""),
                    room = obj.optString("room", ""),
                    teacher = obj.optString("teacher", ""),
                    isSupl = obj.optBoolean("isSupl", false),
                    isCancelled = obj.optBoolean("isCancelled", false),
                    isEvent = obj.optBoolean("isEvent", false)
                ))
            }
        } catch (e: Exception) {
            // Leave empty
        }
    }

    override fun onDestroy() {
        lessons.clear()
    }

    override fun getCount(): Int = lessons.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.lesson_item)
        
        if (position < lessons.size) {
            val lesson = lessons[position]
            views.setTextViewText(R.id.lesson_num, lesson.lessonNum)
            views.setTextViewText(R.id.lesson_time_start, lesson.timeStart)
            views.setTextViewText(R.id.lesson_time_end, lesson.timeEnd)
            views.setTextViewText(R.id.lesson_subject, lesson.subject)
            views.setTextViewText(R.id.lesson_teacher, lesson.teacher)
            views.setTextViewText(R.id.lesson_room, lesson.room)
            
            // Hide teacher tag if text is empty
            if (lesson.teacher.isEmpty()) {
                views.setViewVisibility(R.id.lesson_teacher_tag, View.GONE)
            } else {
                views.setViewVisibility(R.id.lesson_teacher_tag, View.VISIBLE)
            }
            
            // Hide room tag if text is empty
            if (lesson.room.isEmpty()) {
                views.setViewVisibility(R.id.lesson_room_tag, View.GONE)
            } else {
                views.setViewVisibility(R.id.lesson_room_tag, View.VISIBLE)
            }
            
            // Set indicator stripe color based on lesson type
            val indicatorColor = when {
                lesson.isCancelled -> Color.parseColor("#252525") // Same as background for cancelled
                lesson.isSupl -> Color.parseColor("#FF3B30") // Red for substitute
                lesson.isEvent -> Color.parseColor("#2196F3") // Blue for event
                else -> Color.WHITE // White for normal
            }
            views.setInt(R.id.lesson_indicator, "setBackgroundColor", indicatorColor)
            
            // Apply grey color and strikethrough for cancelled lessons
            if (lesson.isCancelled) {
                views.setInt(R.id.lesson_subject, "setPaintFlags", 
                    android.graphics.Paint.STRIKE_THRU_TEXT_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
                views.setTextColor(R.id.lesson_subject, Color.parseColor("#888888"))
                views.setInt(R.id.lesson_num, "setPaintFlags", 
                    android.graphics.Paint.STRIKE_THRU_TEXT_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
                views.setTextColor(R.id.lesson_num, Color.parseColor("#888888"))
            } else {
                views.setInt(R.id.lesson_subject, "setPaintFlags", android.graphics.Paint.ANTI_ALIAS_FLAG)
                views.setTextColor(R.id.lesson_subject, Color.WHITE)
                views.setInt(R.id.lesson_num, "setPaintFlags", android.graphics.Paint.ANTI_ALIAS_FLAG)
                views.setTextColor(R.id.lesson_num, Color.WHITE)
            }
        }
        
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
