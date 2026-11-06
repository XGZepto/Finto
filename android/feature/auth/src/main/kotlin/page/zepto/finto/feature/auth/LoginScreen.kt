package page.zepto.finto.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.AnimatedFintoMark
import page.zepto.finto.core.designsystem.component.BrandSurface
import kotlin.math.roundToInt

@Composable
fun LoginRoute(
    notice: String? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginScreen(
        state = if (state.notice == null && state.error == null) state.copy(notice = notice) else state,
        onIdentifierChange = viewModel::onIdentifierChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
    )
}

private val FieldShape = RoundedCornerShape(18.dp)
private val Ink = Color(0xFFE9EAEC)
private val Hairline = Color(0xFF2A2D33)
private val Muted = Color(0xFF8A8F98)

/**
 * Sign-in on pure black: the mark and the name up top, the form low where the
 * thumb is. The mark makes room when the keyboard opens. The server is fixed
 * when the APK is built, so there is nothing to choose here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(
    state: LoginUiState,
    onIdentifierChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    /** False in screenshot tests, which capture the settled layout. */
    animateEntrance: Boolean = true,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current

    // A wrong password nudges the form sideways once, the way a lock screen does.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            for (target in listOf(18f, -14f, 9f, -5f, 0f)) {
                shake.animateTo(target, spring(stiffness = Spring.StiffnessHigh))
            }
        }
    }

    val entered = remember { MutableTransitionState(!animateEntrance).apply { targetState = true } }
    val keyboard = WindowInsets.isImeVisible
    val markSize by animateDpAsState(if (keyboard) 64.dp else 136.dp, tween(320), label = "mark")

    BrandSurface {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            val screen = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = screen)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val top by animateDpAsState(if (keyboard) 16.dp else screen * 0.16f, tween(320), label = "top")
                    Spacer(Modifier.height(top))
                    AnimatedFintoMark(size = markSize, animate = animateEntrance)
                    AnimatedVisibility(
                        visibleState = entered,
                        enter = fadeIn(tween(500, delayMillis = 300)) + slideInVertically(tween(500, delayMillis = 300)) { it / 3 },
                    ) {
                        Text(
                            "Finto",
                            style = if (keyboard) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                            modifier = Modifier.padding(top = if (keyboard) 8.dp else 20.dp),
                        )
                    }
                }

                AnimatedVisibility(
                    visibleState = entered,
                    enter = fadeIn(tween(500, delayMillis = 450)) + slideInVertically(tween(500, delayMillis = 450)) { it / 5 },
                ) {
                    LoginForm(
                        state = state,
                        showPassword = showPassword,
                        onTogglePassword = { showPassword = !showPassword },
                        passwordFocus = passwordFocus,
                        onIdentifierChange = onIdentifierChange,
                        onPasswordChange = onPasswordChange,
                        onSubmit = onSubmit,
                        modifier = Modifier
                            .widthIn(max = 440.dp)
                            .fillMaxWidth()
                            .padding(top = 32.dp, bottom = 24.dp)
                            .offset { IntOffset(shake.value.roundToInt(), 0) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginForm(
    state: LoginUiState,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    passwordFocus: FocusRequester,
    onIdentifierChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = Hairline,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        unfocusedLabelColor = Muted,
        cursorColor = MaterialTheme.colorScheme.primary,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.returning) {
            Text(
                "Welcome back",
                style = MaterialTheme.typography.headlineSmall,
                color = Ink,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
            )
        }
        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        OutlinedTextField(
            value = state.identifier,
            onValueChange = onIdentifierChange,
            label = { Text("Username or email") },
            singleLine = true,
            shape = FieldShape,
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            shape = FieldShape,
            colors = fieldColors,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onTogglePassword) {
                    Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = Muted,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            isError = state.error != null,
            supportingText = state.error?.let { { Text(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus)
                .semantics { contentType = ContentType.Password },
        )
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Ink,
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFF16181B),
                disabledContentColor = Color(0xFF5C6066),
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(56.dp),
        ) {
            AnimatedContent(
                targetState = state.submitting,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "sign-in",
            ) { submitting ->
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.Black)
                } else {
                    Text("Sign in", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
