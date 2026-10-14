package au.com.shiftyjelly.pocketcasts.musicassistant.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import au.com.shiftyjelly.pocketcasts.compose.bars.ThemedTopAppBar
import au.com.shiftyjelly.pocketcasts.compose.buttons.RowButton
import au.com.shiftyjelly.pocketcasts.compose.components.FormField
import au.com.shiftyjelly.pocketcasts.compose.components.SettingRow
import au.com.shiftyjelly.pocketcasts.compose.components.TextP40
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.musicassistant.R
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.images.R as IR

@Composable
fun MusicAssistantSettingsRow(modifier: Modifier = Modifier) {
    SettingRow(
        primaryText = stringResource(R.string.pod_pocket_music_assistant),
        secondaryText = stringResource(R.string.pod_pocket_ma_settings_summary),
        icon = painterResource(IR.drawable.ic_chrome_cast),
        modifier = modifier,
    )
}

@Composable
internal fun MusicAssistantSettingsPage(
    state: MusicAssistantSettingsState,
    bottomInset: Dp,
    onBackPress: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onChooseProvider: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    Column {
        ThemedTopAppBar(
            title = stringResource(R.string.pod_pocket_music_assistant),
            bottomShadow = true,
            onNavigationClick = onBackPress,
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset + 16.dp),
            modifier = Modifier.background(MaterialTheme.theme.colors.primaryUi02),
        ) {
            val account = state.account
            if (!state.ready) {
                return@LazyColumn
            }
            if (account == null) {
                item { SignInFields(state.form, onServerUrlChange, onUsernameChange, onPasswordChange, onSignIn) }
                return@LazyColumn
            }
            item {
                SettingRow(
                    primaryText = account.serverUrl.toString(),
                    secondaryText = connectionText(state.connection),
                    indent = false,
                )
            }
            if (account.gpodderInstanceId == null && state.form.providerChoices.isEmpty()) {
                item { TextP40(text = stringResource(R.string.pod_pocket_ma_no_provider), modifier = Modifier.padding(vertical = 8.dp)) }
            }
            if (state.form.providerChoices.isNotEmpty()) {
                item { TextP40(text = stringResource(R.string.pod_pocket_ma_choose_provider), modifier = Modifier.padding(vertical = 8.dp)) }
                items(state.form.providerChoices, key = { it.instanceId }) { provider ->
                    SettingRow(
                        primaryText = provider.name,
                        secondaryText = provider.instanceId,
                        indent = false,
                        modifier = Modifier.clickable { onChooseProvider(provider.instanceId) },
                    )
                }
            }
            item {
                RowButton(
                    text = stringResource(if (state.form.busy) R.string.pod_pocket_ma_signing_out else R.string.pod_pocket_ma_sign_out),
                    onClick = onSignOut,
                    enabled = !state.form.busy,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SignInFields(
    form: SignInForm,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    Column {
        FormField(
            value = form.serverUrl,
            placeholder = stringResource(R.string.pod_pocket_ma_server),
            onValueChange = onServerUrlChange,
            enabled = !form.busy,
            isError = form.error == SignInError.InvalidAddress,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        FormField(
            value = form.username,
            placeholder = stringResource(R.string.pod_pocket_ma_username),
            onValueChange = onUsernameChange,
            enabled = !form.busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            modifier = Modifier.padding(top = 12.dp),
        )
        FormField(
            value = form.password,
            placeholder = stringResource(R.string.pod_pocket_ma_password),
            onValueChange = onPasswordChange,
            enabled = !form.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            onImeAction = onSignIn,
            modifier = Modifier.padding(top = 12.dp),
        )
        form.error?.let { error ->
            TextP40(text = errorText(error), color = MaterialTheme.theme.colors.support05, modifier = Modifier.padding(top = 8.dp))
        }
        RowButton(
            text = stringResource(if (form.busy) R.string.pod_pocket_ma_signing_in else R.string.pod_pocket_ma_sign_in),
            onClick = onSignIn,
            enabled = !form.busy,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun errorText(error: SignInError): String = when (error) {
    SignInError.InvalidAddress -> stringResource(R.string.pod_pocket_ma_invalid_address)
    is SignInError.Rejected -> stringResource(R.string.pod_pocket_ma_rejected, error.reason)
    is SignInError.Unreachable -> stringResource(R.string.pod_pocket_ma_unreachable, error.reason)
}

@Composable
private fun connectionText(state: MaSessionState): String = when (state) {
    is MaSessionState.Connected -> stringResource(R.string.pod_pocket_ma_status_connected)
    MaSessionState.Connecting -> stringResource(R.string.pod_pocket_ma_status_connecting)
    is MaSessionState.Reconnecting -> stringResource(R.string.pod_pocket_ma_status_reconnecting)
    MaSessionState.SignInRequired -> stringResource(R.string.pod_pocket_ma_status_sign_in)
    MaSessionState.Disconnected, MaSessionState.NotConfigured -> stringResource(R.string.pod_pocket_ma_status_offline)
}
