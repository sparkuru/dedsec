import 'package:flutter/material.dart';
import 'package:xterm/xterm.dart';

abstract final class CtosColors {
  static const background = Color(0xff0c1115);
  static const surface = Color(0xff151d23);
  static const surfaceRaised = Color(0xff1d2830);
  static const primary = Color(0xff65efb4);
  static const secondary = Color(0xff75bdff);
  static const text = Color(0xffe8f0f3);
  static const textSecondary = Color(0xffa2afb8);
  static const warning = Color(0xffffc078);
  static const error = Color(0xffff9292);
  static const border = Color(0xff35434d);
  static const controlBorder = Color(0xff647480);
}

abstract final class CtosTheme {
  static ThemeData dark() {
    final base = ThemeData(brightness: Brightness.dark, useMaterial3: true);
    final scheme = ColorScheme.fromSeed(
      seedColor: CtosColors.primary,
      brightness: Brightness.dark,
      surface: CtosColors.surface,
      primary: CtosColors.primary,
      secondary: CtosColors.secondary,
      error: CtosColors.error,
      onSurface: CtosColors.text,
      onSurfaceVariant: CtosColors.textSecondary,
      outline: CtosColors.controlBorder,
    );
    final text = base.textTheme
        .apply(bodyColor: CtosColors.text, displayColor: CtosColors.text)
        .copyWith(
          headlineLarge: const TextStyle(
            fontSize: 32,
            fontWeight: FontWeight.w700,
            height: 1.18,
            letterSpacing: -.8,
          ),
          headlineMedium: const TextStyle(
            fontSize: 28,
            fontWeight: FontWeight.w700,
            height: 1.2,
            letterSpacing: -.5,
          ),
          headlineSmall: const TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.w700,
            height: 1.25,
          ),
          titleLarge: const TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w600,
            height: 1.3,
          ),
          titleMedium: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
            height: 1.4,
          ),
          bodyLarge: const TextStyle(fontSize: 16, height: 1.5),
          bodyMedium: const TextStyle(fontSize: 14, height: 1.5),
          bodySmall: const TextStyle(
            fontSize: 13,
            height: 1.45,
            color: CtosColors.textSecondary,
          ),
          labelLarge: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
          ),
          labelMedium: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w500,
          ),
          labelSmall: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w500,
          ),
        );
    final shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(16),
    );
    return base.copyWith(
      colorScheme: scheme,
      scaffoldBackgroundColor: CtosColors.background,
      textTheme: text,
      appBarTheme: const AppBarTheme(
        backgroundColor: CtosColors.background,
        foregroundColor: CtosColors.text,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
      ),
      cardTheme: CardThemeData(
        color: CtosColors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: const EdgeInsets.only(bottom: 16),
        shape: shape,
      ),
      dividerTheme: const DividerThemeData(
        color: CtosColors.border,
        thickness: .5,
        space: 24,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: CtosColors.surfaceRaised,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 16,
        ),
        hintStyle: const TextStyle(
          color: CtosColors.textSecondary,
          fontSize: 14,
        ),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: CtosColors.controlBorder),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: CtosColors.controlBorder),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: CtosColors.primary, width: 1.5),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(48, 48),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(48, 48),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          minimumSize: const Size(48, 48),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(minimumSize: const Size(48, 48)),
      ),
      navigationBarTheme: const NavigationBarThemeData(
        backgroundColor: CtosColors.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: Color(0xff284637),
        labelTextStyle: WidgetStatePropertyAll(
          TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        ),
      ),
      navigationRailTheme: const NavigationRailThemeData(
        backgroundColor: CtosColors.surface,
        indicatorColor: Color(0xff284637),
        selectedIconTheme: IconThemeData(color: CtosColors.primary),
        selectedLabelTextStyle: TextStyle(
          color: CtosColors.primary,
          fontSize: 13,
        ),
        unselectedLabelTextStyle: TextStyle(
          color: CtosColors.textSecondary,
          fontSize: 13,
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: CtosColors.surfaceRaised,
        contentTextStyle: text.bodyMedium,
        behavior: SnackBarBehavior.floating,
        shape: shape,
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: CtosColors.surface,
        surfaceTintColor: Colors.transparent,
        shape: shape,
      ),
      listTileTheme: const ListTileThemeData(
        contentPadding: EdgeInsets.symmetric(horizontal: 20, vertical: 8),
        iconColor: CtosColors.textSecondary,
        textColor: CtosColors.text,
      ),
    );
  }

  static ThemeData withMotion(BuildContext context) {
    final theme = Theme.of(context);
    final motion = duration(context);
    return theme.copyWith(
      expansionTileTheme: theme.expansionTileTheme.copyWith(
        expansionAnimationStyle: AnimationStyle(
          duration: motion,
          reverseDuration: motion,
        ),
      ),
      pageTransitionsTheme: MediaQuery.disableAnimationsOf(context)
          ? PageTransitionsTheme(
              builders: {
                for (final platform in TargetPlatform.values)
                  platform: const _InstantPageTransitions(),
              },
            )
          : theme.pageTransitionsTheme,
      filledButtonTheme: FilledButtonThemeData(
        style: theme.filledButtonTheme.style?.copyWith(
          animationDuration: motion,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: theme.outlinedButtonTheme.style?.copyWith(
          animationDuration: motion,
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: theme.textButtonTheme.style?.copyWith(animationDuration: motion),
      ),
    );
  }

  static final terminal = TerminalTheme(
    cursor: CtosColors.primary,
    selection: const Color(0x5565efb4),
    foreground: CtosColors.text,
    background: CtosColors.background,
    black: TerminalThemes.defaultTheme.black,
    red: TerminalThemes.defaultTheme.red,
    green: TerminalThemes.defaultTheme.green,
    yellow: TerminalThemes.defaultTheme.yellow,
    blue: TerminalThemes.defaultTheme.blue,
    magenta: TerminalThemes.defaultTheme.magenta,
    cyan: TerminalThemes.defaultTheme.cyan,
    white: TerminalThemes.defaultTheme.white,
    brightBlack: TerminalThemes.defaultTheme.brightBlack,
    brightRed: TerminalThemes.defaultTheme.brightRed,
    brightGreen: TerminalThemes.defaultTheme.brightGreen,
    brightYellow: TerminalThemes.defaultTheme.brightYellow,
    brightBlue: TerminalThemes.defaultTheme.brightBlue,
    brightMagenta: TerminalThemes.defaultTheme.brightMagenta,
    brightCyan: TerminalThemes.defaultTheme.brightCyan,
    brightWhite: TerminalThemes.defaultTheme.brightWhite,
    searchHitBackground: TerminalThemes.defaultTheme.searchHitBackground,
    searchHitBackgroundCurrent:
        TerminalThemes.defaultTheme.searchHitBackgroundCurrent,
    searchHitForeground: TerminalThemes.defaultTheme.searchHitForeground,
  );

  static const numeric = TextStyle(
    fontFeatures: [FontFeature.tabularFigures()],
  );
  static Duration duration(BuildContext context) =>
      MediaQuery.disableAnimationsOf(context)
      ? Duration.zero
      : const Duration(milliseconds: 200);
}

class _InstantPageTransitions extends PageTransitionsBuilder {
  const _InstantPageTransitions();
  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) => child;
}
