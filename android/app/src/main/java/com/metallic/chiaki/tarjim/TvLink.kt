package com.metallic.chiaki.tarjim

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Client for the Tarjim TV overlay app (wire protocol v1, TCP port 47800): the same
 * protocol the laptop program speaks. A frame is two big-endian 32-bit lengths, a
 * JSON header and a binary payload (PNG images).
 *
 * The TV clears everything by itself when it hears nothing for a second, so a ping
 * thread keeps the link alive and a dead link can never leave old text on screen.
 */
class TvLink(private val host: String, private val port: Int = 47800)
{
	class Layer(val id: String, val png: ByteArray, val x: Int, val y: Int, val w: Int, val h: Int, val z: Int = 0, val ttlMs: Int = 700)

	var width = 1920; private set
	var height = 1080; private set
	var window = "A"; private set

	private var socket: Socket? = null
	private var out: OutputStream? = null
	private var seq = 0
	private val lock = Any()
	@Volatile private var closed = false
	private var pinger: Thread? = null

	val connected get() = socket?.isConnected == true && socket?.isClosed == false

	@Throws(IOException::class)
	fun connect()
	{
		val s = Socket()
		s.tcpNoDelay = true
		s.connect(InetSocketAddress(host, port), 3000)
		s.soTimeout = 3000
		val output = s.getOutputStream()
		output.write(frame(JSONObject().put("type", "hello").put("v", 1), null))
		output.flush()
		val (ack, _) = read(DataInputStream(s.getInputStream()))
		if(ack.optString("type") != "hello_ack")
			throw IOException("the TV answered ${ack.optString("type")} instead of hello_ack")
		width = ack.optInt("w", 1920)
		height = ack.optInt("h", 1080)
		val windows = ack.optJSONArray("windows") ?: JSONArray()
		val names = (0 until windows.length()).map { windows.getString(it) }
		if(names.isEmpty())
			throw IOException("the TV app has no overlay window (setup incomplete)")
		window = if("A" in names) "A" else names[0]
		s.soTimeout = 0
		synchronized(lock) { socket = s; out = output }
		closed = false
		// Drain what the TV sends (acks, pongs); we only need to notice when it hangs up.
		Thread({
			try
			{
				val input = DataInputStream(s.getInputStream())
				while(!closed) read(input)
			}
			catch(e: IOException) { drop() }
		}, "tarjim-tv-read").apply { isDaemon = true; start() }
		pinger = Thread({
			while(!closed && connected)
			{
				send(JSONObject().put("type", "ping").put("ts", System.currentTimeMillis().toDouble()), null)
				try { Thread.sleep(300) } catch(e: InterruptedException) { return@Thread }
			}
		}, "tarjim-tv-ping").apply { isDaemon = true; start() }
	}

	fun show(layers: List<Layer>): Boolean
	{
		val entries = JSONArray()
		var offset = 0
		for(l in layers)
		{
			entries.put(JSONObject().put("id", l.id).put("z", l.z).put("x", l.x).put("y", l.y).put("w", l.w).put("h", l.h)
				.put("ttl_ms", l.ttlMs).put("fade_ms", 0).put("offset", offset).put("len", l.png.size))
			offset += l.png.size
		}
		val payload = ByteArray(offset)
		var at = 0
		for(l in layers) { System.arraycopy(l.png, 0, payload, at, l.png.size); at += l.png.size }
		return send(JSONObject().put("type", "show").put("seq", ++seq).put("win", window).put("layers", entries), payload)
	}

	fun keep(ids: List<String>) = send(JSONObject().put("type", "keep").put("ids", JSONArray(ids)), null)

	fun clear() = send(JSONObject().put("type", "clear").put("fade_ms", 0).put("all", true), null)

	fun close()
	{
		closed = true
		try { clear() } catch(e: Exception) { }
		pinger?.interrupt()
		drop()
	}

	private fun drop()
	{
		synchronized(lock)
		{
			try { socket?.close() } catch(e: IOException) { }
			socket = null
			out = null
		}
	}

	private fun send(header: JSONObject, payload: ByteArray?): Boolean
	{
		synchronized(lock)
		{
			val o = out ?: return false
			return try
			{
				o.write(frame(header, payload))
				o.flush()
				true
			}
			catch(e: IOException)
			{
				drop()
				false
			}
		}
	}

	private fun frame(header: JSONObject, payload: ByteArray?): ByteArray
	{
		val body = header.toString().toByteArray(Charsets.UTF_8)
		val bin = payload ?: ByteArray(0)
		return ByteBuffer.allocate(8 + body.size + bin.size).putInt(body.size).putInt(bin.size).put(body).put(bin).array()
	}

	private fun read(input: DataInputStream): Pair<JSONObject, ByteArray>
	{
		val jsonLen = input.readInt()
		val binLen = input.readInt()
		if(jsonLen <= 0 || jsonLen > 65536 || binLen < 0 || binLen > 8 * 1024 * 1024)
			throw IOException("bad frame from the TV")
		val body = ByteArray(jsonLen)
		input.readFully(body)
		val bin = ByteArray(binLen)
		input.readFully(bin)
		return Pair(JSONObject(String(body, Charsets.UTF_8)), bin)
	}
}
