package com.shounak.localmeshai.ui.models

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateOf

/** Owned by the chat view model so switching tabs does not silently discard a draft. */
class ComposerAttachments {
    val bitmap = mutableStateOf<Bitmap?>(null)
    val fileUri = mutableStateOf<Uri?>(null)
    val fileName = mutableStateOf<String?>(null)
    val audioUri = mutableStateOf<Uri?>(null)
    val audioName = mutableStateOf<String?>(null)
    val audioBytes = mutableStateOf<ByteArray?>(null)
}
