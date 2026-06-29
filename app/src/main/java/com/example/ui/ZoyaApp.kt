package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.SocketState
import com.example.ZoyaState
import com.example.ZoyaStateController
import com.example.service.BackgroundAudioService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

// Theme Colors
val Obsidian = Color(0xFF050505) // Deep interstellar black (#050505)
val CyberBlue = Color(0xFF00E5FF) // Electric cyan
val NeonPurple = Color(0xFFD500F9) // Vivid violet
val GlowCyan = Color(0x3300E5FF)
val GlowPurple = Color(0x33D500F9)

@Composable
fun ZoyaApp() {
    val context = LocalContext.current
    
    // Core Permissions
    val corePermissions = remember {
        mutableStateListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var permissionsGranted by remember {
        mutableStateOf(hasRequiredCorePermissions(context, corePermissions))
    }

    // Launch background service if core permissions are granted
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            startZoyaService(context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
    ) {
        if (!permissionsGranted) {
            PermissionsOnboardingScreen(
                permissions = corePermissions,
                onAllGranted = {
                    permissionsGranted = true
                }
            )
        } else {
            ZoyaAssistantDashboard()
        }
    }
}

private fun hasRequiredCorePermissions(context: Context, permissions: List<String>): Boolean {
    // Specifically, Microphone and Notification (if applicable) are mandatory to start the service
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    return micGranted && notifGranted
}

private fun startZoyaService(context: Context) {
    val intent = Intent(context, BackgroundAudioService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

@Composable
fun PermissionsOnboardingScreen(
    permissions: List<String>,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Check if mandatory permissions are granted (Microphone + Notifications)
        val micOk = results[Manifest.permission.RECORD_AUDIO] == true
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }

        if (micOk && notifOk) {
            onAllGranted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Visual Header
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "✨",
                fontSize = 54.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(
                text = "Awaken Zoya",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Configure permissions to activate your sassy, real-time background voice assistant.",
                fontSize = 15.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Permissions Breakdown List
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                PermissionItem(
                    title = "Microphone Access",
                    desc = "Required to capture your voice for wake-word activation and real-time conversation.",
                    emojiIcon = "🎙️",
                    isGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                )
                Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 12.dp))
                PermissionItem(
                    title = "Notifications Access",
                    desc = "Required to keep Zoya active in the background without being killed by Android battery optimizations.",
                    emojiIcon = "🔔",
                    isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    } else true
                )
                Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 12.dp))
                PermissionItem(
                    title = "Contacts & Calling (Optional but Recommended)",
                    desc = "Allows Zoya to query your address book, call friends, and trigger deep-linked WhatsApp messages.",
                    emojiIcon = "📱",
                    isGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                )
            }
        }

        // Action Trigger Button
        Button(
            onClick = {
                launcher.launch(permissions.toTypedArray())
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = NeonPurple,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(text = "GRANT ACCESS", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PermissionItem(title: String, desc: String, emojiIcon: String, isGranted: Boolean) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF1C1C1C))
                .align(Alignment.CenterVertically),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emojiIcon,
                fontSize = 18.sp
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = desc, fontSize = 12.sp, color = Color.Gray)
        }
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = CyberBlue,
                modifier = Modifier.size(24.dp).align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
fun ZoyaAssistantDashboard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val assistantState by ZoyaStateController.assistantState.collectAsState()
    val socketState by ZoyaStateController.socketState.collectAsState()
    val userTranscript by ZoyaStateController.userTranscript.collectAsState()
    val zoyaTranscript by ZoyaStateController.zoyaTranscript.collectAsState()

    var showPermissionAlert by remember { mutableStateOf<String?>(null) }

    // Listen to permission denied triggers from the tool execution engine
    LaunchedEffect(Unit) {
        ZoyaStateController.actionEvent.collectLatest { event ->
            if (event.startsWith("permission_denied:")) {
                showPermissionAlert = event.substringAfter("permission_denied:")
            }
        }
    }

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showPermissionAlert = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Futuristic Top Status bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ZOYA LIVE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberBlue,
                    letterSpacing = 2.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (socketState) {
                                    SocketState.CONNECTED -> CyberBlue
                                    SocketState.CONNECTING -> NeonPurple
                                    else -> Color.Red
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (socketState) {
                            SocketState.CONNECTED -> "SECURE WEBSOCKET ACTIVE"
                            SocketState.CONNECTING -> "AWAKENING VOICE ENGINE..."
                            SocketState.DISCONNECTED -> "STANDBY MODE"
                            SocketState.ERROR -> "CONNECTION OFFLINE"
                        },
                        fontSize = 10.sp,
                        color = Color.LightGray
                    )
                }
            }

            // Power Settings button
            IconButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "System Settings",
                    tint = Color.White
                )
            }
        }

        // Central Content Area
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Interactive Zoya Orb
            ZoyaOrb(
                state = assistantState,
                onClick = {
                    coroutineScope.launch {
                        ZoyaStateController.actionEvent.emit("toggle_session")
                    }
                }
            )

            Spacer(modifier = Modifier.height(36.dp))

            // State indicators
            Text(
                text = when (assistantState) {
                    ZoyaState.IDLE -> "Say \"Zoya\" or Tap to Talk"
                    ZoyaState.LISTENING -> "listening..."
                    ZoyaState.THINKING -> "thinking..."
                    ZoyaState.SPEAKING -> "talking..."
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                color = when (assistantState) {
                    ZoyaState.LISTENING -> CyberBlue
                    ZoyaState.THINKING, ZoyaState.SPEAKING -> NeonPurple
                    ZoyaState.IDLE -> Color.Gray
                },
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Interactive Subtitle Overlay - Sassy dialogue style!
            AnimatedVisibility(
                visible = zoyaTranscript.isNotEmpty() && assistantState != ZoyaState.IDLE,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "“Zoya”",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonPurple,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = zoyaTranscript.takeLast(140),
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Bottom Glassmorphic Control Panel Container
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)), // bg-white/10
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF)))),
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Permissions Indicators (Overlapping circles)
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.width(68.dp)) {
                            // Circle 1: Mic
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF121212))
                                    .border(2.dp, NeonPurple.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🎙️", fontSize = 16.sp)
                            }
                            // Circle 2: Phone (overlapping offset)
                            Box(
                                modifier = Modifier
                                    .padding(start = 24.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF121212))
                                    .border(2.dp, CyberBlue.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📱", fontSize = 16.sp)
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Column {
                            Text(
                                text = "SYSTEM INTEGRITY",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.5f),
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Secure & Active",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // Right: Wake Word status
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "WAKE WORD",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.5f),
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Hey Zoya",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberBlue
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Divider(color = Color(0x1AFFFFFF), thickness = 1.dp)
                
                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons Row (1x2 Grid)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left Settings
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x1AFFFFFF)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = "SETTINGS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                    }

                    // Right Toggle Action
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                ZoyaStateController.actionEvent.emit("toggle_session")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonPurple.copy(alpha = 0.2f),
                            contentColor = NeonPurple
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(listOf(NeonPurple, CyberBlue))
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = if (assistantState == ZoyaState.IDLE) "AWAKEN" else "STANDBY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonPurple,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        // Permission Alert Dialog
        if (showPermissionAlert != null) {
            AlertDialog(
                onDismissRequest = { showPermissionAlert = null },
                title = { Text(text = "Sassy Interruption", color = Color.White) },
                text = {
                    Text(
                        text = "Oh honey, you're asking me to execute magic but you locked my tools! Enable the '${showPermissionAlert?.substringAfterLast(".")}' permission so I can fulfill your command.",
                        color = Color.LightGray
                    )
                },
                containerColor = Color(0xFF161724),
                confirmButton = {
                    TextButton(
                        onClick = {
                            val permission = showPermissionAlert
                            if (permission != null) {
                                permissionLauncher.launch(permission)
                            }
                        }
                    ) {
                        Text(text = "ENABLE ACCESS", color = CyberBlue)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionAlert = null }) {
                        Text(text = "IGNORE", color = Color.Gray)
                    }
                }
            )
        }
    }
}

@Composable
fun ZoyaOrb(
    state: ZoyaState,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    
    // Animate Breathing Glow (Idle)
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Animate Pulsing Ring (Thinking)
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    // Live real-time amplitudes
    val micAmp by ZoyaStateController.micAmplitude.collectAsState()
    val speakerAmp by ZoyaStateController.speakerAmplitude.collectAsState()

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(240.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = 80.dp.toPx()

            when (state) {
                ZoyaState.IDLE -> {
                    // Draw breathing layered neon glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(GlowCyan, Color.Transparent),
                            center = center,
                            radius = baseRadius * breatheScale * 1.5f
                        ),
                        center = center,
                        radius = baseRadius * breatheScale * 1.5f
                    )

                    // Central glowing orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(CyberBlue, Obsidian),
                            center = center,
                            radius = baseRadius
                        ),
                        center = center,
                        radius = baseRadius
                    )
                }

                ZoyaState.LISTENING -> {
                    // Active listening waveform paths based on mic input amplitude
                    val phaseShift = System.currentTimeMillis() / 200f
                    val waveAmplitude = 10.dp.toPx() + micAmp * 80.dp.toPx()

                    // Draw 3 layered offset waveforms
                    for (layer in 0..2) {
                        val path = Path()
                        val layerPhase = phaseShift + (layer * 45f)
                        val pointsCount = 60
                        
                        for (i in 0..pointsCount) {
                            val angle = (i.toFloat() / pointsCount) * 2 * Math.PI
                            // Mutate radius based on sine wave and amplitude
                            val rOffset = sin(angle * 6 + layerPhase) * waveAmplitude
                            val r = baseRadius + rOffset
                            
                            val x = center.x + (r * cos(angle)).toFloat()
                            val y = center.y + (r * sin(angle)).toFloat()
                            
                            if (i == 0) {
                                path.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                            }
                        }
                        path.close()
                        
                        drawPath(
                            path = path,
                            brush = Brush.linearGradient(
                                colors = when (layer) {
                                    0 -> listOf(CyberBlue, GlowCyan)
                                    1 -> listOf(NeonPurple, GlowPurple)
                                    else -> listOf(CyberBlue, NeonPurple)
                                }
                            ),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }

                    // Solid central indicator
                    drawCircle(
                        color = Obsidian,
                        center = center,
                        radius = baseRadius - 10.dp.toPx()
                    )
                    drawCircle(
                        color = CyberBlue,
                        center = center,
                        radius = 24.dp.toPx()
                    )
                }

                ZoyaState.THINKING -> {
                    // Pulsing concentric spinning neon rings
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(GlowPurple, Color.Transparent),
                            center = center,
                            radius = baseRadius * 1.6f
                        ),
                        center = center,
                        radius = baseRadius * 1.6f
                    )

                    // Draw rotating dashed or sliced ring
                    val slices = 8
                    for (i in 0 until slices) {
                        val startAngle = (i * (360f / slices)) + ringRotation
                        val path = Path()
                        val r1 = baseRadius + 15.dp.toPx()
                        val r2 = baseRadius + 22.dp.toPx()
                        
                        // Compute start/end coordinates of current segment slice
                        val radStart = Math.toRadians(startAngle.toDouble())
                        val radEnd = Math.toRadians((startAngle + 20f).toDouble())
                        
                        val x1 = center.x + (r1 * cos(radStart)).toFloat()
                        val y1 = center.y + (r1 * sin(radStart)).toFloat()
                        val x2 = center.x + (r2 * cos(radStart)).toFloat()
                        val y2 = center.y + (r2 * sin(radStart)).toFloat()
                        
                        val x3 = center.x + (r2 * cos(radEnd)).toFloat()
                        val y3 = center.y + (r2 * sin(radEnd)).toFloat()
                        val x4 = center.x + (r1 * cos(radEnd)).toFloat()
                        val y4 = center.y + (r1 * sin(radEnd)).toFloat()

                        path.moveTo(x1, y1)
                        path.lineTo(x2, y2)
                        path.lineTo(x3, y3)
                        path.lineTo(x4, y4)
                        path.close()

                        drawPath(
                            path = path,
                            color = NeonPurple
                        )
                    }

                    // Rotating inner ring
                    drawCircle(
                        color = CyberBlue,
                        center = center,
                        radius = baseRadius,
                        style = Stroke(
                            width = 4.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(40f, 20f),
                                ringRotation * 1.5f
                            )
                        )
                    )

                    // central core
                    drawCircle(
                        color = Obsidian,
                        center = center,
                        radius = baseRadius - 8.dp.toPx()
                    )
                }

                ZoyaState.SPEAKING -> {
                    // Outer audio dynamic visualizer bars radiating outward matching speech amplitude
                    val barsCount = 36
                    val maxBarHeight = 40.dp.toPx()
                    
                    for (i in 0 until barsCount) {
                        val angleDegrees = i * (360f / barsCount)
                        val angleRad = Math.toRadians(angleDegrees.toDouble())
                        
                        // Modulate bar height with speech stream amplitude and some randomized wave dynamics
                        val randomFactor = sin((angleDegrees + System.currentTimeMillis() / 15f) / 10f) * 0.3f + 0.7f
                        val height = (speakerAmp * maxBarHeight * randomFactor).coerceAtLeast(4.dp.toPx())
                        
                        val startRadius = baseRadius + 8.dp.toPx()
                        val endRadius = startRadius + height

                        val startX = center.x + (startRadius * cos(angleRad)).toFloat()
                        val startY = center.y + (startRadius * sin(angleRad)).toFloat()
                        
                        val endX = center.x + (endRadius * cos(angleRad)).toFloat()
                        val endY = center.y + (endRadius * sin(angleRad)).toFloat()

                        drawLine(
                            brush = Brush.linearGradient(listOf(CyberBlue, NeonPurple)),
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = 6.dp.toPx()
                        )
                    }

                    // Central core orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(NeonPurple, Obsidian),
                            center = center,
                            radius = baseRadius
                        ),
                        center = center,
                        radius = baseRadius
                    )
                }
            }
        }
    }
}
