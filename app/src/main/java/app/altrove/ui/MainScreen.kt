package app.altrove.ui

import android.Manifest
import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.altrove.AltroveWidgetProvider
import app.altrove.Coordinates
import app.altrove.MockLocationService
import app.altrove.Prefs
import app.altrove.R
import app.altrove.Session
import app.altrove.Setup
import java.util.Locale
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(28.dp)
private val FieldShape = RoundedCornerShape(16.dp)
private const val MAX_FIELD_LENGTH = 20

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val session by Session.active.collectAsStateWithLifecycle()
    val widgetPlaced by AltroveWidgetProvider.placed.collectAsStateWithLifecycle()

    var isMockApp by remember { mutableStateOf(Setup.isMockLocationApp(context)) }
    var missingPermissions by remember { mutableStateOf(Setup.missingOptionalPermissions(context)) }
    var permissionsHandled by remember { mutableStateOf(Prefs.optionalPermissionsHandled(context)) }
    var destination by remember { mutableStateOf(Prefs.destination(context)) }

    // Back from Settings or from the launcher: re-read whatever may have changed out there.
    LifecycleResumeEffect(Unit) {
        isMockApp = Setup.isMockLocationApp(context)
        missingPermissions = Setup.missingOptionalPermissions(context)
        AltroveWidgetProvider.refresh(context)
        onPauseOrDispose { }
    }

    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // They are optional and were asked once: whatever the answers, don't ask again.
        missingPermissions = Setup.missingOptionalPermissions(context)
        Prefs.setOptionalPermissionsHandled(context)
        permissionsHandled = true
    }
    val permissionsPrompt = if (permissionsHandled) null else optionalPermissionsPrompt(missingPermissions)

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header()

                if (!isMockApp || permissionsPrompt != null) {
                    SetupCard(
                        showMockStep = !isMockApp,
                        permissionsPrompt = permissionsPrompt,
                        onAllowPermissions = { permissionRequest.launch(missingPermissions.toTypedArray()) },
                        onSkipPermissions = {
                            Prefs.setOptionalPermissionsHandled(context)
                            permissionsHandled = true
                        },
                    )
                }

                DestinationCard(
                    onSaved = { saved ->
                        destination = saved
                        if (Session.isActive) {
                            // Move the running session too, and fix the text in its notification.
                            Session.retarget(saved)
                            context.startService(
                                MockLocationService.intent(context, MockLocationService.ACTION_REFRESH),
                            )
                        }
                        AltroveWidgetProvider.refresh(context)
                    },
                )

                SessionCard(session = session, destination = destination, isMockApp = isMockApp)

                if (!widgetPlaced && AltroveWidgetProvider.canRequestPin(context)) {
                    OutlinedButton(
                        onClick = { AltroveWidgetProvider.requestPin(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_widgets),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_widget))
                    }
                }

                Text(
                    text = stringResource(R.string.footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pin),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The text inviting to grant whichever optional permissions are missing, or null if none is. */
@SuppressLint("InlinedApi") // POST_NOTIFICATIONS is only compared as a string here.
@Composable
private fun optionalPermissionsPrompt(missing: List<String>): String? {
    val location = Manifest.permission.ACCESS_FINE_LOCATION in missing
    val notifications = Manifest.permission.POST_NOTIFICATIONS in missing
    return when {
        location && notifications -> stringResource(R.string.setup_permissions_both)
        location -> stringResource(R.string.setup_permissions_location)
        notifications -> stringResource(R.string.setup_permissions_notifications)
        else -> null
    }
}

@Composable
private fun SetupCard(
    showMockStep: Boolean,
    permissionsPrompt: String?,
    onAllowPermissions: () -> Unit,
    onSkipPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = colors.tertiary,
        contentColor = colors.onTertiary,
    )
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)

            if (showMockStep) {
                Text(
                    text = stringResource(R.string.setup_mock_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { Setup.openDeveloperOptions(context) }, colors = buttonColors) {
                    Text(stringResource(R.string.setup_mock_action))
                }
                // Apps can't reliably tell whether Developer options are on (Android 17 hides
                // it), so the way to enable them is always offered, as a quiet footnote.
                val hint = stringResource(R.string.setup_developer_hint)
                val link = stringResource(R.string.setup_developer_link)
                Text(
                    text = buildAnnotatedString {
                        append(hint)
                        append(" ")
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "about-phone",
                                styles = TextLinkStyles(
                                    SpanStyle(
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                                linkInteractionListener = { Setup.openAboutPhone(context) },
                            ),
                        ) {
                            append(link)
                        }
                        append(".")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }

            if (showMockStep && permissionsPrompt != null) {
                HorizontalDivider(color = colors.onTertiaryContainer.copy(alpha = 0.16f))
            }

            if (permissionsPrompt != null) {
                Text(text = permissionsPrompt, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAllowPermissions, colors = buttonColors) {
                        Text(stringResource(R.string.setup_permissions_action))
                    }
                    TextButton(
                        onClick = onSkipPermissions,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.onTertiaryContainer),
                    ) {
                        Text(stringResource(R.string.setup_permissions_skip))
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationCard(onSaved: (Coordinates) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var latitude by rememberSaveable { mutableStateOf(Prefs.latitudeText(context)) }
    var longitude by rememberSaveable { mutableStateOf(Prefs.longitudeText(context)) }
    var savedLatitude by rememberSaveable { mutableStateOf(latitude) }
    var savedLongitude by rememberSaveable { mutableStateOf(longitude) }
    var justSaved by remember { mutableStateOf(false) }

    val parsedLatitude = Coordinates.parseLatitude(latitude)
    val parsedLongitude = Coordinates.parseLongitude(longitude)
    val changed = Coordinates.normalize(latitude) != savedLatitude ||
        Coordinates.normalize(longitude) != savedLongitude
    val canSave = parsedLatitude != null && parsedLongitude != null && changed

    fun edit(text: String, assign: (String) -> Unit) {
        val pair = Coordinates.splitPair(text)
        if (pair != null) {
            // A whole "lat, lng" was pasted: spread it over the two fields.
            latitude = pair.first
            longitude = pair.second
        } else {
            assign(text.filter { it.isDigit() || it in ".,+-−" }.take(MAX_FIELD_LENGTH))
        }
        justSaved = false
    }

    fun save() {
        if (parsedLatitude == null || parsedLongitude == null || !changed) return
        val cleanLatitude = Coordinates.normalize(latitude)
        val cleanLongitude = Coordinates.normalize(longitude)
        Prefs.saveDestination(context, cleanLatitude, cleanLongitude)
        latitude = cleanLatitude
        longitude = cleanLongitude
        savedLatitude = cleanLatitude
        savedLongitude = cleanLongitude
        justSaved = true
        focusManager.clearFocus()
        onSaved(Coordinates(parsedLatitude, parsedLongitude))
    }

    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(2000)
            justSaved = false
        }
    }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.destination_title), style = MaterialTheme.typography.titleMedium)

            CoordinateField(
                value = latitude,
                onValueChange = { text -> edit(text) { latitude = it } },
                label = stringResource(R.string.latitude),
                example = stringResource(R.string.latitude_example),
                error = stringResource(R.string.latitude_error)
                    .takeIf { latitude.isNotBlank() && parsedLatitude == null },
                imeAction = ImeAction.Next,
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            )
            CoordinateField(
                value = longitude,
                onValueChange = { text -> edit(text) { longitude = it } },
                label = stringResource(R.string.longitude),
                example = stringResource(R.string.longitude_example),
                error = stringResource(R.string.longitude_error)
                    .takeIf { longitude.isNotBlank() && parsedLongitude == null },
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(
                    onDone = { if (canSave) save() else focusManager.clearFocus() },
                ),
            )

            Text(
                text = stringResource(R.string.destination_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { save() },
                // Stays lit for a moment after saving, to show the "Saved" confirmation.
                enabled = canSave || justSaved,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                AnimatedContent(targetState = justSaved, label = "save") { saved ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (saved) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(if (saved) R.string.saved else R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun CoordinateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    example: String,
    error: String?,
    imeAction: ImeAction,
    keyboardActions: KeyboardActions,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = {
            // Clearly fainter than a typed value, so an empty field never looks filled in.
            Text(example, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
        },
        suffix = { Text("°") },
        supportingText = error?.let { message -> { Text(message) } },
        isError = error != null,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        keyboardActions = keyboardActions,
        shape = FieldShape,
    )
}

@Composable
private fun SessionCard(session: Session.Active?, destination: Coordinates?, isMockApp: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val active = session != null
    val container by animateColorAsState(
        targetValue = if (active) colors.primary else colors.surfaceContainerHigh,
        label = "container",
    )
    val content by animateColorAsState(
        targetValue = if (active) colors.onPrimary else colors.onSurface,
        label = "content",
    )

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(
                        if (active) R.string.status_active_title else R.string.status_idle_title,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (session != null) {
                    Text(
                        text = countdownText(session),
                        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.status_active_body, session.destination.format()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = content.copy(alpha = 0.85f),
                    )
                } else {
                    Text(
                        text = stringResource(
                            when {
                                !isMockApp -> R.string.status_idle_body_setup
                                destination == null -> R.string.status_idle_body_no_destination
                                else -> R.string.status_idle_body
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            if (active) {
                Button(
                    onClick = {
                        context.startService(
                            MockLocationService.intent(context, MockLocationService.ACTION_STOP),
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.onPrimary,
                        contentColor = colors.primary,
                    ),
                ) {
                    Text(stringResource(R.string.stop))
                }
            } else {
                FilledTonalButton(
                    onClick = {
                        ContextCompat.startForegroundService(
                            context,
                            MockLocationService.intent(context, MockLocationService.ACTION_START),
                        )
                    },
                    enabled = isMockApp && destination != null,
                ) {
                    Text(stringResource(R.string.start))
                }
            }
        }
    }
}

/** "04:59", ticking down in step with the widget and the notification. */
@Composable
private fun countdownText(session: Session.Active): String {
    fun secondsLeft() =
        ((session.endElapsedRealtime - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)

    var seconds by remember(session.endElapsedRealtime) { mutableLongStateOf(secondsLeft()) }
    LaunchedEffect(session.endElapsedRealtime) {
        while (true) {
            val remaining = session.endElapsedRealtime - SystemClock.elapsedRealtime()
            seconds = (remaining / 1000).coerceAtLeast(0)
            if (remaining <= 0) break
            // Sleep until just past the next whole second.
            delay(remaining % 1000 + 1)
        }
    }
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
