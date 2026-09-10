package org.lsposed.npatch.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.viewmodel.SelfStartAppViewModel

/** Level 4B: service control for one app — a warning + per-service background toggles. */
@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun SelfStartServiceScreen(packageName: String, navigator: DestinationsNavigator) {
    val vm: SelfStartAppViewModel = viewModel()
    val ctx = LocalContext.current

    LaunchedEffect(packageName) {
        vm.load(ctx, packageName)
    }

    Scaffold(
        topBar = {
            SelfStartBackTopBar(stringResource(R.string.self_start_service_control)) { navigator.navigateUp() }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Text(
                text = stringResource(R.string.self_start_services_warn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(
                    items = vm.services,
                    key = { it.className }
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
