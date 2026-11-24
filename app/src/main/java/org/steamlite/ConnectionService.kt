package org.steamlite

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

class LiteApplication : Application() {
    val model by lazy { LiteModel(this) }
}

/** Owns the ongoing messaging notification while the shared connection is active. */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("connection", "后台消息连接", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun notification(text: String) = Notification.Builder(this, "connection")
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Steam Lite")
            .setContentText(text).setContentIntent(open).setOngoing(true).build()
        startForeground(42, notification("保持 Steam 消息连接"))
        val steam = (application as LiteApplication).model.steam
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { steam.networkChanged(true) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) steam.networkChanged(true)
            }
            override fun onLost(network: Network) { steam.networkChanged(connectivity.activeNetwork != null) }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback); networkCallback = callback }
        scope.launch { while (isActive) { delay(30_000); steam.resumeSession() } }
        scope.launch { combine(steam.status, steam.online, steam.busy, steam.hasSession) { status, online, busy, saved ->
            ConnectionNotice(status, online, busy, saved)
        }.collectLatest { state ->
            if (!state.online && !state.saved && !state.busy) stopSelf()
            else manager.notify(42, notification(if (state.online) "Steam 已连接 · 后台接收消息" else state.status))
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        networkCallback?.let { callback -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) } }
        scope.cancel(); super.onDestroy()
    }
}

private data class ConnectionNotice(val status: String, val online: Boolean, val busy: Boolean, val saved: Boolean)
