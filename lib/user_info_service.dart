import 'dart:convert';
import 'package:http/http.dart' as http;

class UserInfoService {
  /// Returns the user info JSON if successful, or null otherwise
  static Future<Map<String, dynamic>?> getUserInfo(String accessToken) async {
    final url = Uri.parse('https://aplikace.skolaonline.cz/solapi/api/v1/user');
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
}
