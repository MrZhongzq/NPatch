package org.lsposed.npatch.ui.page.manage

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.result.NavResult
import com.ramcosta.composedestinations.result.ResultRecipient
import kotlinx.coroutines.launch
import org.lsposed.npatch.R
import org.lsposed.npatch.BuildConfig
import org.lsposed.npatch.config.ConfigManager
import org.lsposed.npatch.config.Configs
import org.lsposed.npatch.database.entity.Module
import org.lsposed.npatch.lspApp
import org.lsposed.npatch.share.LSPConfig
import org.lsposed.npatch.util.Lv4Compat
import org.lsposed.npatch.ui.component.AnywhereDropdown
import org.lsposed.npatch.ui.component.AppItem
import org.lsposed.npatch.ui.component.LoadingDialog
import org.lsposed.npatch.ui.page.ACTION_APPLIST
import org.lsposed.npatch.ui.page.ACTION_STORAGE
import org.lsposed.npatch.ui.page.SelectAppsResult
import org.lsposed.npatch.ui.page.destinations.NewPatchScreenDestination
import org.lsposed.npatch.ui.page.destinations.SelectAppsScreenDestination
import org.lsposed.npatch.ui.util.LocalSnackbarHost
import org.lsposed.npatch.ui.viewmodel.manage.AppManageViewModel
import org.lsposed.npatch.ui.viewstate.ProcessingState
import nkbe.util.NPackageManager
import nkbe.util.ShizukuApi
import java.io.IOException

private const val TAG = "AppManagePage"

@Composable
fun AppManageBody(
    navigator: DestinationsNavigator,
    resultRecipient: ResultRecipient<SelectAppsScreenDestination, SelectAppsResult>
) {
    val viewModel = viewModel<AppManageViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    var scopeApp by rememberSaveable { mutableStateOf("") }
    var afterCheckManager by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    resultRecipient.onNavResult {
        if (it is NavResult.Value) {
            scope.launch {
                val result = it.value as SelectAppsResult.MultipleApps
                ConfigManager.getModulesForApp(scopeApp).forEach {
                    ConfigManager.deactivateModule(scopeApp, it)
                }
                result.selected.forEach {
                    Log.d(TAG, "Activate ${it.app.packageName} for $scopeApp")
                    ConfigManager.activateModule(scopeApp, Module(it.app.packageName, it.app.sourceDir))
                }
            }
        }
    }

    run {
        val updateState = viewModel.updateLoaderState
        if (updateState !is ProcessingState.Idle) {
            val done = updateState as? ProcessingState.Done
            val updateSuccessfully = stringResource(R.string.manage_update_loader_successfully)
            val updateFailed = stringResource(R.string.manage_update_loader_failed)
            Dialog(
                onDismissRequest = {
                    if (done != null) viewModel.dispatch(AppManageViewModel.ViewAction.ClearUpdateLoaderResult)
                },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false
                )
            ) {
                // Block back while patching so the user can't leave mid-operation.
                if (done == null) BackHandler {}
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.manage_update_loader),
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Serif,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        ProvideTextStyle(
                            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        ) {
                            val scrollState = rememberLazyListState()
                            LazyColumn(
                                state = scrollState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                items(viewModel.updateLogs) {
                                    when (it.first) {
                                        Log.ERROR -> Text(it.second, color = MaterialTheme.colorScheme.error)
                                        else -> Text(it.second)
                                    }
                                }
                            }
                            LaunchedEffect(viewModel.updateLogs.size) {
                                if (viewModel.updateLogs.isNotEmpty()) {
                                    scrollState.animateScrollToItem(viewModel.updateLogs.size - 1)
                                }
                            }
                        }
                        if (done == null) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                            )
                        } else {
                            val patchSucceeded = done.result.isSuccess
                            LaunchedEffect(Unit) {
                                done.result.onSuccess {
                                    snackbarHost.showSnackbar(updateSuccessfully)
                                }.onFailure {
                                    snackbarHost.showSnackbar(updateFailed)
                                }
                            }
                            val installHint = viewModel.installHint
                            if (patchSucceeded && installHint != AppManageViewModel.InstallHint.NONE) {
                                Text(
                                    text = when (installHint) {
                                        AppManageViewModel.InstallHint.SIGNATURE_MISMATCH ->
                                            stringResource(R.string.manage_install_sig_mismatch)
                                        AppManageViewModel.InstallHint.NEED_SHIZUKU_SPLIT ->
                                            stringResource(R.string.manage_install_need_shizuku)
                                        else -> ""
                                    },
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            Row(Modifier.padding(top = 12.dp)) {
                                if (patchSucceeded) {
                                    if (installHint == AppManageViewModel.InstallHint.SIGNATURE_MISMATCH) {
                                        // Data-preserving update is impossible (different key) —
                                        // offer the uninstall-then-install path instead.
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            onClick = { viewModel.dispatch(AppManageViewModel.ViewAction.InstallUpdatedForce) },
                                            content = { Text(stringResource(R.string.manage_uninstall_and_install)) }
                                        )
                                    } else {
                                        // Normal install (also the retry after granting Shizuku
                                        // for split apps, or if the installer dialog didn't show).
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            onClick = { viewModel.dispatch(AppManageViewModel.ViewAction.InstallUpdated) },
                                            content = { Text(stringResource(R.string.install)) }
                                        )
                                    }
                                    Spacer(Modifier.weight(0.2f))
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.dispatch(AppManageViewModel.ViewAction.ClearUpdateLoaderResult) },
                                        content = { Text(stringResource(R.string.patch_return)) }
                                    )
                                } else {
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.dispatch(AppManageViewModel.ViewAction.ClearUpdateLoaderResult) },
                                        content = { Text(stringResource(R.string.patch_return)) }
                                    )
                                    Spacer(Modifier.weight(0.2f))
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(
                                                ClipData.newPlainText(
                                                    "NPatch",
                                                    viewModel.updateLogs.joinToString("\n") { line -> line.second }
                                                )
                                            )
                                        },
                                        content = { Text(stringResource(R.string.copy_error)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    when (viewModel.optimizeState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> LoadingDialog()
        is ProcessingState.Done -> {
            val it = viewModel.optimizeState as ProcessingState.Done
            val optimizeSucceed = stringResource(R.string.manage_optimize_successfully)
            val optimizeFailed = stringResource(R.string.manage_optimize_failed)
            LaunchedEffect(Unit) {
                snackbarHost.showSnackbar(if (it.result) optimizeSucceed else optimizeFailed)
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearOptimizeResult)
            }
        }
    }
    when (viewModel.restoreState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> LoadingDialog()
        is ProcessingState.Done -> {
            val done = viewModel.restoreState as ProcessingState.Done
            val restoreOk = stringResource(R.string.manage_restore_triggered)
            val restoreFail = stringResource(R.string.manage_restore_failed)
            LaunchedEffect(Unit) {
                snackbarHost.showSnackbar(if (done.result) restoreOk else restoreFail)
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearRestoreResult)
            }
        }
    }
    // 下拉刷新
    SwipeRefresh(
        state = rememberSwipeRefreshState(viewModel.isRefreshing),
        onRefresh = { viewModel.dispatch(AppManageViewModel.ViewAction.Refresh) },
        modifier = Modifier.fillMaxSize()
    ) {
        if (viewModel.appList.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    modifier = Modifier.align(Alignment.Center),
                    text = run {
                        if (NPackageManager.appList.isEmpty()) stringResource(R.string.manage_loading)
                        else stringResource(R.string.manage_no_apps)
                    },
                    fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(
                    items = viewModel.appList,
                    key = { it.first.app.packageName }
                ) { (appInfo, patchConfig) ->
                    // A patch baked by a different NPatch version than the currently
                    // installed manager may be incompatible after a manager upgrade — the
                    // manager-mode loader/metaloader protocol can change between versions
                    // (see the libnpatch.so load break). There is no reliable "rolling"
                    // exemption, so flag ANY version/manager mismatch and let the user
                    // re-patch (which preserves app data) to re-sync with this manager.
                    val isOutdated = patchConfig.lspConfig.VERSION_CODE != LSPConfig.instance.VERSION_CODE ||
                            patchConfig.managerPackageName != BuildConfig.APPLICATION_ID
                    // Patched at lv4 but the app is known to ship its own seccomp sandbox — lv4
                    // usually crashes it. Every lv4 app also gets a "re-sign with lv3" menu item.
                    val isLv4 = patchConfig.sigBypassLevel == 4
                    val isLv4Incompatible = isLv4 &&
                            Lv4Compat.isIncompatibleWithLv4(appInfo.app.packageName)
                    var expanded by remember { mutableStateOf(false) }

                    AnywhereDropdown(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        onClick = { expanded = true },
                        onLongClick = { expanded = true },
                        surface = {
                            AppItem(
                                icon = NPackageManager.getIcon(appInfo),
                                label = appInfo.label,
                                packageName = appInfo.app.packageName,
                                additionalContent = {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val patchText = if (patchConfig.useManager) {
                                                stringResource(R.string.patch_local)
                                            } else {
                                                stringResource(R.string.patch_integrated)
                                            }
                                            val patchColor = if (patchConfig.useManager) {
                                                MaterialTheme.colorScheme.secondary
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            }
                                            val versionText = patchConfig.lspConfig.VERSION_CODE.toString()

                                            Text(
                                                text = "$patchText  $versionText",
                                                color = patchColor,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = FontFamily.Serif,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            if (!patchConfig.installerSource.isNullOrEmpty() && patchConfig.installerSource != "Unknown") {
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = patchConfig.installerSource,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                        // 版本/管理器不一致的不兼容警告(点整项后可在菜单里"重新修补")
                                        if (isOutdated) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                with(LocalDensity.current) {
                                                    val size = MaterialTheme.typography.labelSmall.fontSize * 1.2
                                                    Icon(
                                                        Icons.Filled.Warning,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(size.toDp())
                                                    )
                                                }
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    text = stringResource(R.string.manage_incompatible_warning),
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                        // lv4-incompatible (own seccomp sandbox) warning — can
                                        // coexist with the version warning above.
                                        if (isLv4Incompatible) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                with(LocalDensity.current) {
                                                    val size = MaterialTheme.typography.labelSmall.fontSize * 1.2
                                                    Icon(
                                                        Icons.Filled.Warning,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(size.toDp())
                                                    )
                                                }
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    text = stringResource(R.string.manage_lv4_incompatible_warning),
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = appInfo.label, color = MaterialTheme.colorScheme.primary) },
                            onClick = {}, enabled = false
                        )
                        val shizukuUnavailable = stringResource(R.string.shizuku_unavailable)
                        // Re-patch is available for EVERY patched app, so the user can
                        // one-click re-patch (data-preserving) to re-sync any app with the
                        // current manager after an upgrade — not only ones we flagged.
                        if (isLv4) {
                            // Available on EVERY lv4 app so the user can self-downgrade any app
                            // they discover to be seccomp-incompatible, not just whitelisted ones.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_resign_lv3)) },
                                onClick = {
                                    expanded = false
                                    scope.launch {
                                        viewModel.dispatch(AppManageViewModel.ViewAction.ResignLv3(appInfo, patchConfig))
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.manage_update_loader)) },
                            onClick = {
                                expanded = false
                                scope.launch {
                                    viewModel.dispatch(AppManageViewModel.ViewAction.UpdateLoader(appInfo, patchConfig))
                                }
                            }
                        )
                        // Convert between integrated (集成) and local/manager (本地) mode by
                        // re-patching with useManager toggled (data-preserving update install).
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (patchConfig.useManager) R.string.manage_convert_to_integrated
                                        else R.string.manage_convert_to_local
                                    )
                                )
                            },
                            onClick = {
                                expanded = false
                                scope.launch {
                                    viewModel.dispatch(AppManageViewModel.ViewAction.ConvertMode(appInfo, patchConfig))
                                }
                            }
                        )
                        if (patchConfig.useManager) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_module_scope)) },
                                onClick = {
                                    expanded = false
                                    scope.launch {
                                        scopeApp = appInfo.app.packageName
                                        val activated = ConfigManager.getModulesForApp(scopeApp).map { it.pkgName }.toSet()
                                        val initialSelected = NPackageManager.appList.mapNotNullTo(ArrayList()) {
                                            if (activated.contains(it.app.packageName)) it.app.packageName else null
                                        }
                                        navigator.navigate(SelectAppsScreenDestination(true, initialSelected))
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.manage_optimize)) },
                            onClick = {
                                expanded = false
                                scope.launch {
                                    if (!ShizukuApi.isPermissionGranted) {
                                        snackbarHost.showSnackbar(shizukuUnavailable)
                                    } else {
                                        viewModel.dispatch(AppManageViewModel.ViewAction.PerformOptimize(appInfo))
                                    }
                                }
                            }
                        )
                        if (patchConfig.mirrorMode) {
                            val restoreNeedShizuku = stringResource(R.string.manage_restore_need_shizuku)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_restore_now)) },
                                onClick = {
                                    expanded = false
                                    scope.launch {
                                        if (!ShizukuApi.isPermissionGranted) {
                                            snackbarHost.showSnackbar(restoreNeedShizuku)
                                        } else {
                                            viewModel.dispatch(
                                                AppManageViewModel.ViewAction.RestoreNow(appInfo.app.packageName)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        val uninstallSuccessfully = stringResource(R.string.manage_uninstall_successfully)
                        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                            if (result.resultCode == Activity.RESULT_OK) {
                                scope.launch {
                                    snackbarHost.showSnackbar(uninstallSuccessfully)
                                    viewModel.dispatch(AppManageViewModel.ViewAction.Refresh)
                                }
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.uninstall)) },
                            onClick = {
                                expanded = false
                                val intent = Intent(Intent.ACTION_DELETE).apply {
                                    data = Uri.parse("package:${appInfo.app.packageName}")
                                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                                }
                                launcher.launch(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppManageFab(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var shouldSelectDirectory by remember { mutableStateOf(false) }
    var showNewPatchDialog by remember { mutableStateOf(false) }

    val errorText = stringResource(R.string.patch_select_dir_error)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        try {
            if (it.resultCode == Activity.RESULT_CANCELED) return@rememberLauncherForActivityResult
            val uri = it.data?.data ?: throw IOException("No data")
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            Configs.storageDirectory = uri.toString()
            Log.i(TAG, "Storage directory: ${uri.path}")
            showNewPatchDialog = true
        } catch (e: Exception) {
            Log.e(TAG, "Error when requesting saving directory", e)
            scope.launch { snackbarHost.showSnackbar(errorText) }
        }
    }

    if (shouldSelectDirectory) {
        AlertDialog(
            onDismissRequest = { shouldSelectDirectory = false },
            confirmButton = {
                TextButton(
                    content = { Text(stringResource(android.R.string.ok)) },
                    onClick = {
                        launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                        shouldSelectDirectory = false
                    }
                )
            },
            dismissButton = {
                TextButton(
                    content = { Text(stringResource(android.R.string.cancel)) },
                    onClick = { shouldSelectDirectory = false }
                )
            },
            title = {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.patch_select_dir_title),
                    textAlign = TextAlign.Center
                )
            },
            text = { Text(stringResource(R.string.patch_select_dir_text)) }
        )
    }

    if (showNewPatchDialog) {
        AlertDialog(
            onDismissRequest = { showNewPatchDialog = false },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    content = { Text(stringResource(android.R.string.cancel)) },
                    onClick = { showNewPatchDialog = false }
                )
            },
            title = {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.screen_new_patch),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                        onClick = {
                            navigator.navigate(NewPatchScreenDestination(id = ACTION_STORAGE))
                            showNewPatchDialog = false
                        }
                    ) {
                        Text(
                            modifier = Modifier.padding(vertical = 8.dp),
                            text = stringResource(R.string.patch_from_storage),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                        onClick = {
                            navigator.navigate(NewPatchScreenDestination(id = ACTION_APPLIST))
                            showNewPatchDialog = false
                        }
                    ) {
                        Text(
                            modifier = Modifier.padding(vertical = 8.dp),
                            text = stringResource(R.string.patch_from_applist),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        )
    }

    FloatingActionButton(
        content = { Icon(Icons.Filled.Add, stringResource(R.string.add)) },
        onClick = {
            val uri = Configs.storageDirectory?.toUri()
            if (uri == null) {
                shouldSelectDirectory = true
            } else {
                runCatching {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    if (DocumentFile.fromTreeUri(context, uri)?.exists() == false) throw IOException("Storage directory was deleted")
                }.onSuccess {
                    showNewPatchDialog = true
                }.onFailure {
                    Log.w(TAG, "Failed to take persistable permission for saved uri", it)
                    Configs.storageDirectory = null
                    shouldSelectDirectory = true
                }
            }
        }
    )
}
