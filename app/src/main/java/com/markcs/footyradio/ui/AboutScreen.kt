package com.markcs.footyradio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import coil3.compose.AsyncImage
import com.markcs.footyradio.Config
import com.markcs.footyradio.PlayerViewModel
import com.markcs.footyradio.R
import com.markcs.footyradio.ui.theme.SubtitleGray
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    
    val appVersion = try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pInfo.versionName} (${PackageInfoCompat.getLongVersionCode(pInfo)})"
    } catch (_: Exception) {
        "1.0"
    }

    var stationsUrl by remember { mutableStateOf(viewModel.getStationsUrl()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.app_title)) }
                    append(stringResource(R.string.about_header_part1))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.about_header_kotlin)) }
                    append(stringResource(R.string.about_header_part2))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.about_header_compose)) }
                    append(stringResource(R.string.about_header_part3))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.about_header_building)) }
                    append(stringResource(R.string.about_header_part4))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.about_header_customizing)) }
                    append(stringResource(R.string.about_header_part5))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SubtitleGray,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Stations Config
            SectionHeader(stringResource(R.string.about_section_stations))
            SectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = stationsUrl,
                        onValueChange = { stationsUrl = it },
                        label = { Text(stringResource(R.string.about_station_url)) },
                        placeholder = { Text(stringResource(R.string.about_station_url_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = SubtitleGray.copy(alpha = 0.5f)
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                viewModel.resetStationsUrl()
                                stationsUrl = viewModel.getStationsUrl()
                                keyboardController?.hide()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.about_station_url_reset))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                viewModel.updateStationsUrl(stationsUrl)
                                keyboardController?.hide()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.about_station_url_save))
                        }
                    }
                }
            }

            // Features
            SectionHeader(stringResource(R.string.about_section_features))
            SectionCard {
                AboutRow(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(R.string.about_section_features),
                    onClick = null
                )
            }
            features.forEach { feature ->
                FeatureRow(icon = feature.icon, title = stringResource(feature.titleRes), subtitle = stringResource(feature.subtitleRes))
            }

            // Contact
            SectionHeader(stringResource(R.string.about_section_contact))
            SectionCard {
                AboutRow(
                    icon = Icons.Filled.Email,
                    title = stringResource(R.string.about_email),
                    subtitle = Config.email,
                    onClick = { openEmail(context) }
                )
                AboutRow(
                    icon = Icons.Filled.Feedback,
                    title = stringResource(R.string.about_feedback),
                    subtitle = stringResource(R.string.about_feedback_subtitle),
                    onClick = { openUrl(context, Config.feedbackURL) }
                )
            }

            // Support
            SectionHeader(stringResource(R.string.about_section_support))
            SectionCard {
                AboutRow(
                    icon = Icons.Filled.Star,
                    title = stringResource(R.string.about_rate),
                    onClick = { openPlayStore(context) }
                )
                AboutRow(
                    icon = Icons.Filled.Share,
                    title = stringResource(R.string.about_share),
                    onClick = { shareApp(context) }
                )
            }

            // Credits
            SectionHeader(stringResource(R.string.about_section_credits))
            SectionCard {
                Config.libraries.forEach { lib ->
                    AboutRow(
                        icon = Icons.Filled.Code,
                        title = "${lib.owner}/${lib.repo}",
                        onClick = {
                            openUrl(context, "https://github.com/${lib.owner}/${lib.repo}")
                        }
                    )
                }
            }

            // Legal
            SectionHeader(stringResource(R.string.about_section_legal))
            SectionCard {
                AboutRow(
                    icon = Icons.Filled.Gavel,
                    title = stringResource(R.string.about_license),
                    subtitle = stringResource(R.string.about_license_subtitle),
                    onClick = { openUrl(context, Config.licenseURL) }
                )
            }

            // Version
            SectionHeader(stringResource(R.string.about_section_version))
            SectionCard {
                AboutRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.about_app_version),
                    subtitle = appVersion,
                    onClick = null
                )
            }

            // Footer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = R.mipmap.ic_launcher_round,
                    contentDescription = stringResource(R.string.app_title),
                    modifier = Modifier.size(60.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.about_copyright, Calendar.getInstance().get(Calendar.YEAR)),
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleGray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// --- Section components ---

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SubtitleGray,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun AboutRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)?
) {
    val modifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable { onClick() }
    } else {
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleGray
                )
            }
        }
        if (onClick != null) {
            Icon(
                painter = painterResource(R.drawable.ic_next),
                contentDescription = null,
                tint = SubtitleGray,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SubtitleGray
            )
        }
    }
}

// --- Actions ---

private fun openEmail(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:${Config.email}")
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.about_email_subject))
    }
    try { context.startActivity(intent) } catch (_: Exception) {}
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {}
}

private fun openPlayStore(context: Context) {
    val packageName = context.packageName
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
    } catch (_: Exception) {
        openUrl(context, "https://play.google.com/store/apps/details?id=$packageName")
    }
}

private fun shareApp(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, Config.shareText)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.about_share_chooser)))
}

// --- Features data ---

private data class Feature(val icon: ImageVector, val titleRes: Int, val subtitleRes: Int)

private val features = listOf(
    Feature(Icons.Filled.Code, R.string.feature_kotlin_title, R.string.feature_kotlin_subtitle),
    Feature(Icons.Filled.DirectionsCar, R.string.feature_auto_title, R.string.feature_auto_subtitle),
    Feature(Icons.Filled.Palette, R.string.feature_ui_title, R.string.feature_ui_subtitle),
    Feature(Icons.Filled.MusicNote, R.string.feature_metadata_title, R.string.feature_metadata_subtitle),
    Feature(Icons.Filled.Lock, R.string.feature_lockscreen_title, R.string.feature_lockscreen_subtitle),
    Feature(Icons.Filled.Radio, R.string.feature_stations_title, R.string.feature_stations_subtitle),
    Feature(Icons.Filled.Verified, R.string.feature_setup_title, R.string.feature_setup_subtitle),
)
