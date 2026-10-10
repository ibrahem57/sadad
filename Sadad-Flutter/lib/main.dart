import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:workmanager/workmanager.dart';

import 'core/config.dart';
import 'core/controller.dart';
import 'core/storage.dart';
import 'ui/auth.dart';
import 'ui/home.dart';
import 'ui/shared.dart';

@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    WidgetsFlutterBinding.ensureInitialized();
    final store = LedgerStore();
    try {
      await store.open();
      final session = await SessionVault().active();
      if (session == null || session.demo || session.forcePasswordChange) {
        return true;
      }
      final app = SadadController(store);
      app.session = session;
      await app.engine.run(session);
      app.dispose();
      return true;
    } catch (_) {
      return false;
    } finally {
      try {
        await store.db.close();
      } catch (_) {
        /* Database may not have opened. */
      }
    }
  });
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final store = LedgerStore();
  try {
    await store.open();
    final app = SadadController(store);
    runApp(SadadApp(app));
    unawaited(app.init());
    if (!AppConfig.demo) {
      try {
        await Workmanager().initialize(callbackDispatcher);
        if (Platform.isAndroid) {
          await Workmanager().registerPeriodicTask(
            'sadad.periodic.sync',
            'sadad.sync',
            frequency: const Duration(minutes: 15),
            constraints: Constraints(networkType: NetworkType.connected),
          );
        }
      } catch (_) {
        /* Foreground synchronization remains available. */
      }
    }
  } catch (_) {
    runApp(
      const MaterialApp(
        home: Scaffold(
          body: Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: Text(
                'تعذّر فتح التخزين المحلي. أعد فتح التطبيق دون حذف بياناته.',
                textDirection: TextDirection.rtl,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class SadadApp extends StatefulWidget {
  final SadadController app;
  const SadadApp(this.app, {super.key});
  @override
  State<SadadApp> createState() => _SadadAppState();
}

class _SadadAppState extends State<SadadApp> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      unawaited(widget.app.lockNow());
    }
    if (state == AppLifecycleState.resumed) {
      unawaited(widget.app.reload());
      unawaited(widget.app.sync());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.app.dispose();
    super.dispose();
  }

  ThemeData theme(Brightness brightness) {
    final dark = brightness == Brightness.dark;
    final scheme = ColorScheme.fromSeed(
      seedColor: const Color(0xff087f50),
      brightness: brightness,
      primary: dark ? const Color(0xff118359) : const Color(0xff087f50),
      onPrimary: Colors.white,
      surface: dark ? const Color(0xff111d18) : Colors.white,
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      fontFamily: 'Tajawal',
      brightness: brightness,
      scaffoldBackgroundColor: dark ? const Color(0xff0c1511) : Colors.white,
      appBarTheme: AppBarTheme(
        backgroundColor: scheme.surface,
        surfaceTintColor: Colors.transparent,
        centerTitle: false,
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: dark ? const Color(0xff183027) : const Color(0xffeef8f2),
        margin: const EdgeInsets.only(bottom: 12),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: dark ? const Color(0xff183027) : const Color(0xfff3f7f5),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: scheme.primary, width: 2),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(48, 48),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(48, 48),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.app,
    builder: (context, _) => MaterialApp(
      title: AppConfig.demo ? 'سدد · تجربة' : 'سدد',
      debugShowCheckedModeBanner: false,
      theme: theme(Brightness.light),
      darkTheme: theme(Brightness.dark),
      themeMode: widget.app.themeMode,
      locale: const Locale('ar'),
      supportedLocales: const [Locale('ar'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      builder: (context, child) => Stack(
        children: [
          ExcludeFocus(
            excluding: widget.app.locked,
            child: Offstage(offstage: widget.app.locked, child: child!),
          ),
          if (widget.app.locked) Positioned.fill(child: LockPage(widget.app)),
        ],
      ),
      home: widget.app.startupError.isNotEmpty
          ? Scaffold(
              body: Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(widget.app.startupError),
                      const SizedBox(height: 20),
                      FilledButton(
                        onPressed: widget.app.init,
                        child: const Text('إعادة المحاولة'),
                      ),
                    ],
                  ),
                ),
              ),
            )
          : widget.app.loading
          ? const SplashPage()
          : widget.app.session == null
          ? LoginPage(widget.app)
          : widget.app.session!.forcePasswordChange
          ? PasswordPage(widget.app)
          : HomeShell(widget.app, key: ValueKey(widget.app.session!.scope)),
    ),
  );
}

class SplashPage extends StatelessWidget {
  const SplashPage({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: 0.7, end: 1),
        duration: const Duration(milliseconds: 650),
        builder: (context, value, child) => Opacity(
          opacity: value,
          child: Transform.scale(scale: value, child: child),
        ),
        child: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Brand(size: 150),
            SizedBox(height: 24),
            Text(
              'سدد',
              style: TextStyle(fontSize: 32, fontWeight: FontWeight.bold),
            ),
            SizedBox(height: 16),
            CircularProgressIndicator(),
          ],
        ),
      ),
    ),
  );
}
