package io.github.oustery.opencodephone.ui.bootstrap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.R
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.AppFonts
import io.github.oustery.opencodephone.core.designsystem.OcDisclosure
import io.github.oustery.opencodephone.core.designsystem.OcShapeEmblem
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.core.model.BootstrapPhase
import io.github.oustery.opencodephone.core.settings.SetupChoice
import kotlin.math.roundToInt

/**
 * One-time environment setup wizard. Wavy progress indicators are the
 * expressive hero of this screen; the step list stays quiet and factual.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BootstrapScreen(graph: AppGraph) {
    val state by graph.bootstrap.state.collectAsStateWithLifecycle()
    val setupChoice by graph.setupChoice.collectAsStateWithLifecycle()

    // Setup runs heavy downloads and process work; keep the screen (and thus
    // the app in the foreground) on so Android does not freeze or kill us.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(state.phase) {
        val active = state.phase != BootstrapPhase.FAILED && state.phase != BootstrapPhase.READY
        if (active) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        // Expressive hero: the emblem morphs only while setup is actually
        // running — a finished or failed setup stays quiet (E-015 rule).
        OcShapeEmblem(
            icon = painterResource(R.drawable.ic_launcher_foreground),
            animated = state.phase != BootstrapPhase.FAILED && state.phase != BootstrapPhase.READY,
            size = 84.dp,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.bootstrap_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        if (state.phase == BootstrapPhase.NOT_STARTED && setupChoice == SetupChoice.UNSET) {
            // Remote-first onboarding (functional audit F-022): before any
            // heavy download the user decides between the local environment
            // and an existing opencode server.
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.bootstrap_choice_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.bootstrap_choice_local_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { graph.chooseSetup(SetupChoice.LOCAL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.bootstrap_choice_local))
                    }
                    Text(
                        text = stringResource(R.string.bootstrap_choice_remote_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { graph.chooseSetup(SetupChoice.REMOTE_ONLY) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.bootstrap_choice_remote))
                    }
                }
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (state.phase) {
                        BootstrapPhase.FAILED -> {
                            Text(
                                text = stringResource(R.string.bootstrap_failed_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = state.error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Raw setup log collapsed behind a disclosure: the
                            // failure headline stays readable, the details are
                            // one tap away (mirrors the tool-card pattern).
                            if (state.log.isNotEmpty()) {
                                OcDisclosure(
                                    title = stringResource(R.string.bootstrap_details),
                                    expandedDescription = stringResource(R.string.state_expanded),
                                    collapsedDescription = stringResource(R.string.state_collapsed),
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = state.log.takeLast(12).joinToString("\n"),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = AppFonts.Code,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = stringResource(R.string.bootstrap_hint_kill),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { graph.bootstrap.retry() }) {
                                Text(stringResource(R.string.bootstrap_retry))
                            }
                        }
                        else -> {
                            val progress = state.progress
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (progress != null) {
                                    LinearWavyProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.weight(1f),
                                        wavelength = 28.dp,
                                    )
                                    Text(
                                        text = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
                                }
                            }
                            Text(
                                text = state.label.ifEmpty { stringResource(R.string.bootstrap_preparing) },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (state.message.isNotEmpty()) {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        BootstrapSteps(state.phase, state.failedPhase)

        Spacer(Modifier.height(48.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.bootstrap_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class StepVisual { PENDING, ACTIVE, DONE, FAILED }

@Composable
private fun BootstrapSteps(phase: BootstrapPhase, failedPhase: BootstrapPhase?) {
    val steps = listOf(
        Triple(BootstrapPhase.DOWNLOADING_ROOTFS, stringResource(R.string.bootstrap_step_rootfs), 1),
        Triple(BootstrapPhase.EXTRACTING_ROOTFS, stringResource(R.string.bootstrap_step_extract), 2),
        Triple(BootstrapPhase.INSTALLING_TOOLS, stringResource(R.string.bootstrap_step_tools), 3),
        Triple(BootstrapPhase.INSTALLING_OPENCODE, stringResource(R.string.bootstrap_step_opencode), 4),
        Triple(BootstrapPhase.CONFIGURING, stringResource(R.string.bootstrap_step_configure), 5),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEach { (stepPhase, label, order) ->
            val visual = if (phase == BootstrapPhase.FAILED && failedPhase != null) {
                // Only the step that was running failed; earlier steps stay done,
                // later ones stay pending.
                val failedOrder = phaseOrder(failedPhase)
                when {
                    order < failedOrder -> StepVisual.DONE
                    order == failedOrder -> StepVisual.FAILED
                    else -> StepVisual.PENDING
                }
            } else {
                val current = phaseOrder(phase)
                when {
                    current > order -> StepVisual.DONE
                    current == order -> StepVisual.ACTIVE
                    else -> StepVisual.PENDING
                }
            }
            StepRow(visual, label, order)
        }
    }
}

/** Numeric order of phases for step comparison; READY completes everything. */
private fun phaseOrder(phase: BootstrapPhase): Int = when (phase) {
    BootstrapPhase.NOT_STARTED -> 0
    BootstrapPhase.DOWNLOADING_ROOTFS -> 1
    BootstrapPhase.EXTRACTING_ROOTFS -> 2
    BootstrapPhase.INSTALLING_TOOLS -> 3
    BootstrapPhase.INSTALLING_OPENCODE -> 4
    BootstrapPhase.CONFIGURING -> 5
    BootstrapPhase.READY -> 6
    BootstrapPhase.FAILED -> 0
}

@Composable
private fun StepRow(visual: StepVisual, label: String, order: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val color = when (visual) {
            StepVisual.DONE -> MaterialTheme.colorScheme.primary
            StepVisual.ACTIVE -> MaterialTheme.colorScheme.tertiary
            StepVisual.FAILED -> MaterialTheme.colorScheme.error
            StepVisual.PENDING -> MaterialTheme.colorScheme.outlineVariant
        }
        // Circular step badge: the number sits in a round emblem instead of
        // a rounded chip, keeping the step list in the shape language of the
        // entry rows.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = order.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
        }
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (visual == StepVisual.PENDING) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (visual == StepVisual.FAILED) {
                OcStatusChip(
                    text = stringResource(R.string.bootstrap_step_failed),
                    tone = OcChipTone.ERROR,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        when (visual) {
            StepVisual.DONE -> Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            StepVisual.ACTIVE -> CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
            else -> Unit
        }
    }
}
