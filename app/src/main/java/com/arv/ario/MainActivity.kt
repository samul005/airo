package com.arv.ario

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arv.ario.service.ArioService
import com.arv.ario.ui.theme.ArioTheme
import com.arv.ario.utils.DebugLogger
import com.arv.ario.viewmodel.ArioState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArioTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    val debugLogs by DebugLogger.logs.collectAsState()

    // Register Broadcast Receiver for Logs
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msg = intent?.getStringExtra("message")
                if (msg != null) {
                    // We can log it again or just rely on the singleton. 
                    // Since ArioService calls DebugLogger.log() AND sends broadcast, 
                    // we don't need to duplicate it here if we are observing the singleton.
                    // But to be safe and explicit as requested:
                    // DebugLogger.log("BROADCAST: $msg") 
                }
            }
        }
        val filter = IntentFilter("ARIO_DEBUG_LOG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Permission Launchers
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text("Ario Setup", style = MaterialTheme.typography.headlineMedium, color = Color.Cyan)
        
        Spacer(modifier = Modifier.height(32.dp))

        if (!hasAudioPermission) {
            Button(onClick = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Grant Microphone Permission")
            }
        } else {
            Text("Microphone Permission Granted ✅", color = Color.Green)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!hasOverlayPermission) {
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                overlayPermissionLauncher.launch(intent)
            }) {
                Text("Grant Overlay Permission")
            }
        } else {
            Text("Overlay Permission Granted ✅", color = Color.Green)
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (hasAudioPermission && hasOverlayPermission) {
            Button(
                onClick = {
                    val intent = Intent(context, ArioService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan, contentColor = Color.Black)
            ) {
                Text("Start Ario Service")
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Debug Console
        Text("Debug Console:", color = Color.White, modifier = Modifier.align(Alignment.Start).padding(start = 16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .background(Color.DarkGray.copy(alpha = 0.5f))
        ) {
            Text(
                text = debugLogs,
                color = Color.Red,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
fun ArioVisualizer(state: ArioState) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    // Animations based on state
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ArioState.LISTENING) 1.5f else 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == ArioState.LISTENING) 500 else 2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = Modifier.size(200.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = size.width / 3

        // Glow effect
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Cyan.copy(alpha = 0.5f), Color.Transparent),
                center = center,
                radius = baseRadius * pulseScale * 1.5f
            ),
            radius = baseRadius * pulseScale * 1.5f
        )

        // Core Circle
        if (state == ArioState.THINKING) {
            // Spinning Arc for thinking
            drawArc(
                color = Color.Cyan,
                startAngle = rotation,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                size = androidx.compose.ui.geometry.Size(baseRadius * 2, baseRadius * 2),
                style = Stroke(width = 8.dp.toPx())
            )
        } else {
            // Solid/Stroked circle for other states
            drawCircle(
                color = Color.Cyan,
                radius = baseRadius * pulseScale,
                style = if (state == ArioState.LISTENING) Stroke(width = 4.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill
            )
        }
    }
}

