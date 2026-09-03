package ch.boazgruener.myday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Boaz's "group all authorizations into one button" ask, scoped to what Android actually allows:
 * one screen listing every grant Myday needs, each with its own status and its own "Grant"
 * action. Android has no way to unify a runtime permission dialog, a special-access Settings
 * toggle, and an OAuth consent screen into a single native prompt - this is the closest honest
 * equivalent, one app screen instead of five scattered buttons.
 *
 * Deliberately stateless - every status flag and every action is owned by the caller (the same
 * launchers/logic MainActivity already had), this just presents them together.
 */
@Composable
fun PermissionsScreen(
    onDismiss: () -> Unit,
    micLocationContactsGranted: Boolean,
    onRequestMicLocationContacts: () -> Unit,
    notificationAccessGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    dndAccessGranted: Boolean,
    onOpenDndSettings: () -> Unit,
    googleAuthorized: Boolean,
    onAuthorizeGoogle: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Authorization", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                Text(
                    "Everything Myday needs access to, in one place.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                PermissionRow(
                    title = "Microphone, Location & Contacts",
                    subtitle = "To hear \"Myday\", find places, and match names you say",
                    granted = micLocationContactsGranted,
                    onGrant = onRequestMicLocationContacts
                )
                PermissionRow(
                    title = "Notification Access",
                    subtitle = "To read WhatsApp messages and clear finished-meeting reminders",
                    granted = notificationAccessGranted,
                    onGrant = onOpenNotificationSettings
                )
                PermissionRow(
                    title = "Do Not Disturb Access",
                    subtitle = "Silences the recognizer's beep while Myday is listening",
                    granted = dndAccessGranted,
                    onGrant = onOpenDndSettings
                )
                PermissionRow(
                    title = "Google Sign-in",
                    subtitle = "For Gmail and Calendar",
                    granted = googleAuthorized,
                    onGrant = onAuthorizeGoogle
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        ) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = if (granted) "Granted" else "Not granted",
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 10.dp)
            )
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!granted) {
            Button(onClick = onGrant) { Text("Grant") }
        }
    }
}
