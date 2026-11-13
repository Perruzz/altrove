package app.altrove.ui

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import app.altrove.ui.space.Globe
import app.altrove.ui.space.SpaceBackground
import app.altrove.ui.space.SpaceScene
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAX_FIELD_LENGTH = 20

/**
 * The one screen of the app: a sky, the Earth turned towards the destination, and under it
 * the two fields that say where that is.
 */
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val session by Session.active.collectAsStateWithLifecycle()
    val widgetPlaced by AltroveWidgetProvider.placed.collectAsStateWithLifecycle()

    var isMockApp by remember { mutableStateOf(Setup.isMockLocationApp(context)) }
    var missingPermissions by remember { mutableStateOf(Setup.missingOptionalPermissions(context)) }
    var permissionsHandled by remember { mutableStateOf(Prefs.optionalPermissionsHandled(context)) }
    var destination by remember { mutableStateOf(Prefs.destination(context)) }

    // The two fields live here, not in their card: the globe previews what is typed in them,
    // and a tap on the globe fills them in.
    var latitude by rememberSaveable { mutableStateOf(Prefs.latitudeText(context)) }
    var longitude by rememberSaveable { mutableStateOf(Prefs.longitudeText(context)) }
    var justSaved by remember { mutableStateOf(false) }

    val parsedLatitude = Coordinates.parseLatitude(latitude)
    val parsedLongitude = Coordinates.parseLongitude(longitude)
    val typed = if (parsedLatitude != null && parsedLongitude != null) {
        Coordinates(parsedLatitude, parsedLongitude)
    } else {
        null
    }
    // What is in the fields, when it is a valid place that has not been saved yet.
    val draft = typed?.takeIf { it != destination }

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

    // --- The scene and its choreography -------------------------------------------------

    val scene = remember {
        SpaceScene(calm = !ValueAnimator.areAnimatorsEnabled()).also { it.globe.target = destination }
    }
    val active = session != null
    val mood by animateFloatAsState(if (active) 1f else 0f, tween(900), label = "mood")

    // The globe looks at what is being typed; failing that, at where you are (or will be).
    SideEffect { scene.globe.target = draft ?: session?.destination ?: destination }

    var introPlayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scene) {
        launch { scene.run() }
        if (scene.calm || introPlayed) {
            scene.skipIntro()
        } else {
            introPlayed = true
            scene.playIntro()
        }
    }

    // A session starting or ending while the screen is open, whoever started it.
    var shownActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active == shownActive) return@LaunchedEffect
        shownActive = active
        if (active) scene.playLaunch() else scene.playReturn()
    }

    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(2200)
            justSaved = false
        }
    }

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
        val place = draft ?: return
        val cleanLatitude = Coordinates.normalize(latitude)
        val cleanLongitude = Coordinates.normalize(longitude)
        Prefs.saveDestination(context, cleanLatitude, cleanLongitude)
        latitude = cleanLatitude
        longitude = cleanLongitude
        destination = place
        justSaved = true
        focusManager.clearFocus()
        if (Session.isActive) {
            // Move the running session too, and fix the text in its notification.
            Session.retarget(place)
            context.startService(MockLocationService.intent(context, MockLocationService.ACTION_REFRESH))
        }
        AltroveWidgetProvider.refresh(context)
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch { scene.playSave() }
    }

    // --- Layout ---------------------------------------------------------------------------

    // While typing, the globe shrinks so that it stays in view above the keyboard.
    val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val globeSize by animateDpAsState(
        targetValue = if (typing) 168.dp else 304.dp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 280f),
        label = "globe",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Space.Night),
    ) {
        SpaceBackground(scene = scene, active = { mood }, modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(compact = typing, modifier = Modifier.entrance(scene, order = 0))

                // The canvas is larger than the room it takes: its margin (halo, orbit, ring)
                // is allowed to overlap what is above and below.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(globeSize - 36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Globe(
                        scene = scene,
                        destination = destination,
                        draft = draft,
                        session = session,
                        active = { mood },
                        onPick = { place ->
                            latitude = String.format(Locale.US, "%.4f", place.latitude)
                            longitude = String.format(Locale.US, "%.4f", place.longitude)
                            justSaved = false
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        modifier = Modifier
                            .requiredSize(globeSize)
                            .graphicsLayer {
                                // It arrives first, growing out of the distance.
                                val p = (scene.intro.value / 0.6f).coerceIn(0f, 1f)
                                val eased = 1f - (1f - p) * (1f - p) * (1f - p)
                                alpha = eased
                                scaleX = 0.7f + 0.3f * eased
                                scaleY = 0.7f + 0.3f * eased
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                    )
                }

                if (!isMockApp || permissionsPrompt != null) {
                    SetupCard(
                        showMockStep = !isMockApp,
                        permissionsPrompt = permissionsPrompt,
                        onAllowPermissions = { permissionRequest.launch(missingPermissions.toTypedArray()) },
                        onSkipPermissions = {
                            Prefs.setOptionalPermissionsHandled(context)
                            permissionsHandled = true
                        },
                        modifier = Modifier.entrance(scene, order = 1),
                    )
                }

                DestinationCard(
                    latitude = latitude,
                    longitude = longitude,
                    onLatitudeChange = { text -> edit(text) { latitude = it } },
                    onLongitudeChange = { text -> edit(text) { longitude = it } },
                    parsedLatitude = parsedLatitude,
                    parsedLongitude = parsedLongitude,
                    canSave = draft != null,
                    justSaved = justSaved,
                    onSave = { save() },
                    modifier = Modifier.entrance(scene, order = 2),
                )

                SessionCard(
                    session = session,
                    destination = destination,
                    isMockApp = isMockApp,
                    onStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        ContextCompat.startForegroundService(
                            context,
                            MockLocationService.intent(context, MockLocationService.ACTION_START),
                        )
                    },
                    onStop = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        context.startService(
                            MockLocationService.intent(context, MockLocationService.ACTION_STOP),
                        )
                    },
                    modifier = Modifier.entrance(scene, order = 3),
                )

                if (!widgetPlaced && AltroveWidgetProvider.canRequestPin(context)) {
                    GhostButton(
                        onClick = { AltroveWidgetProvider.requestPin(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .entrance(scene, order = 4),
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
                    color = Space.Starlight.copy(alpha = 0.5f),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .entrance(scene, order = 4),
                )
            }
        }
    }
}

@Composable
private fun Header(compact: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF9D8FFF), Color(0xFF4A3DD6))),
                    RoundedCornerShape(15.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pin),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            // Dropped while typing, to leave the room to the globe and the fields.
            AnimatedVisibility(visible = !compact) {
                Text(
                    text = stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Space.Muted,
                )
            }
        }
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val amber = listOf(Space.Amber, Color(0xFFFF9D5C))
    GlassCard(
        modifier = modifier.animateContentSize(),
        accent = Space.Amber,
        tint = Color(0xFF4A3516),
    ) {
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.titleMedium,
            color = Space.Gold,
        )

        if (showMockStep) {
            Text(
                text = stringResource(R.string.setup_mock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Space.Starlight,
            )
            GlowButton(
                onClick = { Setup.openDeveloperOptions(context) },
                modifier = Modifier.height(46.dp),
                colors = amber,
                contentColor = Space.Night,
            ) {
                Text(stringResource(R.string.setup_mock_action), fontWeight = FontWeight.SemiBold)
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
                                    color = Space.Gold,
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
                color = Space.Muted,
            )
        }

        if (showMockStep && permissionsPrompt != null) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        }

        if (permissionsPrompt != null) {
            Text(
                text = permissionsPrompt,
                style = MaterialTheme.typography.bodyMedium,
                color = Space.Starlight,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlowButton(
                    onClick = onAllowPermissions,
                    modifier = Modifier.height(46.dp),
                    colors = amber,
                    contentColor = Space.Night,
                ) {
                    Text(stringResource(R.string.setup_permissions_action), fontWeight = FontWeight.SemiBold)
                }
                GhostButton(onClick = onSkipPermissions, modifier = Modifier.height(46.dp)) {
                    Text(stringResource(R.string.setup_permissions_skip))
                }
            }
        }
    }
}

@Composable
private fun DestinationCard(
    latitude: String,
    longitude: String,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    parsedLatitude: Double?,
    parsedLongitude: Double?,
    canSave: Boolean,
    justSaved: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    GlassCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.destination_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )

        CoordinateField(
            value = latitude,
            onValueChange = onLatitudeChange,
            label = stringResource(R.string.latitude),
            example = stringResource(R.string.latitude_example),
            error = stringResource(R.string.latitude_error)
                .takeIf { latitude.isNotBlank() && parsedLatitude == null },
            hemisphere = parsedLatitude?.let { if (it < 0) "S" else "N" },
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        )
        CoordinateField(
            value = longitude,
            onValueChange = onLongitudeChange,
            label = stringResource(R.string.longitude),
            example = stringResource(R.string.longitude_example),
            error = stringResource(R.string.longitude_error)
                .takeIf { longitude.isNotBlank() && parsedLongitude == null },
            hemisphere = parsedLongitude?.let { if (it < 0) "W" else "E" },
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(
                onDone = { if (canSave) onSave() else focusManager.clearFocus() },
            ),
        )

        Text(
            text = stringResource(R.string.destination_tip),
            style = MaterialTheme.typography.bodySmall,
            color = Space.Muted,
        )

        GlowButton(
            onClick = onSave,
            // Stays lit for a moment after saving, to show the "Saved" confirmation.
            enabled = canSave || justSaved,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = if (justSaved) listOf(Color(0xFF3CCB9B), Color(0xFF1FA6B8)) else listOf(Space.Violet, Color(0xFF5546E0)),
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
                    Text(
                        text = stringResource(if (saved) R.string.saved else R.string.save),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: Session.Active?,
    destination: Coordinates?,
    isMockApp: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = session != null
    val accent by animateColorAsState(if (active) Space.Magenta else Color.White, tween(700), label = "accent")
    val tint by animateColorAsState(if (active) Color(0xFF6A1D6E) else Space.Panel, tween(700), label = "tint")

    // The size animation clips to the card, not to the row: the glow of the button needs room.
    GlassCard(modifier = modifier.animateContentSize(), accent = accent, tint = tint) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(
                        if (active) R.string.status_active_title else R.string.status_idle_title,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                if (session != null) {
                    RollingCountdown(
                        seconds = secondsLeft(session),
                        style = MaterialTheme.typography.displaySmall.copy(
                            color = Color.White,
                            fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Text(
                        text = stringResource(R.string.status_active_body, session.destination.format()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Space.Starlight.copy(alpha = 0.86f),
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
                        color = Space.Muted,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            if (active) {
                GhostButton(onClick = onStop, modifier = Modifier.height(48.dp), color = Color.White) {
                    Text(stringResource(R.string.stop), fontWeight = FontWeight.SemiBold)
                }
            } else {
                GlowButton(
                    onClick = onStart,
                    modifier = Modifier.height(48.dp),
                    enabled = isMockApp && destination != null,
                    colors = listOf(Space.Cyan, Color(0xFF7C8CFF)),
                    contentColor = Space.Night,
                ) {
                    Text(stringResource(R.string.start), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Whole seconds left in the session, ticking down in step with the widget and the notification. */
@Composable
private fun secondsLeft(session: Session.Active): Long {
    fun now() = ((session.endElapsedRealtime - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)

    var seconds by remember(session.endElapsedRealtime) { mutableLongStateOf(now()) }
    LaunchedEffect(session.endElapsedRealtime) {
        while (true) {
            val remaining = session.endElapsedRealtime - SystemClock.elapsedRealtime()
            seconds = (remaining / 1000).coerceAtLeast(0)
            if (remaining <= 0) break
            // Sleep until just past the next whole second.
            delay(remaining % 1000 + 1)
        }
    }
    return seconds
}
