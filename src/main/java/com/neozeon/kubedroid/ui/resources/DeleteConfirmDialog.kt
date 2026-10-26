package com.neozeon.kubedroid.ui.resources

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Spec 06 "Delete flow": ONE confirmation dialog shared by the detail top
 * bar and the list row overflow. Simple dialog (no typed confirmation —
 * human decision 2026-10-26 Q4) naming kind, name and namespace, plus the
 * catalog's cascade caution when the type has one.
 */
@Composable
fun DeleteConfirmDialog(
    kind: String,
    name: String,
    namespace: String?,
    caution: String?,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("delete-dialog"),
        title = { Text("Delete ${kind}?") },
        text = {
            Text(
                "$kind '$name' in ${namespace ?: "the cluster"} will be permanently deleted." +
                    (caution?.let { " $it" } ?: ""),
                modifier = Modifier.testTag("delete-message"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !deleting,
                // content-desc: the uiautomator driver can't use testTag and
                // the label "Delete" also exists in the detail top bar behind
                // the dialog — this unambiguously identifies the confirm.
                modifier = Modifier
                    .testTag("delete-confirm")
                    .semantics { contentDescription = "confirm-delete" },
            ) { Text(if (deleting) "Deleting…" else "Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("delete-cancel")) {
                Text(if (deleting) "Cancel" else "Keep")
            }
        },
    )
}

/** Info banner when a DELETE found the object already gone (404-as-success). */
@Composable
fun DeleteInfoBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("delete-info"),
    )
}
