import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'dart:async';

import 'access_token_service.dart';
import 'home_widget_service.dart';
import 'login_service.dart';
import 'user_info_service.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData.light().copyWith(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.green),
      ),
      darkTheme: ThemeData.dark().copyWith(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.green,
          brightness: Brightness.dark,
        ),
      ),
      themeMode: ThemeMode.system,
      home: const LoginForm(),
    );
  }
}

class LoginForm extends StatefulWidget {
  const LoginForm({super.key});

  @override
  State<LoginForm> createState() => _LoginFormState();
}

class _LoginFormState extends State<LoginForm> {
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();

  bool _passwordVisible = false;
  bool _loading = false;
  bool _initializing = true;
  bool _waitingForInternet = false;
  Map<String, dynamic>? _userInfo;
  Timer? _connectivityCheckTimer;

  @override
  void initState() {
    super.initState();
    _checkExistingSession();
  }

  @override
  void dispose() {
    _usernameController.dispose();
    _passwordController.dispose();
    _connectivityCheckTimer?.cancel();
    super.dispose();
  }

  Future<void> _checkExistingSession() async {
    // Check internet connectivity first
    final connectivityResult = await Connectivity().checkConnectivity();
    final hasInternet = connectivityResult.any(
      (result) => result != ConnectivityResult.none,
    );

    if (!hasInternet) {
      setState(() => _waitingForInternet = true);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Připojte se k internetu'),
            backgroundColor: Colors.orange,
            duration: Duration(seconds: 3),
          ),
        );
      }
      // Start periodic check every 3 seconds
      _connectivityCheckTimer = Timer.periodic(
        const Duration(seconds: 3),
        (timer) => _retryConnectionCheck(),
      );
      return;
    }

    final prefs = await SharedPreferences.getInstance();
    final refreshToken = prefs.getString('refresh_token');

    if (refreshToken == null) {
      setState(() => _initializing = false);
      return;
    }

    final accessToken = await AccessTokenService.getAccessToken();
    if (accessToken == null) {
      setState(() => _initializing = false);
      return;
    }

    final userInfo = await UserInfoService.getUserInfo(accessToken);
    if (userInfo == null) {
      setState(() => _initializing = false);
      return;
    }

    if (mounted) {
      setState(() {
        _userInfo = userInfo;
        _initializing = false;
      });
    }
  }

  Future<void> _retryConnectionCheck() async {
    final connectivityResult = await Connectivity().checkConnectivity();
    final hasInternet = connectivityResult.any(
      (result) => result != ConnectivityResult.none,
    );

    if (hasInternet) {
      _connectivityCheckTimer?.cancel();
      setState(() => _waitingForInternet = false);
      // Retry the session check now that we have internet
      _checkExistingSession();
    }
  }

  Future<void> _login() async {
    setState(() => _loading = true);

    final username = _usernameController.text;
    final password = _passwordController.text;
    final errorDescription = await LoginService.login(username, password);
    _passwordController.clear();
    if (!mounted) return;

    if (errorDescription == null) {
      await _handleSuccessfulLogin();
    } else {
      _showErrorSnackBar(errorDescription);
      setState(() => _loading = false);
    }
  }

  Future<void> _handleSuccessfulLogin() async {
    final accessToken = await AccessTokenService.getAccessToken();
    if (accessToken == null) {
      _showErrorSnackBar('Nepodařilo se získat access token');
      setState(() => _loading = false);
      return;
    }

    final userInfo = await UserInfoService.getUserInfo(accessToken);
    if (userInfo == null) {
      _showErrorSnackBar('Nepodařilo se získat informace o uživateli');
      setState(() => _loading = false);
      return;
    }

    setState(() {
      _userInfo = userInfo;
      _loading = false;
    });
    _showSuccessSnackBar('Přihlášení úspěšné');

    // Update the home widget with today's schedule
    await HomeWidgetService.updateWidget();
  }

  Future<void> _logout() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('refresh_token');

    setState(() => _userInfo = null);

    if (mounted) {
      _showSuccessSnackBar('Úspěšně odhlášeno');
    }
  }

  void _showSuccessSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: Colors.green),
    );
  }

  void _showErrorSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: Colors.red),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_initializing) {
      return const Scaffold(
        body: Center(
          child: SizedBox(
            width: 80,
            height: 80,
            child: CircularProgressIndicator(
              strokeWidth: 6,
              valueColor: AlwaysStoppedAnimation<Color>(Colors.green),
            ),
          ),
        ),
      );
    }

    return Scaffold(
      body: Center(
        child: _userInfo != null
            ? LoggedInWidget(userInfo: _userInfo!, onLogout: _logout)
            : _buildLoginForm(),
      ),
    );
  }

  Widget _buildLoginForm() {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Form(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextFormField(
              controller: _usernameController,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: "Uživatelské jméno",
              ),
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: _passwordController,
              decoration: InputDecoration(
                border: const OutlineInputBorder(),
                labelText: "Heslo",
                suffixIcon: IconButton(
                  icon: Icon(
                    _passwordVisible ? Icons.visibility_off : Icons.visibility,
                  ),
                  onPressed: () {
                    setState(() => _passwordVisible = !_passwordVisible);
                  },
                ),
              ),
              obscureText: !_passwordVisible,
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              onPressed: _loading ? null : _login,
              child: _loading
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                      ),
                    )
                  : const Text("Přihlásit se"),
            ),
          ],
        ),
      ),
    );
  }
}

class LoggedInWidget extends StatelessWidget {
  final Map<String, dynamic> userInfo;
  final VoidCallback onLogout;

  const LoggedInWidget({
    super.key,
    required this.userInfo,
    required this.onLogout,
  });

  static const platform = MethodChannel(
    'me.ondrejnedoma.skolaonlinewidget/widget',
  );

  Future<void> _requestPinWidget(BuildContext context) async {
    try {
      await platform.invokeMethod('requestPinWidget');
    } on PlatformException catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Chyba: ${e.message}'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final fullName = userInfo["fullName"] ?? "?";
    final userType = userInfo["userTypeText"] ?? "?";
    return Padding(
      padding: const EdgeInsets.all(24.0),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Text("Přihlášen", style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 16),
          Text(
            "$fullName - $userType",
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 32),
          ElevatedButton(
            onPressed: () => _requestPinWidget(context),
            child: const Text("Přidat widget"),
          ),
          const SizedBox(height: 8),
          ElevatedButton(onPressed: onLogout, child: const Text("Odhlásit se")),
        ],
      ),
    );
  }
}
