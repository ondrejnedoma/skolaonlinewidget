import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class LoginService {
  /// Returns null on success, or error description on failure
  static Future<String?> login(String username, String password) async {
    try {
      final url = Uri.parse(
        'https://aplikace.skolaonline.cz/solapi/api/connect/token',
      );
      final response = await http.post(
        url,
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: {
          'client_id': 'test_client',
          'grant_type': 'password',
          'password': password,
          'scope': 'sol_api offline_access profile openid',
          'username': username,
        },
      );
      Map<String, dynamic>? data;
      try {
        data = json.decode(response.body) as Map<String, dynamic>?;
      } catch (_) {
        data = null;
      }
      if (response.statusCode == 200) {
        final refreshToken = data != null ? data['refresh_token'] : null;
        if (refreshToken != null) {
          final prefs = await SharedPreferences.getInstance();
          await prefs.setString('refresh_token', refreshToken);
          return null;
        }
        return "Server neposlal refresh token";
      }
      return data != null && data['error_description'] is String
          ? data['error_description'] as String
          : "Neznámá chyba při přihlášení";
    } catch (e) {
      return e.toString();
    }
  }
}
