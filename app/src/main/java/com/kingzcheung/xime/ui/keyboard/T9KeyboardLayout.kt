package com.kingzcheung.xime.ui.keyboard

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.DisplayMode
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.swipeHandlerFor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 拼音九键（T9）键盘布局。
 *
 * 布局说明：
 * - 左侧候选区：占用右侧第 1/4/7 三个数字键的垂直高度，显示当前音节候选拼音。
 * - 右侧主键区：
 *   第1行：分词(1) | ABC(2) | DEF(3) | 退格
 *   第2行：GHI(4) | JKL(5) | MNO(6) | 重输
 *   第3行：PQRS(7) | TUV(8) | WXYZ(9) | 0
 *   第4行：符 | 123 | 空格(显示方案名) | 中/En(地球图标) | 发送
 */
@Composable
fun T9KeyboardLayout(
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    t9Controller: T9InputController,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    accentColor: Color = Color(0xFF1A73E8),
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val controller = t9Controller
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun handleDelete() {
        // onDeleted 在后台队列中处理（flush 不阻塞 UI），结果通过回调返回（Main 线程）。
        controller.onDeleted { result ->
            when (result) {
                T9InputController.DeleteResult.UNDO_COMMIT -> {
                    controller.clearRimeAndResend()
                }

                T9InputController.DeleteResult.NOT_CONSUMED -> {
                    onKeyPress("delete")
                }

                T9InputController.DeleteResult.DELETED, T9InputController.DeleteResult.UNDO_CHOICE -> {
                }
            }
        }
    }

    T9KeyboardSwipeOverlay(
        modifier = modifier,
        keyboardBackgroundColor = keyboardBackgroundColor,
        keyCornerRadius = keyCornerRadius,
        keyTextColor = keyTextColor,
        isLandscape = isLandscape,
        isFloatingMode = isFloatingMode,
        onKeyPress = onKeyPress,
        callbacks = callbacks,
        uiState = uiState,
        controller = controller,
        keyBackgroundColor = keyBackgroundColor,
        specialKeyBackgroundColor = specialKeyBackgroundColor,
        bubbleBackgroundColor = bubbleBackgroundColor,
        accentColor = accentColor,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        onKeyPressDown = onKeyPressDown,
        onDelete = ::handleDelete,
        specialKeyTextColor = specialKeyTextColor,
        candidateState = candidateState,
        keySpacingX = keySpacingX,
        keySpacingY = keySpacingY,
        onGestureAction = onGestureAction,
    )
}


// ─── 滑动气泡覆盖层（隔离 swipeState 作用域，避免全键盘重组） ────────

@Composable
private fun T9KeyboardSwipeOverlay(
    modifier: Modifier,
    keyboardBackgroundColor: Color,
    keyCornerRadius: Dp,
    keyTextColor: Color,
    isLandscape: Boolean,
    isFloatingMode: Boolean,
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    controller: T9InputController,
    keyBackgroundColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    accentColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    onDelete: () -> Unit,
    specialKeyTextColor: Color = Color.White,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else {
            state
        }
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - keyboardBounds.left,
                top = bounds.top - keyboardBounds.top,
                right = bounds.right - keyboardBounds.left,
                bottom = bounds.bottom - keyboardBounds.top
            )
        )
    }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeBubble.state,
        keyBounds = swipeBubble.keyBounds,
        keyBackgroundColor = bubbleBackgroundColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeBubble.state.isSwiping || swipeBubble.state.isPressed) swipeBubble.keyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width
    )

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius) {
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp)) {
        if (isLandscape) {
            // 横屏：去除原左侧候选大面板（候选已在顶部候选栏展示），
            // 九键布局撑满键盘区域（与全键盘横屏同款 50dp 边距）
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 2.dp, horizontal = 50.dp),
                ) {
                    T9KeyboardContent(
                        onKeyPress = onKeyPress,
                        callbacks = callbacks,
                        uiState = uiState,
                        controller = controller,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        accentColor = accentColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        onDelete = onDelete,
                        compactMode = true,
                        candidateState = candidateState,
                        onGestureAction = onGestureAction,
                    )
                }
            }
        } else {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                ) {
                    T9KeyboardContent(
                        onKeyPress = onKeyPress,
                        callbacks = callbacks,
                        uiState = uiState,
                        controller = controller,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        accentColor = accentColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        onDelete = onDelete,
                        compactMode = false,
                        candidateState = candidateState,
                        onGestureAction = onGestureAction,
                    )
                }
            }
        }
    }
    }
}

// ─── 九键键盘三列主体（竖屏整宽 / 横屏右栏复用） ──────────────────────

@SuppressLint("SuspiciousIndentation")
@Composable
private fun T9KeyboardContent(
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    controller: T9InputController,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    accentColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    onDelete: () -> Unit,
    compactMode: Boolean = false,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val t9DigitFontSize = if (compactMode) 13.sp else 16.sp
    val ctrlFontSize = if (compactMode) 11.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val candidateFontSize = if (compactMode) 11.sp else 13.sp
    val specialKeyTextColor = if (uiState.isDarkTheme) Color.White
        else KeyboardThemes.getAccentColor(uiState.themeId, false)

    // 数字键滑动手势（keyboard.t9.keys，热重载经 configVersion 感知）：
    // 上滑默认直接上屏数字（T9 模式 onKeyPress(数字) 会进拼音数字码组合，须走 onCommitText），
    // 下滑默认绑定快捷编辑动作。提示开关只控制提示显示；组件内上滑触发只看回调绑定，
    // 提示关闭/横屏紧凑时手势仍可用。上滑键面提示尊重 display: bubble（仅气泡不印键面）。
    val configVersion by KeysConfigHelper.configVersion.collectAsState()
    val swipeHints = rememberSwipeHintsEnabled()
    val hintsActive = !compactMode
    val commitDirect: (String) -> Unit =
        { text -> callbacks.onCommitText?.invoke(text) ?: onKeyPress(text) }

    fun swipesFor(id: String): T9KeySwipes {
        val gesture = KeysConfigHelper.getT9KeyGesture(id) ?: return T9KeySwipes()
        val upHint = gesture.swipeUp?.let { it.label.ifEmpty { it.value } }
        val downHint = gesture.swipeDown?.let { it.label.ifEmpty { it.value } }
        // display 只管静态键面提示位置（bubble 不画键面，用空串压制回退）；
        // 运行时气泡由 bubble 独立控制。手势回调与二者无关。
        val swipeUpKeyLabel = when {
            !swipeHints.up || !hintsActive -> null
            gesture.swipeUp?.display == DisplayMode.BUBBLE -> ""
            else -> upHint
        }
        val swipeDownKeyLabel = when {
            !swipeHints.down || !hintsActive -> null
            gesture.swipeDown?.display == DisplayMode.BUBBLE -> ""
            else -> downHint
        }
        return T9KeySwipes(
            onSwipeUp = swipeHandlerFor(gesture.swipeUp, commitDirect, onGestureAction),
            onSwipeDown = swipeHandlerFor(gesture.swipeDown, commitDirect, onGestureAction),
            swipeUpText = if (swipeHints.up && hintsActive && (gesture.swipeUp?.bubble ?: true)) upHint else null,
            swipeDownText = if (swipeHints.down && hintsActive && (gesture.swipeDown?.bubble ?: true)) downHint else null,
            swipeUpKeyLabel = swipeUpKeyLabel,
            swipeDownKeyLabel = swipeDownKeyLabel,
        )
    }

    val density = LocalDensity.current
    val candidateShadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, keyBackgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(keyBackgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ── 第1列：左侧候选区（拼音候选项） ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.8f),
            verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(3f)
                    .padding(LocalKeyVisualPadding.current)
                    .then(candidateShadowModifier)
                    .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                    .background(keyBackgroundColor)
            ) {
                val showCandidates = controller.leftPanelState != T9InputController.LeftPanelState.IDLE
                val currentFirstOptions = controller.firstOptions
                // 空闲态符号列表来自 xime.yaml keyboard.t9.side_symbols（可自定义，>4 滚动）
                val configVersion by KeysConfigHelper.configVersion.collectAsState()
                val t9SideSymbols = remember(configVersion) { KeysConfigHelper.getT9SideSymbols() }
                val displayItems: List<String> = if (showCandidates) {
                    currentFirstOptions.map { it.pinyin }
                } else {
                    t9SideSymbols
                }
                if (displayItems.size <= 4) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        displayItems.forEachIndexed { index, item ->
                            if (showCandidates) {
                            val option = currentFirstOptions[index]
                            val isSelected = controller.leftPanelState == T9InputController.LeftPanelState.SELECTION &&
                                    controller.selectedOption == option &&
                                    controller.isSelectedOptionInCurrentCandidates()
                                    CandidateItem(
                                        text = option.pinyin,
                                        onClick = { controller.onChoiceSelected(option) },
                                        onPress = { onKeyPressDown?.invoke(option.pinyin) },
                                        textColor = keyTextColor,
                                        backgroundColor = keyBackgroundColor,
                                        accentColor = accentColor,
                                        fontSize = candidateFontSize,
                                        isSelected = isSelected,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    )
                            } else {
                                CandidateItem(
                                    text = item,
                                    onClick = { onKeyPress(item) },
                                    onPress = { onKeyPressDown?.invoke(item) },
                                    textColor = keyTextColor,
                                    backgroundColor = keyBackgroundColor,
                                    accentColor = accentColor,
                                    fontSize = candidateFontSize,
                                    isSelected = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        itemsIndexed(displayItems) { index, item ->
                            if (showCandidates) {
                                val option = currentFirstOptions[index]
                                val isSelected = controller.leftPanelState == T9InputController.LeftPanelState.SELECTION &&
                                        controller.selectedOption == option &&
                                        controller.isSelectedOptionInCurrentCandidates()
                                CandidateItem(
                                    text = option.pinyin,
                                    onClick = { controller.onChoiceSelected(option) },
                                    onPress = { onKeyPressDown?.invoke(option.pinyin) },
                                    textColor = keyTextColor,
                                    backgroundColor = keyBackgroundColor,
                                    accentColor = accentColor,
                                    fontSize = candidateFontSize,
                                    isSelected = isSelected,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (compactMode) 26.dp else 32.dp)
                                )
                            } else {
                                CandidateItem(
                                    text = item,
                                    onClick = { onKeyPress(item) },
                                    onPress = { onKeyPressDown?.invoke(item) },
                                    textColor = keyTextColor,
                                    backgroundColor = keyBackgroundColor,
                                    accentColor = accentColor,
                                    fontSize = candidateFontSize,
                                    isSelected = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (compactMode) 26.dp else 32.dp)
                                )
                            }
                        }
                    }
                }
            }

            KeyButton(
                text = "符号",
                onClick = { onKeyPress("symbol") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                onPress = { onKeyPressDown?.invoke("symbol") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                fontSize = ctrlFontSize,
            )
        }

        // ── 第2列：数字键区 ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(3.4f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                NineKeyButton(
                    swipes = swipesFor("1"),

                    digit = "1", letters = "分词",
                    onClick = { controller.onDigitPressed("1") },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("1") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("2"),
                    digit = "2", letters = "ABC", longPressItems = listOf("A", "B", "C"),
                    onClick = { controller.onDigitPressed("2") },
                    onLongPressSelect = { letter -> controller.clearAll(); onKeyPress(letter) },
                    onSwipeStateChange = { state, bounds ->
                        if (!(state.isPressed && !state.isLongPress && state.pressedText != null))
                            onSwipeStateChange?.invoke(state, bounds)
                    },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("2") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("3"),
                    digit = "3", letters = "DEF", longPressItems = listOf("D", "E", "F"),
                    onClick = { controller.onDigitPressed("3") },
                    onLongPressSelect = { letter -> controller.clearAll(); onKeyPress(letter) },
                    onSwipeStateChange = { state, bounds ->
                        if (!(state.isPressed && !state.isLongPress && state.pressedText != null))
                            onSwipeStateChange?.invoke(state, bounds)
                    },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("3") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                T9DigitKey(
                    swipes = swipesFor("4"),
                    digit = "4", letters = "GHI", longPressItems = listOf("G", "H", "I"),
                    onClick = { controller.onDigitPressed("4") },
                    onLongPressSelect = { letter -> controller.clearAll(); onKeyPress(letter) },
                    onSwipeStateChange = { state, bounds ->
                        if (!(state.isPressed && !state.isLongPress && state.pressedText != null))
                            onSwipeStateChange?.invoke(state, bounds)
                    },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("4") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("5"),
                    digit = "5", letters = "JKL", longPressItems = listOf("J", "K", "L"),
                    onClick = { controller.onDigitPressed("5") },
                    onLongPressSelect = { letter -> controller.clearAll(); onKeyPress(letter) },
                    onSwipeStateChange = { state, bounds ->
                        if (!(state.isPressed && !state.isLongPress && state.pressedText != null))
                            onSwipeStateChange?.invoke(state, bounds)
                    },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("5") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("6"),
                    digit = "6", letters = "MNO", longPressItems = listOf("M", "N", "O"),
                    onClick = { controller.onDigitPressed("6") },
                    onLongPressSelect = { letter -> controller.clearAll(); onKeyPress(letter) },
                    onSwipeStateChange = { state, bounds ->
                        if (!(state.isPressed && !state.isLongPress && state.pressedText != null))
                            onSwipeStateChange?.invoke(state, bounds)
                    },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("6") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                T9DigitKey(
                    swipes = swipesFor("7"),
                    digit = "7", letters = "PQRS", longPressItems = listOf("P", "Q", "R", "S"),
                    onClick = { controller.onDigitPressed("7") },
                    onLongPressSelect = { letter -> controller.clearAll(); onKeyPress(letter) },
                    onSwipeStateChange = { state, bounds ->
                        if (!(state.isPressed && !state.isLongPress && state.pressedText != null))
                            onSwipeStateChange?.invoke(state, bounds)
                    },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("7") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("8"),
                    digit = "8", letters = "TUV", longPressItems = listOf("T", "U", "V"),
                    onClick = { controller.onDigitPressed("8") },
                    onLongPressSelect = { letter -> controller.clearAll(); onKeyPress(letter) },
                    onSwipeStateChange = { state, bounds ->
                        if (!(state.isPressed && !state.isLongPress && state.pressedText != null))
                            onSwipeStateChange?.invoke(state, bounds)
                    },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("8") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("9"),
                    digit = "9", letters = "WXYZ", longPressItems = listOf("W", "X", "Y", "Z"),
                    onClick = { controller.onDigitPressed("9") },
                    onLongPressSelect = { letter -> controller.clearAll(); onKeyPress(letter) },
                    onSwipeStateChange = { state, bounds ->
                        if (!(state.isPressed && !state.isLongPress && state.pressedText != null))
                            onSwipeStateChange?.invoke(state, bounds)
                    },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("9") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                KeyButton(
                    text = "123", onClick = { onKeyPress("number") },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("mode_change") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                )
                T9SpaceKey(
                    schemaName = uiState.schemaName, isSttEnabled = uiState.isSttEnabled,
                    voiceSticky = uiState.voiceSticky,
                    onKeyPress = onKeyPress, onKeyPressDown = onKeyPressDown,
                    onVoiceModeChange = callbacks.onVoiceModeChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1.8f),
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                )
                IconKeyButton(
                    icon = rememberVectorPainter(Icons.Default.Language),
                    onClick = { onKeyPress("ime_switch") },
                    backgroundColor = keyBackgroundColor, iconColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("ime_switch") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                )
            }
        }

        // ── 第3列：功能键（退格 / 重输 / 确定） ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.8f),
        ) {
            SwipeableIconKeyButton(
                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                onClick = { onDelete() },
                onLongClick = { onDelete() },
                backgroundColor = specialKeyBackgroundColor,
                iconColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                swipeText = if (compactMode) null else "清空",
                onSwipe = { onKeyPress("clear_composition") },
                onPress = { onKeyPressDown?.invoke("delete") },
                swipeUpLabel = if (compactMode) null else "上滑清空",
                swipeDownLabel = if (compactMode) null else "下滑撤回",
                onSwipeUp = {
                    controller.clearAll()
                    onKeyPress("clear_all")
                },
                onSwipeDown = { onKeyPress("undo_clear") },
                onSwipeLeft = { onKeyPress("clear_composition") },
                onSwipeStateChange = { state, bounds ->
                    onSwipeStateChange?.invoke(state, bounds)
                },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            ResetKey(
                onClick = {
                    controller.clearAll()
                    // 重输 = 只清输入态（预编辑/候选/左栏），不动已上屏文本（对标主流输入法 2026-08-11）：
                    //   · 输入态点重输 → 清除输入态内容
                    //   · 空闲态点重输 → 无反应（clear_composition 无可清内容）
                    onKeyPress("clear_composition")
                },
                onPress = { onKeyPressDown?.invoke("clear") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                compactMode = compactMode,
            )
            KeyButton(
                text = uiState.enterKeyText,
                onClick = { onKeyPress("enter") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(2f),
                onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
        }
    }
}

// ─── 九键数字键（可长按） ──────────────────────────────────────────────

/** 九键数字键的滑动配置：回调 + 提示文本（均受 keyboard.t9.keys 配置与提示开关控制）。 */
private data class T9KeySwipes(
    val onSwipeUp: (() -> Unit)? = null,
    val onSwipeDown: (() -> Unit)? = null,
    /** 上滑滑动气泡文本（display: key 时不传） */
    val swipeUpText: String? = null,
    /** 下滑滑动气泡文本（display: key 时不传） */
    val swipeDownText: String? = null,
    /** 上滑键面提示（空串 = 显式不印键面，bubble 模式用；null = 不显示） */
    val swipeUpKeyLabel: String? = null,
    val swipeDownKeyLabel: String? = null,
)

/**
 * 九键数字键，复用 [SwipeableKeyButton] 的长按弹出逻辑。
 * - 主体显示字母（ABC），右上角叠加数字浮标。
 * - 点按走 [onClick]，长按走 [onLongPressSelect]，上/下滑走 [onSwipeUp]/[onSwipeDown]。
 */
@Composable
private fun T9DigitKey(
    digit: String,
    letters: String,
    longPressItems: List<String>,
    onClick: () -> Unit,
    onLongPressSelect: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    swipes: T9KeySwipes = T9KeySwipes(),
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentSwipes by rememberUpdatedState(swipes)

    SwipeableKeyButton(
        text = letters,
        onClick = { currentOnClick() },
        backgroundColor = backgroundColor,
        textColor = textColor,
        fontSize = fontSize,
        modifier = modifier,
        onPress = { currentOnPress?.invoke() },
        longPressItems = longPressItems,
        onLongPressSelect = { letter -> currentOnLongPressSelect?.invoke(letter) },
        onSwipeStateChange = onSwipeStateChange,
        badgeText = digit,
        swipeText = currentSwipes.swipeUpText,
        swipeDownText = currentSwipes.swipeDownText,
        swipeUpKeyLabel = currentSwipes.swipeUpKeyLabel,
        swipeDownKeyLabel = currentSwipes.swipeDownKeyLabel,
        onSwipe = currentSwipes.onSwipeUp?.let { handler -> { _: String -> handler() } },
        onSwipeDown = currentSwipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

// ─── 子组件 ───────────────────────────────────────────────────────────

@Composable
private fun CandidateItem(
    text: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    textColor: Color,
    backgroundColor: Color = Color.Transparent,
    accentColor: Color = Color(0xFF1A73E8),
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    isSelected: Boolean = false,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(accentColor.copy(alpha = 0.2f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = text,
                    color = accentColor,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    fontFamily = AppFonts.candidateFontFamily
                )
            }
        } else {
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                fontFamily = AppFonts.candidateFontFamily
            )
        }
    }
}

/** 数字 1 分词键，与 [T9DigitKey] 统一使用 [SwipeableKeyButton] 保持角标位置一致 */
@Composable
private fun NineKeyButton(
    digit: String,
    letters: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipes: T9KeySwipes = T9KeySwipes(),
) {
    SwipeableKeyButton(
        text = letters,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        onPress = onPress,
        badgeText = digit,
        swipeText = swipes.swipeUpText,
        swipeDownText = swipes.swipeDownText,
        swipeUpKeyLabel = swipes.swipeUpKeyLabel,
        swipeDownKeyLabel = swipes.swipeDownKeyLabel,
        onSwipe = swipes.onSwipeUp?.let { handler -> { _: String -> handler() } },
        onSwipeDown = swipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        fontSize = fontSize,
    )
}

/** 重输键 —— 文本在上、图标在下，参考功能键风格 */
@Composable
private fun ResetKey(
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    compactMode: Boolean = false,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val density = LocalDensity.current
    val shape = RoundedCornerShape(shadowShapeRadius)
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .then(shadowModifier)
            .clip(shape)
            .background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            }, contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "重输",
            tint = textColor,
            modifier = Modifier.size(if (compactMode) 16.dp else 20.dp)
        )

        if (!compactMode) {
            Text(
                text = "重输",
                color = textColor.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.offset(y = (-14).dp)
            )
        }
    }
}

/** T9 空格键 */
@Composable
private fun T9SpaceKey(
    schemaName: String,
    isSttEnabled: Boolean,
    voiceSticky: Boolean = false,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    onVoiceModeChange: ((Boolean) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val currentOnKeyPress by rememberUpdatedState(onKeyPress)
    val currentOnVoiceModeChange by rememberUpdatedState(onVoiceModeChange)
    val density = LocalDensity.current
    val spaceShadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .pointerInput(voiceSticky) {
                detectTapGestures(
                    onPress = {
                        if (voiceSticky) {
                            tryAwaitRelease()
                        } else {
                            onKeyPressDown?.invoke("space")
                            tryAwaitRelease()
                        }
                    },
                    onTap = {
                        if (voiceSticky) {
                            currentOnVoiceModeChange?.invoke(false)
                        } else {
                            currentOnKeyPress("space")
                        }
                    },
                    onLongPress = {
                        if (voiceSticky) return@detectTapGestures
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        if (!PermissionHelper.hasRecordAudioPermission(context)) {
                            Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
                            PermissionHelper.requestRecordAudioPermission(context)
                        } else {
                            currentOnVoiceModeChange?.invoke(true)
                        }
                    }
                )
            }, contentAlignment = Alignment.Center
    ) {
        // 背景层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(spaceShadowModifier)
                .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                .background(backgroundColor)
        )
        if (voiceSticky) {
            Text(
                text = "轻触结束语音",
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        } else {
            Text(
                text = schemaName,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            if (isSttEnabled) {
                Icon(
                    painter = painterResource(R.drawable.voice),
                    contentDescription = "语音输入",
                    tint = textColor.copy(alpha = 0.3f),
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 2.dp)
                )
            } else {
                Text(
                    text = "空格",
                    color = textColor.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 2.dp)
                )
            }
        }
    }
}
