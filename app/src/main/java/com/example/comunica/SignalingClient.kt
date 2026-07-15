package com.example.comunica

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException

class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener
) {
    private var socket: Socket? = null

    interface SignalingListener {
        fun onConnectionEstablished(myId: String)
        fun onOfferReceived(from: String, description: String)
        fun onAnswerReceived(from: String, description: String)
        fun onIceCandidateReceived(from: String, candidate: JSONObject)
        fun onUserListUpdated(users: List<String>)
        fun onCallEnded(from: String)
    }

    init {
        try {
            socket = IO.socket(serverUrl)
            
            socket?.on(Socket.EVENT_CONNECT) {
                listener.onConnectionEstablished(socket?.id() ?: "")
            }

            socket?.on("offer") { args ->
                val data = args[0] as JSONObject
                val from = data.getString("from")
                android.util.Log.d("ComunicaDebug", "Socket: Recebeu OFFER de $from")
                listener.onOfferReceived(from, data.getString("sdp"))
            }

            socket?.on("answer") { args ->
                val data = args[0] as JSONObject
                val from = data.getString("from")
                android.util.Log.d("ComunicaDebug", "Socket: Recebeu ANSWER de $from")
                listener.onAnswerReceived(from, data.getString("sdp"))
            }

            socket?.on("candidate") { args ->
                val data = args[0] as JSONObject
                val from = data.getString("from")
                android.util.Log.d("ComunicaDebug", "Socket: Recebeu CANDIDATE de $from")
                listener.onIceCandidateReceived(from, data.getJSONObject("candidate"))
            }

            socket?.on("update-user-list") { args ->
                val usersJson = args[0] as JSONArray
                val users = mutableListOf<String>()
                for (i in 0 until usersJson.length()) {
                    val userId = usersJson.getString(i)
                    if (userId != socket?.id()) {
                        users.add(userId)
                    }
                }
                listener.onUserListUpdated(users)
            }

            socket?.on("end-call") { args ->
                val data = args[0] as JSONObject
                listener.onCallEnded(data.getString("from"))
            }

            socket?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    fun joinRoom(roomId: String) {
        socket?.emit("join", roomId)
    }

    fun sendOffer(targetId: String, sdp: String) {
        android.util.Log.d("ComunicaDebug", "Socket: Enviando OFFER para $targetId")
        val data = JSONObject().apply {
            put("target", targetId)
            put("from", socket?.id())
            put("sdp", sdp)
        }
        socket?.emit("offer", data)
    }

    fun sendAnswer(targetId: String, sdp: String) {
        android.util.Log.d("ComunicaDebug", "Socket: Enviando ANSWER para $targetId")
        val data = JSONObject().apply {
            put("target", targetId)
            put("from", socket?.id())
            put("sdp", sdp)
        }
        socket?.emit("answer", data)
    }

    fun sendIceCandidate(targetId: String, candidate: JSONObject) {
        // Log omitido por ser muito frequente, mas o envio continua
        val data = JSONObject().apply {
            put("target", targetId)
            put("from", socket?.id())
            put("candidate", candidate)
        }
        socket?.emit("candidate", data)
    }

    fun endCall(targetId: String) {
        val data = JSONObject().apply {
            put("target", targetId)
            put("from", socket?.id())
        }
        socket?.emit("end-call", data)
    }
}
