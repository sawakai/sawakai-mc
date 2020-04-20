package jp.sawa_kai.sawakai.spigot.skyway

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import jp.sawa_kai.sawakai.spigot.skyway.types.GetSignalingResponse
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.bukkit.Bukkit
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom


class SkyWayClient(private val apiKey: String, private val roomId: String) {

    private val okHttpClient = {
        val loggingInterceptor = HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                Bukkit.getLogger().info("okhttp: $message")
            }
        }).apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        }

        OkHttpClient.Builder()
            .cookieJar(CookieJar())
            .addInterceptor {
                val newRequest = it.request().newBuilder()
                    .addHeader("Origin", "https://minecraft-voice-chat-test.web.app")
                    .build()

                val resp = it.proceed(newRequest)
                resp
            }
            .addNetworkInterceptor(loggingInterceptor)
            .build()
    }()

    private val signalingServerDomain by lazy {
        val req = Request.Builder()
            .url("https://dispatcher.webrtc.ecl.ntt.com/signaling")
            .build()
        okHttpClient.newCall(req).execute().use {
            if (!it.isSuccessful) {
                throw IOException("Failed to get /signaling $it")
            }

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val jsonAdapter = moshi.adapter(GetSignalingResponse::class.java)
            val json = it.body?.string() ?: throw IOException("Body is null $it")
            jsonAdapter.fromJson(json)?.domain ?: throw IOException("Failed to get /signaling $it")
        }
    }

    private val signalingServerUrl by lazy {
        "https://${signalingServerDomain}"
    }

    private val onConnectedHandler: (() -> Unit)? = null
    private val onUserJoinedRoomHandler: (() -> Unit)? = null
    private val onUserLeftRoomHandler: (() -> Unit)? = null

    fun connect() {
        Bukkit.getLogger().info("Signaling server is $signalingServerUrl")

        val token = SecureRandom.getInstanceStrong()
            .nextLong()
            .toString(36)
            .substring(2)

        IO.setDefaultOkHttpCallFactory(okHttpClient)
        IO.setDefaultOkHttpWebSocketFactory(okHttpClient)
        val socket = IO.socket(signalingServerUrl, IO.Options().apply {
            query = "apiKey=${apiKey}&token=${token}"
            timestampRequests = true
            secure = true
            forceNew = true
        })
        socket.on(Socket.EVENT_CONNECT, Emitter.Listener {
            Bukkit.getLogger().info("connected!")
            val obj = JSONObject().apply {
                put("roomName", roomId)
                put("roomType", "sfu")
            }
            Bukkit.getLogger().info("RoomId ${roomId}")
            socket.emit("ROOM_JOIN", obj)
        }).on(Socket.EVENT_ERROR, Emitter.Listener {
            Bukkit.getLogger().info("error")
            Bukkit.getLogger().info(it.map { it.toString() }.toString())
        }).on(Socket.EVENT_DISCONNECT) {
            Bukkit.getLogger().info("Socket was disconnected")
        }.on(Socket.EVENT_MESSAGE) {
            Bukkit.getLogger().info("message: ${it}")
        }.on(Socket.EVENT_PING) {
            Bukkit.getLogger().info("ping")
        }.on(Socket.EVENT_PONG) {
            Bukkit.getLogger().info("pong")
        }.on(Socket.EVENT_RECONNECTING) {
            Bukkit.getLogger().info("reconnecting")
        }.on(Socket.EVENT_CONNECTING) {
            Bukkit.getLogger().info("connecting")
        }.on("ROOM_USER_JOIN") {
            Bukkit.getLogger().info("ROOM_USER_JOIN, ${it[0]}")
            //socket.emit("SFU_GET_OFFER", "{\"roomName\":\"${roomId}\"}")
            val obj = JSONObject().apply {
                put("roomName", roomId)
                put("data", "from minecraft")
            }
            socket.emit("ROOM_SEND_DATA", obj)
        }
        socket.connect()

        GlobalScope.launch {
            while (true) {
                socket.emit("PING")
                delay(25_000)
            }
        }
    }

    fun onConnected(handler: () -> Unit): SkyWayClient {
        return this
    }
}
