package com.metallic.chiaki.tarjim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.ConnectInfo
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps the translation running while another app is open. One engine at a time.
 * The interface (served by [PhoneServer]) reads the state from the companion object.
 */
class TarjimService : Service()
{
	private var wakeLock: PowerManager.WakeLock? = null
	private var wifiLock: WifiManager.WifiLock? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		if(intent?.action == ACTION_STOP)
		{
			stopSelf()
			return START_NOT_STICKY
		}
		@Suppress("DEPRECATION")
		val info = intent?.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		val tv = intent?.getStringExtra(EXTRA_TV)
		val game = intent?.getStringExtra(EXTRA_PROFILE) ?: "default"
		if(info == null || tv.isNullOrBlank())
		{
			stopSelf()
			return START_NOT_STICKY
		}
		val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		if(Build.VERSION.SDK_INT >= 26)
			manager.createNotificationChannel(NotificationChannel(CHANNEL, "ترجِم", NotificationManager.IMPORTANCE_LOW))
		val open = PendingIntent.getActivity(this, 0, Intent(this, TarjimActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
		val stop = PendingIntent.getService(this, 1, Intent(this, TarjimService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
		val builder = if(Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
		val notification = builder.setSmallIcon(R.drawable.ic_tarjim_stat).setContentTitle("ترجِم شغّال")
			.setContentText("يترجم لعبتك على التلفزيون").setContentIntent(open).setOngoing(true)
			.addAction(Notification.Action.Builder(null, "إيقاف", stop).build()).build()
		if(Build.VERSION.SDK_INT >= 29)
			startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
		else
			startForeground(1, notification)

		if(engine == null)
		{
			val power = getSystemService(Context.POWER_SERVICE) as PowerManager
			wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tarjim:engine").apply { acquire(8 * 60 * 60 * 1000L) }
			val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
			@Suppress("DEPRECATION")
			wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tarjim:wifi").apply { acquire() }
			val e = TarjimEngine(applicationContext, info, tv, Store(applicationContext), game)
			e.onStatus = { s -> status = s; for(l in listeners) l("status") }
			e.onLine = { line ->
				synchronized(lines) { lines.add(line); while(lines.size > 60) lines.removeAt(0) }
				lastLine = line
				for(l in listeners) l("line")
			}
			synchronized(lines) { lines.clear() }
			status = TarjimEngine.Status()
			profile = game
			startedAt = 0L
			engine = e
			running = true
			e.start()
			for(l in listeners) l("status")
		}
		return START_NOT_STICKY
	}

	override fun onDestroy()
	{
		engine?.onStatus = null
		engine?.onLine = null
		engine?.stop()
		engine = null
		running = false
		startedAt = 0L
		try { wakeLock?.release() } catch(e: Exception) { }
		try { wifiLock?.release() } catch(e: Exception) { }
		for(l in listeners) l("status")
		super.onDestroy()
	}

	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		const val EXTRA_TV = "tv"
		const val EXTRA_PROFILE = "profile"
		const val ACTION_STOP = "dev.tarjim.mobile.STOP"
		private const val CHANNEL = "tarjim"

		@Volatile var engine: TarjimEngine? = null
		@Volatile var running = false
		@Volatile var status = TarjimEngine.Status()
		@Volatile var profile = "default"
		@Volatile var startedAt = 0L  // wall-clock seconds when the picture first arrived
		@Volatile var lastLine: TarjimEngine.Line? = null
		val lines = ArrayList<TarjimEngine.Line>()
		/** Called (from any thread) with "status" or "line" when something changed. */
		val listeners = CopyOnWriteArrayList<(String) -> Unit>()

		fun start(context: Context, info: ConnectInfo, tv: String, profile: String)
		{
			val intent = Intent(context, TarjimService::class.java).putExtra(EXTRA_CONNECT_INFO, info).putExtra(EXTRA_TV, tv).putExtra(EXTRA_PROFILE, profile)
			if(Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
		}

		fun stop(context: Context) = context.stopService(Intent(context, TarjimService::class.java))
	}
}
