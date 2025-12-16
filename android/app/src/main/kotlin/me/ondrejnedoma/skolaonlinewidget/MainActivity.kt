package me.ondrejnedoma.skolaonlinewidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "me.ondrejnedoma.skolaonlinewidget/widget"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPinWidget" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val appWidgetManager = AppWidgetManager.getInstance(this)
                        val myProvider = ComponentName(this, ScheduleWidgetProvider::class.java)
                        
                        if (appWidgetManager.isRequestPinAppWidgetSupported) {
                            appWidgetManager.requestPinAppWidget(myProvider, null, null)
                            result.success(true)
                        } else {
                            result.error("UNSUPPORTED", "Widget pinning is not supported on this device", null)
                        }
                    } else {
                        result.error("UNSUPPORTED", "Widget pinning requires Android 8.0 or higher", null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
}
