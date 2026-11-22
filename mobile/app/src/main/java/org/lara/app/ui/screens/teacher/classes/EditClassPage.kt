package org.lara.app.ui.screens.teacher.classes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.Cached
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.theme.*

/** Creates (classId null) or edits only the class details the contract has today (name and section). Room, subject and level need a contract change first. */
@Composable
fun EditClassPage(container: AppContainer, userId: String, reachable: StateFlow<Boolean>, expired: () -> Unit, classId: String?, close: () -> Unit) {
    val latestExpired by rememberUpdatedState(expired)
    val model: ClassManagementViewModel = viewModel(key = "class-management-$userId", factory = viewModelFactory {
        initializer { ClassManagementViewModel(container, userId, reachable) { latestExpired() } }
    })
    LaunchedEffect(model, classId) { model.state.first { !it.busy }; if (classId != null) model.select(classId) }
    val online by reachable.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val cached by model.classrooms.collectAsStateWithLifecycle()
    val row = (cached as? Cached.Content)?.value.orEmpty().find { it.id == classId }
    var name by rememberSaveable(row?.id) { mutableStateOf(row?.name.orEmpty()) }
    var section by rememberSaveable(row?.id) { mutableStateOf(row?.section.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    // Close once the save finishes without an error.
    LaunchedEffect(state.busy, state.error) { if (saving && !state.busy) { saving = false; if (state.error == null) close() } }
    run {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = LaraSpacing.S4).padding(bottom = LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            Text(stringResource(if (classId == null) R.string.classes_create else R.string.classes_manage), style = MaterialTheme.typography.titleLarge)
            if (online) state.error?.let { DiscoveryBanner(it, warning = true, success = false) }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.classes_name)) }, singleLine = true, shape = LaraShapes.medium)
            OutlinedTextField(section, { section = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.classes_section)) }, singleLine = true, shape = LaraShapes.medium)
            Button({ saving = true; if (classId == null) model.create(name.trim(), section.trim()) else model.rename(name.trim(), section.trim()) },
                Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget), enabled = online && !state.busy && name.isNotBlank() && section.isNotBlank()) {
                Text(stringResource(if (classId == null) R.string.classes_create_action else R.string.classes_save))
            }
        }
    }
}
