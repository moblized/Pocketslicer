package org.moblized.pocketslicer.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Swaps the launcher icon between the light and dark versions. */
object IconSwitcher {
    private const val LIGHT = "org.moblized.pocketslicer.LauncherLight"
    private const val DARK = "org.moblized.pocketslicer.LauncherDark"

    fun apply(context: Context, dark: Boolean) {
        val pm = context.packageManager
        val enable = ComponentName(context, if (dark) DARK else LIGHT)
        val disable = ComponentName(context, if (dark) LIGHT else DARK)
        if (isEnabled(pm, enable, defaultEnabled = !dark) && !isEnabled(pm, disable, defaultEnabled = dark)) {
            return
        }
        pm.setComponentEnabledSetting(
            enable,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            disable,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun isEnabled(pm: PackageManager, component: ComponentName, defaultEnabled: Boolean): Boolean =
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> defaultEnabled
            else -> false
        }
}
