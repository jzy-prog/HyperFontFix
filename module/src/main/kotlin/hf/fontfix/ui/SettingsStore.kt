package hf.fontfix.ui

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HfKeys {
    const val GROUP = "hyperfontfix"
    const val ENABLE = "enable"
    const val CLOCK = "clock"
    const val NETWORK = "network"
    const val CARRIER = "carrier"
    const val BATTERY = "battery"
    const val MARK = "mark"
    const val HOLLOW = "hollow"
    const val DATE = "date"
    const val ISLAND = "island"
    const val OTHER = "other"
    const val SPEED = "speed"
    const val ALARM = "alarm"
    const val LOCKDATE = "lockdate"
    const val SINGLECARRIER = "singlecarrier"
    const val FAMILY = "family"
    const val G1 = "g1"
    const val GLOBAL = "global"
    const val ALL = "all"
    const val AOD = "aod"
    const val LOG = "log"
}

data class HfConfig(
    val enable: Boolean = true,
    val clock: Int = 500,
    val network: Int = 660,
    val carrier: Int = 430,
    val battery: Int = 500,
    val mark: Int = 600,
    val hollow: Int = 620,
    val date: Int = 0,
    val island: Int = 0,
    val other: Int = 0,
    val speed: Int = 900,
    val alarm: Int = 500,
    val lockdate: Int = 400,
    val singlecarrier: Boolean = false,
    val family: Boolean = false,
    val g1: Boolean = false,
    val global: Int = 460,
    val all: Int = 0,
    val aod: Boolean = false,
    val log: Int = 0,
)

data class HfBridge(
    val service: XposedService,
    val prefs: SharedPreferences,
    val frameworkName: String,
    val frameworkVersion: String,
    val apiVersion: Int,
)

class SettingsStore {

    private val _bridge = MutableStateFlow<HfBridge?>(null)
    val bridge: StateFlow<HfBridge?> = _bridge.asStateFlow()

    @Volatile
    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                try {
                    val prefs = service.getRemotePreferences(HfKeys.GROUP)
                    _bridge.value = HfBridge(
                        service = service,
                        prefs = prefs,
                        frameworkName = runCatching { service.frameworkName }.getOrDefault("Xposed"),
                        frameworkVersion = runCatching { service.frameworkVersion }.getOrDefault("?"),
                        apiVersion = runCatching { service.apiVersion }.getOrDefault(0),
                    )
                    Log.i(TAG, "service bound: ${service.frameworkName} ${service.frameworkVersion}")
                } catch (t: Throwable) {
                    Log.w(TAG, "remote preferences unavailable", t)
                }
            }

            override fun onServiceDied(service: XposedService) {
                if (_bridge.value?.service === service) _bridge.value = null
            }
        })
    }

    fun read(context: Context): HfConfig {
        val prefs = _bridge.value?.prefs
        fun int(key: String, def: Int): Int = try {
            if (prefs != null && prefs.contains(key)) {
                prefs.getInt(key, def)
            } else {
                Settings.System.getInt(context.contentResolver, "hf_ff_$key", def)
            }
        } catch (t: Throwable) {
            def
        }

        fun flag(key: String, def: Boolean) = int(key, if (def) 1 else 0) != 0
        fun weight(key: String, def: Int) = int(key, def).coerceIn(0, 1000)

        return HfConfig(
            enable = flag(HfKeys.ENABLE, true),
            clock = weight(HfKeys.CLOCK, 500),
            network = weight(HfKeys.NETWORK, 660),
            carrier = weight(HfKeys.CARRIER, 430),
            battery = weight(HfKeys.BATTERY, 500),
            mark = weight(HfKeys.MARK, 600),
            hollow = weight(HfKeys.HOLLOW, 620),
            date = weight(HfKeys.DATE, 0),
            island = weight(HfKeys.ISLAND, 0),
            other = weight(HfKeys.OTHER, 0),
            speed = weight(HfKeys.SPEED, 900),
            alarm = weight(HfKeys.ALARM, 500),
            lockdate = weight(HfKeys.LOCKDATE, 400),
            singlecarrier = flag(HfKeys.SINGLECARRIER, false),
            family = flag(HfKeys.FAMILY, false),
            g1 = flag(HfKeys.G1, false),
            global = weight(HfKeys.GLOBAL, 460),
            all = weight(HfKeys.ALL, 0),
            aod = flag(HfKeys.AOD, false),
            log = int(HfKeys.LOG, 0).coerceIn(0, 2),
        )
    }

    fun putInt(key: String, value: Int) {
        _bridge.value?.prefs?.edit()?.putInt(key, value)?.apply()
    }

    fun putFlag(key: String, value: Boolean) = putInt(key, if (value) 1 else 0)

    fun reset() {
        val prefs = _bridge.value?.prefs ?: return
        val d = HfConfig()
        val values = mapOf(
            HfKeys.ENABLE to if (d.enable) 1 else 0,
            HfKeys.CLOCK to d.clock,
            HfKeys.NETWORK to d.network,
            HfKeys.CARRIER to d.carrier,
            HfKeys.BATTERY to d.battery,
            HfKeys.MARK to d.mark,
            HfKeys.HOLLOW to d.hollow,
            HfKeys.DATE to d.date,
            HfKeys.ISLAND to d.island,
            HfKeys.OTHER to d.other,
            HfKeys.SPEED to d.speed,
            HfKeys.ALARM to d.alarm,
            HfKeys.LOCKDATE to d.lockdate,
            HfKeys.SINGLECARRIER to if (d.singlecarrier) 1 else 0,
            HfKeys.FAMILY to if (d.family) 1 else 0,
            HfKeys.G1 to if (d.g1) 1 else 0,
            HfKeys.GLOBAL to d.global,
            HfKeys.ALL to d.all,
            HfKeys.AOD to if (d.aod) 1 else 0,
            HfKeys.LOG to d.log,
        )
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putInt(key, value) }
        editor.apply()
    }

    private companion object {
        const val TAG = "HfSettings"
    }
}
