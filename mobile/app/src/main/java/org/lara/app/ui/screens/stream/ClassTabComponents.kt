package org.lara.app.ui.screens.stream

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.lara.app.R
import org.lara.app.domain.model.Classroom
import org.lara.app.domain.model.Topic
import org.lara.app.ui.theme.*

@Composable
internal fun ClassBanner(classroom: Classroom) {
    Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface, shape = LaraShapes.medium) {
        Row(Modifier.fillMaxWidth().heightIn(min = 144.dp).padding(LaraSpacing.S6),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                Text(classroom.name, style = MaterialTheme.typography.headlineLarge)
                Text(classroom.section, style = MaterialTheme.typography.titleMedium, color = LaraColors.PrimaryLight)
            }
            Surface(color = LaraColors.Surface.copy(alpha = 0.2f), shape = CircleShape) {
                Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.padding(LaraSpacing.S4).size(40.dp))
            }
        }
    }
}

@Composable
internal fun TopicFilter(topics: List<Topic>, selected: String?, select: (String?) -> Unit, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize(), containerColor = LaraColors.Surface, topBar = {
            Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(LaraSpacing.S3),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    IconButton(onClick = close, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.enrollment_cancel_action))
                    }
                    Text(stringResource(R.string.reading_filter_topics), style = MaterialTheme.typography.titleMedium)
                }
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).selectableGroup(), contentPadding = PaddingValues(LaraSpacing.S4)) {
                item { TopicOption(stringResource(R.string.reading_all_topics), selected == null) { select(null) } }
                items(topics, key = { it.id }) { topic -> TopicOption(topic.name, topic.id == selected) { select(topic.id) } }
            }
        }
    }
}

@Composable
private fun TopicOption(name: String, selected: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
        .selectable(selected, role = Role.RadioButton, onClick = select).padding(LaraSpacing.S3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        RadioButton(selected, onClick = null)
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}
