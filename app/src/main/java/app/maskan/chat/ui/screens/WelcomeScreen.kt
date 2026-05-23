package app.maskan.chat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.ui.theme.SoftCoral
import app.maskan.chat.ui.theme.WarmPeach
import androidx.compose.material.icons.filled.Language

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmPeach)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "مسكن",
            fontSize = 48.sp,
            color = SoftCoral
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Maskan · مسكن",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(40.dp))

        FeatureRow(
            icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = SoftCoral, modifier = Modifier.size(24.dp)) },
            text = stringResource(R.string.welcome_feature_encrypted)
        )
        Spacer(modifier = Modifier.height(16.dp))
        FeatureRow(
            icon = { Icon(Icons.Filled.Security, contentDescription = null, tint = SoftCoral, modifier = Modifier.size(24.dp)) },
            text = stringResource(R.string.welcome_feature_no_tracking)
        )
        Spacer(modifier = Modifier.height(16.dp))
        FeatureRow(
            icon = { Icon(Icons.Filled.Language, contentDescription = null, tint = SoftCoral, modifier = Modifier.size(24.dp)) },
            text = stringResource(R.string.welcome_feature_arabic_first)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SoftCoral)
        ) {
            Text(
                text = stringResource(R.string.welcome_get_started),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun FeatureRow(
    icon: @Composable () -> Unit,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
