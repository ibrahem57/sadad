class AppConfig {
  static const demo =
      String.fromEnvironment('FLUTTER_APP_FLAVOR') == 'demo' ||
      bool.fromEnvironment('SADAD_DEMO');
  static const apiUrl = String.fromEnvironment(
    'SADAD_API_URL',
    defaultValue:
        'https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-api/api',
  );
  // Public gateway key. Store authentication uses Sadad's separate bearer token.
  static const apiKey = String.fromEnvironment(
    'SADAD_API_KEY',
    defaultValue: 'sb_publishable_dK7HuYY8TbO8ZiBPbOZ2cw_1ZRL_chh',
  );
  static const supportUrl = 'https://wa.me/970599562401';
}
