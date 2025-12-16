import 'dart:convert';
import 'package:http/http.dart' as http;

class TimetableService {
  /// Fetches the timetable for the given student and school year
  static Future<Map<String, dynamic>?> getTimetable(
    String accessToken,
    String studentId,
    String schoolYearId,
  ) async {
    final url = Uri.parse(
      'https://aplikace.skolaonline.cz/solapi/api/v1/timeTable'
      '?StudentId=$studentId&SchoolYearId=$schoolYearId',
    );
    try {
      final response = await http.get(
        url,
        headers: {'Authorization': 'Bearer $accessToken'},
      );
      if (response.statusCode == 200) {
        return json.decode(response.body) as Map<String, dynamic>;
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  /// Extracts today's lessons from the timetable data
  static List<Map<String, dynamic>> getTodayLessons(
    Map<String, dynamic> timetableData,
  ) {
    final days = timetableData['days'] as List<dynamic>?;
    if (days == null || days.isEmpty) return [];

    final now = DateTime.now();
    final todayStr =
        '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';

    for (final day in days) {
      final dateStr = day['date'] as String?;
      if (dateStr != null && dateStr.startsWith(todayStr)) {
        final schedules = day['schedules'] as List<dynamic>?;
        if (schedules == null) return [];

        // Collect all lessons
        final lessons = schedules
            .map((schedule) => Map<String, dynamic>.from(schedule))
            .toList();

        // Sort by begin time
        lessons.sort((a, b) {
          final aTime = a['beginTime'] as String? ?? '';
          final bTime = b['beginTime'] as String? ?? '';
          return aTime.compareTo(bTime);
        });

        return lessons;
      }
    }

    return [];
  }

  /// Checks if a lesson is a substitution (supl)
  static bool isSubstitution(Map<String, dynamic> lesson) {
    final hourType = lesson['hourType'] as Map<String, dynamic>?;
    final hourTypeId = hourType?['id'] as String? ?? '';
    return hourTypeId == 'SUPLOVANI';
  }

  /// Checks if a lesson is cancelled (suplovana - the original lesson that was replaced)
  static bool isCancelled(Map<String, dynamic> lesson) {
    final hourType = lesson['hourType'] as Map<String, dynamic>?;
    final hourTypeId = hourType?['id'] as String? ?? '';
    return hourTypeId == 'SUPLOVANA';
  }

  /// Checks if a lesson is a school event
  static bool isSchoolEvent(Map<String, dynamic> lesson) {
    final hourType = lesson['hourType'] as Map<String, dynamic>?;
    final hourTypeId = hourType?['id'] as String? ?? '';
    return hourTypeId == 'SKOLNI_AKCE';
  }

  /// Gets a display-friendly time string from a datetime string
  static String formatTime(String? dateTimeStr) {
    if (dateTimeStr == null) return '';
    try {
      final dt = DateTime.parse(dateTimeStr);
      return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
    } catch (_) {
      return '';
    }
  }

  /// Gets the end time from a lesson
  static String getEndTime(Map<String, dynamic> lesson) {
    return formatTime(lesson['endTime'] as String?);
  }

  /// Gets the start time from a lesson
  static String getStartTime(Map<String, dynamic> lesson) {
    return formatTime(lesson['beginTime'] as String?);
  }

  /// Gets the subject name from a lesson (full name, not abbrev)
  /// For school events, uses the lesson's title instead
  static String getSubjectName(Map<String, dynamic> lesson) {
    // For school events, use the title
    if (isSchoolEvent(lesson)) {
      return lesson['title'] as String? ?? 'Školní akce';
    }
    final subject = lesson['subject'] as Map<String, dynamic>?;
    return subject?['name'] as String? ?? '?';
  }

  /// Gets the room abbreviation from a lesson
  static String getRoomAbbrev(Map<String, dynamic> lesson) {
    final rooms = lesson['rooms'] as List<dynamic>?;
    if (rooms == null || rooms.isEmpty) return '';
    if (rooms.length > 1) return '';
    return rooms[0]['abbrev'] as String? ?? '';
  }

  /// Gets the teacher display name from a lesson (includes surname)
  static String getTeacherDisplayName(Map<String, dynamic> lesson) {
    final teachers = lesson['teachers'] as List<dynamic>?;
    if (teachers == null || teachers.isEmpty) return '';
    if (teachers.length > 1) return '';
    return teachers[0]['displayName'] as String? ?? '';
  }

  /// Gets the lesson number range (e.g., "1" or "3-4")
  static String getLessonNumber(Map<String, dynamic> lesson) {
    final detailHours = lesson['detailHours'] as List<dynamic>?;
    if (detailHours == null || detailHours.isEmpty) return '';

    if (detailHours.length == 1) {
      return detailHours[0]['name'] as String? ?? '';
    } else {
      final firstName = detailHours.first['name'] as String? ?? '';
      final lastName = detailHours.last['name'] as String? ?? '';
      return '$firstName-$lastName';
    }
  }

  /// Gets all days with their lessons from the timetable
  static List<Map<String, dynamic>> getAllDays(
    Map<String, dynamic> timetableData,
  ) {
    final days = timetableData['days'] as List<dynamic>?;
    if (days == null || days.isEmpty) return [];

    final result = <Map<String, dynamic>>[];

    for (final day in days) {
      final dateStr = day['date'] as String?;
      if (dateStr == null) continue;

      final schedules = day['schedules'] as List<dynamic>?;
      if (schedules == null) continue;

      // Collect all lessons
      final lessons = schedules
          .map((schedule) => Map<String, dynamic>.from(schedule))
          .toList();

      // Sort by begin time
      lessons.sort((a, b) {
        final aTime = a['beginTime'] as String? ?? '';
        final bTime = b['beginTime'] as String? ?? '';
        return aTime.compareTo(bTime);
      });

      result.add({
        'date': dateStr,
        'dateLabel': _formatDateLabel(dateStr),
        'lessons': lessons,
      });
    }

    return result;
  }

  /// Formats date string to user-friendly label
  static String _formatDateLabel(String dateStr) {
    try {
      final dt = DateTime.parse(dateStr);
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day);
      final date = DateTime(dt.year, dt.month, dt.day);

      final weekdays = ['Po', 'Út', 'St', 'Čt', 'Pá', 'So', 'Ne'];
      final dayName = weekdays[dt.weekday - 1];

      if (date == today) {
        return 'Dnes ($dayName)';
      } else if (date == today.add(const Duration(days: 1))) {
        return 'Zítra ($dayName)';
      } else if (date == today.subtract(const Duration(days: 1))) {
        return 'Včera ($dayName)';
      } else {
        return '$dayName ${dt.day}.${dt.month}.';
      }
    } catch (_) {
      return dateStr;
    }
  }
}
