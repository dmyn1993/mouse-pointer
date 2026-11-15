package com.dmyn.mousepointer.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.dmyn.mousepointer.ModuleContract
import com.dmyn.mousepointer.MousePointerConfig

class MainActivity : Activity() {

    private lateinit var config: MousePointerConfig
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setDecorFitsSystemWindows(false)
        applySystemBarAppearance()
        config = MousePointerConfig.load(this)
        val scroll = ScrollView(this)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            column.setPadding(
                dp(20),
                bars.top + dp(16),
                dp(20),
                bars.bottom + dp(28),
            )
            insets
        }
        setContentView(scroll)
        buildUi()
    }

    private fun applySystemBarAppearance() {
        try {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val controller = window.insetsController ?: return
            val lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (night) 0 else lightMask, lightMask)
        } catch (_: Throwable) {
        }
    }

    private fun buildUi() {
        column.removeAllViews()

        addSwitch("启用", config.enabled) {
            config = config.copy(enabled = it)
            apply()
        }

        addSection("触发限制")
        addSwitch("横屏时自动关闭", config.disableInLandscape) {
            config = config.copy(disableInLandscape = it)
            apply()
        }
        addSwitch("四角不触发", config.cornerExcludeEnabled) {
            config = config.copy(cornerExcludeEnabled = it)
            apply()
        }
        addSeek(
            "四角区域大小（dp）",
            config.cornerSizeDp,
            MousePointerConfig.CORNER_MIN,
            MousePointerConfig.CORNER_MAX,
        ) {
            config = config.copy(cornerSizeDp = it)
            apply()
        }

        addSection("边缘触发")
        addSeek("边缘触发宽度（dp）", config.edgeWidthDp, MousePointerConfig.EDGE_MIN, MousePointerConfig.EDGE_MAX) {
            config = config.copy(edgeWidthDp = it)
            apply()
        }
        addSeek("弧线距离起始点（dp）", config.arcRadiusDp, MousePointerConfig.RADIUS_MIN, MousePointerConfig.RADIUS_MAX) {
            config = config.copy(arcRadiusDp = it)
            apply()
        }
        addSeek("弧线长度（角度）", config.arcSpanDeg, MousePointerConfig.SPAN_MIN, MousePointerConfig.SPAN_MAX) {
            config = config.copy(arcSpanDeg = it)
            apply()
        }
        addSeek("弧线角度位置（中心角）", config.arcCenterAngleDeg, MousePointerConfig.ANGLE_MIN, MousePointerConfig.ANGLE_MAX) {
            config = config.copy(arcCenterAngleDeg = it)
            apply()
        }
        addSeek("触发容差（±dp）", config.arcToleranceDp, MousePointerConfig.TOLERANCE_MIN, MousePointerConfig.TOLERANCE_MAX) {
            config = config.copy(arcToleranceDp = it)
            apply()
        }
        addSeek("弧线提示时长（ms）", config.hintDurationMs, MousePointerConfig.DURATION_MIN, MousePointerConfig.DURATION_MAX) {
            config = config.copy(hintDurationMs = it)
            apply()
        }

        addSection("鼠标指针")
        addSeek(
            "指针速度（倍率 ×10）",
            config.pointerGainTenths,
            MousePointerConfig.GAIN_MIN,
            MousePointerConfig.GAIN_MAX,
        ) {
            config = config.copy(pointerGainTenths = it)
            apply()
        }
        addSeek("指针大小（dp）", config.pointerSizeDp, MousePointerConfig.SIZE_MIN, MousePointerConfig.SIZE_MAX) {
            config = config.copy(pointerSizeDp = it)
            apply()
        }
        addSpinner("点击方式", MousePointerConfig.CLICK_MODE_LABELS, config.clickMode) {
            config = config.copy(clickMode = it)
            apply()
        }
        addSpinner("指针颜色", MousePointerConfig.COLOR_LABELS, config.pointerColor) {
            config = config.copy(pointerColor = it)
            apply()
        }
        addSwitch("悬停反馈", config.hoverEnabled) {
            config = config.copy(hoverEnabled = it)
            apply()
        }

        addSection("反馈与调试")
        addSwitch("触发时震动", config.vibrateOnTrigger) {
            config = config.copy(vibrateOnTrigger = it)
            apply()
        }
        addSwitch("点击时震动", config.vibrateOnClick) {
            config = config.copy(vibrateOnClick = it)
            apply()
        }
        addSwitch("调试日志", config.debugLog) {
            config = config.copy(debugLog = it)
            apply()
        }

        addButton("恢复默认设置") {
            AlertDialog.Builder(this)
                .setTitle("恢复默认设置")
                .setMessage("确定把所有参数恢复为默认值吗？")
                .setPositiveButton("恢复") { _, _ ->
                    config = MousePointerConfig.defaults()
                    apply()
                    buildUi()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        column.addView(TextView(this).apply {
            text = "修改立即生效；安装/更新模块后需要重启手机。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(18), 0, 0)
        })
    }

    private fun apply() {
        config = config.clamped()
        val intent = Intent(ModuleContract.ACTION_UPDATE_CONFIG)
            .putExtra(ModuleContract.EXTRA_CONFIG_JSON, config.toJson())
        sendBroadcast(intent, ModuleContract.PERMISSION_CONTROL)
        MousePointerConfig.save(this, config)
    }

    private fun addSection(title: String) {
        column.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(18), 0, dp(2))
        })
    }

    private fun addSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        column.addView(
            Switch(this).apply {
                text = title
                textSize = 15f
                isChecked = checked
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, value -> onChange(value) }
            },
        )
    }

    private fun addSeek(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit,
    ) {
        val valueView = TextView(this).apply {
            text = value.toString()
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(valueView)
        }
        column.addView(head)
        column.addView(
            SeekBar(this).apply {
                this.max = max - min
                progress = value - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        valueView.text = (min + progress).toString()
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}

                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        onChange(min + (bar?.progress ?: 0))
                    }
                })
            },
        )
    }

    private fun addSpinner(
        title: String,
        options: Array<String>,
        selected: Int,
        onChange: (Int) -> Unit,
    ) {
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options,
            )
            setSelection(selected.coerceIn(0, options.size - 1), false)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                onChange(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
                addView(
                    TextView(this@MainActivity).apply {
                        text = title
                        textSize = 15f
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(spinner)
            },
        )
    }

    private fun addButton(title: String, onClick: () -> Unit) {
        column.addView(
            Button(this).apply {
                text = title
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
