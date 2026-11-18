package com.metallic.chiaki.tarjim

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the PS5 and the TV again when the network changed under them.
 *
 * The phone is often the Wi-Fi hotspot of the PS5 and the TV, and Android's hotspot moves to a
 * new network now and then (192.168.172.x one day, 192.168.136.x the next) while each device
 * keeps its last number. A saved address that is not on any of the phone's current networks is
 * looked for there: the same last number first, then (for a home-sized network) every address.
 */
object NetFix
{
	private const val TAG = "Tarjim"

	private fun ipv4(s: String): Int?
	{
		val parts = s.trim().split('.')
		if(parts.size != 4) return null
		var v = 0
		for(x in parts) { val n = x.toIntOrNull() ?: return null; if(n !in 0..255) return null; v = (v shl 8) or n }
		return v
	}

	private fun text(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

	private fun mask(prefix: Int) = if(prefix <= 0) 0 else (-1 shl (32 - prefix))

	/** A home network (10.x, 172.16-31.x, 192.168.x), not the operator's. */
	private fun homeNetwork(v: Int): Boolean
	{
		val a = v ushr 24; val b = (v ushr 16) and 255
		return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
	}

	/** The phone's own IPv4 address and prefix on each home network it is on now. */
	private fun networks(): List<Pair<Int, Int>>
	{
		val out = ArrayList<Pair<Int, Int>>()
		try
		{
			for(ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty())
			{
				if(!ni.isUp || ni.isLoopback) continue
				for(ia in ni.interfaceAddresses)
				{
					val a = ia.address as? Inet4Address ?: continue
					val v = ipv4(a.hostAddress ?: continue) ?: continue
					val p = ia.networkPrefixLength.toInt()
					if(homeNetwork(v) && p in 16..30) out.add(Pair(v, p))
				}
			}
		}
		catch(e: Exception) { Log.w(TAG, "could not list the networks", e) }
		return out
	}

	/** True when the address is on one of the phone's current networks (or there is none to compare with). */
	fun onCurrentNetwork(saved: String): Boolean
	{
		val v = ipv4(saved) ?: return true
		val nets = networks()
		return nets.isEmpty() || nets.any { (own, p) -> (own and mask(p)) == (v and mask(p)) }
	}

	/**
	 * The address to use now: `saved` when it is on a current network; otherwise the same last
	 * number on the current network, or any address there, where `answers` says yes; otherwise
	 * `saved` (nothing better found).
	 */
	fun locate(saved: String, answers: (String) -> Boolean): String
	{
		val v = ipv4(saved) ?: return saved
		if(onCurrentNetwork(saved)) return saved
		val nets = networks()
		for((own, p) in nets)
		{
			val m = mask(p)
			val same = (own and m) or (v and m.inv())
			if(same != own && answers(text(same))) { Log.i(TAG, "moved $saved -> ${text(same)}"); return text(same) }
		}
		for((own, p) in nets)
		{
			if(p < 22) continue  // larger than a home network: too many addresses to try
			val m = mask(p)
			val base = own and m
			val pool = Executors.newFixedThreadPool(32)
			try
			{
				val tries = (1 until m.inv()).map { base + it }.filter { it != own }
					.map { a -> pool.submit<String?> { if(answers(text(a))) text(a) else null } }
				for(t in tries)
				{
					val found = try { t.get(4, TimeUnit.SECONDS) } catch(e: Exception) { null }
					if(found != null) { Log.i(TAG, "found $saved at $found"); return found }
				}
			}
			finally { pool.shutdownNow() }
		}
		return saved
	}

	/** Something listens on this TCP port there (the TV app: 47800). */
	fun listens(port: Int, timeoutMs: Int = 600): (String) -> Boolean = { host ->
		try { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }; true }
		catch(e: Exception) { false }
	}

	/** A PS5 answers Sony's discovery there (switched on, or in rest mode). */
	fun ps5(timeoutMs: Int = 700): (String) -> Boolean = { host ->
		try
		{
			DatagramSocket().use { s ->
				s.soTimeout = timeoutMs
				val msg = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n".toByteArray()
				s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), 9302))
				val buf = ByteArray(2048)
				val reply = DatagramPacket(buf, buf.size)
				s.receive(reply)
				val r = String(buf, 0, reply.length)
				r.startsWith("HTTP/1.1 200") || r.startsWith("HTTP/1.1 620")
			}
		}
		catch(e: Exception) { false }
	}
}
