package org.lsposed.npatch.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.lsposed.npatch.manager.SelfStartConfigStore
import org.lsposed.npatch.share.SelfStartDefaults
import org.lsposed.npatch.share.SelfStartVendorLabels

class SelfStartAppViewModel : ViewModel() {

    data class ReceiverRow(
        val className: String,
        val vendorLabel: String,
        val isAutoStart: Boolean,   // 注册了自启类 action → UI 高亮
        val enabled: Boolean        // true=开(不拦), false=关(拦)
    )

    data class ServiceRow(
        val className: String,
        val vendorLabel: String,
        val enabled: Boolean   // true=开(不压), false=关(压)
    )

    var master by mutableStateOf(false)
        private set
    var receivers by mutableStateOf(listOf<ReceiverRow>())
        private set
    var suppressJobs by mutableStateOf(false)
        private set
    var services by mutableStateOf(listOf<ServiceRow>())
        private set

    private lateinit var pkg: String

    fun load(ctx: Context, packageName: String) {
        pkg = packageName
        val pm = ctx.packageManager
        val cfg = SelfStartConfigStore.get(ctx, packageName)
        master = cfg.master

        // 全部静态 receiver。MATCH_DISABLED_COMPONENTS: 纳入默认禁用的组件——WorkManager 的
        // ConstraintProxy 等在 manifest 里 enabled=false、按需才动态启用,不带此 flag 会被 PM 过滤掉,
        // 导致 tab 里看不到(而它们仍是有效的自启向量)。
        val matchFlags = PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS
        val all = try {
            pm.getPackageInfo(packageName, matchFlags)
                .receivers?.map { it.name } ?: emptyList()
        } catch (t: Throwable) { emptyList() }

        // 自启相关: 对自启 action 集 queryBroadcastReceivers,收集本包命中的 receiver 类
        // 同样带 MATCH_DISABLED_COMPONENTS,让默认禁用的自启 receiver 也能被高亮标出。
        val autoStart = HashSet<String>()
        for (action in SelfStartDefaults.DEFAULT_BLACKLIST) {
            try {
                val ris = pm.queryBroadcastReceivers(Intent(action), PackageManager.MATCH_DISABLED_COMPONENTS)
                for (ri in ris) {
                    val ai = ri.activityInfo ?: continue
                    if (ai.packageName == packageName) autoStart.add(ai.name)
                }
            } catch (t: Throwable) { /* ignore this action */ }
        }

        receivers = all.sortedWith(compareByDescending<String> { it in autoStart }.thenBy { it })
            .map { name ->
                ReceiverRow(
                    className = name,
                    vendorLabel = SelfStartVendorLabels.labelFor(name),
                    isAutoStart = name in autoStart,
                    enabled = name !in cfg.disabled
                )
            }

        suppressJobs = cfg.suppressJobs
        val allSvc = try {
            pm.getPackageInfo(packageName,
                PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS)
                .services?.map { it.name } ?: emptyList()
        } catch (t: Throwable) { emptyList() }
        services = allSvc.sorted().map { name ->
            ServiceRow(name, SelfStartVendorLabels.labelFor(name), name !in cfg.disabledServices)
        }
    }

    fun setMaster(ctx: Context, value: Boolean) {
        master = value
        SelfStartConfigStore.setMaster(ctx, pkg, value)
    }

    fun toggleReceiver(ctx: Context, className: String, enabled: Boolean) {
        SelfStartConfigStore.setReceiver(ctx, pkg, className, enabled)
        receivers = receivers.map { if (it.className == className) it.copy(enabled = enabled) else it }
    }

    fun setSuppressJobs(ctx: Context, value: Boolean) {
        suppressJobs = value
        SelfStartConfigStore.setSuppressJobs(ctx, pkg, value)
    }

    fun toggleService(ctx: Context, className: String, enabled: Boolean) {
        SelfStartConfigStore.setService(ctx, pkg, className, enabled)
        services = services.map { if (it.className == className) it.copy(enabled = enabled) else it }
    }
}
