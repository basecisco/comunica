package com.example.comunica

import android.content.Context
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val observer: PeerConnection.Observer
) {
    val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var onMessageReceived: ((String) -> Unit)? = null

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // Configura o áudio do sistema para modo comunicação
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun setOnMessageReceivedListener(listener: (String) -> Unit) {
        onMessageReceived = listener
    }

    fun initPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
        startLocalAudio()
    }

    private fun startLocalAudio() {
        android.util.Log.d("ComunicaDebug", "WebRTC: Criando fonte de áudio local...")
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK_ID", audioSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))
        android.util.Log.d("ComunicaDebug", "WebRTC: Track de áudio local adicionada ao PeerConnection.")
    }

    fun createDataChannel() {
        val dcInit = DataChannel.Init()
        dataChannel = peerConnection?.createDataChannel("chat", dcInit)
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                android.util.Log.d("ComunicaDebug", "DataChannel State: ${dataChannel?.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                val message = String(bytes)
                android.util.Log.d("ComunicaDebug", "DataChannel: Mensagem recebida: $message")
                onMessageReceived?.invoke(message)
            }
        })
    }

    fun onRemoteDataChannel(remoteDataChannel: DataChannel) {
        android.util.Log.d("ComunicaDebug", "DataChannel: Recebeu canal remoto")
        dataChannel = remoteDataChannel
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                android.util.Log.d("ComunicaDebug", "Remote DataChannel State: ${dataChannel?.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                val message = String(bytes)
                android.util.Log.d("ComunicaDebug", "DataChannel: Mensagem recebida (remoto): $message")
                onMessageReceived?.invoke(message)
            }
        })
    }

    fun sendMessage(message: String) {
        if (dataChannel?.state() == DataChannel.State.OPEN) {
            val buffer = DataChannel.Buffer(
                java.nio.ByteBuffer.wrap(message.toByteArray()),
                false
            )
            dataChannel?.send(buffer)
            android.util.Log.d("ComunicaDebug", "DataChannel: Mensagem enviada: $message")
        } else {
            android.util.Log.e("ComunicaDebug", "DataChannel: Falha ao enviar, estado: ${dataChannel?.state()}")
        }
    }

    fun isDataChannelOpen(): Boolean {
        return dataChannel?.state() == DataChannel.State.OPEN
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        createDataChannel()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { callback(sdp) }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, sdp)
    }

    fun createAnswer(callback: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { callback(sdp) }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun closeConnection() {
        try {
            dataChannel?.dispose()
            dataChannel = null
            
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            
            localAudioTrack?.dispose()
            localAudioTrack = null
            
            // peerConnectionFactory.dispose() // Geralmente mantida enquanto o app vive, mas se for recriar o client, melhor descartar
            // rootEglBase.release()
            
            android.util.Log.d("ComunicaDebug", "WebRTC: Conexão encerrada e recursos liberados.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun dispose() {
        closeConnection()
        try {
            peerConnectionFactory.dispose()
            rootEglBase.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
