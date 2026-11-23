package com.jhaiian.lucky.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.BuildConfig
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.LuckyEngine
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SettingsRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val DISCORD_URL = "https://discord.gg/6sPXKt3h49"
private const val REDDIT_URL = "https://www.reddit.com/r/LuckyVideoPlayer"

private class MoreApp(
    @StringRes val name: Int,
    @StringRes val summary: Int,
    val url: String
)

private val MoreApps = listOf(
    MoreApp(R.string.settings_about_app_clint_gecko, R.string.settings_about_app_clint_gecko_summary, "https://github.com/jhaiian/ClintGecko"),
    MoreApp(R.string.settings_about_app_kate, R.string.settings_about_app_kate_summary, "https://github.com/jhaiian/Kate"),
    MoreApp(R.string.settings_about_app_clint_browser, R.string.settings_about_app_clint_browser_summary, "https://github.com/jhaiian/ClintBrowser")
)

private class UsedLibrary(
    @StringRes val name: Int,
    @StringRes val usage: Int,
    @StringRes val license: Int,
    val url: String
)

private val UsedLibraries = listOf(
    UsedLibrary(R.string.engine_label_ffmpeg, R.string.settings_about_lib_ffmpeg_usage, R.string.settings_license_lgpl, "https://ffmpeg.org"),
    UsedLibrary(R.string.engine_label_dav1d, R.string.settings_about_lib_dav1d_usage, R.string.settings_license_bsd2, "https://code.videolan.org/videolan/dav1d"),
    UsedLibrary(R.string.settings_about_lib_jni, R.string.settings_about_lib_jni_usage, R.string.settings_license_mit_apache, "https://github.com/jni-rs/jni-rs"),
    UsedLibrary(R.string.settings_about_lib_ash, R.string.settings_about_lib_ash_usage, R.string.settings_license_mit_apache, "https://github.com/ash-rs/ash"),
    UsedLibrary(R.string.settings_about_lib_glow, R.string.settings_about_lib_glow_usage, R.string.settings_license_mit_apache, "https://github.com/grovesNL/glow"),
    UsedLibrary(R.string.settings_about_lib_compose, R.string.settings_about_lib_compose_usage, R.string.settings_license_apache, "https://developer.android.com/compose")
)

@Composable
internal fun AboutSettings(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val engine = remember { runCatching { LuckyEngine.engineInfo() }.getOrNull() }
    val notAvailable = stringResource(R.string.value_not_available)

    val pageTitle = stringResource(R.string.settings_about)
    val versionTitle = stringResource(R.string.settings_about_section_version)
    val engineTitle = stringResource(R.string.engine_section_title)
    val licenseTitle = stringResource(R.string.settings_about_section_license)
    val librariesTitle = stringResource(R.string.settings_about_section_libraries)
    val communityTitle = stringResource(R.string.settings_about_section_community)
    val moreAppsTitle = stringResource(R.string.settings_about_section_more_apps)

    SubPage(title = pageTitle, onBack = onBack, modifier = modifier) {
        item(key = "about_header") { AboutHeader() }
        section(key = "about_version", title = versionTitle) {
            Column(Modifier.padding(vertical = 8.dp)) {
                InfoLine(stringResource(R.string.settings_about_version), BuildConfig.VERSION_NAME)
                InfoLine(stringResource(R.string.settings_about_build), BuildConfig.VERSION_CODE.toString())
                InfoLine(stringResource(R.string.settings_about_android), Build.VERSION.RELEASE.orEmpty().ifBlank { notAvailable })
                InfoLine(stringResource(R.string.settings_about_api), Build.VERSION.SDK_INT.toString())
                InfoLine(stringResource(R.string.engine_label_abi), Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { notAvailable })
                InfoLine(stringResource(R.string.engine_label_version), engine?.engine.orEmpty().ifBlank { notAvailable })
            }
        }
        section(key = "about_engine", title = engineTitle) {
            if (engine == null) {
                Text(
                    stringResource(R.string.engine_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Column(Modifier.padding(vertical = 8.dp)) {
                    InfoLine(stringResource(R.string.engine_label_ffmpeg), engine.ffmpeg.ifBlank { notAvailable })
                    InfoLine(stringResource(R.string.engine_label_dav1d), engine.dav1d.ifBlank { notAvailable })
                }
            }
        }
        section(key = "about_license", title = licenseTitle) {
            Text(
                stringResource(R.string.settings_about_license_text),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            )
            RowDivider(startInset = 16.dp)
            SettingsRow(
                icon = Icons.Rounded.Gavel,
                title = stringResource(R.string.settings_about_license_link),
                subtitle = stringResource(R.string.settings_about_license_link_summary),
                onClick = { openExternalLink(context, GPL_URL) },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = colors.secondaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }
        section(key = "about_community", title = communityTitle) {
            Text(
                stringResource(R.string.settings_about_community_text),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            )
            RowDivider(startInset = 16.dp)
            CommunityRow(
                icon = Icons.Rounded.Forum,
                title = stringResource(R.string.settings_about_community_discord),
                link = stringResource(R.string.settings_about_community_discord_url),
                onClick = { openExternalLink(context, DISCORD_URL) }
            )
            RowDivider(startInset = 16.dp)
            CommunityRow(
                icon = Icons.Rounded.Groups,
                title = stringResource(R.string.settings_about_community_reddit),
                link = stringResource(R.string.settings_about_community_reddit_url),
                onClick = { openExternalLink(context, REDDIT_URL) }
            )
        }
        section(key = "about_more_apps", title = moreAppsTitle) {
            MoreApps.forEachIndexed { index, app ->
                if (index > 0) RowDivider(startInset = 16.dp)
                MoreAppRow(app) { openExternalLink(context, app.url) }
            }
        }
        section(key = "about_libraries", title = librariesTitle) {
            UsedLibraries.forEachIndexed { index, library ->
                if (index > 0) RowDivider(startInset = 16.dp)
                LibraryRow(library) { openExternalLink(context, library.url) }
            }
        }
    }
}

@Composable
private fun AboutHeader() {
    val colors = LocalLuckyColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)
    ) {
        Box(
            Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(24.dp))
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_background),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.5f)
            )
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.5f)
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Text(
                stringResource(R.string.settings_about_tagline),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun CommunityRow(icon: ImageVector, title: String, link: String, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = link,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier.size(20.dp)
            )
        }
    )
}

@Composable
private fun MoreAppRow(app: MoreApp, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(app.name),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(18.dp)
            )
        }
        Text(
            stringResource(app.summary),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryText,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun LibraryRow(library: UsedLibrary, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(library.name),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(library.license),
                style = MaterialTheme.typography.labelMedium,
                color = colors.secondaryText,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Text(
            stringResource(library.usage),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryText,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}
