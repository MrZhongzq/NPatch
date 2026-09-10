package org.lsposed.npatch.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.settings.SettingsCheckBox
import org.lsposed.npatch.ui.page.destinations.SelfStartBroadcastScreenDestination
import org.lsposed.npatch.ui.page.destinations.SelfStartServiceScreenDestination
import org.lsposed.npatch.ui.viewmodel.SelfStartAppViewModel

/**
 * Level 3 hub for one patched app. Keeps the single background-jobs toggle inline (one control),
 * and routes the broadcast (receiver) and service lists to their own level-4 pages so each gets
 * full scroll room. The lists' own counts/state live on those pages (authoritative there).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun SelfStartAppScreen(packageName: String, navigator: DestinationsNavigator) {
    val vm: SelfStartAppViewModel = viewModel()
    val ctx = LocalContext.current

    LaunchedEffect(packageName) {
        vm.load(ctx, packageName)
    }

    Scaffold(
        topBar = { SelfStartBackTopBar(packageName) { navigator.navigateUp() } }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            SettingsCheckBox(
                modifier = Modifier.clickable { vm.setSuppressJobs(ctx, !vm.suppressJobs) },
                checked = vm.suppressJobs,
                icon = Icons.Outlined.Bolt,
                title = stringResource(R.string.self_start_suppress_jobs),
                desc = stringResource(R.string.self_start_suppress_jobs_desc)
            )
            HorizontalDivider()
            SelfStartNavRow(
                title = stringResource(R.string.self_start_broadcast_control),
                subtitle = stringResource(R.string.self_start_broadcast_control_desc),
                onClick = {
                    navigator.navigate(SelfStartBroadcastScreenDestination(packageName = packageName))
                }
            )
            HorizontalDivider()
            SelfStartNavRow(
                title = stringResource(R.string.self_start_service_control),
                subtitle = stringResource(R.string.self_start_service_control_desc),
                onClick = {
                    navigator.navigate(SelfStartServiceScreenDestination(packageName = packageName))
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SelfStartNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Shared back top bar used by the hub and its level-4 sub-pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelfStartBackTopBar(title: String, onBackClick: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(
                onClick = onBackClick,
                content = { Icon(Icons.Outlined.ArrowBack, null) }
            )
        }
    )
}
