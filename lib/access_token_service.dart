import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class AccessTokenService {
  /// Returns the access_token if successful, or null otherwise
  static Future<String?> getAccessToken() async {
    final prefs = await SharedPreferences.getInstance();
    final refreshToken = prefs.getString('refresh_token');
    if (refreshToken == null) return null;
    final url = Uri.parse(
      'https://aplikace.skolaonline.cz/solapi/api/connect/token',
    );
    try {
      final response = await http.post(
        url,
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: {
          'client_id': 'test_client',
          'grant_type': 'refresh_token',
          'refresh_token': refreshToken,
          'scope': 'offline_access sol_api',
        },
      );
      final data = json.decode(response.body);
      if (response.statusCode == 200 && data['access_token'] is String) {
        // Save the new refresh token if provided
        final newRefreshToken = data['refresh_token'];
        if (newRefreshToken is String) {
          await prefs.setString('refresh_token', newRefreshToken);
        }
        return data['access_token'] as String;
      }
      return null;
    } catch (_) {
      return null;
    }
  }
}
