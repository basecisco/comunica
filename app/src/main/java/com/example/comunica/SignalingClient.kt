package com.example.comunica

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException

data class UserInfo(val id: String, val name: String, val email: String, val photo: String = "")

class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener
) {
    private var socket: Socket? = null

    interface SignalingListener {
        fun onConnectionEstablished(myId: String)
        fun onOfferReceived(fromId: String, description: String, isVideo: Boolean)
        fun onAnswerReceived(from: String, description: String)
        fun onIceCandidateReceived(from: String, candidate: JSONObject)
        fun onUserListUpdated(users: List<UserInfo>)
        fun onCallEnded(from: String)
        fun onChatMessageReceived(from: String, message: String)
        fun onMessageReadReceived(from: String)
    }

    init {
        try {
            socket = IO.socket(serverUrl)
            
            socket?.on(Socket.EVENT_CONNECT) {
                listener.onConnectionEstablished(socket?.id() ?: "")
            }

            socket?.on("offer") { args ->
                val data = args[0] as JSONObject
                val from = data.optString("from")
                val isVideo = data.optBoolean("isVideo", false)
                android.util.Log.d("ComunicaDebug", "Socket: Recebeu OFFER de $from (video: $isVideo)")
                listener.onOfferReceived(from, data.optString("sdp"), isVideo)
            }

            socket?.on("answer") { args ->
                val data = args[0] as JSONObject
                val from = data.optString("from")
                android.util.Log.d("ComunicaDebug", "Socket: Recebeu ANSWER de $from")
                listener.onAnswerReceived(from, data.optString("sdp"))
            }

            socket?.on("candidate") { args ->
                val data = args[0] as JSONObject
                val from = data.optString("from")
                data.optJSONObject("candidate")?.let {
                    listener.onIceCandidateReceived(from, it)
                }
            }

            socket?.on("update-user-list") { args ->
                val usersJson = args[0] as JSONArray
                val users = mutableListOf<UserInfo>()
                for (i in 0 until usersJson.length()) {
                    val userElement = usersJson.opt(i)
                    if (userElement is JSONObject) {
                        val userId = userElement.optString("id")
                        if (userId.isNotEmpty() && userId != socket?.id()) {
                            users.add(UserInfo(
                                id = userId,
                                name = userElement.optString("name", "Unknown"),
                                email = userElement.optString("email", ""),
                                photo = userElement.optString("photo", "")
                            ))
                        }
                    } else if (userElement is String) {
                        if (userElement != socket?.id()) {
                            users.add(UserInfo(userElement, "Usuário " + userElement.take(4), "", ""))
                        }
                    }
                }
                listener.onUserListUpdated(users)
            }

            socket?.on("end-call") { args ->
                val data = args[0] as JSONObject
                listener.onCallEnded(data.optString("from"))
            }

            socket?.on("chat-message") { args ->
                val data = args[0] as JSONObject
                val from = data.optString("from")
                val message = data.optString("message")
                listener.onChatMessageReceived(from, message)
            }

            socket?.on("message-read") { args ->
                val data = args[0] as JSONObject
                val from = data.optString("from")
                listener.onMessageReadReceived(from)
            }

            socket?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    fun joinRoom(roomId: String, name: String, email: String, photo: String = "") {
        val data = JSONObject().apply {
            put("room", roomId)
            put("name", name)
            put("email", email)
            put("photo", photo)
        }
        socket?.emit("join", data)
    }

    fun sendOffer(targetId: String, sdp: String, isVideo: Boolean) {
        val data = JSONObject().apply {
            put("target", targetId)
            put("from", socket?.id())
            put("sdp", sdp)
            put("isVideo", isVideo)
        }
        socket?.emit("offer", data)
    }

    fun sendAnswer(targetId: String, sdp: String) {
        val data = JSONObject().apply {
            put("target", targetId)
            put("from", socket?.id())
            put("sdp", sdp)
        }
        socket?.emit("answer", data)
    }

    fun sendIceCandidate(targetId: String, candidate: JSONObject) {
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

    fun sendChatMessage(targetId: String, message: String) {
        val data = JSONObject().apply {
            put("target", targetId)
            put("from", socket?.id())
            put("message", message)
        }
        socket?.emit("chat-message", data)
    }

    fun sendMessageRead(targetId: String) {
        val data = JSONObject().apply {
            put("target", targetId)
            put("from", socket?.id())
        }
        socket?.emit("message-read", data)
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}
