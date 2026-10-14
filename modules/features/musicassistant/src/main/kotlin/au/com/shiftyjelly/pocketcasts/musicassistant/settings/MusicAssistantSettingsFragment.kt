package au.com.shiftyjelly.pocketcasts.musicassistant.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.utils.extensions.pxToDp
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MusicAssistantSettingsFragment : BaseFragment() {
    @Inject
    lateinit var settings: Settings

    private val viewModel: MusicAssistantSettingsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = contentWithoutConsumedInsets {
        val bottomInset by settings.bottomInset.collectAsStateWithLifecycle(initialValue = 0)
        val state by viewModel.state.collectAsStateWithLifecycle()
        AppThemeWithBackground(theme.activeTheme) {
            MusicAssistantSettingsPage(
                state = state,
                bottomInset = bottomInset.pxToDp(LocalContext.current).dp,
                onBackPress = { activity?.onBackPressedDispatcher?.onBackPressed() },
                onServerUrlChange = viewModel::updateServerUrl,
                onUsernameChange = viewModel::updateUsername,
                onPasswordChange = viewModel::updatePassword,
                onSignIn = viewModel::signIn,
                onChooseProvider = viewModel::chooseProvider,
                onSignOut = viewModel::signOut,
                onRetry = viewModel::retry,
            )
        }
    }
}
