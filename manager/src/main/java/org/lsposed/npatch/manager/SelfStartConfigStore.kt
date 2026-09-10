package org.lsposed.npatch.manager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Per-app self-start config (v2). Persisted in a dedicated SharedPreferences, one JSON per package. */
object SelfStartConfigStore {
    private const val PREFS = "selfstart_config"

    data class AppCfg(
        val master: Boolean,
        val disabled: Set<String>,
        val suppressJobs: Boolean = false,
        val disabledServices: Set<String> = emptySet()
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, pkg: String): AppCfg {
        val raw = prefs(ctx).getString(pkg, null) ?: return AppCfg(false, emptySet())
        return try {
            val o = JSONObject(raw)
            AppCfg(
                master = o.optBoolean("master", false),
                disabled = o.optJSONArray("disabled").toStringSet(),
                suppressJobs = o.optBoolean("jobs", false),
                disabledServices = o.optJSONArray("disabledServices").toStringSet()
            )
        } catch (t: Throwable) {
            AppCfg(false, emptySet())
        }
    }

    private fun org.json.JSONArray?.toStringSet(): Set<String> {
        val a = this ?: return emptySet()
        val s = LinkedHashSet<String>()
        for (i in 0 until a.length()) s.add(a.getString(i))
        return s
    }

    private fun put(ctx: Context, pkg: String, cfg: AppCfg) {
        val o = JSONObject()
        o.put("master", cfg.master)
        o.put("disabled", JSONArray().apply { cfg.disabled.forEach { put(it) } })
        o.put("jobs", cfg.suppressJobs)
        o.put("disabledServices", JSONArray().apply { cfg.disabledServices.forEach { put(it) } })
        prefs(ctx).edit().putString(pkg, o.toString()).apply()
    }

    fun setMaster(ctx: Context, pkg: String, master: Boolean) {
        val cur = get(ctx, pkg); put(ctx, pkg, cur.copy(master = master))
    }

    fun setReceiver(ctx: Context, pkg: String, cls: String, enabled: Boolean) {
        val cur = get(ctx, pkg)
        val next = LinkedHashSet(cur.disabled)
        if (enabled) next.remove(cls) else next.add(cls)   // disabled set holds the OFF ones
        put(ctx, pkg, cur.copy(disabled = next))
    }

    fun setSuppressJobs(ctx: Context, pkg: String, value: Boolean) {
        val cur = get(ctx, pkg); put(ctx, pkg, cur.copy(suppressJobs = value))
    }

    fun setService(ctx: Context, pkg: String, cls: String, enabled: Boolean) {
        val cur = get(ctx, pkg)
        val next = LinkedHashSet(cur.disabledServices)
        if (enabled) next.remove(cls) else next.add(cls)   // disabledServices holds OFF ones
        put(ctx, pkg, cur.copy(disabledServices = next))
    }
}
