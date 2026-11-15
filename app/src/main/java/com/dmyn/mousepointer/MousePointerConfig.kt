package com.dmyn.mousepointer

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/** App and system_server shared contract. */
object ModuleContract {
    const val APP_PACKAGE = "com.dmyn.mousepointer"
    const val ACTION_UPDATE_CONFIG = "com.dmyn.mousepointer.action.UPDATE_CONFIG"
    const val PERMISSION_CONTROL = "com.dmyn.mousepointer.permission.CONTROL"
    const val EXTRA_CONFIG_JSON = "config_json"
    const val SETTINGS_KEY = "mouse_pointer_config"
}

/**
 * All tunable parameters of the module. Stored as one JSON string in Settings.Global by the
 * system_server side and edited from the app.
 */
data class MousePointerConfig(
    var enabled: Boolean = true,
    var disableInLandscape: Boolean = true,
    var cornerExcludeEnabled: Boolean = true,
    var cornerSizeDp: Int = 48,
    var edgeWidthDp: Int = 20,
    var arcRadiusDp: Int = 72,
    var arcSpanDeg: Int = 60,
    var arcCenterAngleDeg: Int = 45,
    var arcToleranceDp: Int = 12,
    var hintDurationMs: Int = 1500,
    var pointerGainTenths: Int = 40,
    var pointerSizeDp: Int = 22,
    var clickMode: Int = CLICK_MOUSE,
    var pointerColor: Int = COLOR_WHITE,
    var hoverEnabled: Boolean = true,
    var vibrateOnTrigger: Boolean = false,
    var vibrateOnClick: Boolean = false,
    var debugLog: Boolean = false,
) {

    fun clamped(): MousePointerConfig = copy(
        cornerSizeDp = cornerSizeDp.coerceIn(CORNER_MIN, CORNER_MAX),
        edgeWidthDp = edgeWidthDp.coerceIn(EDGE_MIN, EDGE_MAX),
        arcRadiusDp = arcRadiusDp.coerceIn(RADIUS_MIN, RADIUS_MAX),
        arcSpanDeg = arcSpanDeg.coerceIn(SPAN_MIN, SPAN_MAX),
        arcCenterAngleDeg = arcCenterAngleDeg.coerceIn(ANGLE_MIN, ANGLE_MAX),
        arcToleranceDp = arcToleranceDp.coerceIn(TOLERANCE_MIN, TOLERANCE_MAX),
        hintDurationMs = hintDurationMs.coerceIn(DURATION_MIN, DURATION_MAX),
        pointerGainTenths = pointerGainTenths.coerceIn(GAIN_MIN, GAIN_MAX),
        pointerSizeDp = pointerSizeDp.coerceIn(SIZE_MIN, SIZE_MAX),
        clickMode = clickMode.coerceIn(CLICK_MOUSE, CLICK_TOUCH),
        pointerColor = pointerColor.coerceIn(COLOR_WHITE, COLOR_COUNT - 1),
    )

    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("disableInLandscape", disableInLandscape)
        put("cornerExcludeEnabled", cornerExcludeEnabled)
        put("cornerSizeDp", cornerSizeDp)
        put("edgeWidthDp", edgeWidthDp)
        put("arcRadiusDp", arcRadiusDp)
        put("arcSpanDeg", arcSpanDeg)
        put("arcCenterAngleDeg", arcCenterAngleDeg)
        put("arcToleranceDp", arcToleranceDp)
        put("hintDurationMs", hintDurationMs)
        put("pointerGainTenths", pointerGainTenths)
        put("pointerSizeDp", pointerSizeDp)
        put("clickMode", clickMode)
        put("pointerColor", pointerColor)
        put("hoverEnabled", hoverEnabled)
        put("vibrateOnTrigger", vibrateOnTrigger)
        put("vibrateOnClick", vibrateOnClick)
        put("debugLog", debugLog)
    }.toString()

    companion object {
        const val CORNER_MIN = 8
        const val CORNER_MAX = 160
        const val EDGE_MIN = 8
        const val EDGE_MAX = 64
        const val RADIUS_MIN = 24
        const val RADIUS_MAX = 260
        const val SPAN_MIN = 10
        const val SPAN_MAX = 180
        const val ANGLE_MIN = 0
        const val ANGLE_MAX = 120
        const val TOLERANCE_MIN = 4
        const val TOLERANCE_MAX = 40
        const val DURATION_MIN = 500
        const val DURATION_MAX = 5000
        const val GAIN_MIN = 10
        const val GAIN_MAX = 120
        const val SIZE_MIN = 12
        const val SIZE_MAX = 48

        const val CLICK_MOUSE = 0
        const val CLICK_TOUCH = 1

        const val COLOR_WHITE = 0
        const val COLOR_BLACK = 1
        const val COLOR_GREEN = 2
        const val COLOR_BLUE = 3
        const val COLOR_ORANGE = 4
        const val COLOR_COUNT = 5

        val CLICK_MODE_LABELS = arrayOf("鼠标点击", "触摸点击")
        val COLOR_LABELS = arrayOf("白色", "黑色", "绿色", "蓝色", "橙色")

        fun defaults(): MousePointerConfig = MousePointerConfig().clamped()

        fun fromJson(raw: String?): MousePointerConfig {
            val base = defaults()
            if (raw.isNullOrBlank()) {
                return base
            }
            return try {
                val o = JSONObject(raw)
                MousePointerConfig(
                    enabled = o.optBoolean("enabled", base.enabled),
                    disableInLandscape = o.optBoolean("disableInLandscape", base.disableInLandscape),
                    cornerExcludeEnabled = o.optBoolean(
                        "cornerExcludeEnabled",
                        base.cornerExcludeEnabled,
                    ),
                    cornerSizeDp = o.optInt("cornerSizeDp", base.cornerSizeDp),
                    edgeWidthDp = o.optInt("edgeWidthDp", base.edgeWidthDp),
                    arcRadiusDp = o.optInt("arcRadiusDp", base.arcRadiusDp),
                    arcSpanDeg = o.optInt("arcSpanDeg", base.arcSpanDeg),
                    arcCenterAngleDeg = o.optInt("arcCenterAngleDeg", base.arcCenterAngleDeg),
                    arcToleranceDp = o.optInt("arcToleranceDp", base.arcToleranceDp),
                    hintDurationMs = o.optInt("hintDurationMs", base.hintDurationMs),
                    pointerGainTenths = o.optInt("pointerGainTenths", base.pointerGainTenths),
                    pointerSizeDp = o.optInt("pointerSizeDp", base.pointerSizeDp),
                    clickMode = o.optInt("clickMode", base.clickMode),
                    pointerColor = o.optInt("pointerColor", base.pointerColor),
                    hoverEnabled = o.optBoolean("hoverEnabled", base.hoverEnabled),
                    vibrateOnTrigger = o.optBoolean("vibrateOnTrigger", base.vibrateOnTrigger),
                    vibrateOnClick = o.optBoolean("vibrateOnClick", base.vibrateOnClick),
                    debugLog = o.optBoolean("debugLog", base.debugLog),
                ).clamped()
            } catch (_: Throwable) {
                base
            }
        }

        /** Reads the persisted config. The app can always read Settings.Global; may be null. */
        fun load(context: Context): MousePointerConfig {
            val raw = try {
                Settings.Global.getString(context.contentResolver, ModuleContract.SETTINGS_KEY)
            } catch (_: Throwable) {
                null
            }
            return fromJson(raw)
        }

        /** Only system_server can always write Settings.Global; app-side failures are ignored. */
        fun save(context: Context, config: MousePointerConfig) {
            try {
                Settings.Global.putString(
                    context.contentResolver,
                    ModuleContract.SETTINGS_KEY,
                    config.clamped().toJson(),
                )
            } catch (_: Throwable) {
            }
        }
    }
}
