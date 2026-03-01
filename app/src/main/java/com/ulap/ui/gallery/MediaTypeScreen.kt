package com.ulap.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ulap.R
import com.ulap.domain.model.MediaType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTypeScreen(
    onItemClick: (String) -> Unit,
    viewModel: MediaTypeViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_media_type)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedType == MediaType.IMAGE,
                    onClick = { viewModel.selectType(MediaType.IMAGE) },
                    label = { Text(stringResource(R.string.media_type_images)) },
                )
                FilterChip(
                    selected = selectedType == MediaType.VIDEO,
                    onClick = { viewModel.selectType(MediaType.VIDEO) },
                    label = { Text(stringResource(R.string.media_type_videos)) },
                )
            }

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selectedType == MediaType.IMAGE)
                            stringResource(R.string.media_type_empty_images)
                        else
                            stringResource(R.string.media_type_empty_videos),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    groups.forEach { group ->
                        item(span = { GridItemSpan(3) }) {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        items(group.items, key = { it.id }) { item ->
                            MediaThumbnail(item = item, onClick = { onItemClick(item.id) })
                        }
                    }
                }
            }
        }
    }
}
