package com.radin.unrot

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "This removes Unrot's uninstall protection. Your scroll limits stop working once Unrot is gone."
}

/** One place that knows which permissions are granted and how to fix the missing ones. */
object Setup {
    fun adminComponent(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun accessibilityOn(ctx: Context): Boolean {
        val enabled = AndroidSettings.Secure.getString(
            ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(ctx, UnrotService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == me
        }
    }

    fun overlayOn(ctx: Context) = AndroidSettings.canDrawOverlays(ctx)

    fun notificationsOn(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun batteryOn(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun callsOn(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun listenerComponent(ctx: Context) = ComponentName(ctx, CallListener::class.java)

    fun notificationAccessOn(ctx: Context) = runCatching {
        ctx.getSystemService(android.app.NotificationManager::class.java)
            .isNotificationListenerAccessGranted(listenerComponent(ctx))
    }.getOrDefault(false)

    fun notificationAccessIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent(ctx).flattenToString())
        } else {
            Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    fun adminOn(ctx: Context) =
        ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent(ctx))

    /** The guard only kicks in after setup is truly done, so you can't lock yourself out mid-setup. */
    fun protectionArmed(ctx: Context): Boolean {
        val s = Store.get(ctx)
        return s.onboarded && adminOn(ctx)
    }

    // ---- Intents to the right settings screens ----
    fun accessibilityIntent() = Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)

    fun appInfoIntent(ctx: Context) = Intent(
        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName),
    )

    fun overlayIntent(ctx: Context) = Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName),
    )

    @android.annotation.SuppressLint("BatteryLife")
    fun batteryIntent(ctx: Context) = Intent(
        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName),
    )

    fun adminIntent(ctx: Context) = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(ctx))
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Stops Unrot from being uninstalled on a weak moment. Your family PIN is needed to turn this off.",
        )

    fun healthConnectSettings() = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
}
