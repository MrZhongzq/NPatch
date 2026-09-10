package org.lsposed.npatch.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.settings.SettingsCheckBox
import org.lsposed.npatch.ui.viewmodel.SelfStartAppViewModel

// isAutoStart highlight accent (brief: error color or purple accent) — purple reads consistently
// in both light and dark themes without leaning on MaterialTheme.colorScheme.error's alarm connotation.
private val AUTO_START_ACCENT = Color(0xFF7E57C2)

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
        topBar = { SelfStartAppTopBar(packageName) { navigator.navigateUp() } }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            SettingsCheckBox(
                modifier = Modifier.clickable { vm.setMaster(ctx, !vm.master) },
                checked = vm.master,
                icon = Icons.Outlined.Bolt,
                title = stringResource(R.string.self_start_master),
                desc = stringResource(R.string.self_start_master_desc)
            )
            HorizontalDivider()
            SettingsCheckBox(
                modifier = Modifier.clickable { vm.setSuppressJobs(ctx, !vm.suppressJobs) },
                checked = vm.suppressJobs,
                title = stringResource(R.string.self_start_suppress_jobs),
                desc = stringResource(R.string.self_start_suppress_jobs_desc)
            )
            HorizontalDivider()
            // 单一 LazyColumn 承载 receiver + service 两个分节,避免嵌套滚动容器崩溃。
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = stringResource(R.string.self_start_receivers),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
                items(
                    items = vm.receivers,
                    key = { "recv:" + it.className }
                ) { row ->
                    ReceiverRowItem(
                        row = row,
                        masterEnabled = vm.master,
                        onToggle = { enabled -> vm.toggleReceiver(ctx, row.className, enabled) }
                    )
                }
                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.self_start_services),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    Text(
                        text = stringResource(R.string.self_start_services_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
                items(
                    items = vm.services,
                    key = { "svc:" + it.className }
                ) { row ->
                    ServiceRowItem(
                        row = row,
                        onToggle = { enabled -> vm.toggleService(ctx, row.className, enabled) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelfStartAppTopBar(packageName: String, onBackClick: () -> Unit) {
    TopAppBar(
        title = { Text(packageName) },
        navigationIcon = {
            IconButton(
                onClick = onBackClick,
                content = { Icon(Icons.Outlined.ArrowBack, null) }
            )
        }
    )
}

@Composable
private fun ReceiverRowItem(
    row: SelfStartAppViewModel.ReceiverRow,
    masterEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val accent = if (row.isAutoStart) AUTO_START_ACCENT else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = row.className.substringAfterLast('.'),
                        style = MaterialTheme.typography.bodyLarge,
                        color = accent
                    )
                    if (row.isAutoStart) {
                        Text(
                            text = stringResource(R.string.self_start_autostart_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent
                        )
                    }
                }
                Text(
                    text = row.className,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (row.vendorLabel.isNotEmpty()) {
                    Text(
                        text = row.vendorLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Switch(
                checked = row.enabled,
                enabled = masterEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun ServiceRowItem(
    row: SelfStartAppViewModel.ServiceRow,
    onToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = row.className.substringAfterLast('.'),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = row.className,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (row.vendorLabel.isNotEmpty()) {
                    Text(
                        text = row.vendorLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}
