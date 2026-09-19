package com.drawlots.app.ui.room

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 蓝牙联机的权限与可用性判定（唯一 owner）。
 *
 * 只在 Android 12+（API 31）提供：更低版本 BLE 扫描要额外申请定位权限，与「单机零权限」冲突太大，
 * 老设备走局域网即可。
 */
object BluetoothPermissions {

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun unsupportedHint(): String = "蓝牙房间需要 Android 12 及以上，或改用局域网"

    @SuppressLint("InlinedApi")
    fun forHosting(): List<String> = if (isSupported()) {
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }

    @SuppressLint("InlinedApi")
    fun forJoining(): List<String> = if (isSupported()) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }

    fun missing(context: Context, permissions: List<String>): List<String> = permissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}
