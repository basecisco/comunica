package com.example.comunica

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener
) {
    private var socket: Socket? = null

    interface SignalingListener {
        fun onConnectionEstablished()
        fun onOfferReceived(description: String)
        fun onAnswerReceived(description: String)
        fun onIceCandidateReceived(candidate: JSONObject)
        fun onCallEnded()
    }

    init {
        try {
            socket = IO.socket(serverUrl)
            
            socket?.on(Socket.EVENT_CONNECT) {
                listener.onConnectionEstablished()
            }

            socket?.on("offer") { args ->
                val data = args[0] as JSONObject
                listener.onOfferReceived(data.getString("sdp"))
            }

            socket?.on("answer") { args ->
                val data = args[0] as JSONObject
                listener.onAnswerReceived(data.getString("sdp"))
            }

            socket?.on("candidate") { args ->
                val data = args[0] as JSONObject
                listener.onIceCandidateReceived(data.getJSONObject("candidate"))
            }

            socket?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    fun joinRoom(roomId: String) {
        socket?.emit("join", roomId)
    }

    fun sendOffer(roomId: String, sdp: String) {
        val data = JSONObject().apply {
            put("room", roomId)
            put("sdp", sdp)
        }
        socket?.emit("offer", data)
    }

    fun sendAnswer(roomId: String, sdp: String) {
        val data = JSONObject().apply {
            put("room", roomId)
            put("sdp", sdp)
        }
        socket?.emit("answer", data)
    }

    fun sendIceCandidate(roomId: String, candidate: JSONObject) {
        val data = JSONObject().apply {
            put("room", roomId)
            put("candidate", candidate)
        }
        socket?.emit("candidate", data)
    }
}
