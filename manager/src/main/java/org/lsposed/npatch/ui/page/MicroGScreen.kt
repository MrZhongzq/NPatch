package org.lsposed.npatch.ui.page

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import org.lsposed.npatch.R
import org.lsposed.npatch.share.Constants
import org.lsposed.npatch.ui.component.CenterTopBar

private const val TAG = "MicroGScreen"

@Destination
@Composable
fun MicroGScreen() {
    val context = LocalContext.current

    var isInstalled by remember { mutableStateOf(false) }
    var installedPackage by remember { mutableStateOf<String?>(null) }
    var accountName by remember { mutableStateOf<String?>(null) }

    val gmsPackages = listOf(
        Constants.NPATCH_GMS_PACKAGE_NAME,
        "app.revanced.android.gms",
        "org.microg.gms"
    )

    LaunchedEffect(Unit) {
        for (pkg in gmsPackages) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                isInstalled = true
                installedPackage = pkg
                break
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        if (isInstalled) {
            accountName = getSignedInAccount(context)
        }
    }

    Scaffold(
        topBar = { CenterTopBar(stringResource(BottomBarDestination.MicroG.label)) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status icon
            Icon(
                imageVector = if (isInstalled) Icons.Outlined.Cloud else Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = if (isInstalled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Description
            Text(
                text = stringResource(R.string.microg_description),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            if (!isInstalled) {
                // Not installed state
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.microg_not_installed),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            // Open ReVanced GmsCore download page
                            try {
                                val intent = Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/ReVanced/GmsCore/releases/latest"))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to open download page", e)
                            }
                        }) {
                            Text(stringResource(R.string.microg_download_revanced))
                        }
                    }
                }
            } else {
                // Installed state
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(8.dp))

                        if (accountName != null) {
                            // Signed in
                            Text(
                                text = stringResource(R.string.microg_account_signed_in, accountName!!),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = {
                                // Open MicroG account management
                                try {
                                    val intent = Intent().apply {
                                        setClassName(
                                            installedPackage!!,
                                            "org.microg.gms.ui.AccountSettingsActivity"
                                        )
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to open MicroG accounts", e)
                                }
                            }) {
                                Icon(Icons.Outlined.Logout, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.microg_sign_out))
                            }
                        } else {
                            // Not signed in
                            Text(
                                text = stringResource(R.string.microg_account_not_signed_in),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                // Open MicroG login
                                try {
                                    val intent = Intent().apply {
                                        setClassName(
                                            installedPackage!!,
                                            "org.microg.gms.auth.login.LoginActivity"
                                        )
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to open MicroG login", e)
                                }
                            }) {
                                Icon(Icons.Outlined.Login, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.microg_sign_in))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getSignedInAccount(context: android.content.Context): String? {
    return try {
        val am = android.accounts.AccountManager.get(context)
        // Check multiple account types - MicroG variants use different types
        val accountTypes = listOf(
            "app.revanced",    // ReVanced GmsCore
            "org.microg",      // Original MicroG
            "com.google"       // Real GMS or NPatch GMS
        )
        for (type in accountTypes) {
            val accounts = am.getAccountsByType(type)
            if (accounts.isNotEmpty()) return accounts[0].name
        }
        null
    } catch (e: Exception) {
        Log.w(TAG, "Failed to query accounts", e)
        null
    }
}
