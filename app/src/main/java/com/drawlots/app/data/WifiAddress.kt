package com.drawlots.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * 取本机 Wi-Fi 的 IPv4 地址——二维码里必须写这个地址，否则可能把蜂窝/VPN 地址写进去导致别人连不上。
 *
 * 拿不到就返回 null，由调用方提示「请先连接 Wi-Fi 或开热点」。
 */
object WifiAddress {

    fun ipv4(context: Context): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val linkProperties = manager.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .map(LinkAddress::getAddress)
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }
}
