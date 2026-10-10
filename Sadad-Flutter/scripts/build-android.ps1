param([string]$FlutterSdk = 'C:\Users\Lenovo\Downloads\flutter_windows_3.47.2-stable\flutter')
$ErrorActionPreference = 'Stop'
$taskProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskFlutter = Join-Path $FlutterSdk 'bin\flutter.bat'
$taskDart = Join-Path $FlutterSdk 'bin\dart.bat'
if (-not (Test-Path -LiteralPath $taskFlutter)) { throw 'حدد مسار Flutter باستخدام -FlutterSdk.' }
$taskJava = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path -LiteralPath $taskJava) { $env:JAVA_HOME = $taskJava }
Push-Location $taskProjectRoot
try {
    & $taskFlutter pub get
    if ($LASTEXITCODE -ne 0) { throw 'فشل تنزيل المكتبات.' }
    & $taskDart analyze lib
    if ($LASTEXITCODE -ne 0) { throw 'راجع ملاحظات تحليل Dart.' }
    & $taskFlutter build apk --debug --flavor demo --dart-define=SADAD_DEMO=true
    if ($LASTEXITCODE -ne 0) { throw 'فشل بناء نسخة التجربة.' }
    & $taskFlutter build apk --debug --flavor official
    if ($LASTEXITCODE -ne 0) { throw 'فشل بناء النسخة الرسمية.' }
    Write-Output 'build\app\outputs\flutter-apk\app-demo-debug.apk'
    Write-Output 'build\app\outputs\flutter-apk\app-official-debug.apk'
} finally { Pop-Location }
