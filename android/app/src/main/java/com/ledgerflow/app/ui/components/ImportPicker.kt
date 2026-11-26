package com.ledgerflow.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Safely launches Android's document picker without crashing on OEM devices where
 * DocumentsUI (ACTION_OPEN_DOCUMENT) is unavailable or rejects multi-MIME arrays.
 */
@Composable
fun rememberDataImportPicker(
    onUriSelected: (Uri) -> Unit,
    onLaunchFailed: (String) -> Unit,
): () -> Unit {
    val openDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onUriSelected)
    }
    val getContentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onUriSelected)
    }
    val chooserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let(onUriSelected)
    }

    return {
        val openDocOk = runCatching {
            openDocLauncher.launch(arrayOf("*/*"))
            true
        }.getOrDefault(false)

        if (!openDocOk) {
            val getContentOk = runCatching {
                getContentLauncher.launch("*/*")
                true
            }.getOrDefault(false)

            if (!getContentOk) {
                val chooserIntent = Intent.createChooser(
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    },
                    "Select CSV, TSV, or JSON file",
                )
                val chooserOk = runCatching {
                    chooserLauncher.launch(chooserIntent)
                    true
                }.getOrDefault(false)

                if (!chooserOk) {
                    onLaunchFailed("No file picker app found on this device to open CSV or JSON files.")
                }
            }
        }
    }
}
