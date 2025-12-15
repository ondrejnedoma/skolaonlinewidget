import 'dart:convert';

import 'package:home_widget/home_widget.dart';

import 'access_token_service.dart';
import 'user_info_service.dart';
import 'timetable_service.dart';

class HomeWidgetService {
  static const String appGroupId = 'group.skolaonlinewidget';
  static const String androidWidgetName = 'ScheduleWidgetProvider';

  /// Updates the home widget with the schedule
  static Future<bool> updateWidget() async {
    try {
      // Get access token
      final accessToken = await AccessTokenService.getAccessToken();
      if (accessToken == null) {
        await _setWidgetError('Nepřihlášen');
        return false;
      }

      // Get user info
      final userInfo = await UserInfoService.getUserInfo(accessToken);
      if (userInfo == null) {
        await _setWidgetError('Chyba načítání');
        return false;
      }

      final personId = userInfo['personID'] as String?;
      final schoolYearId = userInfo['schoolYearId'] as String?;

      if (personId == null || schoolYearId == null) {
        await _setWidgetError('Chybí data');
        return false;
      }

      // Get timetable
      final timetable = await TimetableService.getTimetable(
        accessToken,
        personId,
        schoolYearId,
      );

      if (timetable == null) {
        await _setWidgetError('Chyba rozvrhu');
        return false;
      }

      // Get all days with lessons
      final allDays = TimetableService.getAllDays(timetable);

      final daysData = allDays.map((day) {
        final lessons = day['lessons'] as List<Map<String, dynamic>>;
        return {
          'date': day['date'],
          'dateLabel': day['dateLabel'],
          'lessons': lessons.map((lesson) {
            return {
              'lessonNum': TimetableService.getLessonNumber(lesson),
              'timeStart': TimetableService.getStartTime(lesson),
              'timeEnd': TimetableService.getEndTime(lesson),
              'subject': TimetableService.getSubjectName(lesson),
              'room': TimetableService.getRoomAbbrev(lesson),
              'teacher': TimetableService.getTeacherDisplayName(lesson),
              'isSupl': TimetableService.isSubstitution(lesson),
              'isCancelled': TimetableService.isCancelled(lesson),
              'isEvent': TimetableService.isSchoolEvent(lesson),
            };
          }).toList(),
        };
      }).toList();

      final todayIndex = TimetableService.findTodayIndex(allDays);

      await HomeWidget.saveWidgetData<String>(
        'all_days_data',
        json.encode(daysData),
      );
      await HomeWidget.saveWidgetData<int>('current_day_index', todayIndex);
      await HomeWidget.saveWidgetData<String>('error', '');
      await HomeWidget.saveWidgetData<bool>('is_refreshing', false);
      await HomeWidget.saveWidgetData<String>(
        'last_update',
        DateTime.now().toIso8601String(),
      );

      await HomeWidget.updateWidget(androidName: androidWidgetName);

      return true;
    } catch (e) {
      await _setWidgetError('Chyba: $e');
      return false;
    }
  }

  static Future<void> _setWidgetError(String error) async {
    await HomeWidget.saveWidgetData<String>('all_days_data', '[]');
    await HomeWidget.saveWidgetData<String>('error', error);
    await HomeWidget.saveWidgetData<bool>('is_refreshing', false);
    await HomeWidget.updateWidget(androidName: androidWidgetName);
  }
}
