package au.com.shiftyjelly.pocketcasts.account.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import au.com.shiftyjelly.pocketcasts.account.R
import au.com.shiftyjelly.pocketcasts.compose.buttons.RowOutlinedButton
import au.com.shiftyjelly.pocketcasts.compose.components.FormField
import au.com.shiftyjelly.pocketcasts.compose.components.TextP40
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.selfhosted.ServerStatus
import au.com.shiftyjelly.pocketcasts.selfhosted.ServerUrlViewModel

@Composable
internal fun SelfHostedServerField(
    viewModel: ServerUrlViewModel,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(modifier) {
        FormField(
            value = state.text,
            placeholder = stringResource(R.string.pod_pocket_server_url_hint),
            onValueChange = viewModel::updateText,
            enabled = enabled,
            isError = state.status == ServerStatus.Invalid,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
        )
        state.status.message()?.let { message ->
            TextP40(
                text = message,
                color = state.status.color(),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
internal fun ContinueWithoutAccountButton(
    viewModel: ServerUrlViewModel,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RowOutlinedButton(
        text = stringResource(R.string.pod_pocket_continue_without_account),
        onClick = { viewModel.checkConnection(onContinue) },
        includePadding = false,
        modifier = modifier,
    )
}

@Composable
private fun ServerStatus.message(): String? = when (this) {
    ServerStatus.Unchecked -> null
    ServerStatus.Invalid -> stringResource(R.string.pod_pocket_server_url_invalid)
    ServerStatus.Checking -> stringResource(R.string.pod_pocket_server_checking)
    is ServerStatus.Reachable -> stringResource(R.string.pod_pocket_server_reachable, host)
    is ServerStatus.Unreachable -> stringResource(R.string.pod_pocket_server_unreachable, reason)
}

@Composable
private fun ServerStatus.color(): Color = when (this) {
    ServerStatus.Invalid, is ServerStatus.Unreachable -> MaterialTheme.theme.colors.support05
    else -> MaterialTheme.theme.colors.primaryText02
}
