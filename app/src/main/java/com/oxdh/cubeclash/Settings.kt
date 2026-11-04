package com.oxdh.cubeclash

import android.content.Context
import android.content.SharedPreferences

/** Player preferences (SharedPreferences). Every read is clamped so corrupt values are harmless. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("cubeclash.settings", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()

    private fun f(key: String, def: Float, min: Float, max: Float): Float {
        val v = try { p.getFloat(key, def) } catch (e: ClassCastException) { def }
        return if (v.isNaN()) def else v.coerceIn(min, max)
    }
    private fun i(key: String, def: Int, min: Int, max: Int) = (try { p.getInt(key, def) } catch (e: ClassCastException) { def }).coerceIn(min, max)
    private fun b(key: String, def: Boolean) = try { p.getBoolean(key, def) } catch (e: ClassCastException) { def }

    var musicVolume: Float get() = f("music", 0.55f, 0f, 1f); set(v) = put { putFloat("music", v.coerceIn(0f, 1f)) }
    var sfxVolume: Float get() = f("sfx", 0.85f, 0f, 1f); set(v) = put { putFloat("sfx", v.coerceIn(0f, 1f)) }
    var muted: Boolean get() = b("muted", false); set(v) = put { putBoolean("muted", v) }
    /** 0 = joystick, 1 = tap to steer. */
    var controls: Int get() = i("controls", 0, 0, 1); set(v) = put { putInt("controls", v) }
    /** 0 = fixed angle, 1 = follow heading. */
    var cameraMode: Int get() = i("camMode", 0, 0, 1); set(v) = put { putInt("camMode", v) }
    var cameraZoom: Float get() = f("camZoom", 1f, 0.7f, 1.5f); set(v) = put { putFloat("camZoom", v) }
    var cameraSensitivity: Float get() = f("camSens", 1f, 0.4f, 2f); set(v) = put { putFloat("camSens", v) }
    var turnSensitivity: Float get() = f("turnSens", 1f, 0.5f, 1.6f); set(v) = put { putFloat("turnSens", v) }
    var joystickScale: Float get() = f("joySize", 1f, 0.7f, 1.4f); set(v) = put { putFloat("joySize", v) }
    var showMinimap: Boolean get() = b("minimap", true); set(v) = put { putBoolean("minimap", v) }
    var lowEffects: Boolean get() = b("lowFx", false); set(v) = put { putBoolean("lowFx", v) }
    var targetFps: Int get() = if (i("fps", 60, 30, 60) >= 45) 60 else 30; set(v) = put { putInt("fps", if (v >= 45) 60 else 30) }
    var lastMode: Int get() = i("lastMode", 0, 0, 2); set(v) = put { putInt("lastMode", v) }
    var lastDifficulty: Int get() = i("lastDiff", 1, 0, 2); set(v) = put { putInt("lastDiff", v) }

    private inline fun put(block: SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(block).apply()
        listeners.forEach { it() }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}
