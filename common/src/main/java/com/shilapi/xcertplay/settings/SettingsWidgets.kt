package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.compat.realSdkInt
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R

object SettingsWidgets {

    private fun TextView.applyTextStyle(context: Context, theme: SettingsTheme, bold: Boolean = false) {
        if (theme.isOverlay) {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
        } else {
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(theme.dp(context, 3).toFloat(), 1f)
        }
    }

    data class SwitchRowResult(
        val rowView: View,
        val switch: Switch,
    )

    data class ChoiceRowResult<T>(
        val container: View,
        val radioGroup: RadioGroup,
    )

    data class ResolutionSliderResult(
        val container: View,
        val valueTextView: TextView,
        val seekBar: SeekBar,
    )

    /**
     * Self-drawn radio indicator: an accent ring with a dot when checked, a muted
     * ring otherwise. CompoundButton tint lists only exist on API 21+, so on older
     * head units the framework fell back to the ROM's stock drawable and the menu
     * left the app theme entirely; a state-list drawable renders the same everywhere.
     */
    fun radioDrawable(context: Context, theme: SettingsTheme): StateListDrawable {
        fun ring(color: Int, checked: Boolean): LayerDrawable {
            val outer = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(theme.dp(context, 2), color)
                setSize(theme.dp(context, 22), theme.dp(context, 22))
            }
            if (!checked) return LayerDrawable(arrayOf(outer))
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            val inset = theme.dp(context, 6)
            return LayerDrawable(arrayOf(outer, dot)).apply {
                setLayerInset(1, inset, inset, inset, inset)
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), ring(theme.accent, checked = true))
            addState(intArrayOf(), ring(theme.textSecondary, checked = false))
        }
    }

    fun styleRadioButton(button: RadioButton, context: Context, theme: SettingsTheme) {
        button.setButtonDrawable(radioDrawable(context, theme))
    }

    /** Self-drawn switch thumb and track in theme colors; Switch tint lists need API 23+. */
    fun styleSwitch(switch: Switch, context: Context, theme: SettingsTheme) {
        fun thumb(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(theme.dp(context, 24), theme.dp(context, 24))
        }
        switch.thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), thumb(Color.WHITE))
            addState(intArrayOf(), thumb(theme.textSecondary))
        }
        fun track(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = theme.dp(context, 14).toFloat()
            setColor(color)
            setSize(theme.dp(context, 46), theme.dp(context, 28))
        }
        switch.trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(theme.accentTrack))
            addState(intArrayOf(), track(theme.trackOff))
        }
    }

    /** Accent slider thumb drawn directly; the SeekBar thumb tint needs API 21+. */
    fun styleSeekBarThumb(seekBar: SeekBar, context: Context, theme: SettingsTheme) {
        seekBar.thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.accent)
            setSize(theme.dp(context, 20), theme.dp(context, 20))
        }
    }

    fun createCategoryHeader(
        context: Context,
        title: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
    ): TextView = TextView(context).apply {
        text = title
        textSize = 22f
        setTextColor(theme.textPrimary)
        applyTextStyle(context, theme, bold = true)
    }

    fun createSwitchRow(
        context: Context,
        label: String,
        description: String,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        contentDescription: String = label,
        enabled: Boolean = true,
        labelSizeSp: Float = 18f,
        onChanged: (Boolean) -> Unit,
    ): SwitchRowResult {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switch = Switch(context).apply {
            isChecked = checked
            this.contentDescription = contentDescription
            isEnabled = enabled
            if (realSdkInt() >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                showText = false
                textOn = ""
                textOff = ""
            }
            if (theme.isOverlay) {
                styleSwitch(this, context, theme)
            } else {
                minHeight = theme.dp(context, 56)
                if (realSdkInt() >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    buttonTintList = ColorStateList.valueOf(theme.accent)
                }
            }
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }

        if (theme.isOverlay) {
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textSecondary)
                applyTextStyle(context, theme)
            }
            row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            row.setPadding(0, theme.dp(context, 12), 0, theme.dp(context, 12))
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textPrimary)
                applyTextStyle(context, theme, bold = true)
            }
            val descView = TextView(context).apply {
                text = description
                textSize = 14f
                setTextColor(theme.textSecondary)
                setPadding(0, theme.dp(context, 6), theme.dp(context, 16), 0)
                applyTextStyle(context, theme)
            }
            textCol.addView(labelView)
            textCol.addView(descView)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch)
            // The whole row is the target; the switch stays out of the focus order so D-pad stops once.
            switch.isFocusable = false
            row.isFocusable = true
            row.background = com.shilapi.xcertplay.compatibleRipple(
                ColorStateList.valueOf(ROW_RIPPLE), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            if (realSdkInt() >= 23) row.foreground = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = theme.dp(context, 12).toFloat()
                    setStroke(theme.dp(context, 3), theme.accent)
                })
            }
            row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
            switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = switch.isChecked
                    info.isEnabled = switch.isEnabled
                }
            }
            clampDescription(descView)
        }

        return SwitchRowResult(row, switch)
    }

    /** Long help stays two lines until tapped; screen readers still get the full text. */
    private fun clampDescription(view: TextView) {
        view.maxLines = COLLAPSED_DESCRIPTION_LINES
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        view.post {
            if ((view.layout?.getEllipsisCount(view.lineCount - 1) ?: 0) == 0) return@post
            view.setOnClickListener {
                view.maxLines = Int.MAX_VALUE
                view.ellipsize = null
                view.setOnClickListener(null)
                view.isClickable = false
            }
        }
    }

    private const val COLLAPSED_DESCRIPTION_LINES = 2
    private const val ROW_RIPPLE = 0x336F9FD9

    fun <T> createChoiceRow(
        context: Context,
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (T) -> Unit,
    ): ChoiceRowResult<T> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        container.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, theme.dp(context, 4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(theme.textSecondary)
                styleRadioButton(this, context, theme)
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        var lastSelectedId = group.checkedRadioButtonId
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            if (checkedId == View.NO_ID) {
                lastSelectedId = View.NO_ID
                return@setOnCheckedChangeListener
            }
            val button = radioGroup.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            // RadioGroup may notify while unchecking the old button, then repeat the new ID.
            if (!button.isChecked || checkedId == lastSelectedId) return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            val value = button.tag as? T ?: return@setOnCheckedChangeListener
            lastSelectedId = checkedId
            onSelected(value)
        }
        container.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ChoiceRowResult(container, group)
    }

    fun createResolutionSlider(
        context: Context,
        initialPercent: Int,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onPercentChanged: (Int) -> Unit,
    ): ResolutionSliderResult {
        val initial = initialPercent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.resolution)
            textSize = 20f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueTextView = TextView(context).apply {
            text = "$initial%"
            textSize = 28f
            setTextColor(theme.accent)
            applyTextStyle(context, theme, bold = true)
        }
        header.addView(
            valueTextView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 14) },
        )

        val seekBar = SeekBar(context).apply {
            max = CarPlayDisplayScale.MAX_PERCENT - CarPlayDisplayScale.MIN_PERCENT
            progress = initial - CarPlayDisplayScale.MIN_PERCENT
            styleSeekBarThumb(this, context, theme)
            if (realSdkInt() >= 21) {
                splitTrack = false
                progressTintList = ColorStateList.valueOf(theme.accent)
                thumbTintList = ColorStateList.valueOf(theme.accent)
            }
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        val percent = (CarPlayDisplayScale.MIN_PERCENT + progress)
                            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
                        valueTextView.text = "$percent%"
                        onPercentChanged(percent)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                },
            )
        }
        container.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 8) },
        )

        val range = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val minLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MIN_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(minLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val maxLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MAX_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(
            maxLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        return ResolutionSliderResult(container, valueTextView, seekBar)
    }
}
