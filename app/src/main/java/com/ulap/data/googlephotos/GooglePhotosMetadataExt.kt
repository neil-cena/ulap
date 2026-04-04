package com.ulap.data.googlephotos

/** Parses [GooglePhotosMediaMetadata.width] / [height] strings from the REST API. */
fun GooglePhotosMediaMetadata?.pixelDimensions(): Pair<Int?, Int?> {
    if (this == null) return null to null
    val w = width?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
    val h = height?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
    return w to h
}
