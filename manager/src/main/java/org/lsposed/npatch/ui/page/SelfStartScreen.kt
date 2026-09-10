package org.lsposed.npatch.ui.page

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.AppItem
import org.lsposed.npatch.ui.component.CenterTopBar
import org.lsposed.npatch.ui.page.destinations.SelfStartAppScreenDestination
import nkbe.util.NPackageManager

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Destination
@Composable
fun SelfStartScreen(navigator: DestinationsNavigator) {
    LaunchedEffect(Unit) {
        NPackageManager.fetchAppList()
    }

    val patchedAppList = NPackageManager.appList.filter { NPackageManager.isNPatchPatched(it) }

    Scaffold(
        topBar = { CenterTopBar(stringResource(BottomBarDestination.SelfStart.label)) }
    ) { innerPadding ->
        if (patchedAppList.isEmpty()) {
            Box(
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                Text(
                    modifier = Modifier.align(Alignment.Center),
                    text = stringResource(R.string.self_start_empty),
                    fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                items(
                    items = patchedAppList,
                    key = { it.app.packageName }
                ) { info ->
                    AppItem(
                        modifier = Modifier
                            .animateItem(spring(stiffness = Spring.StiffnessLow))
                            .clickable {
                                navigator.navigate(SelfStartAppScreenDestination(packageName = info.app.packageName))
                            },
                        icon = NPackageManager.getIcon(info),
                        label = info.label,
                        packageName = info.app.packageName
                    )
                }
            }
        }
    }
}
