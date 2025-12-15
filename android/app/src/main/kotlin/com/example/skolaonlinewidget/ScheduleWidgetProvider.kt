package com.example.skolaonlinewidget

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
        const val ACTION_PREV = "com.example.skolaonlinewidget.ACTION_PREV"
        const val ACTION_NEXT = "com.example.skolaonlinewidget.ACTION_NEXT"
        const val ACTION_REFRESH = "com.example.skolaonlinewidget.ACTION_REFRESH"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
            val error = prefs.getString("error", "") ?: ""
            val currentDayIndex = prefs.getInt("current_day_index", 0)
            val allDaysData = prefs.getString("all_days_data", "[]") ?: "[]"
            val isRefreshing = prefs.getBoolean("is_refreshing", false)

            val views = RemoteViews(context.packageName, R.layout.schedule_widget)

            // Set up button intents
            views.setOnClickPendingIntent(R.id.btn_prev, getPendingIntent(context, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.btn_next, getPendingIntent(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.btn_refresh, getPendingIntent(context, ACTION_REFRESH))

            // Show/hide refresh button and progress indicator
            views.setViewVisibility(R.id.btn_refresh, if (isRefreshing) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.refresh_progress, if (isRefreshing) View.VISIBLE else View.GONE)

            if (error.isNotEmpty()) {
                views.setTextViewText(R.id.widget_error, error)
                views.setViewVisibility(R.id.widget_error, View.VISIBLE)
                views.setViewVisibility(R.id.widget_list, View.GONE)
                views.setViewVisibility(R.id.widget_empty, View.GONE)
                views.setViewVisibility(R.id.btn_prev, View.GONE)
                views.setViewVisibility(R.id.btn_next, View.GONE)
                views.setTextViewText(R.id.widget_title, "Rozvrh")
            } else {
                views.setViewVisibility(R.id.widget_error, View.GONE)
                
                try {
                    val daysArray = JSONArray(allDaysData)
                    val totalDays = daysArray.length()

                    // Show/hide navigation buttons
                    views.setViewVisibility(R.id.btn_prev, if (currentDayIndex > 0) View.VISIBLE else View.INVISIBLE)
                    views.setViewVisibility(R.id.btn_next, if (currentDayIndex < totalDays - 1) View.VISIBLE else View.INVISIBLE)

                    if (totalDays == 0) {
                        views.setViewVisibility(R.id.widget_list, View.GONE)
                        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                        views.setTextViewText(R.id.widget_title, "Rozvrh")
                    } else {
                        val safeIndex = currentDayIndex.coerceIn(0, totalDays - 1)
                        val currentDay = daysArray.getJSONObject(safeIndex)
                        val dateLabel = currentDay.optString("dateLabel", "")
                        val lessons = currentDay.optJSONArray("lessons") ?: JSONArray()

                        views.setTextViewText(R.id.widget_title, dateLabel.ifEmpty { "Rozvrh" })

                        if (lessons.length() == 0) {
                            views.setViewVisibility(R.id.widget_list, View.GONE)
                            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                        } else {
                            views.setViewVisibility(R.id.widget_empty, View.GONE)
                            views.setViewVisibility(R.id.widget_list, View.VISIBLE)
                            
                            val intent = Intent(context, ScheduleWidgetService::class.java).apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                            }
                            views.setRemoteAdapter(R.id.widget_list, intent)
                        }
                    }
                } catch (e: Exception) {
                    views.setViewVisibility(R.id.widget_list, View.GONE)
                    views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                    views.setViewVisibility(R.id.btn_prev, View.GONE)
                    views.setViewVisibility(R.id.btn_next, View.GONE)
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
            ACTION_PREV -> navigateDay(context, -1)
            ACTION_NEXT -> navigateDay(context, 1)
            ACTION_REFRESH -> triggerRefresh(context)
        }
    }

    private fun navigateDay(context: Context, delta: Int) {
        val prefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
        val allDaysData = prefs.getString("all_days_data", "[]") ?: "[]"
        val currentIndex = prefs.getInt("current_day_index", 0)

        try {
            val daysArray = JSONArray(allDaysData)
            val totalDays = daysArray.length()
            val newIndex = (currentIndex + delta).coerceIn(0, maxOf(0, totalDays - 1))
            
            prefs.edit().putInt("current_day_index", newIndex).apply()

            // Update all widgets
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, ScheduleWidgetProvider::class.java)
            )
            for (id in widgetIds) {
                updateAppWidget(context, appWidgetManager, id)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun triggerRefresh(context: Context) {
        val prefs = context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("refresh_requested", System.currentTimeMillis().toString())
            .putBoolean("is_refreshing", true)
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

    override fun onEnabled(context: Context) {}
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
        val currentDayIndex = prefs.getInt("current_day_index", 0)
        val allDaysData = prefs.getString("all_days_data", "[]") ?: "[]"
        
        try {
            val daysArray = JSONArray(allDaysData)
            if (daysArray.length() == 0) return
            
            val safeIndex = currentDayIndex.coerceIn(0, daysArray.length() - 1)
            val currentDay = daysArray.getJSONObject(safeIndex)
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
            
            // Set background drawable based on lesson type
            val bgDrawable = when {
                lesson.isEvent -> R.drawable.lesson_item_bg_event
                lesson.isSupl -> R.drawable.lesson_item_bg_supl
                lesson.isCancelled -> R.drawable.lesson_item_bg_cancelled
                else -> R.drawable.lesson_item_bg
            }
            views.setInt(R.id.lesson_item, "setBackgroundResource", bgDrawable)
            
            // Apply strikethrough for cancelled lessons
            if (lesson.isCancelled) {
                views.setInt(R.id.lesson_subject, "setPaintFlags", 
                    android.graphics.Paint.STRIKE_THRU_TEXT_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
                views.setTextColor(R.id.lesson_subject, android.graphics.Color.RED)
            } else {
                views.setInt(R.id.lesson_subject, "setPaintFlags", android.graphics.Paint.ANTI_ALIAS_FLAG)
                views.setTextColor(R.id.lesson_subject, android.graphics.Color.WHITE)
            }
        }
        
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
