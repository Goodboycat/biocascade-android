package com.example.auth

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.ui.theme.LocalBioSkin
import kotlin.math.sin

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
) {
    val skin = LocalBioSkin.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var isLoading by remember { mutableStateOf(false) }
    var isAutoChecking by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
                isAutoChecking = false
                onAuthSuccess()
            },
            onUnauthenticated = {
                isAutoChecking = false
            },
            scope = scope,
        )
    }

    val infinite = rememberInfiniteTransition(label = "auth_pulse")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(skin.backgroundGlow, skin.background),
                    radius = 1200f,
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val midY = size.height * 0.25f
            val path = Path()
            val steps = 80
            for (i in 0..steps) {
                val x = (i.toFloat() / steps) * size.width
                val norm = i.toFloat() / steps
                val ecgSpike = if (norm in 0.44f..0.56f) {
                    val local = (norm - 0.50f) * 60f
                    sin(local * 3f) * 68f
                } else {
                    sin(norm * 10f + phase) * 8f
                }
                val y = midY + ecgSpike
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = skin.primaryAccent.copy(alpha = 0.24f),
                style = Stroke(width = 3f),
            )
            drawCircle(
                color = skin.arterialRed.copy(alpha = 0.08f),
                radius = size.minDimension * 0.42f,
                center = Offset(size.width * 0.5f, size.height * 0.30f),
            )
        }

        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(skin.surfaceElevated)
                    .border(1.5.dp, skin.primaryAccent.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = "BioCascade Heart Emblem",
                    tint = skin.arterialRed,
                    modifier = Modifier.size(34.dp),
                )
            }

            Text(
                text = stringResource(R.string.auth_title),
                style = MaterialTheme.typography.displaySmall,
                color = skin.textPrimary,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.auth_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = skin.textSecondary,
                textAlign = TextAlign.Center,
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = skin.surface.copy(alpha = 0.92f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, skin.borderSubtle, RoundedCornerShape(16.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SecurityBadgeRow(
                        icon = Icons.Filled.Lock,
                        tint = skin.primaryAccent,
                        title = "AES-256-GCM Hardware Keystore",
                        detail = "Biometric profiles encrypted on device at rest",
                    )
                    SecurityBadgeRow(
                        icon = Icons.Filled.Shield,
                        tint = skin.secondaryAccent,
                        title = "Zero-Trust User Isolation",
                        detail = "Cloud Firestore records strictly scoped to your Google UID",
                    )
                    SecurityBadgeRow(
                        icon = Icons.Outlined.VerifiedUser,
                        tint = skin.cautionColor,
                        title = "128-Bit Encrypted Clinical PDFs",
                        detail = "Passphrase-protected reports & immutable HIPAA audit log",
                    )
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.criticalColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    errorMessage = null
                    isLoading = true
                    onGoogleSignInClicked(
                        context = context,
                        credentialManager = credentialManager,
                        onAuthSuccess = {
                            isLoading = false
                            onAuthSuccess()
                        },
                        onAuthError = { msg ->
                            isLoading = false
                            errorMessage = msg
                        },
                        scope = scope,
                        onAuthCancelled = {
                            isLoading = false
                        },
                    )
                },
                enabled = !isLoading && !isAutoChecking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = skin.primaryAccent,
                    contentColor = skin.onAccent,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("google_sign_in_button"),
            ) {
                if (isLoading || isAutoChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = skin.onAccent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (isAutoChecking) "Verifying session..." else "Authenticating...",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.auth_google_button),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityBadgeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    detail: String,
) {
    val skin = LocalBioSkin.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = skin.textPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = skin.textSecondary)
        }
    }
}
