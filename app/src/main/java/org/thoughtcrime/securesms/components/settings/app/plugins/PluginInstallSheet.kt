package org.thoughtcrime.securesms.components.settings.app.plugins

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import im.molly.plugins.PluginInstallPreview
import im.molly.plugins.PluginPermissions
import org.signal.core.ui.compose.BottomSheets
import org.signal.core.ui.compose.SignalIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginInstallSheet(
  onDismiss: () -> Unit,
  onPreviewInstall: (
    name: String,
    version: String,
    author: String,
    description: String,
    sourceCode: String,
    declaredPermissions: Collection<String>
  ) -> PluginInstallPreview,
  onConfirmInstall: (preview: PluginInstallPreview, approvedPermissions: Collection<String>) -> Unit
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var name by remember { mutableStateOf("") }
  var version by remember { mutableStateOf("1.0.0") }
  var author by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }
  var sourceCode by remember { mutableStateOf("") }
  var declaredPermissions by remember { mutableStateOf<List<String>>(emptyList()) }
  var preview by remember { mutableStateOf<PluginInstallPreview?>(null) }
  val approvedMap = remember { mutableStateMapOf<String, Boolean>() }

  val filePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null) {
      val text = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
      }.getOrNull()
      if (!text.isNullOrBlank()) {
        sourceCode = text
        val parsed = parsePluginHeaders(text)
        if (name.isBlank() && parsed.name != null) name = parsed.name
        if (parsed.version != null) version = parsed.version
        if (author.isBlank() && parsed.author != null) author = parsed.author
        if (description.isBlank() && parsed.description != null) description = parsed.description
        if (parsed.permissions.isNotEmpty()) declaredPermissions = parsed.permissions
      }
    }
  }

  BottomSheets.BottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
        .padding(bottom = 24.dp)
    ) {
      val currentPreview = preview
      if (currentPreview == null) {
        Text(
          text = "Install plugin",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Load a .plugin file or paste source code. Molly will scan capabilities before installation.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        FilledTonalButton(
          onClick = { filePicker.launch(arrayOf("text/*", "application/javascript", "application/octet-stream")) },
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(
            imageVector = SignalIcons.File.imageVector,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Choose plugin file")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Plugin name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = version,
            onValueChange = { version = it },
            label = { Text("Version") },
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
            value = author,
            onValueChange = { author = it },
            label = { Text("Author") },
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text("Description (optional)") },
          maxLines = 2,
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = sourceCode,
          onValueChange = { sourceCode = it },
          label = { Text("Plugin source code") },
          minLines = 4,
          maxLines = 8,
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          horizontalArrangement = Arrangement.End,
          modifier = Modifier.fillMaxWidth()
        ) {
          TextButton(onClick = onDismiss) {
            Text("Cancel")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            enabled = name.isNotBlank() || sourceCode.isNotBlank(),
            onClick = {
              val scanned = onPreviewInstall(
                name.ifBlank { "Custom Plugin" },
                version,
                author,
                description,
                sourceCode,
                declaredPermissions
              )
              approvedMap.clear()
              for (perm in PluginPermissions.REQUESTABLE) {
                val requested = perm in scanned.requestedPermissions
                approvedMap[perm] = requested && !PluginPermissions.isDangerous(perm)
              }
              preview = scanned
            }
          ) {
            Text("Scan & review")
          }
        }
      } else {
        PermissionReviewContent(
          preview = currentPreview,
          approvedMap = approvedMap,
          onBack = { preview = null },
          onCancel = onDismiss,
          onInstall = {
            val approved = approvedMap.filterValues { it }.keys.toList()
            onConfirmInstall(currentPreview, approved)
          }
        )
      }
    }
  }
}

@Composable
private fun PermissionReviewContent(
  preview: PluginInstallPreview,
  approvedMap: MutableMap<String, Boolean>,
  onBack: () -> Unit,
  onCancel: () -> Unit,
  onInstall: () -> Unit
) {
  Text(
    text = "Review permissions",
    style = MaterialTheme.typography.headlineSmall,
    fontWeight = FontWeight.SemiBold
  )
  Spacer(modifier = Modifier.height(4.dp))
  Text(
    text = "${preview.name} v${preview.version} by ${preview.author}",
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary
  )
  if (preview.description.isNotBlank()) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = preview.description,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }

  if (preview.scanResult.isObfuscated) {
    Spacer(modifier = Modifier.height(12.dp))
    Card(
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
      ),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = SignalIcons.ErrorCircle.imageVector,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Obfuscation or dynamic code detected",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        preview.scanResult.obfuscationEvidence.take(4).forEach { evidence ->
          Text(
            text = "• $evidence",
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    }
  }

  Spacer(modifier = Modifier.height(16.dp))
  Text(
    text = "Requested & detected permissions",
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary
  )
  Spacer(modifier = Modifier.height(4.dp))
  Text(
    text = "Default-deny is enforced in the main process. Unchecked permissions will be blocked.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
  )
  Spacer(modifier = Modifier.height(8.dp))

  // UI permission is always granted and cannot be revoked
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
  ) {
    Checkbox(checked = true, onCheckedChange = null, enabled = false)
    Spacer(modifier = Modifier.width(8.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = PluginPermissions.describe(PluginPermissions.UI),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium
      )
      Text(
        text = "Always granted (required for plugin notices)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }

  HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

  val displayPerms = if (preview.requestedPermissions.isNotEmpty()) {
    preview.requestedPermissions
  } else {
    PluginPermissions.REQUESTABLE
  }

  for (perm in displayPerms) {
    val checked = approvedMap[perm] == true
    val dangerous = PluginPermissions.isDangerous(perm)
    val evidence = preview.scanResult.capabilities[perm].orEmpty()

    Row(
      verticalAlignment = Alignment.Top,
      modifier = Modifier
        .fillMaxWidth()
        .clickable { approvedMap[perm] = !checked }
        .padding(vertical = 6.dp)
    ) {
      Checkbox(
        checked = checked,
        onCheckedChange = { approvedMap[perm] = it }
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = PluginPermissions.describe(perm),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
          )
          if (dangerous) {
            Surface(
              color = MaterialTheme.colorScheme.errorContainer,
              contentColor = MaterialTheme.colorScheme.onErrorContainer,
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = "Sensitive",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }
        }
        Text(
          text = perm,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        evidence.take(2).forEach { item ->
          Text(
            text = "• $item",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }

  Spacer(modifier = Modifier.height(16.dp))

  Row(
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier = Modifier.fillMaxWidth()
  ) {
    OutlinedButton(onClick = onBack) {
      Text("Back")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = onCancel) {
        Text("Cancel")
      }
      Button(onClick = onInstall) {
        Text("Install")
      }
    }
  }
}

private data class ParsedHeaders(
  val name: String? = null,
  val version: String? = null,
  val author: String? = null,
  val description: String? = null,
  val permissions: List<String> = emptyList()
)

private fun parsePluginHeaders(source: String): ParsedHeaders {
  var name: String? = null
  var version: String? = null
  var author: String? = null
  var description: String? = null
  val permissions = mutableListOf<String>()

  for (line in source.lineSequence().take(40)) {
    val trimmed = line.trim().removePrefix("//").removePrefix("#").trim()
    when {
      trimmed.startsWith("@name ") -> name = trimmed.removePrefix("@name ").trim()
      trimmed.startsWith("@version ") -> version = trimmed.removePrefix("@version ").trim()
      trimmed.startsWith("@author ") -> author = trimmed.removePrefix("@author ").trim()
      trimmed.startsWith("@description ") -> description = trimmed.removePrefix("@description ").trim()
      trimmed.startsWith("@permission ") -> permissions.add(trimmed.removePrefix("@permission ").trim())
      trimmed.startsWith("@permissions ") -> {
        trimmed.removePrefix("@permissions ").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
          permissions.add(it)
        }
      }
    }
  }

  return ParsedHeaders(name, version, author, description, permissions)
}
