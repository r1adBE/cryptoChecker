package com.cryptochecker.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.shownChange24h
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.util.appScope
import com.cryptochecker.app.widget.WidgetColors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preis-Overlay (nur Android, Build 150): die Paare der dauerhaften Kurs-Mitteilung ([PriceOverlayRules.pairs]) als
 * kleine, halbtransparente Pille über anderen Apps — «BTC 67’420 ↗ +1.2%», bis zu drei Zeilen. Eigenes Fenster der App
 * (`TYPE_APPLICATION_OVERLAY`, Berechtigung «Über anderen Apps einblenden»), kein Dienst: es folgt den gespeicherten
 * Kursen, also so oft wie die Kurs-Mitteilung (live bei offener App, sonst im Hintergrund-Intervall). Verschieben mit dem
 * Finger, rastet am Rand ein (Lage bleibt gespeichert); Tippen öffnet die App; auf das «×» unten ziehen schaltet es aus.
 * TalkBack: liest die Zeilen, Doppeltippen öffnet die App, Aktion «Overlay schliessen».
 */
@Singleton
class PriceOverlay @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = appScope(Dispatchers.Main, "PriceOverlay")
    private val windowManager by lazy { context.getSystemService(WindowManager::class.java) }
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var pill: LinearLayout? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var closeTarget: TextView? = null
    private var started = false

    /** Einmal beim Start des Prozesses (Application.onCreate): folgt Einstellungen und Kursen. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(settingsRepository.settings, watchRepository.observeWatches()) { s, w -> s to w }
                .catch { hide() }
                .collect { (settings, watches) -> render(settings, watches) }
        }
    }

    private fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    private fun render(settings: AppSettings, watches: List<WatchEntity>) {
        val pairs = PriceOverlayRules.pairs(watches, settings, canDraw())
        if (pairs.isEmpty()) {
            hide()
            return
        }
        val colors = WidgetColors.of(settings.accentColor, true, settings.priceColorScheme, settings.highContrast,
            settings.priceColorsInverted)
        val view = pill ?: create(colors).also { pill = it }
        view.removeAllViews()
        val spoken = ArrayList<String>()
        pairs.forEach { w ->
            val change = w.shownChange24h
            val changeText = PriceFormat.changePercent(change) ?: "—"
            val line = SpannableStringBuilder()
            val symbol = w.baseAsset.trim().uppercase()
            line.append(symbol, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            line.append("  ").append(PriceFormat.price(w.lastPrice)).append("  ")
            val color = when {
                change == null || kotlin.math.abs(change) < 0.005 -> colors.secondaryTextColor
                change > 0 -> colors.upColor
                else -> colors.downColor
            }
            line.append(PriceFormat.changeArrow(change) + changeText, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            view.addView(TextView(context).apply {
                text = line
                setTextColor(colors.textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                maxLines = 1
            })
            spoken += context.getString(R.string.label_value, w.displayName, PriceFormat.price(w.lastPrice) + ", " + changeText)
        }
        view.contentDescription = context.getString(R.string.a11y_price_overlay, spoken.joinToString("; "))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create(colors: WidgetColors): LinearLayout {
        val density = context.resources.displayMetrics.density
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (PADDING_DP * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                cornerRadius = CORNER_DP * density
                setColor(colors.colorWithOpacity(OPACITY_PERCENT))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { openApp() }
        }
        ViewCompat.addAccessibilityAction(view, context.getString(R.string.price_overlay_close)) { _, _ ->
            turnOff()
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, (START_Y_DP * density).toInt())
        }
        view.setOnTouchListener(DragListener(view, params))
        runCatching { windowManager.addView(view, params) }
        pillParams = params
        return view
    }

    /** Ziehen, einrasten, «×»; ein Tipp ohne Bewegung öffnet die App. */
    private inner class DragListener(private val view: View, private val params: WindowManager.LayoutParams) :
        View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val metrics = context.resources.displayMetrics
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                        showCloseTarget()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = PriceOverlayRules.clampY(startY + dy.toInt(), view.height, metrics.heightPixels)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                        return true
                    }
                    hideCloseTarget()
                    val zone = (CLOSE_ZONE_DP * metrics.density).toInt()
                    if (PriceOverlayRules.inCloseZone(params.x + view.width / 2, params.y + view.height / 2,
                            metrics.widthPixels, metrics.heightPixels, zone)) {
                        turnOff()
                    } else {
                        params.x = PriceOverlayRules.snapX(params.x, view.width, metrics.widthPixels)
                        runCatching { windowManager.updateViewLayout(view, params) }
                        prefs.edit {
                            putInt(KEY_X, params.x)
                            putInt(KEY_Y, params.y)
                        }
                    }
                }
            }
            return true
        }
    }

    private fun showCloseTarget() {
        if (closeTarget != null) return
        val density = context.resources.displayMetrics.density
        val size = (CLOSE_SIZE_DP * density).toInt()
        val target = TextView(context).apply {
            setText(R.string.price_overlay_close_symbol)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (CLOSE_MARGIN_DP * density).toInt()
        }
        if (runCatching { windowManager.addView(target, params) }.isSuccess) closeTarget = target
    }

    private fun hideCloseTarget() {
        closeTarget?.let { runCatching { windowManager.removeView(it) } }
        closeTarget = null
    }

    private fun hide() {
        hideCloseTarget()
        pill?.let { runCatching { windowManager.removeView(it) } }
        pill = null
        pillParams = null
    }

    /** «×» bzw. TalkBack-Aktion: Schalter aus (das Overlay verschwindet über [render]). */
    private fun turnOff() {
        hide()
        scope.launch { runCatching { settingsRepository.setPriceOverlay(false) } }
    }

    private fun openApp() {
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private companion object {
        const val PREFS = "price_overlay"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val TEXT_SP = 13f
        const val PADDING_DP = 12f
        const val CORNER_DP = 16f
        const val START_Y_DP = 120f
        const val OPACITY_PERCENT = 85
        const val CLOSE_SIZE_DP = 56f
        const val CLOSE_MARGIN_DP = 48f
        const val CLOSE_ZONE_DP = 96f
    }
}
