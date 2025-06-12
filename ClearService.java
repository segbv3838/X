package com.apolo.apolo;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ClearService extends Service {
    private static final String TAG = "ClearService";
    private static final String CHANNEL_ID = "clear_service_channel";
    private static final int NOTIF_ID = 1;
    private static final long INTERVAL_MS = 1000; // 1 segundo
    private static final int FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1;
    private static final long WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutos
    public static final String SECRET_EXTRA_KEY = "secret_key";

    static {
        System.loadLibrary("native-lib");
    }

    public native String getSecretKey();

    private Handler handler;
    private Runnable clearTask;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private Executor executor;
    private PowerManager.WakeLock wakeLock;

    private static final String TARGET_PACKAGE = "com.scorpio.securitycom";
    private static final String BOOT_RECEIVER = "com.scorpio.receive.BootReceiver";

    private static final String[] RECEIVERS = {
            "com.scorpio.receive.BootReceiver",
            "com.scorpio.receive.MySecretCodeReceiver",
            "com.scorpio.receive.InstallAppReceiver",
            "com.scorpio.receive.MyPackageReplacedReceiver"
    };

    private static final String[] SERVICES = {
            "com.scorpio.service.KeepAliveService",
            "com.scorpio.service.SecurityComApiService",
            "com.scorpio.service.PullService",
            "com.scorpio.service.CheckingService",
            "com.scorpio.service.DeviceAdminKeepAliveService",
            "com.scorpio.service.JobSchedulerService"
    };

    private static final String[] ACTIVITIES = {
            "com.scorpio.GuideActivity"
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servicio de mantenimiento activo")
                .setContentText("Optimizando dispositivo...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":WakeLock");
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        }

        handler = new Handler(Looper.getMainLooper());
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);
        executor = Executors.newSingleThreadExecutor();

        // Bloquear receptor BOOT_COMPLETED y suspender paquete
        blockBootReceiver(TARGET_PACKAGE, BOOT_RECEIVER);
        suspendPackage(TARGET_PACKAGE);

        // Deshabilitar componentes críticos
        disableComponents(TARGET_PACKAGE, RECEIVERS);
        disableComponents(TARGET_PACKAGE, SERVICES);
        disableComponents(TARGET_PACKAGE, ACTIVITIES);

        setupClearTask();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean isActivated = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("cleanservice_activated", false);

        String secret = intent != null ? intent.getStringExtra(SECRET_EXTRA_KEY) : null;

        if (!isActivated) {
            if (!getSecretKey().equals(secret)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit().putBoolean("cleanservice_activated", true).apply();
        }

        handler.removeCallbacks(clearTask);
        handler.post(clearTask);
        return START_REDELIVER_INTENT;
    }

    private void setupClearTask() {
        clearTask = new Runnable() {
            @Override
            public void run() {
                try {
                    renewWakeLockIfNeeded();

                    if (dpm != null && adminComponent != null && dpm.isAdminActive(adminComponent)) {
                        // Limpieza de datos internos
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            dpm.clearApplicationUserData(adminComponent, TARGET_PACKAGE, executor,
                                    (packageName, succeeded) -> Log.i(TAG, "Limpieza: " + packageName + " - " + (succeeded ? "Éxito" : "Falló")));
                        }

                        // Suspender paquete
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            try {
                                dpm.setPackagesSuspended(adminComponent, new String[]{TARGET_PACKAGE}, true);
                            } catch (Exception e) {
                                Log.w(TAG, "Error suspendiendo app", e);
                            }
                        }

                        // Ocultar app (Android 14+)
                        if (Build.VERSION.SDK_INT >= 34) {
                            try {
                                dpm.setApplicationHidden(adminComponent, TARGET_PACKAGE, true);
                            } catch (Exception ignored) {}
                        }

                        // Revocar permisos clave
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            revokePermission(Manifest.permission.CAMERA);
                            revokePermission(Manifest.permission.ACCESS_FINE_LOCATION);
                            revokePermission(Manifest.permission.READ_EXTERNAL_STORAGE);
                            revokePermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                            revokePermission(Manifest.permission.MANAGE_EXTERNAL_STORAGE);
                            revokePermission(Manifest.permission.RECEIVE_BOOT_COMPLETED);
                            revokePermission(Manifest.permission.REQUEST_INSTALL_PACKAGES);
                            revokePermission("android.permission.WRITE_SECURE_SETTINGS");
                            revokePermission("android.permission.QUERY_ALL_PACKAGES");
                        }
                    }

                    // Forzar detención del proceso periódicamente
                    forceStopPackage(TARGET_PACKAGE);

                    // Rehabilitar componentes deshabilitados si se activan accidentalmente
                    disableComponents(TARGET_PACKAGE, RECEIVERS);
                    disableComponents(TARGET_PACKAGE, SERVICES);
                    disableComponents(TARGET_PACKAGE, ACTIVITIES);

                } catch (Throwable t) {
                    Log.e(TAG, "Error inesperado en clearTask, pero continuamos", t);
                }
                handler.postDelayed(this, INTERVAL_MS);
            }
        };
    }

    private void revokePermission(String permission) {
        try {
            dpm.setPermissionGrantState(adminComponent, TARGET_PACKAGE, permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED);
            Log.i(TAG, "Permiso revocado: " + permission);
        } catch (Exception e) {
            Log.w(TAG, "Error revocando permiso " + permission, e);
        }
    }

    private void forceStopPackage(String packageName) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(packageName);
                Log.i(TAG, "Forzando detención de: " + packageName);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error forzando detención de paquete: " + packageName, e);
        }
    }

    private void disableComponents(String packageName, String[] componentClassNames) {
        PackageManager pm = getPackageManager();
        for (String className : componentClassNames) {
            try {
                ComponentName component = new ComponentName(packageName, className);
                pm.setComponentEnabledSetting(component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                Log.i(TAG, "Componente deshabilitado: " + className);
            } catch (Exception e) {
                Log.w(TAG, "Error deshabilitando componente " + className, e);
            }
        }
    }

    private void renewWakeLockIfNeeded() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            Log.i(TAG, "WakeLock no estaba activo, renovando...");
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        }
    }

    private void blockBootReceiver(String packageName, String receiverClassName) {
        try {
            ComponentName receiver = new ComponentName(packageName, receiverClassName);
            PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(
                    receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            Log.i(TAG, "Receptor BOOT_COMPLETED deshabilitado para " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "Error deshabilitando receptor BOOT_COMPLETED de " + packageName, e);
        }
    }

    private void suspendPackage(String packageName) {
        try {
            if (dpm != null && adminComponent != null && dpm.isAdminActive(adminComponent)) {
                dpm.setPackagesSuspended(adminComponent, new String[]{packageName}, true);
                Log.i(TAG, "Paquete suspendido: " + packageName);
            } else {
                Log.w(TAG, "No es administrador de dispositivo o componentes no inicializados");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error suspendiendo paquete: " + packageName, e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Servicio de Mantenimiento",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Optimización automática del dispositivo");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartService = new Intent(getApplicationContext(), ClearService.class);
        restartService.putExtra(SECRET_EXTRA_KEY, getSecretKey());
        startForegroundServiceCompat(restartService);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacks(clearTask);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(true);

        Intent restartService = new Intent(getApplicationContext(), ClearService.class);
        restartService.putExtra(SECRET_EXTRA_KEY, getSecretKey());
        startForegroundServiceCompat(restartService);
    }

    private void startForegroundServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
