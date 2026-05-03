package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object JarvisAvatarController {
    var appVisible by mutableStateOf(false)
        private set
    var overlayExpanded by mutableStateOf(false)
        private set
    var currentAvatarState by mutableStateOf(AvatarState.IDLE)
        private set

    private var overlayView: OrbOverlayView? = null
    private var windowManager: WindowManager? = null
    private var currentSettings: JarvisSettings = JarvisSettings()

    fun applySettings(settings: JarvisSettings) {
        currentSettings = settings
        if (!appVisible) {
            updateFloatingHud()
        }
    }

    fun onAppForeground(context: Context, settings: JarvisSettings) {
        appVisible = true
        currentSettings = settings
        hideFloatingHud()
        Log.e("JARVIS_CMD", "Avatar controller: app foreground")
    }

    fun onAppBackground(context: Context, settings: JarvisSettings) {
        appVisible = false
        currentSettings = settings
        updateFloatingHud(context.applicationContext)
        Log.e("JARVIS_CMD", "Avatar controller: app background")
    }

    fun onWakeWord(context: Context, settings: JarvisSettings) {
        currentSettings = settings
        overlayExpanded = true
        updateState(AvatarState.LISTENING)
        if (!appVisible) updateFloatingHud(context.applicationContext)
        overlayView?.postDelayed({
            overlayExpanded = false
            overlayView?.expanded = false
            overlayView?.invalidate()
        }, 1800L)
    }

    fun updateState(state: AvatarState) {
        currentAvatarState = state
        overlayView?.avatarState = state
        overlayView?.expanded = overlayExpanded || state != AvatarState.IDLE
        overlayView?.invalidate()
    }

    fun shouldShowFullBody(settings: AvatarSettings, isWide: Boolean, detailsOpen: Boolean): Boolean {
        if (!settings.enabled || settings.mode == AvatarMode.HIDDEN) return false
        if (detailsOpen) return false
        return when (settings.mode) {
            AvatarMode.FULL_BODY -> true
            AvatarMode.COMPACT_ORB -> false
            AvatarMode.HIDDEN -> false
            AvatarMode.AUTO -> true
        }
    }

    fun shouldShowCompact(settings: AvatarSettings, detailsOpen: Boolean): Boolean {
        if (!settings.enabled || settings.mode == AvatarMode.HIDDEN) return false
        return detailsOpen || settings.mode == AvatarMode.COMPACT_ORB
    }

    private fun updateFloatingHud(context: Context? = null) {
        val ctx = context ?: overlayView?.context ?: return
        val settings = currentSettings
        if (!settings.hud.floatingHudEnabled || !settings.avatar.enabled || settings.avatar.mode == AvatarMode.HIDDEN) {
            hideFloatingHud()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            hideFloatingHud()
            Log.e("JARVIS_CMD", "Avatar floating HUD skipped: overlay permission missing")
            return
        }
        if (overlayView != null) {
            overlayView?.avatarState = currentAvatarState
            overlayView?.expanded = overlayExpanded
            overlayView?.invalidate()
            return
        }
        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = OrbOverlayView(ctx).apply {
            avatarState = currentAvatarState
            expanded = overlayExpanded
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val size = if (overlayExpanded) 132 else 92
        val params = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 18
            y = 0
        }
        runCatching { windowManager?.addView(overlayView, params) }
            .onFailure { Log.e("JARVIS_CMD", "Avatar floating HUD failed: ${it.message}") }
    }

    private fun hideFloatingHud() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }
}

private class OrbOverlayView(context: Context) : View(context) {
    var avatarState: AvatarState = AvatarState.IDLE
    var expanded: Boolean = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f) * if (expanded) 0.84f else 0.68f
        val accent = when (avatarState) {
            AvatarState.ERROR -> Color.rgb(255, 94, 77)
            AvatarState.RESEARCHING -> Color.rgb(180, 92, 255)
            AvatarState.THINKING -> Color.rgb(122, 140, 255)
            AvatarState.SPEAKING -> Color.rgb(32, 231, 255)
            AvatarState.LISTENING -> Color.rgb(40, 255, 178)
            AvatarState.AWAITING_CMD -> Color.rgb(180, 92, 255)
            AvatarState.BACKGROUND_ACTIVE -> Color.rgb(89, 121, 142)
            AvatarState.IDLE -> Color.rgb(40, 215, 255)
        }
        paint.shader = RadialGradient(cx, cy, r, accent, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        paint.alpha = 130
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.alpha = 230
        paint.color = accent
        canvas.drawCircle(cx, cy, r * 0.72f, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 210
        canvas.drawCircle(cx, cy, r * 0.28f, paint)
    }
}
