package com.example.comunica

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.comunica.ui.theme.ComunicaTheme
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject
import org.webrtc.*

data class ChatMessage(
    val sender: String,
    val text: String,
    val isTranscription: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity() {
    private var speechService: SpeechService? = null
    private var signalingClient: SignalingClient? = null
    private var webRTCClient by mutableStateOf<WebRTCClient?>(null)
    private var myId: String = ""
    private var myName: String = ""
    private var myEmail: String = ""
    private var currentTargetId by mutableStateOf<String?>(null)
    
    private val messagesState = mutableStateListOf<ChatMessage>()
    private val onlineUsersState = mutableStateListOf<UserInfo>()
    
    private val incomingCallFromState = mutableStateOf<UserInfo?>(null)
    private var remoteOfferSdp: String? = null

    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null

    private fun playNotificationSound() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startRingtone() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, notification)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRingtone() {
        runOnUiThread {
            try {
                ringtone?.stop()
                ringtone = null
                // Força o modo de comunicação após parar o toque
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startRingbackTone() {
        if (toneGenerator == null) {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
        }
        // TONE_SUP_RINGTONE é o som clássico de "chamando"
        toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
    }

    private fun stopRingbackTone() {
        runOnUiThread {
            try {
                toneGenerator?.stopTone()
                toneGenerator?.release()
                toneGenerator = null
                // Força o modo de comunicação após parar o tom
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showIncomingCallNotification(from: UserInfo) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Comunica::IncomingCall")
        wakeLock.acquire(10000) // Acorda a tela por 10 segundos

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "incoming_calls"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Chamadas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notificações de chamadas recebidas"
                enableVibration(true)
                setSound(null, null) 
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Chamada de ${from.name}")
            .setContentText("Toque para atender")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        notificationManager.notify(100, notification)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("comunica_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("user_name", "")
        val savedEmail = prefs.getString("user_email", "")

        if (!savedName.isNullOrBlank()) {
            myName = savedName
            myEmail = savedEmail ?: ""
            val serviceIntent = Intent(this, SignalingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            initWebRTC()
            speechService = SpeechService(this) { transcription ->
                runOnUiThread {
                    messagesState.add(ChatMessage(myName, transcription, isTranscription = true))
                    if (webRTCClient?.isDataChannelOpen() == true) {
                        webRTCClient?.sendMessage(transcription)
                    } else {
                        currentTargetId?.let { targetId ->
                            signalingClient?.sendChatMessage(targetId, transcription)
                        }
                    }
                }
            }
        }

        setContent {
            var loggedIn by remember { mutableStateOf(!savedName.isNullOrBlank()) }
            
            ComunicaTheme {
                if (!loggedIn) {
                    LoginScreen { name, email ->
                        prefs.edit().apply {
                            putString("user_name", name)
                            putString("user_email", email)
                            apply()
                        }
                        myName = name
                        myEmail = email
                        val serviceIntent = Intent(this, SignalingService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                        loggedIn = true
                        initWebRTC()
                        speechService = SpeechService(this) { transcription ->
                            runOnUiThread {
                                messagesState.add(ChatMessage(myName, transcription, isTranscription = true))
                                if (webRTCClient?.isDataChannelOpen() == true) {
                                    webRTCClient?.sendMessage(transcription)
                                } else {
                                    currentTargetId?.let { targetId ->
                                        signalingClient?.sendChatMessage(targetId, transcription)
                                    }
                                }
                            }
                        }
                    }
                } else {
                MainScreen(
                    myId = myId,
                    myName = myName,
                    onlineUsers = onlineUsersState,
                    messagesList = messagesState,
                    incomingCallFrom = incomingCallFromState.value,
                    eglBaseContext = webRTCClient?.rootEglBase?.eglBaseContext,
                    currentTargetId = currentTargetId,
                    onUserSelected = { user ->
                        currentTargetId = user.id
                    },
                    onBack = { currentTargetId = null },
                    onAcceptCall = { from ->
                            stopRingtone()
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(100)
                            val sdp = remoteOfferSdp
                            if (sdp != null) {
                                currentTargetId = from.id
                                webRTCClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp))
                                webRTCClient?.createAnswer { answerSdp ->
                                    signalingClient?.sendAnswer(from.id, answerSdp.description)
                                }
                            }
                            incomingCallFromState.value = null
                            remoteOfferSdp = null
                        },
                        onRejectCall = { from ->
                            stopRingtone()
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(100)
                            signalingClient?.endCall(from.id)
                            incomingCallFromState.value = null
                            remoteOfferSdp = null
                        },
                        onStartCall = { target, isVideo ->
                            startRingbackTone()
                            if (webRTCClient != null && signalingClient != null) {
                                currentTargetId = target.id
                                webRTCClient?.createOffer { offerSdp ->
                                    signalingClient?.sendOffer(target.id, offerSdp.description)
                                }
                            }
                        },
                        onSendMessage = { target, msg ->
                            messagesState.add(ChatMessage("Eu: $myName", msg))
                            if (webRTCClient?.isDataChannelOpen() == true) {
                                webRTCClient?.sendMessage(msg)
                            } else {
                                signalingClient?.sendChatMessage(target.id, msg)
                            }
                        },
                        onHangup = { target ->
                            stopRingbackTone()
                            stopRingtone()
                            webRTCClient?.closeConnection()
                            signalingClient?.endCall(target.id)
                            currentTargetId = null
                            initWebRTC(isRestart = true)
                        },
                        onSpeechServiceAction = { isListening ->
                            if (isListening) speechService?.startListening()
                            else speechService?.stopListening()
                        }
                    )
                }
            }
        }
    }

    private fun initWebRTC(isRestart: Boolean = false) {
        if (isRestart) {
            webRTCClient?.dispose()
            webRTCClient = null
        }

        if (webRTCClient == null) {
            webRTCClient = WebRTCClient(this, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        currentTargetId?.let { target ->
                            val candidateJson = JSONObject().apply {
                                put("sdpMid", it.sdpMid)
                                put("sdpMLineIndex", it.sdpMLineIndex)
                                put("candidate", it.sdp)
                            }
                            signalingClient?.sendIceCandidate(target, candidateJson)
                        }
                    }
                }

                override fun onDataChannel(dc: DataChannel?) {
                    Log.d("ComunicaDebug", "PeerConnection: onDataChannel recebido: ${dc?.label()}")
                    dc?.let { webRTCClient?.onRemoteDataChannel(it) }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    Log.d("ComunicaDebug", "Track remota: ${transceiver?.receiver?.track()?.kind()}")
                }
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
                    val senderName = onlineUsersState.find { it.id == currentTargetId }?.name ?: "Remoto"
                    messagesState.add(ChatMessage(senderName, message))
                    playNotificationSound()
                }
            }
            
            webRTCClient?.initPeerConnection(listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()))
        }

        if (signalingClient == null) {
            signalingClient = SignalingClient("http://192.168.15.33:3000", object : SignalingClient.SignalingListener {
                override fun onConnectionEstablished(id: String) {
                    myId = id
                    Log.d("ComunicaDebug", "Conectado como: $myId. Entrando no lobby...")
                    signalingClient?.joinRoom("sala-padrao", myName, myEmail)
                }

                override fun onOfferReceived(fromId: String, description: String) {
                    runOnUiThread {
                        Log.d("ComunicaDebug", "Signaling: Oferta recebida de: $fromId")
                        startRingtone()
                        remoteOfferSdp = description
                        val user = onlineUsersState.find { it.id == fromId } ?: UserInfo(fromId, "Usuário " + fromId.take(4), "")
                        showIncomingCallNotification(user)
                        incomingCallFromState.value = user
                    }
                }

                override fun onAnswerReceived(from: String, description: String) {
                    Log.d("ComunicaDebug", "Signaling: Resposta recebida de: $from")
                    stopRingbackTone()
                    webRTCClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, description))
                }

                override fun onIceCandidateReceived(from: String, candidate: JSONObject) {
                    Log.d("ComunicaDebug", "Signaling: ICE Candidate recebido de: $from")
                    webRTCClient?.addIceCandidate(IceCandidate(
                        candidate.getString("sdpMid"),
                        candidate.getInt("sdpMLineIndex"),
                        candidate.getString("candidate")
                    ))
                }

                override fun onUserListUpdated(users: List<UserInfo>) {
                    runOnUiThread {
                        onlineUsersState.clear()
                        onlineUsersState.addAll(users)
                    }
                }

                override fun onCallEnded(from: String) {
                    runOnUiThread {
                        Log.d("ComunicaDebug", "Chamada encerrada pelo remoto.")
                        stopRingtone()
                        stopRingbackTone()
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(100)
                        webRTCClient?.closeConnection()
                        currentTargetId = null
                        initWebRTC(isRestart = true)
                        Toast.makeText(this@MainActivity, "Chamada encerrada", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onChatMessageReceived(from: String, message: String) {
                    runOnUiThread {
                        val sender = onlineUsersState.find { it.id == from }
                        if (sender != null) {
                            // Se não estivermos conversando com ninguém ou estivermos conversando com outra pessoa
                            // mudamos o foco para quem enviou a mensagem
                            if (currentTargetId != from) {
                                currentTargetId = from
                                // Precisamos atualizar o selectedUser no MainScreen. 
                                // Como o MainScreen é um Composable, vamos usar um estado para controlar isso.
                            }
                            
                            val senderName = sender.name
                            messagesState.add(ChatMessage(senderName, message))
                            playNotificationSound()
                        }
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopRingtone()
            stopRingbackTone()
            speechService?.destroy()
            webRTCClient?.dispose()
            signalingClient?.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun MainScreen(
    myId: String,
    myName: String,
    onlineUsers: List<UserInfo>,
    messagesList: MutableList<ChatMessage>,
    incomingCallFrom: UserInfo?,
    eglBaseContext: EglBase.Context?,
    currentTargetId: String?,
    onUserSelected: (UserInfo) -> Unit,
    onBack: () -> Unit,
    onAcceptCall: (UserInfo) -> Unit,
    onRejectCall: (UserInfo) -> Unit,
    onStartCall: (UserInfo, Boolean) -> Unit,
    onSendMessage: (UserInfo, String) -> Unit,
    onHangup: (UserInfo) -> Unit,
    onSpeechServiceAction: (Boolean) -> Unit
) {
    var hasPermissions by remember { mutableStateOf(false) }
    
    val selectedUser = remember(currentTargetId, onlineUsers) {
        onlineUsers.find { it.id == currentTargetId }
    }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    if (hasPermissions || LocalInspectionMode.current) {
        if (incomingCallFrom != null) {
            IncomingCallDialog(
                from = incomingCallFrom,
                onAccept = {
                    onUserSelected(incomingCallFrom)
                    onAcceptCall(incomingCallFrom)
                },
                onReject = { onRejectCall(incomingCallFrom) }
            )
        }

        if (selectedUser == null) {
            UserListScreen(onlineUsers) { 
                onUserSelected(it)
            }
        } else {
            CommunicationUI(
                targetUser = selectedUser,
                messages = messagesList,
                myName = myName,
                eglBaseContext = eglBaseContext,
                onBack = onBack,
                onStartCall = { isVideo -> onStartCall(selectedUser, isVideo) },
                onSendMessage = { msg -> onSendMessage(selectedUser, msg) },
                onHangup = { onHangup(selectedUser) },
                onToggleListening = onSpeechServiceAction
            )
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Permissões necessárias para continuar.")
        }
    }
}

@Composable
fun LoginScreen(onLogin: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Bem-vindo ao Comunica", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Entre com seu nome para começar", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        
        Spacer(Modifier.height(32.dp))
        
        OutlinedTextField(
            value = name, 
            onValueChange = { name = it }, 
            label = { Text("Nome de usuário") }, 
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        
        Spacer(Modifier.height(16.dp))
        
        OutlinedTextField(
            value = email, 
            onValueChange = { email = it }, 
            label = { Text("Email (opcional)") }, 
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        
        Spacer(Modifier.height(32.dp))
        
        Button(
            onClick = { if (name.isNotBlank()) onLogin(name, email) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = name.isNotBlank(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Entrar")
        }
    }
}

@Composable
fun IncomingCallDialog(from: UserInfo, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Chamada Recebida", fontWeight = FontWeight.Bold) },
        text = { Text("${from.name} está ligando para você...") },
        confirmButton = {
            Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = Color.Green)) {
                Text("Atender", color = Color.White)
            }
        },
        dismissButton = {
            Button(onClick = onReject, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                Text("Recusar", color = Color.White)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListScreen(users: List<UserInfo>, onUserClick: (UserInfo) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comunica", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Text(
                text = "Usuários Online (${users.size})",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            LazyColumn {
                items(users) { user ->
                    ListItem(
                        headlineContent = { Text(user.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text(
                                text = if (user.email.isNotBlank()) user.email else "ID: ${user.id.take(8)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingContent = {
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
                            }
                        },
                        modifier = Modifier.clickable { onUserClick(user) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunicationUI(
    targetUser: UserInfo,
    messages: List<ChatMessage>,
    myName: String,
    eglBaseContext: EglBase.Context?,
    onBack: () -> Unit,
    onStartCall: (Boolean) -> Unit,
    onSendMessage: (String) -> Unit,
    onHangup: () -> Unit,
    onToggleListening: (Boolean) -> Unit
) {
    var isCallActive by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(targetUser.name, fontSize = 18.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { onStartCall(false); isCallActive = true }) { Icon(Icons.Default.Call, contentDescription = null) }
                    IconButton(onClick = { onStartCall(true); isCallActive = true }) { Icon(Icons.Default.Videocam, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            if (isCallActive) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black)) {
                    VideoView(
                        eglBaseContext = eglBaseContext,
                        modifier = Modifier.align(Alignment.BottomEnd).size(100.dp, 140.dp).padding(8.dp)
                    )
                    Text("Chamada com ${targetUser.name}...", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    IconButton(
                        onClick = { onHangup(); isCallActive = false },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(CircleShape).background(Color.Red)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
                    }
                }
            }

            ChatSection(
                modifier = Modifier.weight(1f),
                messages = messages,
                myName = myName,
                onSendMessage = onSendMessage,
                isListening = isListening,
                onToggleListening = {
                    isListening = !isListening
                    onToggleListening(isListening)
                }
            )
        }
    }
}

@Composable
fun ChatSection(
    modifier: Modifier = Modifier,
    messages: List<ChatMessage>,
    myName: String,
    onSendMessage: (String) -> Unit,
    isListening: Boolean,
    onToggleListening: () -> Unit
) {
    var textState by remember { mutableStateOf("") }

    Column(modifier = modifier.padding(8.dp)) {
        val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
        
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true
        ) {
            items(messages.asReversed()) { msg ->
                val isMe = msg.sender == "Eu: $myName" || msg.sender == myName
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart) {
                    Card(
                        modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 280.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.isTranscription) MaterialTheme.colorScheme.tertiaryContainer else if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(msg.sender, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text(msg.text, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = dateFormat.format(Date(msg.timestamp)),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                modifier = Modifier.align(Alignment.End),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).navigationBarsPadding()) {
            IconButton(onClick = onToggleListening) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = null,
                    tint = if (isListening) Color.Red else MaterialTheme.colorScheme.primary
                )
            }
            TextField(
                value = textState,
                onValueChange = { textState = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensagem...") },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(8.dp))
            FloatingActionButton(
                onClick = { if (textState.isNotBlank()) { onSendMessage(textState); textState = "" } },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Send, contentDescription = "Enviar", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
fun VideoView(eglBaseContext: EglBase.Context?, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier.background(Color.DarkGray, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text("Video", color = Color.White, fontSize = 10.sp)
        }
    } else {
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply { 
                    init(eglBaseContext, null)
                    setEnableHardwareScaler(true)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                }
            },
            modifier = modifier.background(Color.DarkGray, RoundedCornerShape(8.dp))
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ComunicaTheme {
        val messages = remember { mutableStateListOf<ChatMessage>() }
        MainScreen(
            myId = "minha-id",
            myName = "Eu",
            onlineUsers = listOf(UserInfo("1", "User One", "one@test.com")),
            messagesList = messages,
            incomingCallFrom = null,
            eglBaseContext = null,
            currentTargetId = null,
            onUserSelected = {},
            onBack = {},
            onAcceptCall = {},
            onRejectCall = {},
            onStartCall = { _, _ -> },
            onSendMessage = { _, _ -> },
            onHangup = {},
            onSpeechServiceAction = {}
        )
    }
}
