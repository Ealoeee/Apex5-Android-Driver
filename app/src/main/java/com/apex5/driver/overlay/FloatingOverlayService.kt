package com.apex5.driver.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.apex5.driver.model.ControllerInputSource
import com.apex5.driver.model.DriverOutputMode
import com.apex5.driver.model.LiveControllerState
import com.apex5.driver.model.TouchNodeConfig
import com.apex5.driver.model.TouchNodeKind
import com.apex5.driver.model.TouchProfile
import com.apex5.driver.service.DriverController
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
                updateMenuTexts(cfg.outputMode, activeProfile?.name ?: "--", cfg.showTouchHints, status.serviceRunning)
                updateHintsVisibility(
                    show = !isEditing && cfg.outputMode == DriverOutputMode.TOUCH_MAPPER && cfg.showTouchHints,
                    profile = activeProfile
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
        container.addView(menu, FrameLayout.LayoutParams(dp(230f), ViewGroup.LayoutParams.WRAP_CONTENT))
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
        updateMenuTexts(cfg.outputMode, activeProfile.name, cfg.showTouchHints, DriverController.status.value.serviceRunning)
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
    }

    private fun updateHintsVisibility(show: Boolean, profile: TouchProfile?) {
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
        hintsView?.setNodes(profile.nodes)
    }

    private fun openEditor() {
        if (isEditing) return
        isEditing = true
        updateHintsVisibility(false, null)
        bubbleView?.visibility = View.GONE

        val startProfile = DriverController.activeTouchProfile(this)
        var workingProfile = startProfile.copy(nodes = startProfile.nodes.toList())
        var selectedNodeId: String? = workingProfile.nodes.firstOrNull()?.id

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#55090E1A"))
        }

        lateinit var canvasView: EditorCanvasView
        lateinit var inspectorBar: LinearLayout
        lateinit var selectedTitle: TextView
        lateinit var kindBtn: Button
        lateinit var gyroBtn: Button
        lateinit var paramLabel: TextView
        lateinit var paramSeek: SeekBar
        lateinit var addPickerPanel: LinearLayout
        var addListeningInput = false

        fun refreshInspector() {
            val node = workingProfile.nodes.firstOrNull { it.id == selectedNodeId }
            if (node == null) {
                inspectorBar.visibility = View.GONE
                return
            }
            inspectorBar.visibility = View.VISIBLE
            selectedTitle.text = "${node.input.label}"
            kindBtn.text = "类型: ${node.kind.label}"
            gyroBtn.visibility = if (node.kind == TouchNodeKind.CAMERA_SWIPE) View.VISIBLE else View.GONE
            gyroBtn.text = if (node.gyroAssist) "陀螺仪: 开" else "陀螺仪: 关"

            when (node.kind) {
                TouchNodeKind.TAP -> {
                    paramLabel.text = "显示尺寸: ${(node.radiusNorm * 100).roundToInt()}%"
                    paramSeek.max = 12
                    paramSeek.progress = ((node.radiusNorm * 100f).roundToInt() - 3).coerceIn(0, 12)
                }
                TouchNodeKind.RAPID_TAP -> {
                    paramLabel.text = "连发频率: ${node.rapidHz} 次/秒"
                    paramSeek.max = 22
                    paramSeek.progress = (node.rapidHz - 3).coerceIn(0, 22)
                }
                TouchNodeKind.JOYSTICK -> {
                    paramLabel.text = "轮盘半径: ${(node.radiusNorm * 100).roundToInt()}%"
                    paramSeek.max = 18
                    paramSeek.progress = ((node.radiusNorm * 100f).roundToInt() - 5).coerceIn(0, 18)
                }
                TouchNodeKind.CAMERA_SWIPE -> {
                    paramLabel.text = "视角灵敏度: %.2fx".format(node.sensitivity)
                    paramSeek.max = 28
                    paramSeek.progress = (((node.sensitivity - 0.3f) / 0.1f).roundToInt()).coerceIn(0, 28)
                }
            }
        }

        canvasView = EditorCanvasView(
            context = this,
            onNodesChanged = { updated, selId ->
                workingProfile = workingProfile.copy(nodes = updated)
                selectedNodeId = selId
                refreshInspector()
            }
        )
        canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
        root.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top toolbar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#E60F172A"))
                setStroke(dp(1f), Color.parseColor("#334155"))
            }
        }
        val profileTag = TextView(this).apply {
            text = "编辑方案: ${workingProfile.name}"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(10f), 0)
        }
        val addBtn = makeMiniBtn("+ 添加键位", "#0EA5E9", "#082F49") {
            addListeningInput = addPickerPanel.visibility != View.VISIBLE
            addPickerPanel.visibility = if (addListeningInput) View.VISIBLE else View.GONE
        }
        val cancelBtn = makeMiniBtn("取消", "#334155", "#E2E8F0") {
            closeEditor(save = false)
        }
        val saveBtn = makeMiniBtn("保存并退出", "#22C55E", "#052E16") {
            DriverController.saveTouchProfile(this, workingProfile)
            closeEditor(save = true)
        }
        topBar.addView(profileTag)
        topBar.addView(addBtn)
        addHorizontalGap(topBar, 8f)
        topBar.addView(cancelBtn)
        addHorizontalGap(topBar, 8f)
        topBar.addView(saveBtn)

        val topLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(12f) }
        root.addView(topBar, topLp)

        // Quick Add Picker Panel (supports tapping OR pressing physical button on Apex 5!)
        addPickerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#F20B1120"))
                setStroke(dp(1.5f), Color.parseColor("#38BDF8"))
            }
        }
        val pickerHint = TextView(this).apply {
            text = "点选要添加的按键，或直接按一下手柄上的实体按键 (如 M1/RT/A)："
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            setPadding(0, 0, 0, dp(8f))
        }
        addPickerPanel.addView(pickerHint)

        fun addInputNode(src: ControllerInputSource) {
            addListeningInput = false
            addPickerPanel.visibility = View.GONE
            val existing = workingProfile.nodes.firstOrNull { it.input == src }
            if (existing != null) {
                selectedNodeId = existing.id
                canvasView.setProfileNodes(workingProfile.nodes, selectedNodeId)
                refreshInspector()
                return
            }
            val kind = when (src) {
                ControllerInputSource.LEFT_STICK -> TouchNodeKind.JOYSTICK
                ControllerInputSource.RIGHT_STICK -> TouchNodeKind.CAMERA_SWIPE
                else -> TouchNodeKind.TAP
            }
            val radius = when (kind) {
                TouchNodeKind.JOYSTICK -> 0.10f
                TouchNodeKind.CAMERA_SWIPE -> 0.14f
                else -> 0.045f
            }
            val node = TouchNodeConfig(
                id = "n_" + UUID.randomUUID().toString().take(8),
                input = src,
                kind = kind,
                xNorm = 0.5f,
                yNorm = 0.5f,
                radiusNorm = radius
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
        ).apply { topMargin = dp(64f) }
        root.addView(addPickerPanel, pickerLp)

        // Bottom inspector bar
        inspectorBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#E60F172A"))
                setStroke(dp(1f), Color.parseColor("#334155"))
            }
        }
        selectedTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(10f), 0)
        }
        kindBtn = makeMiniBtn("类型", "#1E293B", "#38BDF8") {
            val curId = selectedNodeId ?: return@makeMiniBtn
            val nextNodes = workingProfile.nodes.map { n ->
                if (n.id == curId) {
                    val kinds = TouchNodeKind.entries
                    val nextKind = kinds[(kinds.indexOf(n.kind) + 1) % kinds.size]
                    n.copy(kind = nextKind)
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
        paramLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11.5f
            setPadding(dp(8f), 0, dp(6f), 0)
        }
        paramSeek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(130f), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val curId = selectedNodeId ?: return
                    val nextNodes = workingProfile.nodes.map { n ->
                        if (n.id == curId) {
                            when (n.kind) {
                                TouchNodeKind.TAP -> n.copy(radiusNorm = (progress + 3) / 100f)
                                TouchNodeKind.RAPID_TAP -> n.copy(rapidHz = (progress + 3).coerceIn(3, 25))
                                TouchNodeKind.JOYSTICK -> n.copy(radiusNorm = (progress + 5) / 100f)
                                TouchNodeKind.CAMERA_SWIPE -> n.copy(sensitivity = (0.3f + progress * 0.1f).coerceIn(0.3f, 3.1f))
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
        inspectorBar.addView(gyroBtn)
        inspectorBar.addView(paramLabel)
        inspectorBar.addView(paramSeek)
        addHorizontalGap(inspectorBar, 6f)
        inspectorBar.addView(deleteBtn)

        val botLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(14f) }
        root.addView(inspectorBar, botLp)
        refreshInspector()

        // Listen for physical controller press while addPickerPanel is open
        scope.launch {
            var prev = LiveControllerState()
            DriverController.status.collect { st ->
                if (!isEditing || !addListeningInput) {
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
            profile = activeProfile
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

    private class HintsOverlayView(context: Context) : View(context) {
        private var nodes: List<TouchNodeConfig> = emptyList()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#2A0F172A")
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.parseColor("#6638BDF8")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAFFFFFF")
            textAlign = Paint.Align.CENTER
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }

        fun setNodes(list: List<TouchNodeConfig>) {
            nodes = list
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val minDim = minOf(w, h)
            nodes.forEach { n ->
                val cx = n.xNorm * w
                val cy = n.yNorm * h
                val r = (n.radiusNorm * minDim).coerceAtLeast(36f)
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, ringPaint)
                canvas.drawText(n.input.shortLabel, cx, cy + 10f, textPaint)
            }
        }
    }

    private class EditorCanvasView(
        context: Context,
        private val onNodesChanged: (List<TouchNodeConfig>, String?) -> Unit
    ) : View(context) {
        private var nodes: List<TouchNodeConfig> = emptyList()
        private var selectedId: String? = null
        private var draggingId: String? = null
        private var dragOffsetX = 0f
        private var dragOffsetY = 0f

        private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
            color = Color.parseColor("#8838BDF8")
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textAlign = Paint.Align.CENTER
            textSize = 22f
        }

        fun setProfileNodes(list: List<TouchNodeConfig>, selected: String?) {
            nodes = list
            selectedId = selected
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val minDim = minOf(w, h)

            nodes.forEach { n ->
                val cx = n.xNorm * w
                val cy = n.yNorm * h
                val isSelected = n.id == selectedId
                val zoneR = n.radiusNorm * minDim

                if (n.kind == TouchNodeKind.JOYSTICK || n.kind == TouchNodeKind.CAMERA_SWIPE) {
                    zonePaint.color = if (n.kind == TouchNodeKind.CAMERA_SWIPE) {
                        Color.parseColor("#99A855F7")
                    } else {
                        Color.parseColor("#9938BDF8")
                    }
                    canvas.drawCircle(cx, cy, zoneR, zonePaint)
                }

                val handleR = when (n.kind) {
                    TouchNodeKind.TAP, TouchNodeKind.RAPID_TAP -> zoneR.coerceIn(42f, 88f)
                    else -> 54f
                }

                fillPaint.color = if (isSelected) Color.parseColor("#CC0284C7") else Color.parseColor("#B30F172A")
                borderPaint.color = when {
                    isSelected -> Color.parseColor("#22C55E")
                    n.kind == TouchNodeKind.CAMERA_SWIPE -> Color.parseColor("#A855F7")
                    n.kind == TouchNodeKind.RAPID_TAP -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#38BDF8")
                }

                canvas.drawCircle(cx, cy, handleR, fillPaint)
                canvas.drawCircle(cx, cy, handleR, borderPaint)
                canvas.drawText(n.input.shortLabel, cx, cy + 8f, labelPaint)
                val kindTag = when (n.kind) {
                    TouchNodeKind.TAP -> "点击"
                    TouchNodeKind.RAPID_TAP -> "${n.rapidHz}Hz"
                    TouchNodeKind.JOYSTICK -> "摇杆"
                    TouchNodeKind.CAMERA_SWIPE -> "视角"
                }
                canvas.drawText(kindTag, cx, cy + 32f, subPaint)
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
                    val hit = nodes.lastOrNull { n ->
                        val cx = n.xNorm * w
                        val cy = n.yNorm * h
                        val hitR = maxOf(68f, if (n.kind == TouchNodeKind.TAP || n.kind == TouchNodeKind.RAPID_TAP) n.radiusNorm * minDim else 68f)
                        hypot((tx - cx).toDouble(), (ty - cy).toDouble()) <= hitR
                    }
                    if (hit != null) {
                        draggingId = hit.id
                        selectedId = hit.id
                        dragOffsetX = tx - hit.xNorm * w
                        dragOffsetY = ty - hit.yNorm * h
                        onNodesChanged(nodes, selectedId)
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val id = draggingId ?: return false
                    val nx = ((event.x - dragOffsetX) / w).coerceIn(0.02f, 0.98f)
                    val ny = ((event.y - dragOffsetY) / h).coerceIn(0.02f, 0.98f)
                    nodes = nodes.map { n -> if (n.id == id) n.copy(xNorm = nx, yNorm = ny) else n }
                    onNodesChanged(nodes, selectedId)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingId = null
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
