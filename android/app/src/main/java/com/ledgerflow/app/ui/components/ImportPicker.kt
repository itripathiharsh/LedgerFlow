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
        var lastError: Throwable? = null
        val openDocOk = runCatching {
            openDocLauncher.launch(arrayOf("*/*"))
            true
        }.getOrElse {
            lastError = it
            false
        }

        if (!openDocOk) {
            val getContentOk = runCatching {
                getContentLauncher.launch("*/*")
                true
            }.getOrElse {
                lastError = it
                false
            }

            if (!getContentOk) {
                val openableChooser = Intent.createChooser(
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    },
                    "Select CSV, TSV, or JSON file",
                )
                val openableOk = runCatching {
                    chooserLauncher.launch(openableChooser)
                    true
                }.getOrElse {
                    lastError = it
                    false
                }

                if (!openableOk) {
                    val genericChooser = Intent.createChooser(
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                        },
                        "Select CSV, TSV, or JSON file",
                    )
                    val genericOk = runCatching {
                        chooserLauncher.launch(genericChooser)
                        true
                    }.getOrElse {
                        lastError = it
                        false
                    }

                    if (!genericOk) {
                        val detail = lastError?.let { " (${it.javaClass.simpleName})" }.orEmpty()
                        onLaunchFailed(
                            "No file picker app could be opened$detail. Share/send the file to LedgerFlow or use Smart Paste."
                        )
                    }
                }
            }
        }
    }
}
