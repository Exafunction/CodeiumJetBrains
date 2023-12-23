/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable

/**
 * Supports storing the application settings in a persistent way. The [State] and [Storage]
 * annotations define the name of the data and the file name where these persistent application
 * settings are stored.
 */
@State(
    name = "com.codeium.intellij.settings.AppSettingsState",
    storages = [Storage("CodeiumSettings.xml")])
class AppSettingsState : PersistentStateComponent<AppSettingsState> {
  var chatInlayEnabled = true
  var indexingEnabled = false
  var indexingMaxFileCount = 5000

  @Nullable
  override fun getState(): AppSettingsState {
    return this
  }

  override fun loadState(@NotNull state: AppSettingsState) {
    XmlSerializerUtil.copyBean(state, this)
  }

  companion object {
    val instance: AppSettingsState
      get() = ApplicationManager.getApplication().getService(AppSettingsState::class.java)
  }
}
