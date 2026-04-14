package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage

@Composable
fun ChatImageFromUrl(
    url: String,
    modifier: Modifier = Modifier,
) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Fit,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp
                )
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
    )
}
