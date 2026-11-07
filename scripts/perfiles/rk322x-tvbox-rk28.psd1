# Perfil de firmware: Rockchip RK3228A, Android 7.1.2, build TVBOX-20260608-RK28 (docs/04).
# preparar.ps1 lo usa cuando la caja coincide con "Detectar". Todo lo de aquí ya se probó en cajas reales.
@{
    Nombre      = 'Rockchip RK3228A, Android 7.1 (TVBOX-20260608-RK28)'
    Detectar    = @{
        'ro.build.fingerprint' = 'Android/rk322x_box/rk322x_box:7.1.2/NHG47K/ubuntu06080940:userdebug/test-keys'
        'ro.build.display.id'  = 'TVBOX-20260608-RK28'
    }
    # Carpeta de apps sugerida para fabrica.ps1 (32 bits, Android 7).
    Apks        = 'apks\fabrica-rk322x'

    # Se detienen antes del respaldo (am force-stop no cambia nada y se revierte al reiniciar).
    Detener     = @('com.rockchip.itvbox', 'com.lz.sysplugin', 'com.ztv.service', 'com.droidlogic.mboxlauncher',
                    'com.android.network', 'com.android.network.iotvads', 'com.android.tvh', 'com.android.appstore',
                    'com.tv.napp', 'android.rockchip.update.service', 'com.cghs.stresstest', 'com.bsw.update')

    # Malware conocido. Tras limpiar y tras formatear, ninguno debe existir.
    Malware     = @('com.android.tvh', 'com.tv.napp', 'com.android.appstore', 'com.android.network',
                    'com.android.network.iotvads', 'com.android.gms.service', 'com.charon.rocketfly',
                    'com.lz.sysplugin', 'com.rockchip.itvbox', 'com.ztv.service', 'com.droidlogic.mboxlauncher',
                    'com.droidlogic.launcher.provider', 'com.bsw.update', 'android.rockchip.update.service')

    # Se deshabilitan primero, para que no reinstalen nada mientras se limpia.
    Deshabilitar = @('com.android.tvh', 'com.lz.sysplugin', 'com.rockchip.itvbox', 'com.ztv.service',
                     'com.droidlogic.mboxlauncher', 'com.droidlogic.launcher.provider', 'com.bsw.update',
                     'android.rockchip.update.service', 'com.android.appstore', 'com.tv.napp', 'com.android.network',
                     'com.android.network.iotvads', 'com.dyos.factorytest', 'com.cghs.stresstest', 'com.DeviceTest',
                     'com.example.videoplayercustom', 'com.rk_itvui.allapp')

    # Apps de /data que traía el firmware y no son malware: se desinstalan sin guardar copia.
    # Cualquier otra app de /data se guarda en el respaldo antes de desinstalarla.
    Conocidas   = @('com.android.chrome', 'com.netflix.mediaclient', 'com.google.android.youtube.tv',
                    'com.android.mgstv', 'com.tvcode.youtv', 'com.hpplay.happyplay.aw', 'com.agileapps.airplay')

    # El preinstall.sh del firmware reinstala /system/preinstall y lanza epark en cada arranque.
    ReemplazarPreinstall = $true

    # Deben cumplirse antes de borrar de /system (WebView y teclado que quedan).
    Comprobar   = @{
        'settings get global webview_provider'      = 'com.android.webview'
        'settings get secure default_input_method' = 'com.android.inputmethod.latin/.LatinIME'
    }

    BorrarSistema = @(
        '/system/app/LZ_Sysplugin', '/system/app/epark', '/system/app/MxqLauncher_new', '/system/app/LauncherCustomization',
        '/system/app/LocalUpdate', '/system/app/RKUpdateService', '/system/app/DyFactoryTest2', '/system/app/Videoplayercustom',
        '/system/app/RkExplorer', '/system/app/AllApp', '/system/app/Gallery2', '/system/app/PicoTts', '/system/app/BasicDreams',
        '/system/app/PrintSpooler', '/system/app/PinyinIME', '/system/app/Katniss', '/system/app/GoogleCalendarSyncAdapter',
        '/system/app/GoogleContactsSyncAdapter', '/system/app/WebViewGoogle',
        '/system/priv-app/TvService', '/system/priv-app/StressTest', '/system/priv-app/DeviceTest',
        '/system/bin/lz_plug', '/system/bin/rksmbd', '/system/bin/rksmbpasswd',
        '/system/media/Aging_Test_Video.mp4', '/system/etc/CtsTest',
        '/system/xbin/fio', '/system/xbin/micro_bench', '/system/xbin/micro_bench_static', '/system/xbin/rawbu', '/system/xbin/puncture_fs')

    # Restos en /data y /sdcard (incluye la cola de instalación del malware).
    BorrarDatos = @('/sdcard/Download/app', '/sdcard/LZUI', '/sdcard/Android/data/com.droidlogic.mboxlauncher',
                    '/sdcard/Android/data/com.lz.sysplugin', '/sdcard/Android/data/.um', '/data/local/pre_apks_installed')

    # Lo que debe quedar en /system/app y /system/priv-app tras limpiar (más Aura). Otra cosa se avisa.
    SistemaEsperado = @(
        'CaptivePortalLogin', 'CertInstaller', 'CtsShimPrebuilt', 'ExtShared', 'GoogleExtShared', 'HTMLViewer', 'KeyChain',
        'LatinIME', 'PacProcessor', 'UserDictionaryProvider', 'WallpaperBackup', 'webview',
        'Aura', 'BackupRestoreConfirmation', 'CalendarProvider', 'ConfigUpdater', 'ContactsProvider', 'CtsShimPrivPrebuilt',
        'DefaultContainerService', 'DocumentsUI', 'DownloadProvider', 'ExtServices', 'ExternalStorageProvider',
        'FusedLocation', 'GmsCore', 'GoogleBackupTransport', 'GoogleExtServices', 'GoogleLoginService',
        'GoogleOneTimeInitializer', 'GooglePartnerSetup', 'GoogleServicesFramework', 'InputDevices', 'MediaProvider',
        'PackageInstaller', 'Phonesky', 'Provision', 'ProxyHandler', 'Settings', 'SettingsProvider', 'SharedStorageBackup',
        'Shell', 'StatementService', 'SystemUI', 'TvSettings', 'VpnDialogs')

    # Valores reales en /system/build.prop (ro.build.fingerprint no se toca).
    BuildProp   = @{
        'ro.build.version.release' = '7.1.2'
        'ro.product.manufacturer'  = 'Rockchip'
        'ro.product.brand'         = 'Rockchip'
        'ro.product.board'         = 'rk322x'
        'ro.product.chipset'       = 'RK3228A'
    }

    Javacore    = $true    # patch_javacore.py
    Ajustes     = $true    # patch_settings.py + fabrica.ps1 -Bind
    ParcheAudio = $true    # patch_audiopolicy.py (fabrica.ps1 -ParcheAudio)
    Overscan    = 100

    Formatear   = 'am broadcast -a android.intent.action.MASTER_CLEAR -n android/com.android.server.MasterClearReceiver --receiver-foreground'
}
