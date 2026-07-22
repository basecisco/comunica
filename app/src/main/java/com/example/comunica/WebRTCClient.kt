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
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var dataChannel: DataChannel? = null
    private var onMessageReceived: ((String) -> Unit)? = null

    private var localRenderer: VideoSink? = null
    private var remoteRenderer: VideoSink? = null

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // Configura o áudio do sistema para modo comunicação
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

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

    fun startLocalVideo() {
        android.util.Log.d("ComunicaDebug", "WebRTCClient: startLocalVideo chamado")
        if (localVideoTrack != null) {
            android.util.Log.d("ComunicaDebug", "WebRTCClient: localVideoTrack já existe")
            return
        }

        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        android.util.Log.d("ComunicaDebug", "WebRTCClient: Câmeras encontradas: ${deviceNames.joinToString()}")
        // Tenta encontrar a câmera frontal
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                android.util.Log.d("ComunicaDebug", "WebRTCClient: Usando câmera frontal: $deviceName")
                videoCapturer = enumerator.createCapturer(deviceName, null)
                break
            }
        }

        if (videoCapturer == null && deviceNames.isNotEmpty()) {
            android.util.Log.d("ComunicaDebug", "WebRTCClient: Câmera frontal não encontrada, usando: ${deviceNames[0]}")
            videoCapturer = enumerator.createCapturer(deviceNames[0], null)
        }

        if (videoCapturer == null) {
            android.util.Log.e("ComunicaDebug", "WebRTCClient: Falha ao criar videoCapturer")
            return
        }

        videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
        videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(640, 480, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK_ID", videoSource)
        localVideoTrack?.setEnabled(true)
        android.util.Log.d("ComunicaDebug", "WebRTCClient: localVideoTrack criado. Renderer configurado? ${localRenderer != null}")
        
        localRenderer?.let { 
            android.util.Log.d("ComunicaDebug", "WebRTCClient: Adicionando sink ao localRenderer")
            if (it is SurfaceViewRenderer) it.setMirror(true)
            localVideoTrack?.addSink(it) 
        }
        
        peerConnection?.addTrack(localVideoTrack, listOf("ARDAMS"))
        android.util.Log.d("ComunicaDebug", "WebRTCClient: localVideoTrack adicionado ao PeerConnection")
    }

    fun setupLocalRenderer(videoSink: VideoSink) {
        android.util.Log.d("ComunicaDebug", "WebRTCClient: setupLocalRenderer chamado")
        localRenderer = videoSink
        if (localRenderer is SurfaceViewRenderer) {
            (localRenderer as SurfaceViewRenderer).setMirror(true)
            (localRenderer as SurfaceViewRenderer).setZOrderMediaOverlay(true)
        }
        localVideoTrack?.let {
            android.util.Log.d("ComunicaDebug", "WebRTCClient: Vinculando localVideoTrack ao renderer fornecido")
            it.addSink(localRenderer)
        }
    }

    fun setRemoteVideoTrack(track: VideoTrack) {
        android.util.Log.d("ComunicaDebug", "WebRTCClient: setRemoteVideoTrack chamado")
        remoteVideoTrack = track
        remoteRenderer?.let {
            android.util.Log.d("ComunicaDebug", "WebRTCClient: Vinculando remoteVideoTrack ao renderer fornecido")
            remoteVideoTrack?.addSink(it)
        }
    }

    fun setupRemoteRenderer(videoSink: VideoSink) {
        android.util.Log.d("ComunicaDebug", "WebRTCClient: setupRemoteRenderer chamado")
        remoteRenderer = videoSink
        remoteVideoTrack?.let {
            android.util.Log.d("ComunicaDebug", "WebRTCClient: Vinculando remoteVideoTrack ao renderer fornecido")
            it.addSink(remoteRenderer)
        }
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
        android.util.Log.d("ComunicaDebug", "WebRTCClient: setRemoteDescription tipo ${sdp.type}")
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                android.util.Log.d("ComunicaDebug", "WebRTCClient: setRemoteDescription SUCESSO")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                android.util.Log.e("ComunicaDebug", "WebRTCClient: setRemoteDescription FALHA: $error")
            }
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

    fun setSpeakerphoneOn(on: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.isSpeakerphoneOn = on
    }

    fun closeConnection() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            videoSource?.dispose()
            videoSource = null
            
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localVideoTrack?.dispose()
            localVideoTrack = null

            remoteVideoTrack = null

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
