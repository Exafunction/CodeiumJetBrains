/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.statusbar

import com.codeium.intellij.CodeiumNotification
import com.codeium.intellij.isWellSupportedLanguage
import com.codeium.intellij.language_server.LanguageServerService
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import exa.codeium_common_pb.CodeiumCommon
import exa.language_server_pb.LanguageServer
import io.grpc.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser

const val PERSISTED_LANGUAGE_CONFIGURATION_NAME = "codeium.language_configuration"
const val PERSISTED_API_SERVER_URL_NAME = "com.codeium.apiServerUrl"
const val PERSISTED_PORTAL_URL_NAME = "com.codeium.portalUrl"

const val ENTERPRISE_PLUGIN_ID = "com.codeium.enterprise"
const val STANDARD_PLUGIN_ID = "com.codeium.intellij"

const val DEFAULT_API_SERVER_URL = "https://server.codeium.com"
const val DEFAULT_WEBSITE_URL = "https://www.codeium.com"

class CodeiumStatusService {
  private var status: LanguageServer.State? = null
  private var popupMessage: String? = null
  private var codeiumLanguageConfig = mutableMapOf<String, Boolean>()
  private val logger = logger<CodeiumStatusService>()

  private var shownAbortErrorNotification = false

  init {
    val propertiesComponent = PropertiesComponent.getInstance()
    val languageConfiguration = propertiesComponent.getValue(PERSISTED_LANGUAGE_CONFIGURATION_NAME)
    if (languageConfiguration != null) {
      val parser = JSONParser()
      val json = parser.parse(languageConfiguration) as JSONObject
      json.forEach { (key, value) -> codeiumLanguageConfig[key as String] = value as Boolean }
    } else {
      codeiumLanguageConfig["*"] = true
    }
  }

  fun hasEnterprisePlugin(): Boolean {
    val enterpriseCodeiumPlugin = PluginManagerCore.getPlugin(PluginId.getId(ENTERPRISE_PLUGIN_ID))
    return enterpriseCodeiumPlugin != null
  }

  fun getApiServerUrl(): String {
    if (hasEnterprisePlugin()) {
      val propertiesComponent = PropertiesComponent.getInstance()
      val apiServerUrl = propertiesComponent.getValue(PERSISTED_API_SERVER_URL_NAME)
      if (apiServerUrl != null) {
        return apiServerUrl
      } else {
        logger.warn("$PERSISTED_API_SERVER_URL_NAME is not set for the enterprise plugin")
      }
    }
    return DEFAULT_API_SERVER_URL
  }

  fun getWebsiteUrl(): String {
    if (hasEnterprisePlugin()) {
      val propertiesComponent = PropertiesComponent.getInstance()
      val portalUrl = propertiesComponent.getValue(PERSISTED_PORTAL_URL_NAME)
      if (portalUrl != null) {
        return portalUrl
      } else {
        logger.warn("$PERSISTED_PORTAL_URL_NAME is not set for the enterprise plugin")
      }
    }
    return DEFAULT_WEBSITE_URL
  }

  fun getVersion(): String {
    if (hasEnterprisePlugin()) {
      return PluginManagerCore.getPlugin(PluginId.getId(ENTERPRISE_PLUGIN_ID))?.version ?: ""
    }
    return PluginManagerCore.getPlugin(PluginId.getId(STANDARD_PLUGIN_ID))?.version ?: ""
  }

  fun toggleCodeiumEnabled(languageId: String = "*") {
    val eventJson = JSONObject()
    eventJson["languageId"] = languageId
    val copilot = PluginManagerCore.getPlugin(PluginId.getId("com.github.copilot"))
    eventJson["copilotInstalled"] = copilot != null
    eventJson["copilotEnabled"] = copilot?.isEnabled ?: false

    val isCodeiumEnabled = isCodeiumEnabled(languageId)
    val eventType =
        if (isCodeiumEnabled) CodeiumCommon.EventType.EVENT_TYPE_DISABLE_CODEIUM
        else CodeiumCommon.EventType.EVENT_TYPE_ENABLE_CODEIUM

    // Toggle the language configuration.
    codeiumLanguageConfig[languageId] = !isCodeiumEnabled
    // If the current configuration equals the default configuration, remove it from the
    // configuration.
    if (codeiumLanguageConfig[languageId] == isWellSupportedLanguage(languageId)) {
      codeiumLanguageConfig.remove(languageId)
    }

    val languageServerService = service<LanguageServerService>()
    val event =
        CodeiumCommon.Event.newBuilder()
            .setEventType(eventType)
            .setEventJson(eventJson.toJSONString())
            .build()
    CoroutineScope(Dispatchers.IO).launch { languageServerService.recordEvent(event) }

    // Persist the language configuration
    val jsonObject = JSONObject()
    codeiumLanguageConfig.forEach { (key, value) -> jsonObject[key] = value }
    PropertiesComponent.getInstance()
        .setValue(PERSISTED_LANGUAGE_CONFIGURATION_NAME, jsonObject.toJSONString())

    updateWidgets()
  }

  fun isCodeiumEnabled(languageId: String): Boolean {
    // If all languages are disabled, return false
    if (codeiumLanguageConfig["*"] == false) {
      return false
    }

    // If the language is explicitly set, return that value, otherwise return whether it is
    // enabled by default.
    return codeiumLanguageConfig.getOrElse(languageId) { isWellSupportedLanguage(languageId) }
  }

  fun updateState(status: LanguageServer.State) {
    this.status = status
    this.popupMessage = null
    updateWidgets()
  }

  fun updateState(state: LanguageServer.CodeiumState) {
    this.status = LanguageServer.State.newBuilder().setState(state).build()
    this.popupMessage = null
    updateWidgets()
  }

  private fun updateWidgets() {
    ProjectManager.getInstance().openProjects.forEach { project ->
      val statusBar = WindowManager.getInstance().getStatusBar(project)
      val widget = statusBar?.getWidget("CodeiumWidget") ?: return@forEach
      if (widget is CodeiumWidget) {
        widget.update { statusBar.updateWidget("CodeiumWidget") }
      }
    }
  }

  fun getState(): LanguageServer.State? {
    return status
  }

  fun showAbortErrorNotification(status: Status, editor: Editor) {
    if (shownAbortErrorNotification) {
      return
    }
    val notification =
        CodeiumNotification(
            "com.codeium",
            "Codeium",
            status.description ?: "Unknown error",
            NotificationType.WARNING)
    notification.notify(editor.project)
    shownAbortErrorNotification = true
  }
}
