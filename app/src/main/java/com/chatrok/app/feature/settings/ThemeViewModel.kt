package com.chatrok.app.feature.settings

import androidx.lifecycle.ViewModel
import com.chatrok.app.core.designsystem.theme.ThemeController
import com.chatrok.app.core.designsystem.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val themeController: ThemeController
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = themeController.themeMode

    fun setThemeMode(mode: ThemeMode) {
        themeController.setThemeMode(mode)
    }
}
