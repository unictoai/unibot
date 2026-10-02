package ai.unicto.unibot.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.autofill.AutofillProfileStore
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar

/**
 * Autofill from personal context — strictly opt-in, on-device only.
 *
 * Two gates, both required before unibot can fill anything:
 *  1. the in-app toggle here (default OFF),
 *  2. picking unibot in Android's system autofill settings (Android enforces
 *     this; we only deep-link to the page).
 *
 * The profile (name / email / phone) is stored in a private preferences file
 * on this phone. The service never saves form data and never networks —
 * it only offers the three stored values for fields the OS identifies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AutofillProfileStore.get(context) }
    val profile by store.profile.collectAsState()
    val autofillManager = remember {
        context.getSystemService(AutofillManager::class.java)
    }
    var systemEnabled by remember {
        mutableStateOf(autofillManager?.hasEnabledAutofillServices() == true)
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text("Autofill") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { Spacer(Modifier.height(8.dp)) }

            item {
                MuseCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.AutoFixHigh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Fill forms from my profile", fontSize = 16.sp, lineHeight = 21.sp)
                            Text(
                                "Name, email and phone — stored on this phone only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = profile.enabled,
                            onCheckedChange = { store.update(profile.copy(enabled = it)) },
                        )
                    }
                }
                MuseCaption(
                    "Android itself must also grant this: unibot can't fill anything until you choose it in the system autofill settings below.",
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }

            item {
                MuseSectionLabel("System permission")
                MuseCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (systemEnabled) "unibot is your autofill service."
                            else "unibot is not your autofill service yet.",
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Android shows a confirmation before switching. You can switch back any time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 21.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                            // Re-check on return; the settings screen above resumes us.
                            systemEnabled = autofillManager?.hasEnabledAutofillServices() == true
                        }) { Text("Choose in system settings") }
                    }
                }
            }

            item {
                MuseSectionLabel("Profile")
                MuseCard {
                    AutofillProfileEditor(
                        name = profile.name,
                        email = profile.email,
                        phone = profile.phone,
                        onSave = { n, e, p ->
                            store.update(profile.copy(name = n, email = e, phone = p))
                        },
                    )
                }
                MuseCaption(
                    "Only these three values are ever offered, and only to fields Android identifies as name, email or phone. unibot never reads what you type elsewhere and never saves forms.",
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun AutofillProfileEditor(
    name: String,
    email: String,
    phone: String,
    onSave: (String, String, String) -> Unit,
) {
    var n by remember(name) { mutableStateOf(name) }
    var e by remember(email) { mutableStateOf(email) }
    var p by remember(phone) { mutableStateOf(phone) }
    val dirty = n != name || e != email || p != phone
    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = n,
            onValueChange = { n = it },
            label = { Text("Full name") },
            leadingIcon = { Icon(Icons.Outlined.Badge, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = e,
            onValueChange = { e = it },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = p,
            onValueChange = { p = it },
            label = { Text("Phone") },
            leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSave(n.trim(), e.trim(), p.trim()) },
            enabled = dirty,
            modifier = Modifier.align(Alignment.End),
        ) { Text("Save") }
    }
}
