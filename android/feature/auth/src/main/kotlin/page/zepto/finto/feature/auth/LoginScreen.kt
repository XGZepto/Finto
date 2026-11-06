package page.zepto.finto.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.BrandSurface
import page.zepto.finto.core.designsystem.component.FintoMark
import kotlin.math.roundToInt

@Composable
fun LoginRoute(
    notice: String? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginScreen(
        state = if (state.notice == null && state.error == null) state.copy(notice = notice) else state,
        onServerChange = viewModel::onServerChange,
        onIdentifierChange = viewModel::onIdentifierChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
        onDemo = viewModel::useDemo,
    )
}

private val FieldShape = RoundedCornerShape(16.dp)

@Composable
fun LoginScreen(
    state: LoginUiState,
    onServerChange: (String) -> Unit,
    onIdentifierChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDemo: () -> Unit,
    /** False in screenshot tests, which capture the settled layout. */
    animateEntrance: Boolean = true,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var editServer by rememberSaveable { mutableStateOf(false) }
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

    BrandSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedVisibility(
                visibleState = entered,
                enter = fadeIn(tween(500)) + scaleIn(tween(500), initialScale = 0.85f),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FintoMark(size = 88.dp)
                    Spacer(Modifier.height(20.dp))
                    Text("Finto", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(40.dp))
            AnimatedVisibility(
                visibleState = entered,
                enter = fadeIn(tween(450, delayMillis = 150)) +
                    slideInVertically(tween(450, delayMillis = 150)) { it / 6 },
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .offset { IntOffset(shake.value.roundToInt(), 0) },
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    state.notice?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ServerRow(
                        server = state.server,
                        editing = editServer,
                        onEdit = { editServer = true },
                        onServerChange = onServerChange,
                    )
                    OutlinedTextField(
                        value = state.identifier,
                        onValueChange = onIdentifierChange,
                        label = { Text("Username or email") },
                        singleLine = true,
                        shape = FieldShape,
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
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
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
                        shape = FieldShape,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        AnimatedContent(
                            targetState = state.submitting,
                            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                            label = "sign-in",
                        ) { submitting ->
                            if (submitting) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Text("Sign in", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    TextButton(
                        onClick = onDemo,
                        enabled = !state.submitting,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("Try the demo")
                    }
                }
            }
        }
    }
}

/** The server folds to one line until it needs changing; most people never do. */
@Composable
private fun ServerRow(
    server: String,
    editing: Boolean,
    onEdit: () -> Unit,
    onServerChange: (String) -> Unit,
) {
    Box {
        AnimatedVisibility(visible = !editing, enter = fadeIn(), exit = fadeOut() + shrinkVertically()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(server, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Change server", modifier = Modifier.size(18.dp))
                }
            }
        }
        AnimatedVisibility(visible = editing, enter = fadeIn() + expandVertically(), exit = fadeOut()) {
            OutlinedTextField(
                value = server,
                onValueChange = onServerChange,
                label = { Text("Server") },
                leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                singleLine = true,
                shape = FieldShape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
