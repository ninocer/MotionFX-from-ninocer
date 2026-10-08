package dev.motionfx.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import dev.motionfx.plugin.PluginApi
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var editor: EditorViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = ViewModelProvider(this)[EditorViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFBCA2FF), background = Color(0xFF111018), surface = Color(0xFF1D1B27),
                secondary = Color(0xFF8EDFD0),
            )) { Surface(Modifier.fillMaxSize()) { Editor(editor) } }
        }
    }
    override fun onStop() { editor.pause(); super.onStop() }

    @Composable
    private fun Editor(vm: EditorViewModel) {
        val state by vm.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importImage) }
        val pluginPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::install) }
        LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
        val editable = state.ready && !state.busy
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).safeDrawingPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("MotionFX", style = MaterialTheme.typography.headlineMedium)
                            Text(stringResource(R.string.subtitle), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("API ${PluginApi.version}", color = MaterialTheme.colorScheme.primary)
                    }
                }
                item {
                    Card {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                            state.preview?.let { Image(it.asImageBitmap(), stringResource(R.string.preview), Modifier.fillMaxSize()) }
                                ?: CircularProgressIndicator()
                        }
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.composition), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text("640 × 360 · 24 fps", style = MaterialTheme.typography.labelSmall)
                            }
                            Slider(value = state.time, onValueChange = { vm.pause(); vm.seek(it) }, valueRange = 0f..state.project.durationSeconds, enabled = editable)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Button(onClick = vm::play, enabled = editable) { Text(stringResource(if (state.playing) R.string.pause else R.string.play)) }
                                Text(String.format(Locale.ROOT, "%.2f / %.2f s", state.time, state.project.durationSeconds))
                                Text(stringResource(R.string.autosaved), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, enabled = editable, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.import_image)) }
                        OutlinedButton(onClick = { vm.exportPng() }, enabled = editable, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_png)) }
                    }
                    Button(onClick = vm::exportVideo, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_mp4)) }
                    state.exportProgress?.let { progress ->
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = vm::cancelExport) { Text(stringResource(R.string.cancel)) }
                    }
                    state.exportedUri?.let { uri ->
                        TextButton(onClick = {
                            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this@MainActivity, R.string.no_player, Toast.LENGTH_LONG).show() }
                        }) { Text(stringResource(R.string.open_video)) }
                    }
                }
                if (state.issues.isNotEmpty()) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.plugin_warning), style = MaterialTheme.typography.titleSmall)
                            state.issues.forEach { Text("${it.pluginId}: ${it.message}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                item { Text(stringResource(R.string.effect_stack), style = MaterialTheme.typography.titleLarge) }
                if (state.project.plugins.isEmpty()) item { Text(stringResource(R.string.empty_stack)) }
                itemsIndexed(state.project.plugins) { index, ref ->
                    val contribution = state.plugins.find { it.installed.manifest.id == ref.pluginId }?.contributions?.find { it.id == ref.contributionId }
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(contribution?.name ?: ref.contributionId, style = MaterialTheme.typography.titleMedium)
                                    Text("${ref.pluginId} · ${ref.version}", style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(checked = ref.enabled, onCheckedChange = { vm.toggle(index) }, enabled = editable)
                                TextButton(onClick = { vm.remove(index) }, enabled = editable) { Text("×") }
                            }
                            contribution?.parameters?.forEach { parameter ->
                                val persisted = ref.parameters[parameter.id] ?: parameter.default
                                var value by remember(persisted, index) { mutableFloatStateOf(persisted) }
                                Text("${parameter.name}: ${String.format(Locale.ROOT, "%.2f", value)}", style = MaterialTheme.typography.bodySmall)
                                Slider(value = value.coerceIn(parameter.min, parameter.max), onValueChange = { value = it },
                                    onValueChangeFinished = { vm.parameter(index, parameter.id, value) },
                                    valueRange = parameter.min..parameter.max, enabled = editable && ref.enabled)
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.plugins), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { pluginPicker.launch(arrayOf("*/*")) }, enabled = editable) { Text(stringResource(R.string.install)) }
                    }
                    Text(stringResource(R.string.package_hint), style = MaterialTheme.typography.bodySmall)
                }
                itemsIndexed(state.plugins, key = { _, row -> row.installed.manifest.id }) { _, row ->
                    val plugin = row.installed
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(plugin.manifest.name, style = MaterialTheme.typography.titleMedium)
                                    Text("v${plugin.manifest.version} · ${plugin.manifest.author}", style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(plugin.enabled, { vm.setEnabled(plugin.manifest.id, it) }, enabled = editable)
                            }
                            Text(plugin.manifest.description, style = MaterialTheme.typography.bodySmall)
                            row.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            if (!plugin.enabled) Text(stringResource(R.string.disabled_hint), color = MaterialTheme.colorScheme.secondary)
                            row.contributions.forEach { contribution ->
                                OutlinedButton(onClick = { vm.add(plugin, contribution) }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                                    Text("+ ${contribution.name} · ${contribution.kind}")
                                }
                            }
                            TextButton(onClick = { vm.uninstall(plugin.manifest.id) }, enabled = editable) { Text(stringResource(R.string.uninstall)) }
                        }
                    }
                }
                item { Text(stringResource(R.string.scope_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}
