package com.apex5.driver.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.apex5.driver.model.ControllerInputSource
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.DriverOutputMode
import com.apex5.driver.model.LiveControllerState
import com.apex5.driver.model.MacroStep
import com.apex5.driver.model.TouchNodeConfig
import com.apex5.driver.model.TouchNodeKind
import com.apex5.driver.model.TouchProfile
import com.apex5.driver.service.DriverController
import com.apex5.driver.usb.StickMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class FloatingOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.apex5.driver.overlay.SHOW"
        const val ACTION_HIDE = "com.apex5.driver.overlay.HIDE"
        const val ACTION_OPEN_EDITOR = "com.apex5.driver.overlay.OPEN_EDITOR"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var expandedMenu: LinearLayout? = null
    private var bubbleCollapsedView: TextView? = null
    private var menuModeBtn: Button? = null
    private var menuProfileBtn: Button? = null
    private var menuHintsBtn: Button? = null
    private var menuOpacityLabel: TextView? = null
    private var menuOpacitySeek: SeekBar? = null
    private var menuStatusLabel: TextView? = null

    private var hintsView: HintsOverlayView? = null
    private var editorRoot: FrameLayout? = null
    private var isEditing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        DriverController.updateOverlayState(true)

        scope.launch {
            combine(
                DriverController.config,
                DriverController.touchProfiles,
                DriverController.status
            ) { cfg, profiles, status ->
                Triple(cfg, profiles, status)
            }.collect { (cfg, profiles, status) ->
                val activeProfile = profiles.firstOrNull { it.id == cfg.activeTouchProfileId } ?: profiles.firstOrNull()
                updateMenuTexts(
                    mode = cfg.outputMode,
                    profileName = activeProfile?.name ?: "--",
                    showHints = cfg.showTouchHints,
                    opacityPercent = cfg.touchHintsOpacityPercent,
                    serviceRunning = status.serviceRunning
                )
                updateHintsVisibility(
                    show = !isEditing && cfg.outputMode == DriverOutputMode.TOUCH_MAPPER && cfg.showTouchHints,
                    profile = activeProfile,
                    opacityPercent = cfg.touchHintsOpacityPercent
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_HIDE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_EDITOR -> {
                ensureBubbleShown()
                openEditor()
            }
            else -> {
                ensureBubbleShown()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        closeEditor(save = false)
        hintsView?.let { runCatching { wm.removeView(it) } }
        hintsView = null
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        DriverController.updateOverlayState(false)
        super.onDestroy()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun dpF(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun ensureBubbleShown() {
        if (bubbleView != null) return

        val container = FrameLayout(this)

        val pill = TextView(this).apply {
            text = "A5"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC0F172A"))
                setStroke(dp(2f), Color.parseColor("#38BDF8"))
            }
        }
        val pillLp = FrameLayout.LayoutParams(dp(46f), dp(46f))
        container.addView(pill, pillLp)
        bubbleCollapsedView = pill

        val menu = buildExpandedMenu().apply {
            visibility = View.GONE
        }
        container.addView(menu, FrameLayout.LayoutParams(dp(240f), ViewGroup.LayoutParams.WRAP_CONTENT))
        expandedMenu = menu

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18f)
            y = dp(120f)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        pill.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (hypot(dx.toDouble(), dy.toDouble()) > dp(6f)) {
                        moved = true
                    }
                    params.x = (startX + dx).roundToInt()
                    params.y = (startY + dy).roundToInt()
                    runCatching { wm.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toggleExpandedMenu(true)
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = container
        bubbleParams = params
        wm.addView(container, params)

        val cfg = DriverController.config.value
        val activeProfile = DriverController.activeTouchProfile(this)
        updateMenuTexts(
            mode = cfg.outputMode,
            profileName = activeProfile.name,
            showHints = cfg.showTouchHints,
            opacityPercent = cfg.touchHintsOpacityPercent,
            serviceRunning = DriverController.status.value.serviceRunning
        )
    }

    private fun toggleExpandedMenu(expand: Boolean) {
        bubbleCollapsedView?.visibility = if (expand) View.GONE else View.VISIBLE
        expandedMenu?.visibility = if (expand) View.VISIBLE else View.GONE
    }

    private fun buildExpandedMenu(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#E60B1120"))
                setStroke(dp(1.5f), Color.parseColor("#38BDF8"))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Apex 5 悬浮控制"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val collapseBtn = makeMiniBtn("收起", "#1E293B", "#94A3B8") {
            toggleExpandedMenu(false)
        }
        header.addView(title)
        header.addView(collapseBtn)
        root.addView(header)

        val statusTxt = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11.5f
            setPadding(0, dp(4f), 0, dp(8f))
        }
        menuStatusLabel = statusTxt
        root.addView(statusTxt)

        val editBtn = makeMenuActionBtn("编辑当前触屏键位", "#0EA5E9", "#082F49") {
            toggleExpandedMenu(false)
            openEditor()
        }
        root.addView(editBtn)
        addSpacer(root, 6f)

        val profileBtn = makeMenuActionBtn("切换方案: --", "#1E293B", "#E2E8F0") {
            val all = DriverController.touchProfiles.value
            if (all.isNotEmpty()) {
                val curId = DriverController.config.value.activeTouchProfileId
                val curIdx = all.indexOfFirst { it.id == curId }
                val next = all[(curIdx + 1) % all.size]
                DriverController.selectTouchProfile(this, next.id)
            }
        }
        menuProfileBtn = profileBtn
        root.addView(profileBtn)
        addSpacer(root, 6f)

        val modeBtn = makeMenuActionBtn("切换输出模式", "#1E293B", "#38BDF8") {
            val cur = DriverController.config.value.outputMode
            val next = if (cur == DriverOutputMode.TOUCH_MAPPER) {
                DriverOutputMode.GAMEPAD
            } else {
                DriverOutputMode.TOUCH_MAPPER
            }
            DriverController.switchOutputMode(this, next)
        }
        menuModeBtn = modeBtn
        root.addView(modeBtn)
        addSpacer(root, 6f)

        val hintsBtn = makeMenuActionBtn("键位提示: 显示", "#1E293B", "#CBD5E1") {
            DriverController.updateConfig(this) { it.copy(showTouchHints = !it.showTouchHints) }
        }
        menuHintsBtn = hintsBtn
        root.addView(hintsBtn)
        addSpacer(root, 6f)

        // Quick Opacity SeekBar inside Bubble Menu
        val opacityBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(9f).toFloat()
                setColor(Color.parseColor("#111827"))
            }
        }
        val opLabel = TextView(this).apply {
            text = "键位透明度: ${DriverController.config.value.touchHintsOpacityPercent}%"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
        }
        menuOpacityLabel = opLabel
        val opSeek = SeekBar(this).apply {
            max = 100
            progress = DriverController.config.value.touchHintsOpacityPercent.coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    DriverController.updateConfig(this@FloatingOverlayService) {
                        it.copy(touchHintsOpacityPercent = progress.coerceIn(0, 100))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        menuOpacitySeek = opSeek
        opacityBox.addView(opLabel)
        opacityBox.addView(opSeek)
        root.addView(opacityBox)
        addSpacer(root, 6f)

        val closeBtn = makeMenuActionBtn("关闭悬浮窗", "#3F1D2B", "#FCA5A5") {
            stopSelf()
        }
        root.addView(closeBtn)

        return root
    }

    private fun updateMenuTexts(
        mode: DriverOutputMode,
        profileName: String,
        showHints: Boolean,
        opacityPercent: Int,
        serviceRunning: Boolean
    ) {
        val runState = if (serviceRunning) "驱动已启动" else "驱动未启动"
        val modeShort = if (mode == DriverOutputMode.TOUCH_MAPPER) "触屏映射" else "Xbox手柄"
        menuStatusLabel?.text = "$runState · $modeShort"
        menuProfileBtn?.text = "方案: $profileName (点此切换)"
        menuModeBtn?.text = if (mode == DriverOutputMode.TOUCH_MAPPER) {
            "切回 Xbox 360 手柄模式"
        } else {
            "切到 触屏映射模式"
        }
        menuHintsBtn?.text = if (showHints) "键位提示: 已显示" else "键位提示: 已隐藏"
        menuOpacityLabel?.text = "键位透明度: $opacityPercent%"
        if (menuOpacitySeek?.progress != opacityPercent) {
            menuOpacitySeek?.progress = opacityPercent.coerceIn(0, 100)
        }
    }

    private fun updateHintsVisibility(show: Boolean, profile: TouchProfile?, opacityPercent: Int) {
        if (!show || profile == null) {
            hintsView?.let { runCatching { wm.removeView(it) } }
            hintsView = null
            return
        }
        if (hintsView == null) {
            val hv = HintsOverlayView(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            hintsView = hv
            wm.addView(hv, lp)
        }
        hintsView?.setData(profile.nodes, opacityPercent)
    }

    private fun openEditor() {
        if (isEditing) return
        isEditing = true
        updateHintsVisibility(false, null, 0)
        bubbleView?.visibility = View.GONE

        val startProfile = DriverController.activeTouchProfile(this)
        var workingProfile = startProfile.copy(nodes = startProfile.nodes.toList())
        var selectedNodeId: String? = workingProfile.nodes.firstOrNull()?.id
        var toolbarCollapsed = false
        var showGuides = true
        var testMode = false
        var opacityDrawerOpen = false

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#38060A14"))
        }

        lateinit var canvasView: EditorCanvasView
        lateinit var inspectorScroll: HorizontalScrollView
        lateinit var inspectorBar: LinearLayout
        lateinit var selectedTitle: TextView
        lateinit var kindBtn: Button
        lateinit var modBtn: Button
        lateinit var gyroBtn: Button
        lateinit var addMacroStepBtn: Button
        lateinit var clearMacroBtn: Button
        lateinit var paramLabel: TextView
        lateinit var paramSeek: SeekBar
        lateinit var addPickerPanel: LinearLayout
        lateinit var opacityDrawer: LinearLayout
        lateinit var saveAsModal: LinearLayout
        var addListeningInput = false
        var addComboMacroMode = false

        val modifierCandidates = listOf(
            null,
            ControllerInputSource.LT,
            ControllerInputSource.LB,
            ControllerInputSource.M1,
            ControllerInputSource.M2,
            ControllerInputSource.RB,
            ControllerInputSource.RT
        )

        fun refreshInspector() {
            val node = workingProfile.nodes.firstOrNull { it.id == selectedNodeId }
            if (node == null) {
                inspectorScroll.visibility = View.GONE
                return
            }
            inspectorScroll.visibility = View.VISIBLE
            selectedTitle.text = node.overlayLabel()
            kindBtn.text = "类型: ${node.kind.label.substringBefore(" ")}"
            modBtn.text = if (node.modifierInput != null) "组合: ${node.modifierInput.shortLabel}+" else "组合: 单键"
            gyroBtn.visibility = if (node.kind == TouchNodeKind.CAMERA_SWIPE) View.VISIBLE else View.GONE
            gyroBtn.text = if (node.gyroAssist) "陀螺仪: 开" else "陀螺仪: 关"
            val isMacro = node.kind == TouchNodeKind.MACRO
            addMacroStepBtn.visibility = if (isMacro) View.VISIBLE else View.GONE
            clearMacroBtn.visibility = if (isMacro && node.macroSteps.isNotEmpty()) View.VISIBLE else View.GONE

            when (node.kind) {
                TouchNodeKind.TAP, TouchNodeKind.SKILL_AIM -> {
                    val tag = if (node.kind == TouchNodeKind.SKILL_AIM) "技能圈" else "按键圈"
                    paramLabel.text = "$tag ${(node.radiusNorm * 100).roundToInt()}%"
                    paramSeek.max = 25
                    paramSeek.progress = ((node.radiusNorm * 100f).roundToInt() - 3).coerceIn(0, 25)
                }
                TouchNodeKind.RAPID_TAP -> {
                    paramLabel.text = "连发 ${node.rapidHz}Hz"
                    paramSeek.max = 22
                    paramSeek.progress = (node.rapidHz - 3).coerceIn(0, 22)
                }
                TouchNodeKind.JOYSTICK -> {
                    paramLabel.text = "轮盘 ${(node.radiusNorm * 100).roundToInt()}%"
                    paramSeek.max = 25
                    paramSeek.progress = ((node.radiusNorm * 100f).roundToInt() - 3).coerceIn(0, 25)
                }
                TouchNodeKind.CAMERA_SWIPE -> {
                    paramLabel.text = "灵敏 %.2fx".format(node.sensitivity)
                    paramSeek.max = 28
                    paramSeek.progress = (((node.sensitivity - 0.3f) / 0.1f).roundToInt()).coerceIn(0, 28)
                }
                TouchNodeKind.MACRO -> {
                    val stepCount = node.macroSteps.size.coerceAtLeast(1)
                    val intervalMs = node.macroSteps.firstOrNull()?.delayAfterMs ?: 55
                    paramLabel.text = "宏 ${stepCount}步 · ${intervalMs}ms"
                    paramSeek.max = 20
                    paramSeek.progress = ((intervalMs - 20) / 10).coerceIn(0, 20)
                }
            }
        }

        canvasView = EditorCanvasView(
            context = this,
            dpProvider = { dpF(it) },
            onNodesChanged = { updated, selId ->
                workingProfile = workingProfile.copy(nodes = updated)
                selectedNodeId = selId
                refreshInspector()
            }
        )
        canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
        canvasView.setEditorVisualOptions(
            showGuides = showGuides,
            opacityPercent = DriverController.config.value.touchHintsOpacityPercent,
            testMode = testMode
        )
        root.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top Collapsible Dark Glass Toolbar (Flydigi Space Station style)
        val topDockWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#E6090D16"))
                setStroke(dp(1f), Color.parseColor("#475569"))
            }
        }

        val profileTag = TextView(this).apply {
            text = workingProfile.name
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(2f), 0, dp(8f), 0)
        }

        lateinit var testBtn: Button
        lateinit var guideBtn: Button
        lateinit var opacityBtn: Button

        val addBtn = makeDockBtn("＋ 添加", "#0EA5E9", "#082F49") {
            addListeningInput = addPickerPanel.visibility != View.VISIBLE
            addPickerPanel.visibility = if (addListeningInput) View.VISIBLE else View.GONE
            if (addListeningInput) {
                opacityDrawerOpen = false
                opacityDrawer.visibility = View.GONE
            }
        }

        val saveAsBtn = makeDockBtn("另存为", "#1E293B", "#E2E8F0") {
            saveAsModal.visibility = View.VISIBLE
            addListeningInput = false
            addPickerPanel.visibility = View.GONE
            opacityDrawerOpen = false
            opacityDrawer.visibility = View.GONE
        }

        testBtn = makeDockBtn("测试", "#1E293B", "#E2E8F0") {
            testMode = !testMode
            updateDockBtnStyle(
                testBtn,
                bgHex = if (testMode) "#065F46" else "#1E293B",
                fgHex = if (testMode) "#6EE7B7" else "#E2E8F0"
            )
            testBtn.text = if (testMode) "测试中" else "测试"
            canvasView.setEditorVisualOptions(
                showGuides = showGuides,
                opacityPercent = DriverController.config.value.touchHintsOpacityPercent,
                testMode = testMode
            )
        }

        guideBtn = makeDockBtn("辅助线 开", "#0C4A6E", "#7DD3FC") {
            showGuides = !showGuides
            updateDockBtnStyle(
                guideBtn,
                bgHex = if (showGuides) "#0C4A6E" else "#1E293B",
                fgHex = if (showGuides) "#7DD3FC" else "#94A3B8"
            )
            guideBtn.text = if (showGuides) "辅助线 开" else "辅助线 关"
            canvasView.setEditorVisualOptions(
                showGuides = showGuides,
                opacityPercent = DriverController.config.value.touchHintsOpacityPercent,
                testMode = testMode
            )
        }

        opacityBtn = makeDockBtn("透明度 ${DriverController.config.value.touchHintsOpacityPercent}%", "#1E293B", "#CBD5E1") {
            opacityDrawerOpen = !opacityDrawerOpen
            opacityDrawer.visibility = if (opacityDrawerOpen) View.VISIBLE else View.GONE
            if (opacityDrawerOpen) {
                addListeningInput = false
                addPickerPanel.visibility = View.GONE
            }
        }

        val cancelBtn = makeDockBtn("取消", "#334155", "#E2E8F0") {
            closeEditor(save = false)
        }

        val saveBtn = makeDockBtn("保存", "#22C55E", "#052E16") {
            DriverController.saveTouchProfile(this, workingProfile)
            closeEditor(save = true)
        }

        val topBarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val topBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBtnRow.addView(profileTag)
        topBtnRow.addView(addBtn)
        addHorizontalGap(topBtnRow, 6f)
        topBtnRow.addView(saveAsBtn)
        addHorizontalGap(topBtnRow, 6f)
        topBtnRow.addView(testBtn)
        addHorizontalGap(topBtnRow, 6f)
        topBtnRow.addView(guideBtn)
        addHorizontalGap(topBtnRow, 6f)
        topBtnRow.addView(opacityBtn)
        addHorizontalGap(topBtnRow, 6f)
        topBtnRow.addView(cancelBtn)
        addHorizontalGap(topBtnRow, 6f)
        topBtnRow.addView(saveBtn)
        topBarScroll.addView(topBtnRow)
        topBar.addView(topBarScroll)

        // Collapse chevron tab underneath the toolbar (matches UP主 screenshot!)
        lateinit var collapseTab: TextView
        collapseTab = TextView(this).apply {
            text = "▲"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dp(18f), dp(2f), dp(18f), dp(3f))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(8f).toFloat(), dp(8f).toFloat(), dp(8f).toFloat(), dp(8f).toFloat())
                setColor(Color.parseColor("#DD090D16"))
                setStroke(dp(1f), Color.parseColor("#334155"))
            }
            setOnClickListener {
                toolbarCollapsed = !toolbarCollapsed
                topBar.visibility = if (toolbarCollapsed) View.GONE else View.VISIBLE
                collapseTab.text = if (toolbarCollapsed) "▼ 展开菜单" else "▲"
                if (toolbarCollapsed) {
                    addListeningInput = false
                    addPickerPanel.visibility = View.GONE
                    opacityDrawerOpen = false
                    opacityDrawer.visibility = View.GONE
                }
            }
        }

        topDockWrapper.addView(topBar)
        topDockWrapper.addView(collapseTab)

        val topLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(8f)
            marginStart = dp(10f)
            marginEnd = dp(10f)
        }
        root.addView(topDockWrapper, topLp)

        // Inline Opacity Control Drawer below top bar
        opacityDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#F0090D16"))
                setStroke(dp(1.2f), Color.parseColor("#38BDF8"))
            }
        }
        val opTitle = TextView(this).apply {
            text = "提示透明度"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        val opValText = TextView(this).apply {
            text = "${DriverController.config.value.touchHintsOpacityPercent}%"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8f), 0, dp(4f), 0)
        }
        val editorOpSeek = SeekBar(this).apply {
            max = 100
            progress = DriverController.config.value.touchHintsOpacityPercent.coerceIn(0, 100)
            layoutParams = LinearLayout.LayoutParams(dp(200f), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val clamped = progress.coerceIn(0, 100)
                    opValText.text = "$clamped%"
                    opacityBtn.text = "透明度 $clamped%"
                    DriverController.updateConfig(this@FloatingOverlayService) {
                        it.copy(touchHintsOpacityPercent = clamped)
                    }
                    canvasView.setEditorVisualOptions(
                        showGuides = showGuides,
                        opacityPercent = clamped,
                        testMode = testMode
                    )
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val opDoneBtn = makeMiniBtn("完成", "#0EA5E9", "#082F49") {
            opacityDrawerOpen = false
            opacityDrawer.visibility = View.GONE
        }
        opacityDrawer.addView(opTitle)
        addHorizontalGap(opacityDrawer, 8f)
        opacityDrawer.addView(editorOpSeek)
        opacityDrawer.addView(opValText)
        addHorizontalGap(opacityDrawer, 6f)
        opacityDrawer.addView(opDoneBtn)
        val opDrawerLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(66f) }
        root.addView(opacityDrawer, opDrawerLp)

        // Quick Add Picker Panel (supports tapping OR pressing physical button on Apex 5!)
        addPickerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#F5090E1A"))
                setStroke(dp(1.5f), Color.parseColor("#38BDF8"))
            }
        }
        val pickerTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8f))
        }
        val pickerHint = TextView(this).apply {
            text = "点选按键，或直接按手柄实体键添加："
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        lateinit var modeToggleBtn: Button
        modeToggleBtn = makeMiniBtn("模式: 普通键位", "#1E293B", "#E2E8F0") {
            addComboMacroMode = !addComboMacroMode
            modeToggleBtn.text = if (addComboMacroMode) "模式: +组合宏(LT+)" else "模式: 普通键位"
            modeToggleBtn.setTextColor(Color.parseColor(if (addComboMacroMode) "#F59E0B" else "#E2E8F0"))
        }
        pickerTopRow.addView(pickerHint)
        pickerTopRow.addView(modeToggleBtn)
        addPickerPanel.addView(pickerTopRow)

        fun addInputNode(src: ControllerInputSource) {
            addListeningInput = false
            addPickerPanel.visibility = View.GONE
            if (!addComboMacroMode) {
                val existing = workingProfile.nodes.firstOrNull { it.input == src && it.modifierInput == null }
                if (existing != null) {
                    selectedNodeId = existing.id
                    canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
                    refreshInspector()
                    return
                }
            }
            val kind = when {
                addComboMacroMode -> TouchNodeKind.MACRO
                src == ControllerInputSource.LEFT_STICK -> TouchNodeKind.JOYSTICK
                src == ControllerInputSource.RIGHT_STICK -> TouchNodeKind.CAMERA_SWIPE
                else -> TouchNodeKind.TAP
            }
            val modifier = if (addComboMacroMode) {
                if (src == ControllerInputSource.LT) ControllerInputSource.LB else ControllerInputSource.LT
            } else null
            val radius = when (kind) {
                TouchNodeKind.JOYSTICK -> 0.11f
                TouchNodeKind.CAMERA_SWIPE -> 0.14f
                TouchNodeKind.SKILL_AIM -> 0.055f
                else -> 0.045f
            }
            val defaultSteps = if (kind == TouchNodeKind.MACRO) {
                listOf(
                    MacroStep(0.50f, 0.50f, 45, 55),
                    MacroStep(0.58f, 0.45f, 45, 55)
                )
            } else emptyList()

            val node = TouchNodeConfig(
                id = "n_" + UUID.randomUUID().toString().take(8),
                input = src,
                kind = kind,
                xNorm = 0.5f,
                yNorm = 0.5f,
                radiusNorm = radius,
                modifierInput = modifier,
                macroSteps = defaultSteps
            )
            workingProfile = workingProfile.copy(nodes = workingProfile.nodes + node)
            selectedNodeId = node.id
            canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
            refreshInspector()
        }

        val hScroll = HorizontalScrollView(this)
        val btnStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ControllerInputSource.entries.forEach { src ->
            val b = makeMiniBtn(src.shortLabel, "#1E293B", "#F8FAFC") {
                addInputNode(src)
            }
            btnStrip.addView(b)
            addHorizontalGap(btnStrip, 6f)
        }
        hScroll.addView(btnStrip)
        addPickerPanel.addView(hScroll)

        val pickerLp = FrameLayout.LayoutParams(
            dp(540f),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(66f) }
        root.addView(addPickerPanel, pickerLp)

        // Inline Save-As Modal inside editorRoot
        saveAsModal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#F50B1120"))
                setStroke(dp(1.5f), Color.parseColor("#38BDF8"))
            }
        }
        val saveAsTitle = TextView(this).apply {
            text = "另存为新映射方案"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10f))
        }
        val nameInput = EditText(this).apply {
            setText("${workingProfile.name} 副本")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            hint = "输入方案名称"
            textSize = 13.5f
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(9f).toFloat()
                setColor(Color.parseColor("#111827"))
                setStroke(dp(1f), Color.parseColor("#334155"))
            }
        }
        val modalBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12f), 0, 0)
        }
        val modalCancel = makeMiniBtn("取消", "#334155", "#E2E8F0") {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(nameInput.windowToken, 0)
            saveAsModal.visibility = View.GONE
        }
        val modalConfirm = makeMiniBtn("确认保存", "#22C55E", "#052E16") {
            val cleanName = nameInput.text.toString().trim().ifEmpty { "自定义方案" }
            val newId = "p_" + UUID.randomUUID().toString().take(8)
            val cloned = workingProfile.copy(id = newId, name = cleanName)
            DriverController.saveTouchProfile(this, cloned)
            DriverController.selectTouchProfile(this, cloned.id)
            workingProfile = cloned
            profileTag.text = cloned.name
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(nameInput.windowToken, 0)
            saveAsModal.visibility = View.GONE
            DriverController.appendLog("已另存为触屏方案: ${cloned.name}")
        }
        modalBtnRow.addView(modalCancel)
        addHorizontalGap(modalBtnRow, 8f)
        modalBtnRow.addView(modalConfirm)
        saveAsModal.addView(saveAsTitle)
        saveAsModal.addView(nameInput)
        saveAsModal.addView(modalBtnRow)
        val saveAsLp = FrameLayout.LayoutParams(
            dp(320f),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(saveAsModal, saveAsLp)

        // Bottom inspector bar
        inspectorScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        inspectorBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#EB090E1A"))
                setStroke(dp(1f), Color.parseColor("#334155"))
            }
        }
        selectedTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8f), 0)
        }
        kindBtn = makeMiniBtn("类型", "#1E293B", "#38BDF8") {
            val curId = selectedNodeId ?: return@makeMiniBtn
            val nextNodes = workingProfile.nodes.map { n ->
                if (n.id == curId) {
                    val kinds = TouchNodeKind.entries
                    val nextKind = kinds[(kinds.indexOf(n.kind) + 1) % kinds.size]
                    val nextSteps = if (nextKind == TouchNodeKind.MACRO && n.macroSteps.isEmpty()) {
                        listOf(
                            MacroStep(n.xNorm, n.yNorm, 45, 55),
                            MacroStep((n.xNorm + 0.08f).coerceIn(0.05f, 0.95f), (n.yNorm - 0.06f).coerceIn(0.05f, 0.95f), 45, 55)
                        )
                    } else n.macroSteps
                    n.copy(kind = nextKind, macroSteps = nextSteps)
                } else n
            }
            workingProfile = workingProfile.copy(nodes = nextNodes)
            canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
            refreshInspector()
        }
        modBtn = makeMiniBtn("组合: 单键", "#1E293B", "#F59E0B") {
            val curId = selectedNodeId ?: return@makeMiniBtn
            val nextNodes = workingProfile.nodes.map { n ->
                if (n.id == curId) {
                    val options = modifierCandidates.filter { it == null || it != n.input }
                    val idx = options.indexOf(n.modifierInput)
                    val nextMod = options[(idx + 1) % options.size]
                    n.copy(modifierInput = nextMod)
                } else n
            }
            workingProfile = workingProfile.copy(nodes = nextNodes)
            canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
            refreshInspector()
        }
        gyroBtn = makeMiniBtn("陀螺仪: 开", "#1E293B", "#A855F7") {
            val curId = selectedNodeId ?: return@makeMiniBtn
            val nextNodes = workingProfile.nodes.map { n ->
                if (n.id == curId) n.copy(gyroAssist = !n.gyroAssist) else n
            }
            workingProfile = workingProfile.copy(nodes = nextNodes)
            canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
            refreshInspector()
        }
        addMacroStepBtn = makeMiniBtn("+宏点", "#1E293B", "#34D399") {
            val curId = selectedNodeId ?: return@makeMiniBtn
            val nextNodes = workingProfile.nodes.map { n ->
                if (n.id == curId) {
                    val baseSteps = n.macroSteps.ifEmpty {
                        listOf(MacroStep(n.xNorm, n.yNorm, 45, 55))
                    }
                    val last = baseSteps.last()
                    val nextX = (last.xNorm + 0.07f).let { if (it > 0.94f) (last.xNorm - 0.08f).coerceAtLeast(0.06f) else it }
                    val nextY = (last.yNorm - 0.05f).let { if (it < 0.06f) (last.yNorm + 0.08f).coerceAtMost(0.94f) else it }
                    n.copy(macroSteps = baseSteps + MacroStep(nextX, nextY, last.holdMs, last.delayAfterMs))
                } else n
            }
            workingProfile = workingProfile.copy(nodes = nextNodes)
            canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
            refreshInspector()
        }
        clearMacroBtn = makeMiniBtn("重置宏", "#1E293B", "#FCA5A5") {
            val curId = selectedNodeId ?: return@makeMiniBtn
            val nextNodes = workingProfile.nodes.map { n ->
                if (n.id == curId) n.copy(macroSteps = emptyList()) else n
            }
            workingProfile = workingProfile.copy(nodes = nextNodes)
            canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
            refreshInspector()
        }
        paramLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11.5f
            setPadding(dp(8f), 0, dp(6f), 0)
        }
        paramSeek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120f), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val curId = selectedNodeId ?: return
                    val nextNodes = workingProfile.nodes.map { n ->
                        if (n.id == curId) {
                            when (n.kind) {
                                TouchNodeKind.TAP, TouchNodeKind.SKILL_AIM, TouchNodeKind.JOYSTICK ->
                                    n.copy(radiusNorm = ((progress + 3) / 100f).coerceIn(0.025f, 0.28f))
                                TouchNodeKind.RAPID_TAP ->
                                    n.copy(rapidHz = (progress + 3).coerceIn(3, 25))
                                TouchNodeKind.CAMERA_SWIPE ->
                                    n.copy(sensitivity = (0.3f + progress * 0.1f).coerceIn(0.3f, 3.1f))
                                TouchNodeKind.MACRO -> {
                                    val newDelay = (20 + progress * 10).coerceIn(20, 220)
                                    val steps = if (n.macroSteps.isEmpty()) {
                                        listOf(
                                            MacroStep(n.xNorm, n.yNorm, 45, newDelay),
                                            MacroStep(n.xNorm, n.yNorm, 45, newDelay)
                                        )
                                    } else {
                                        n.macroSteps.map { it.copy(delayAfterMs = newDelay) }
                                    }
                                    n.copy(macroSteps = steps)
                                }
                            }
                        } else n
                    }
                    workingProfile = workingProfile.copy(nodes = nextNodes)
                    canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
                    refreshInspector()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val deleteBtn = makeMiniBtn("删除", "#7F1D1D", "#FECACA") {
            val curId = selectedNodeId ?: return@makeMiniBtn
            val nextNodes = workingProfile.nodes.filterNot { it.id == curId }
            workingProfile = workingProfile.copy(nodes = nextNodes)
            selectedNodeId = nextNodes.firstOrNull()?.id
            canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
            refreshInspector()
        }

        inspectorBar.addView(selectedTitle)
        inspectorBar.addView(kindBtn)
        addHorizontalGap(inspectorBar, 6f)
        inspectorBar.addView(modBtn)
        addHorizontalGap(inspectorBar, 6f)
        inspectorBar.addView(gyroBtn)
        inspectorBar.addView(addMacroStepBtn)
        addHorizontalGap(inspectorBar, 6f)
        inspectorBar.addView(clearMacroBtn)
        inspectorBar.addView(paramLabel)
        inspectorBar.addView(paramSeek)
        addHorizontalGap(inspectorBar, 6f)
        inspectorBar.addView(deleteBtn)
        inspectorScroll.addView(inspectorBar)

        val botLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(12f)
            marginStart = dp(12f)
            marginEnd = dp(12f)
        }
        root.addView(inspectorScroll, botLp)
        refreshInspector()

        // Listen for physical controller input (Add key OR Live Test Mode)
        scope.launch {
            var prev = LiveControllerState()
            DriverController.status.collect { st ->
                if (!isEditing) return@collect
                if (testMode) {
                    canvasView.setLiveState(st.liveState, DriverController.config.value)
                }
                if (!addListeningInput) {
                    prev = st.liveState
                    return@collect
                }
                val cur = st.liveState
                val pressed = detectNewlyPressedInput(prev, cur)
                prev = cur
                if (pressed != null) {
                    addInputNode(pressed)
                }
            }
        }

        val editorLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        editorRoot = root
        wm.addView(root, editorLp)
    }

    private fun closeEditor(save: Boolean) {
        if (!isEditing && editorRoot == null) return
        isEditing = false
        editorRoot?.let { runCatching { wm.removeView(it) } }
        editorRoot = null
        bubbleView?.visibility = View.VISIBLE
        val cfg = DriverController.config.value
        val activeProfile = DriverController.activeTouchProfile(this)
        updateHintsVisibility(
            show = cfg.outputMode == DriverOutputMode.TOUCH_MAPPER && cfg.showTouchHints,
            profile = activeProfile,
            opacityPercent = cfg.touchHintsOpacityPercent
        )
        if (save) {
            DriverController.appendLog("触屏键位方案已保存 (${activeProfile.name}, ${activeProfile.nodes.size} 个节点)")
        }
    }

    private fun detectNewlyPressedInput(prev: LiveControllerState, cur: LiveControllerState): ControllerInputSource? {
        return when {
            cur.a && !prev.a -> ControllerInputSource.A
            cur.b && !prev.b -> ControllerInputSource.B
            cur.x && !prev.x -> ControllerInputSource.X
            cur.y && !prev.y -> ControllerInputSource.Y
            cur.lb && !prev.lb -> ControllerInputSource.LB
            cur.rb && !prev.rb -> ControllerInputSource.RB
            cur.lt >= 100 && prev.lt < 100 -> ControllerInputSource.LT
            cur.rt >= 100 && prev.rt < 100 -> ControllerInputSource.RT
            cur.l3 && !prev.l3 -> ControllerInputSource.L3
            cur.r3 && !prev.r3 -> ControllerInputSource.R3
            cur.m1 && !prev.m1 -> ControllerInputSource.M1
            cur.m2 && !prev.m2 -> ControllerInputSource.M2
            cur.m3 && !prev.m3 -> ControllerInputSource.M3
            cur.m4 && !prev.m4 -> ControllerInputSource.M4
            cur.lm && !prev.lm -> ControllerInputSource.LM
            cur.rm && !prev.rm -> ControllerInputSource.RM
            cur.hatY < 0 && prev.hatY >= 0 -> ControllerInputSource.DPAD_UP
            cur.hatY > 0 && prev.hatY <= 0 -> ControllerInputSource.DPAD_DOWN
            cur.hatX < 0 && prev.hatX >= 0 -> ControllerInputSource.DPAD_LEFT
            cur.hatX > 0 && prev.hatX <= 0 -> ControllerInputSource.DPAD_RIGHT
            else -> null
        }
    }

    private fun makeMiniBtn(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.parseColor(fgHex))
            textSize = 11.5f
            isAllCaps = false
            minHeight = dp(32f)
            minimumHeight = dp(32f)
            minWidth = dp(40f)
            minimumWidth = dp(40f)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeDockBtn(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.parseColor(fgHex))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(30f)
            minimumHeight = dp(30f)
            minWidth = dp(46f)
            minimumWidth = dp(46f)
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
            background = GradientDrawable().apply {
                cornerRadius = dp(7f).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateDockBtnStyle(btn: Button, bgHex: String, fgHex: String) {
        btn.setTextColor(Color.parseColor(fgHex))
        btn.background = GradientDrawable().apply {
            cornerRadius = dp(7f).toFloat()
            setColor(Color.parseColor(bgHex))
        }
    }

    private fun makeMenuActionBtn(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.parseColor(fgHex))
            textSize = 12f
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = dp(36f)
            minimumHeight = dp(36f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(9f).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun addSpacer(parent: LinearLayout, heightDp: Float) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun addHorizontalGap(parent: LinearLayout, widthDp: Float) {
        parent.addView(View(this), LinearLayout.LayoutParams(dp(widthDp), 1))
    }

    // ---------------------------------------------------------------------------------------------
    // UP主-style clean neon-ring HintsOverlayView with 0-100% live opacity scaling
    // ---------------------------------------------------------------------------------------------
    private class HintsOverlayView(context: Context) : View(context) {
        private var nodes: List<TouchNodeConfig> = emptyList()
        private var opacityFrac: Float = 0.55f

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#0B1120")
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.2f
            color = Color.parseColor("#2E7CF6")
        }
        private val innerJoyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.parseColor("#E2E8F0")
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#38BDF8")
        }
        private val aimZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
            color = Color.parseColor("#34D399")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 1.5f, Color.BLACK)
        }
        private val chevronPath = Path()

        fun setData(list: List<TouchNodeConfig>, opacityPercent: Int) {
            nodes = list
            opacityFrac = (opacityPercent / 100f).coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (opacityFrac <= 0.01f) return

            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val minDim = minOf(w, h)

            nodes.forEach { n ->
                val cx = n.xNorm * w
                val cy = n.yNorm * h
                val r = (n.radiusNorm * minDim).coerceIn(22f, 240f)

                val fillAlpha = (opacityFrac * 42f).roundToInt().coerceIn(0, 255)
                val ringAlpha = (opacityFrac * 220f).roundToInt().coerceIn(0, 255)
                val textAlpha = (opacityFrac * 245f).roundToInt().coerceIn(0, 255)

                fillPaint.alpha = fillAlpha
                ringPaint.alpha = ringAlpha
                textPaint.alpha = textAlpha

                when (n.kind) {
                    TouchNodeKind.JOYSTICK -> {
                        fillPaint.alpha = (opacityFrac * 56f).roundToInt().coerceIn(0, 255)
                        canvas.drawCircle(cx, cy, r, fillPaint)
                        ringPaint.color = Color.parseColor("#2E7CF6")
                        ringPaint.alpha = ringAlpha
                        canvas.drawCircle(cx, cy, r, ringPaint)

                        val innerR = (r * 0.44f).coerceIn(14f, 96f)
                        innerJoyPaint.alpha = (opacityFrac * 210f).roundToInt().coerceIn(0, 255)
                        canvas.drawCircle(cx, cy, innerR, innerJoyPaint)

                        arrowPaint.alpha = (opacityFrac * 220f).roundToInt().coerceIn(0, 255)
                        drawCardinalChevrons(canvas, cx, cy, (r + innerR) * 0.5f, (r * 0.09f).coerceIn(5f, 12f), arrowPaint, chevronPath)

                        val label = n.overlayLabel()
                        textPaint.textSize = (innerR * 0.72f).coerceIn(18f, 44f)
                        canvas.drawText(label, cx, cy + textPaint.textSize * 0.36f, textPaint)
                    }
                    TouchNodeKind.SKILL_AIM -> {
                        canvas.drawCircle(cx, cy, r, fillPaint)
                        ringPaint.color = Color.parseColor("#2E7CF6")
                        ringPaint.alpha = ringAlpha
                        canvas.drawCircle(cx, cy, r, ringPaint)

                        val aimR = (n.radiusNorm * 1.45f * minDim).coerceIn(r + 8f, 260f)
                        aimZonePaint.alpha = (opacityFrac * 95f).roundToInt().coerceIn(0, 255)
                        canvas.drawCircle(cx, cy, aimR, aimZonePaint)

                        val label = n.overlayLabel()
                        textPaint.textSize = if (label.length >= 4) (r * 0.44f).coerceIn(15f, 24f) else (r * 0.58f).coerceIn(18f, 32f)
                        canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)
                    }
                    TouchNodeKind.CAMERA_SWIPE -> {
                        canvas.drawCircle(cx, cy, r, fillPaint)
                        ringPaint.color = Color.parseColor("#A855F7")
                        ringPaint.alpha = (opacityFrac * 165f).roundToInt().coerceIn(0, 255)
                        canvas.drawCircle(cx, cy, r, ringPaint)

                        val label = n.overlayLabel()
                        textPaint.textSize = (r * 0.34f).coerceIn(18f, 32f)
                        canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)
                    }
                    TouchNodeKind.MACRO -> {
                        canvas.drawCircle(cx, cy, r, fillPaint)
                        ringPaint.color = Color.parseColor("#F59E0B")
                        ringPaint.alpha = ringAlpha
                        canvas.drawCircle(cx, cy, r, ringPaint)

                        val label = n.overlayLabel()
                        textPaint.textSize = if (label.length >= 4) (r * 0.44f).coerceIn(15f, 24f) else (r * 0.58f).coerceIn(18f, 32f)
                        canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)
                    }
                    TouchNodeKind.TAP, TouchNodeKind.RAPID_TAP -> {
                        canvas.drawCircle(cx, cy, r, fillPaint)
                        ringPaint.color = if (n.kind == TouchNodeKind.RAPID_TAP) {
                            Color.parseColor("#F59E0B")
                        } else {
                            Color.parseColor("#2E7CF6")
                        }
                        ringPaint.alpha = ringAlpha
                        canvas.drawCircle(cx, cy, r, ringPaint)

                        val label = n.overlayLabel()
                        textPaint.textSize = if (label.length >= 4) (r * 0.44f).coerceIn(15f, 24f) else (r * 0.58f).coerceIn(18f, 32f)
                        canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // EditorCanvasView: UP主-style rings, alignment crosshairs & snapping, drag/pinch resize, Live Test
    // ---------------------------------------------------------------------------------------------
    private class EditorCanvasView(
        context: Context,
        private val dpProvider: (Float) -> Float,
        private val onNodesChanged: (List<TouchNodeConfig>, String?) -> Unit
    ) : View(context) {

        private enum class DragMode {
            NONE, MOVE_NODE, MOVE_MACRO_STEP, RESIZE_HANDLE, PINCH_RESIZE
        }

        private var nodes: List<TouchNodeConfig> = emptyList()
        private var selectedId: String? = null
        private var showGuides = true
        private var opacityFrac = 0.65f
        private var testMode = false
        private var liveState = LiveControllerState()
        private var driverConfig = DriverConfig()

        private var dragMode = DragMode.NONE
        private var activeNodeId: String? = null
        private var draggingMacroStepIdx: Int? = null
        private var dragOffsetX = 0f
        private var dragOffsetY = 0f
        private var pinchStartSpan = 1f
        private var pinchStartRadius = 0.08f
        private var snapAlignX: Float? = null
        private var snapAlignY: Float? = null

        private val chevronPath = Path()
        private val hudRect = RectF()

        private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            color = Color.parseColor("#AA38BDF8")
        }
        private val snapGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.parseColor("#EE22C55E")
        }
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            color = Color.parseColor("#88E2E8F0")
        }
        private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        private val macroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            color = Color.parseColor("#CCF59E0B")
        }
        private val macroStepFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#D978350F")
        }
        private val macroStepStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.parseColor("#FBBF24")
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        private val innerJoyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.parseColor("#DCE2E8F0")
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#38BDF8")
        }
        private val gripBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FF0EA5E9")
        }
        private val gripStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }
        private val gripArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 1.5f, Color.BLACK)
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textAlign = Paint.Align.CENTER
            textSize = 20f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#CC090D16")
        }
        private val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#6638BDF8")
        }
        private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textAlign = Paint.Align.CENTER
            textSize = 26f
            typeface = Typeface.MONOSPACE
        }
        private val testDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#22C55E")
        }
        private val testLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            color = Color.parseColor("#9922C55E")
        }

        fun setProfileNodes(list: List<TouchNodeConfig>, selected: String?) {
            nodes = list
            selectedId = selected
            invalidate()
        }

        fun setEditorVisualOptions(showGuides: Boolean, opacityPercent: Int, testMode: Boolean) {
            this.showGuides = showGuides
            this.opacityFrac = (opacityPercent / 100f).coerceIn(0f, 1f)
            this.testMode = testMode
            invalidate()
        }

        fun setLiveState(live: LiveControllerState, cfg: DriverConfig) {
            this.liveState = live
            this.driverConfig = cfg
            if (testMode) invalidate()
        }

        private fun dpF(v: Float): Float = dpProvider(v)

        private fun computeDisplayRadius(n: TouchNodeConfig, minDim: Float): Float {
            return (n.radiusNorm * minDim).coerceIn(22f, 240f)
        }

        private fun computeResizeGripPos(cx: Float, cy: Float, r: Float, w: Float, h: Float): Pair<Float, Float> {
            val gx = (cx + r * 0.7071f).coerceIn(28f, w - 28f)
            val gy = (cy + r * 0.7071f).coerceIn(28f, h - 28f)
            return gx to gy
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val minDim = minOf(w, h)

            val activeNode = nodes.firstOrNull { it.id == (activeNodeId ?: selectedId) }

            // 1. Full-screen alignment guide lines for active/selected node (or snapped lines)
            if (showGuides && activeNode != null) {
                val targetX: Float
                val targetY: Float
                val stepIdx = draggingMacroStepIdx
                if (dragMode == DragMode.MOVE_MACRO_STEP && stepIdx != null && stepIdx in activeNode.macroSteps.indices) {
                    targetX = activeNode.macroSteps[stepIdx].xNorm * w
                    targetY = activeNode.macroSteps[stepIdx].yNorm * h
                } else {
                    targetX = activeNode.xNorm * w
                    targetY = activeNode.yNorm * h
                }

                // Subtle center screen crosshair reference when dragging
                if (dragMode == DragMode.MOVE_NODE) {
                    guideLinePaint.color = Color.parseColor("#3394A3B8")
                    canvas.drawLine(w * 0.5f, 0f, w * 0.5f, h, guideLinePaint)
                    canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, guideLinePaint)
                }

                // Active vertical & horizontal guide lines
                if (snapAlignX != null) {
                    canvas.drawLine(snapAlignX!!, 0f, snapAlignX!!, h, snapGuidePaint)
                } else {
                    guideLinePaint.color = Color.parseColor("#9938BDF8")
                    canvas.drawLine(targetX, 0f, targetX, h, guideLinePaint)
                }

                if (snapAlignY != null) {
                    canvas.drawLine(0f, snapAlignY!!, w, snapAlignY!!, snapGuidePaint)
                } else {
                    guideLinePaint.color = Color.parseColor("#9938BDF8")
                    canvas.drawLine(0f, targetY, w, targetY, guideLinePaint)
                }
            }

            // 2. Draw all nodes
            val idleAlphaFloor = maxOf(0.32f, opacityFrac)
            nodes.forEach { n ->
                val cx = n.xNorm * w
                val cy = n.yNorm * h
                val isSelected = n.id == selectedId
                val isLivePressed = testMode && isNodeTriggeredLive(n, liveState, nodes)
                val r = computeDisplayRadius(n, minDim)
                val alphaFactor = if (isSelected || isLivePressed) 1f else idleAlphaFloor

                // Outer aiming / camera swipe dashed zone
                if (n.kind == TouchNodeKind.SKILL_AIM) {
                    val aimR = (n.radiusNorm * 1.45f * minDim).coerceIn(r + 10f, 260f)
                    zonePaint.color = if (isLivePressed) Color.parseColor("#CC22C55E") else Color.parseColor("#8834D399")
                    zonePaint.alpha = (alphaFactor * 185f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(cx, cy, aimR, zonePaint)
                } else if (n.kind == TouchNodeKind.CAMERA_SWIPE) {
                    zonePaint.color = if (isLivePressed) Color.parseColor("#CC22C55E") else Color.parseColor("#88A855F7")
                    zonePaint.alpha = (alphaFactor * 185f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(cx, cy, r, zonePaint)
                }

                // Macro waypoints
                if (n.kind == TouchNodeKind.MACRO && n.macroSteps.isNotEmpty() && isSelected) {
                    var prevX = cx
                    var prevY = cy
                    n.macroSteps.forEachIndexed { idx, step ->
                        val sx = step.xNorm * w
                        val sy = step.yNorm * h
                        canvas.drawLine(prevX, prevY, sx, sy, macroLinePaint)
                        canvas.drawCircle(sx, sy, 30f, macroStepFill)
                        canvas.drawCircle(sx, sy, 30f, macroStepStroke)
                        labelPaint.textSize = 22f
                        canvas.drawText("${idx + 1}", sx, sy + 8f, labelPaint)
                        prevX = sx
                        prevY = sy
                    }
                }

                // Node fill + crisp UP主 ring
                fillPaint.color = when {
                    isLivePressed -> Color.parseColor("#4422C55E")
                    isSelected -> Color.parseColor("#380EA5E9")
                    else -> Color.parseColor("#240B1120")
                }
                borderPaint.color = when {
                    isLivePressed -> Color.parseColor("#22C55E")
                    isSelected -> Color.parseColor("#38BDF8")
                    n.kind == TouchNodeKind.CAMERA_SWIPE -> Color.parseColor("#A855F7")
                    n.kind == TouchNodeKind.RAPID_TAP -> Color.parseColor("#F59E0B")
                    n.kind == TouchNodeKind.MACRO -> Color.parseColor("#FBBF24")
                    n.kind == TouchNodeKind.SKILL_AIM -> Color.parseColor("#34D399")
                    else -> Color.parseColor("#2E7CF6")
                }
                borderPaint.alpha = (alphaFactor * 240f).roundToInt().coerceIn(0, 255)
                borderPaint.strokeWidth = if (isSelected || isLivePressed) 4f else 3f

                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, borderPaint)

                // Inner precision crosshair (+) for selected/dragged node
                if (showGuides && isSelected) {
                    canvas.drawLine(cx - r * 0.72f, cy, cx + r * 0.72f, cy, crosshairPaint)
                    canvas.drawLine(cx, cy - r * 0.72f, cx, cy + r * 0.72f, crosshairPaint)
                }

                // UP主-style double concentric ring + 4 cardinal directional arrows for JOYSTICK
                if (n.kind == TouchNodeKind.JOYSTICK) {
                    val innerR = (r * 0.44f).coerceIn(14f, 96f)
                    innerJoyPaint.alpha = (alphaFactor * 220f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(cx, cy, innerR, innerJoyPaint)
                    arrowPaint.alpha = (alphaFactor * 230f).roundToInt().coerceIn(0, 255)
                    drawCardinalChevrons(canvas, cx, cy, (r + innerR) * 0.5f, (r * 0.09f).coerceIn(5f, 12f), arrowPaint, chevronPath)
                }

                // Label + subtle sub-tag for special types
                val trigLabel = n.overlayLabel()
                labelPaint.alpha = (alphaFactor * 255f).roundToInt().coerceIn(0, 255)
                labelPaint.textSize = when {
                    n.kind == TouchNodeKind.JOYSTICK -> (r * 0.34f).coerceIn(22f, 44f)
                    trigLabel.length >= 4 -> (r * 0.42f).coerceIn(16f, 24f)
                    else -> (r * 0.54f).coerceIn(20f, 34f)
                }
                val showSubTag = n.kind == TouchNodeKind.RAPID_TAP || n.kind == TouchNodeKind.MACRO || n.kind == TouchNodeKind.SKILL_AIM
                val labelYOffset = if (showSubTag) -4f else labelPaint.textSize * 0.35f
                canvas.drawText(trigLabel, cx, cy + labelYOffset, labelPaint)

                if (showSubTag) {
                    subPaint.alpha = (alphaFactor * 220f).roundToInt().coerceIn(0, 255)
                    subPaint.textSize = (r * 0.28f).coerceIn(14f, 20f)
                    val tag = when (n.kind) {
                        TouchNodeKind.RAPID_TAP -> "${n.rapidHz}Hz"
                        TouchNodeKind.MACRO -> "宏${n.macroSteps.size.coerceAtLeast(1)}步"
                        TouchNodeKind.SKILL_AIM -> "指向"
                        else -> ""
                    }
                    canvas.drawText(tag, cx, cy + subPaint.textSize + 8f, subPaint)
                }

                // 3. Live Test Mode physical stick / skill deflection indicator
                if (testMode) {
                    if (n.kind == TouchNodeKind.JOYSTICK) {
                        val stickCfg = if (n.input == ControllerInputSource.RIGHT_STICK) driverConfig.rightStick else driverConfig.leftStick
                        val rawX = if (n.input == ControllerInputSource.RIGHT_STICK) liveState.rx else liveState.lx
                        val rawY = if (n.input == ControllerInputSource.RIGHT_STICK) liveState.ry else liveState.ly
                        val (sx, sy) = StickMath.shapeStick(rawX, rawY, stickCfg)
                        if (hypot(sx, sy) > 0.02f) {
                            val dotX = cx + sx * r
                            val dotY = cy + sy * r
                            canvas.drawLine(cx, cy, dotX, dotY, testLinePaint)
                            canvas.drawCircle(dotX, dotY, 12f, testDotPaint)
                        }
                    } else if (n.kind == TouchNodeKind.SKILL_AIM && isLivePressed) {
                        val (sx, sy) = StickMath.shapeStick(liveState.rx, liveState.ry, driverConfig.rightStick)
                        val aimR = (n.radiusNorm * 1.45f * minDim).coerceIn(r + 10f, 260f)
                        val dotX = cx + sx * aimR
                        val dotY = cy + sy * aimR
                        canvas.drawLine(cx, cy, dotX, dotY, testLinePaint)
                        canvas.drawCircle(dotX, dotY, 13f, testDotPaint)
                    }
                }

                // 4. Resize grip pill (↘) on the 45° bottom-right perimeter of the selected node
                if (isSelected) {
                    val (gx, gy) = computeResizeGripPos(cx, cy, r, w, h)
                    val gripR = dpF(11f)
                    canvas.drawCircle(gx, gy, gripR, gripBgPaint)
                    canvas.drawCircle(gx, gy, gripR, gripStrokePaint)
                    // Tiny diagonal resize glyph inside pill
                    val d = gripR * 0.38f
                    canvas.drawLine(gx - d, gy - d, gx + d, gy + d, gripArrowPaint)
                    canvas.drawLine(gx + d, gy + d, gx + d * 0.1f, gy + d, gripArrowPaint)
                    canvas.drawLine(gx + d, gy + d, gx + d, gy + d * 0.1f, gripArrowPaint)
                }
            }

            // 5. Live Coordinate & Radius HUD badge
            val hudNode = activeNode
            if (hudNode != null) {
                val alignTag = buildString {
                    if (snapAlignX != null) append(" · 垂直对齐")
                    if (snapAlignY != null) append(" · 水平对齐")
                }
                val modePrefix = if (testMode) "测试模式 · " else ""
                val hudText = "$modePrefix${hudNode.overlayLabel()}  X ${"%.1f".format(hudNode.xNorm * 100f)}%  Y ${"%.1f".format(hudNode.yNorm * 100f)}%  半径 ${"%.1f".format(hudNode.radiusNorm * 100f)}%$alignTag"
                val textW = hudTextPaint.measureText(hudText)
                val pillW = textW + dpF(28f)
                val pillH = dpF(28f)
                val topY = h - dpF(92f)
                hudRect.set((w - pillW) / 2f, topY, (w + pillW) / 2f, topY + pillH)
                canvas.drawRoundRect(hudRect, pillH / 2f, pillH / 2f, hudBgPaint)
                canvas.drawRoundRect(hudRect, pillH / 2f, pillH / 2f, hudBorderPaint)
                canvas.drawText(hudText, w / 2f, hudRect.centerY() + 9f, hudTextPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val minDim = minOf(w, h)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val tx = event.x
                    val ty = event.y
                    snapAlignX = null
                    snapAlignY = null

                    // 1. Check resize grip handle on currently selected node
                    val selNode = nodes.firstOrNull { it.id == selectedId }
                    if (selNode != null) {
                        val cx = selNode.xNorm * w
                        val cy = selNode.yNorm * h
                        val r = computeDisplayRadius(selNode, minDim)
                        val (gx, gy) = computeResizeGripPos(cx, cy, r, w, h)
                        if (hypot((tx - gx).toDouble(), (ty - gy).toDouble()) <= dpF(24f)) {
                            dragMode = DragMode.RESIZE_HANDLE
                            activeNodeId = selNode.id
                            draggingMacroStepIdx = null
                            invalidate()
                            return true
                        }
                    }

                    // 2. Check macro waypoints on currently selected MACRO node
                    if (selNode != null && selNode.kind == TouchNodeKind.MACRO && selNode.macroSteps.isNotEmpty()) {
                        for (idx in selNode.macroSteps.indices.reversed()) {
                            val st = selNode.macroSteps[idx]
                            val sx = st.xNorm * w
                            val sy = st.yNorm * h
                            if (hypot((tx - sx).toDouble(), (ty - sy).toDouble()) <= dpF(24f)) {
                                dragMode = DragMode.MOVE_MACRO_STEP
                                activeNodeId = selNode.id
                                draggingMacroStepIdx = idx
                                dragOffsetX = tx - sx
                                dragOffsetY = ty - sy
                                invalidate()
                                return true
                            }
                        }
                    }

                    // 3. Hit test nodes (pick smallest / topmost matching node so overlapping buttons stay easy to grab)
                    val hit = nodes.lastOrNull { n ->
                        val cx = n.xNorm * w
                        val cy = n.yNorm * h
                        val hitR = maxOf(dpF(26f), computeDisplayRadius(n, minDim))
                        hypot((tx - cx).toDouble(), (ty - cy).toDouble()) <= hitR
                    }
                    if (hit != null) {
                        dragMode = DragMode.MOVE_NODE
                        activeNodeId = hit.id
                        draggingMacroStepIdx = null
                        selectedId = hit.id
                        dragOffsetX = tx - hit.xNorm * w
                        dragOffsetY = ty - hit.yNorm * h
                        onNodesChanged(nodes, selectedId)
                        invalidate()
                        return true
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 2-finger pinch resize on selected node!
                    val selNode = nodes.firstOrNull { it.id == (activeNodeId ?: selectedId) }
                    if (event.pointerCount == 2 && selNode != null) {
                        val span = hypot(
                            (event.getX(1) - event.getX(0)).toDouble(),
                            (event.getY(1) - event.getY(0)).toDouble()
                        ).toFloat()
                        if (span > dpF(16f)) {
                            dragMode = DragMode.PINCH_RESIZE
                            activeNodeId = selNode.id
                            draggingMacroStepIdx = null
                            snapAlignX = null
                            snapAlignY = null
                            pinchStartSpan = span
                            pinchStartRadius = selNode.radiusNorm
                            invalidate()
                            return true
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val id = activeNodeId ?: return false
                    when (dragMode) {
                        DragMode.RESIZE_HANDLE -> {
                            val target = nodes.firstOrNull { it.id == id } ?: return false
                            val cx = target.xNorm * w
                            val cy = target.yNorm * h
                            val dist = hypot((event.x - cx).toDouble(), (event.y - cy).toDouble()).toFloat()
                            val newRadius = (dist / minDim).coerceIn(0.025f, 0.28f)
                            nodes = nodes.map { if (it.id == id) it.copy(radiusNorm = newRadius) else it }
                            onNodesChanged(nodes, selectedId)
                            invalidate()
                            return true
                        }
                        DragMode.PINCH_RESIZE -> {
                            if (event.pointerCount >= 2) {
                                val curSpan = hypot(
                                    (event.getX(1) - event.getX(0)).toDouble(),
                                    (event.getY(1) - event.getY(0)).toDouble()
                                ).toFloat()
                                val scale = (curSpan / pinchStartSpan.coerceAtLeast(10f)).coerceIn(0.25f, 4f)
                                val newRadius = (pinchStartRadius * scale).coerceIn(0.025f, 0.28f)
                                nodes = nodes.map { if (it.id == id) it.copy(radiusNorm = newRadius) else it }
                                onNodesChanged(nodes, selectedId)
                                invalidate()
                            }
                            return true
                        }
                        DragMode.MOVE_MACRO_STEP -> {
                            val stepIdx = draggingMacroStepIdx ?: return false
                            val nx = ((event.x - dragOffsetX) / w).coerceIn(0.02f, 0.98f)
                            val ny = ((event.y - dragOffsetY) / h).coerceIn(0.02f, 0.98f)
                            nodes = nodes.map { n ->
                                if (n.id == id && stepIdx in n.macroSteps.indices) {
                                    val nextSteps = n.macroSteps.toMutableList()
                                    nextSteps[stepIdx] = nextSteps[stepIdx].copy(xNorm = nx, yNorm = ny)
                                    n.copy(macroSteps = nextSteps)
                                } else n
                            }
                            onNodesChanged(nodes, selectedId)
                            invalidate()
                            return true
                        }
                        DragMode.MOVE_NODE -> {
                            var targetX = (event.x - dragOffsetX).coerceIn(w * 0.02f, w * 0.98f)
                            var targetY = (event.y - dragOffsetY).coerceIn(h * 0.02f, h * 0.98f)

                            if (showGuides) {
                                val snapThreshold = dpF(8f)
                                val xCandidates = mutableListOf(w * 0.5f)
                                val yCandidates = mutableListOf(h * 0.5f)
                                nodes.forEach { other ->
                                    if (other.id != id) {
                                        xCandidates.add(other.xNorm * w)
                                        yCandidates.add(other.yNorm * h)
                                    }
                                }
                                val bestX = xCandidates.minByOrNull { abs(targetX - it) }
                                if (bestX != null && abs(targetX - bestX) <= snapThreshold) {
                                    targetX = bestX
                                    snapAlignX = bestX
                                } else {
                                    snapAlignX = null
                                }
                                val bestY = yCandidates.minByOrNull { abs(targetY - it) }
                                if (bestY != null && abs(targetY - bestY) <= snapThreshold) {
                                    targetY = bestY
                                    snapAlignY = bestY
                                } else {
                                    snapAlignY = null
                                }
                            } else {
                                snapAlignX = null
                                snapAlignY = null
                            }

                            val nx = (targetX / w).coerceIn(0.02f, 0.98f)
                            val ny = (targetY / h).coerceIn(0.02f, 0.98f)
                            nodes = nodes.map { if (it.id == id) it.copy(xNorm = nx, yNorm = ny) else it }
                            onNodesChanged(nodes, selectedId)
                            invalidate()
                            return true
                        }
                        DragMode.NONE -> return false
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (dragMode == DragMode.PINCH_RESIZE) {
                        dragMode = DragMode.NONE
                        activeNodeId = null
                        invalidate()
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragMode = DragMode.NONE
                    activeNodeId = null
                    draggingMacroStepIdx = null
                    snapAlignX = null
                    snapAlignY = null
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}

private fun drawCardinalChevrons(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    ringMidR: Float,
    arrowSize: Float,
    paint: Paint,
    path: Path
) {
    // Up ▲
    path.reset()
    path.moveTo(cx - arrowSize, cy - ringMidR + arrowSize * 0.45f)
    path.lineTo(cx, cy - ringMidR - arrowSize * 0.45f)
    path.lineTo(cx + arrowSize, cy - ringMidR + arrowSize * 0.45f)
    canvas.drawPath(path, paint)

    // Down ▼
    path.reset()
    path.moveTo(cx - arrowSize, cy + ringMidR - arrowSize * 0.45f)
    path.lineTo(cx, cy + ringMidR + arrowSize * 0.45f)
    path.lineTo(cx + arrowSize, cy + ringMidR - arrowSize * 0.45f)
    canvas.drawPath(path, paint)

    // Left ◀
    path.reset()
    path.moveTo(cx - ringMidR + arrowSize * 0.45f, cy - arrowSize)
    path.lineTo(cx - ringMidR - arrowSize * 0.45f, cy)
    path.lineTo(cx - ringMidR + arrowSize * 0.45f, cy + arrowSize)
    canvas.drawPath(path, paint)

    // Right ▶
    path.reset()
    path.moveTo(cx + ringMidR - arrowSize * 0.45f, cy - arrowSize)
    path.lineTo(cx + ringMidR + arrowSize * 0.45f, cy)
    path.lineTo(cx + ringMidR - arrowSize * 0.45f, cy + arrowSize)
    canvas.drawPath(path, paint)
}

private fun isInputPressedLive(src: ControllerInputSource, live: LiveControllerState): Boolean {
    return when (src) {
        ControllerInputSource.LEFT_STICK -> hypot(live.lx / 32767f, live.ly / 32767f) > 0.08f
        ControllerInputSource.RIGHT_STICK -> hypot(live.rx / 32767f, live.ry / 32767f) > 0.08f
        ControllerInputSource.A -> live.a
        ControllerInputSource.B -> live.b
        ControllerInputSource.X -> live.x
        ControllerInputSource.Y -> live.y
        ControllerInputSource.LB -> live.lb
        ControllerInputSource.RB -> live.rb
        ControllerInputSource.LT -> live.lt >= 48
        ControllerInputSource.RT -> live.rt >= 48
        ControllerInputSource.L3 -> live.l3
        ControllerInputSource.R3 -> live.r3
        ControllerInputSource.BACK -> live.back
        ControllerInputSource.START -> live.start
        ControllerInputSource.DPAD_UP -> live.hatY < 0
        ControllerInputSource.DPAD_DOWN -> live.hatY > 0
        ControllerInputSource.DPAD_LEFT -> live.hatX < 0
        ControllerInputSource.DPAD_RIGHT -> live.hatX > 0
        ControllerInputSource.M1 -> live.m1
        ControllerInputSource.M2 -> live.m2
        ControllerInputSource.M3 -> live.m3
        ControllerInputSource.M4 -> live.m4
        ControllerInputSource.LM -> live.lm
        ControllerInputSource.RM -> live.rm
    }
}

private fun isNodeTriggeredLive(
    node: TouchNodeConfig,
    live: LiveControllerState,
    allNodes: List<TouchNodeConfig>
): Boolean {
    val mod = node.modifierInput
    if (mod == null) {
        val suppressed = allNodes.any { other ->
            other.modifierInput != null &&
                other.input == node.input &&
                isInputPressedLive(other.modifierInput, live) &&
                isInputPressedLive(other.input, live)
        }
        if (suppressed) return false
        return isInputPressedLive(node.input, live)
    }
    return isInputPressedLive(mod, live) && isInputPressedLive(node.input, live)
}
