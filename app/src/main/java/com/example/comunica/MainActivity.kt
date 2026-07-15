package com.example.comunica

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.comunica.ui.theme.ComunicaTheme
import org.json.JSONObject
import org.webrtc.*

data class ChatMessage(val sender: String, val text: String, val isTranscription: Boolean = false)

class MainActivity : ComponentActivity() {
    private var speechService: SpeechService? = null
    private var signalingClient: SignalingClient? = null
    private var webRTCClient: WebRTCClient? = null
    private var roomId: String = "sala-padrao"
    private var messagesState = mutableStateListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initWebRTC()
        setContent {
            ComunicaTheme {
                MainScreen(
                    initialRoomId = roomId,
                    onJoinRoom = { id ->
                        Log.d("ComunicaDebug", "Tentando conectar na sala: $id")
                        Toast.makeText(this, "Conectando à sala: $id", Toast.LENGTH_SHORT).show()
                        roomId = id
                        signalingClient?.joinRoom(id)
                    },
                    onStartCall = {
                        webRTCClient?.createOffer { offerSdp ->
                            signalingClient?.sendOffer(roomId, offerSdp.description)
                        }
                    },
                    onSendMessage = { msg ->
                        webRTCClient?.sendMessage(msg)
                    },
                    onSpeechServiceAction = { action, isListening ->
                        if (isListening) speechService?.startListening()
                        else speechService?.stopListening()
                    },
                    messagesList = messagesState
                )
            }
        }
    }

    private fun initWebRTC() {
        webRTCClient = WebRTCClient(this, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val candidateJson = JSONObject().apply {
                        put("sdpMid", it.sdpMid)
                        put("sdpMLineIndex", it.sdpMLineIndex)
                        put("candidate", it.sdp)
                    }
                    signalingClient?.sendIceCandidate(roomId, candidateJson)
                }
            }

            override fun onDataChannel(dc: DataChannel?) {
                dc?.let { webRTCClient?.onRemoteDataChannel(it) }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })

        webRTCClient?.setOnMessageReceivedListener { message ->
            runOnUiThread {
                messagesState.add(ChatMessage("Remoto", message))
            }
        }

        signalingClient = SignalingClient("http://192.168.15.33:3000", object : SignalingClient.SignalingListener {
            override fun onConnectionEstablished() {
                Log.d("ComunicaDebug", "Conectado ao servidor de sinalização! Entrando na sala: $roomId")
                signalingClient?.joinRoom(roomId)
            }

            override fun onOfferReceived(description: String) {
                Log.d("ComunicaDebug", "Oferta recebida! Enviando resposta...")
                webRTCClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, description))
                webRTCClient?.createAnswer { answerSdp ->
                    Log.d("ComunicaDebug", "Resposta criada, enviando para o servidor...")
                    signalingClient?.sendAnswer(roomId, answerSdp.description)
                }
            }

            override fun onAnswerReceived(description: String) {
                Log.d("ComunicaDebug", "Resposta recebida! Conexão deve ser estabelecida em breve.")
                webRTCClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, description))
            }

            override fun onIceCandidateReceived(candidate: JSONObject) {
                Log.d("ComunicaDebug", "Candidato ICE recebido do remoto.")
                webRTCClient?.addIceCandidate(IceCandidate(
                    candidate.getString("sdpMid"),
                    candidate.getInt("sdpMLineIndex"),
                    candidate.getString("candidate")
                ))
            }

            override fun onCallEnded() {}
        })

        webRTCClient?.initPeerConnection(listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()))
    }
}

@Composable
fun MainScreen(
    initialRoomId: String,
    onJoinRoom: (String) -> Unit,
    onStartCall: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSpeechServiceAction: (String, Boolean) -> Unit,
    messagesList: MutableList<ChatMessage> = remember { mutableStateListOf() }
) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var isCallActive by remember { mutableStateOf(false) }
    var roomName by remember { mutableStateOf(initialRoomId) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    if (hasPermissions || LocalInspectionMode.current) {
        if (!LocalInspectionMode.current) {
            DisposableEffect(Unit) {
                val speechService = SpeechService(context) { transcribedText ->
                    messagesList.add(ChatMessage("Eu (Voz)", transcribedText, true))
                    onSendMessage(transcribedText)
                }
                onDispose {
                    speechService.destroy()
                }
            }
        }

        CommunicationUI(
            messages = messagesList,
            isListening = isListening,
            isCallActive = isCallActive,
            roomName = roomName,
            onRoomNameChange = { 
                roomName = it
                onJoinRoom(it)
            },
            onToggleListening = {
                isListening = !isListening
                onSpeechServiceAction("toggle", isListening)
            },
            onToggleCall = {
                isCallActive = !isCallActive
                if (isCallActive) onStartCall()
            },
            onSendMessage = { newMsg ->
                messagesList.add(ChatMessage("Eu", newMsg))
                onSendMessage(newMsg)
            }
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Permissões necessárias para áudio, vídeo e transcrição.")
        }
    }
}

@Composable
fun CommunicationUI(
    messages: List<ChatMessage>,
    isListening: Boolean,
    isCallActive: Boolean,
    roomName: String,
    onRoomNameChange: (String) -> Unit,
    onToggleListening: () -> Unit,
    onToggleCall: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = roomName,
                onValueChange = { onRoomNameChange(it) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
                placeholder = { Text("Nome da Sala", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onRoomNameChange(roomName) },
                modifier = Modifier.height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Conectar")
            }
        }

        Box(modifier = Modifier.weight(0.5f).fillMaxWidth().background(Color.Black)) {
            VideoView(modifier = Modifier.align(Alignment.BottomEnd).size(120.dp, 160.dp).padding(8.dp))
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                Text(if (isCallActive) "Chamada Ativa" else "Aguardando Chamada", color = Color.White)
            }
            
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = onToggleListening,
                    containerColor = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                ) {
                    Icon(if (isListening) Icons.Default.Mic else Icons.Default.MicOff, contentDescription = "Transcrição")
                }

                FloatingActionButton(
                    onClick = onToggleCall,
                    containerColor = if (isCallActive) Color.Red else Color.Green
                ) {
                    Icon(
                        imageVector = if (isCallActive) Icons.Default.CallEnd else Icons.Default.Call,
                        contentDescription = "Ligar/Desligar",
                        tint = Color.White
                    )
                }
            }
        }

        ChatSection(modifier = Modifier.weight(0.5f), messages, onSendMessage)
    }
}

@Composable
fun VideoView(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier.background(Color.DarkGray, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text("Preview de Vídeo", color = Color.White)
        }
    } else {
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(null, null)
                }
            },
            modifier = modifier.background(Color.DarkGray, RoundedCornerShape(8.dp))
        )
    }
}

@Composable
fun ChatSection(modifier: Modifier = Modifier, messages: List<ChatMessage>, onSendMessage: (String) -> Unit) {
    var textState by remember { mutableStateOf("") }

    Column(modifier = modifier.padding(8.dp)) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { msg ->
                val isMe = msg.sender.startsWith("Eu")
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart) {
                    Card(
                        modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 280.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.isTranscription) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else if (isMe) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = msg.sender,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = msg.text,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = textState,
                onValueChange = { textState = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Digite uma mensagem...") },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )
            IconButton(onClick = {
                if (textState.isNotBlank()) {
                    onSendMessage(textState)
                    textState = ""
                }
            }) {
                Icon(Icons.Default.Send, contentDescription = "Enviar")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ComunicaTheme {
        MainScreen(
            initialRoomId = "sala-teste",
            onJoinRoom = {},
            onStartCall = {},
            onSendMessage = {},
            onSpeechServiceAction = { _, _ -> }
        )
    }
}
