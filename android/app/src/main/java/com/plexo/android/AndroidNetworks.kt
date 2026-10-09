package com.plexo.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

data class AndroidNetwork(
    val id: String,
    val label: String,
    val kind: String,
    val network: Network
)

class AndroidNetworks(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun list(): List<AndroidNetwork> = manager.allNetworks.mapNotNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) return@mapNotNull null

        val kind = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> return@mapNotNull null
        }
        val interfaceName = manager.getLinkProperties(network)?.interfaceName
        AndroidNetwork(
            id = network.networkHandle.toString(),
            label = if (interfaceName.isNullOrBlank()) kind else "$kind · $interfaceName",
            kind = kind,
            network = network
        )
    }.distinctBy { it.id }
}