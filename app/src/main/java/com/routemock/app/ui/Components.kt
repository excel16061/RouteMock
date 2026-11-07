package com.routemock.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.routemock.app.R
import com.routemock.app.data.RouteRepository
import com.routemock.app.domain.InputError

enum class NoticeTone { INFO, WARNING, ERROR, ACTIVE }

@Composable
fun Notice(
    tone: NoticeTone,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actions: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val (container: Color, content: Color) = when (tone) {
        NoticeTone.INFO -> scheme.surfaceVariant to scheme.onSurfaceVariant
        NoticeTone.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        NoticeTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        NoticeTone.ACTIVE -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            actions()
        }
    }
}

/**
 * A text field for a signed decimal. Many number keyboards have no minus key,
 * so the sign is flipped with a button instead.
 */
@Composable
fun CoordinateField(
    label: String,
    value: String,
    error: InputError?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(messageFor(it))) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = {
            TextButton(onClick = { onValueChange(flipSign(value)) }) {
                Text(stringResource(R.string.flip_sign))
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

private fun flipSign(text: String): String {
    val trimmed = text.trim()
    return when {
        trimmed.startsWith("-") -> trimmed.drop(1)
        trimmed.startsWith("+") -> "-" + trimmed.drop(1)
        else -> "-$trimmed"
    }
}

@StringRes
fun messageFor(error: InputError): Int = when (error) {
    InputError.REQUIRED -> R.string.error_required
    InputError.NOT_A_NUMBER -> R.string.error_not_a_number
    InputError.LATITUDE_OUT_OF_RANGE -> R.string.error_latitude_range
    InputError.LONGITUDE_OUT_OF_RANGE -> R.string.error_longitude_range
    InputError.SPEED_NOT_POSITIVE -> R.string.error_speed_positive
    InputError.SPEED_TOO_HIGH -> R.string.error_speed_high
}

/** Asks for a route name. Used for both saving and renaming. */
@Composable
fun RouteNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(RouteRepository.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.route_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = RouteRepository.isValidName(name)) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Hides "required" until the user has tried to continue, so a new form starts clean. */
fun InputError?.visible(showAll: Boolean): InputError? =
    if (this == InputError.REQUIRED && !showAll) null else this

