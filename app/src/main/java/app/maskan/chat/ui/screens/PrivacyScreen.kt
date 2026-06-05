package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.ui.theme.maskanColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_screen_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.maskanColors.softLavender
                ),
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.back_button))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.privacy_core_principle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // What Maskan protects
            SectionHeader(
                icon = Icons.Filled.Lock,
                iconTint = MaterialTheme.maskanColors.mintGreen,
                title = stringResource(R.string.privacy_protects_header)
            )
            Spacer(modifier = Modifier.height(8.dp))
            PrivacyBullet(stringResource(R.string.privacy_protects_no_middleman))
            PrivacyBullet(stringResource(R.string.privacy_protects_encrypted))
            PrivacyBullet(stringResource(R.string.privacy_protects_your_choice))
            PrivacyBullet(stringResource(R.string.privacy_protects_no_google))
            PrivacyBullet(stringResource(R.string.privacy_protects_open_source))

            Spacer(modifier = Modifier.height(24.dp))

            // What Maskan cannot do
            SectionHeader(
                icon = Icons.Default.Info,
                iconTint = MaterialTheme.maskanColors.softCoral,
                title = stringResource(R.string.privacy_cannot_header)
            )
            Spacer(modifier = Modifier.height(8.dp))
            PrivacyBullet(stringResource(R.string.privacy_cannot_hide_from_provider))

            Spacer(modifier = Modifier.height(24.dp))

            // Privacy ladder
            Text(
                text = stringResource(R.string.privacy_ladder_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            PrivacyTierCard(
                tier = "1",
                title = stringResource(R.string.privacy_tier1_title),
                description = stringResource(R.string.privacy_tier1_desc),
                trust = stringResource(R.string.privacy_tier1_trust),
                color = MaterialTheme.maskanColors.mintGreen
            )
            Spacer(modifier = Modifier.height(8.dp))
            PrivacyTierCard(
                tier = "2",
                title = stringResource(R.string.privacy_tier2_title),
                description = stringResource(R.string.privacy_tier2_desc),
                trust = stringResource(R.string.privacy_tier2_trust),
                color = MaterialTheme.maskanColors.skyBlue
            )
            Spacer(modifier = Modifier.height(8.dp))
            PrivacyTierCard(
                tier = "3",
                title = stringResource(R.string.privacy_tier3_title),
                description = stringResource(R.string.privacy_tier3_desc),
                trust = stringResource(R.string.privacy_tier3_trust),
                color = MaterialTheme.maskanColors.warmPeach
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.privacy_ladder_tip),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PrivacyBullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PrivacyTierCard(
    tier: String,
    title: String,
    description: String,
    trust: String,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.3f),
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "$tier. $title",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = trust,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
