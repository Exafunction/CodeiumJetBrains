/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.auth

import com.codeium.intellij.CodeiumNotification
import com.codeium.intellij.chat_window.ChatViewerWindowService
import com.codeium.intellij.language_server.LanguageServerService
import com.codeium.intellij.statusbar.CodeiumStatusService
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import exa.language_server_pb.LanguageServer
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser

const val API_DOMAIN = "api.codeium.com"
const val PERSISTED_API_KEY_NAME = "codeium.api_key"

class CodeiumAuthService {
  private var showingNotification = AtomicBoolean()
  var loggedIn = AtomicBoolean()
  var isTeams = false
  private var redirectUri = ""
  private val logger = logger<CodeiumAuthService>()

  fun setAuthPort(port: Int) {
    redirectUri = "http://127.0.0.1:$port/auth"
  }

  fun isLoggedIn(): Boolean {
    if (!loggedIn.get()) {
      // If we previously logged in and have a saved API key, return
      val languageServerService = service<LanguageServerService>()
      PropertiesComponent.getInstance().getValue(PERSISTED_API_KEY_NAME)?.let {
        languageServerService.apiKey = it
        loggedIn.set(true)
      }
    }
    return loggedIn.get()
  }

  suspend fun maybeShowLoginPrompt(project: Project) {
    if (loggedIn.get()) {
      return
    }

    // If we previously logged in and have a saved API key, return
    val languageServerService = service<LanguageServerService>()
    PropertiesComponent.getInstance().getValue(PERSISTED_API_KEY_NAME)?.let {
      languageServerService.apiKey = it
      loggedIn.set(true)
      return
    }

    while (!service<LanguageServerService>().languageServerStarted()) {
      if (showingNotification.get()) {
        return
      }
      delay(1000)
    }

    val codeiumStatusService = service<CodeiumStatusService>()
    codeiumStatusService.updateState(LanguageServer.CodeiumState.CODEIUM_STATE_WARNING)

    // User is not logged in. Show a notification to the user to log in.
    if (!showingNotification.compareAndSet(false, true)) {
      return
    }
    val notification =
        CodeiumNotification(
            "com.codeium",
            "Codeium",
            "Please log in to Codeium to use the plugin.",
            NotificationType.WARNING)
    notification.addAction(
        object : NotificationAction("Log in") {
          override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            login(project) { notification.expire() }
          }
        })
    notification.whenExpired { showingNotification.set(false) }
    notification.notify(project)
  }

  fun login(project: Project, onSuccess: () -> Unit) {
    if (redirectUri.isEmpty()) {
      logger.warn("Redirect URI is empty. Cannot log in.")
      return
    }

    val languageServerService = service<LanguageServerService>()
    val uuid = java.util.UUID.randomUUID().toString()
    val scopes = "openid%20profile%20email"
    val urlSearchParams =
        "response_type=token&redirect_uri=$redirectUri&state=$uuid&scope=$scopes&redirect_parameters_type=query"
    val websiteUrl = service<CodeiumStatusService>().getWebsiteUrl()
    val url = "$websiteUrl/profile?$urlSearchParams"
    logger.info("Logging in to Codeium: $url")
    BrowserUtil.browse(url)

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val token = languageServerService.getAuthToken(uuid)
        registerUser(token, project)
        project.service<ChatViewerWindowService>().maybeShowChatWindow()
        project.service<ChatViewerWindowService>().reload()
        onSuccess()
      } catch (e: Exception) {
        logger.warn("Failed to log in to Codeium", e)
        service<CodeiumStatusService>()
            .updateState(
                LanguageServer.State.newBuilder()
                    .setState(LanguageServer.CodeiumState.CODEIUM_STATE_ERROR)
                    .setMessage("Failed to authenticate to Codeium")
                    .build())
      }
    }
  }

  private suspend fun registerUser(authToken: String, project: Project) {
    val registerUserUrl =
        if (service<CodeiumStatusService>().hasEnterprisePlugin()) {
          URL(
              "${service<CodeiumStatusService>().getApiServerUrl()}/exa.seat_management_pb.SeatManagementService/RegisterUser")
        } else {
          URL("https://$API_DOMAIN/register_user/")
        }

    val connection =
        withContext(Dispatchers.IO) { registerUserUrl.openConnection() }
            as java.net.HttpURLConnection
    val request = JSONObject()
    request["firebase_id_token"] = authToken
    val postData = request.toJSONString()
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Content-Length", postData.length.toString())
    connection.requestMethod = "POST"
    connection.outputStream.use { it.write(postData.toByteArray()) }
    val response = connection.inputStream.bufferedReader().use { it.readText() }
    val parser = JSONParser()
    val json = parser.parse(response) as JSONObject
    val apiKey = json["api_key"] as String
    service<LanguageServerService>().apiKey = apiKey
    PropertiesComponent.getInstance().setValue(PERSISTED_API_KEY_NAME, apiKey)
    loggedIn.set(true)

    CodeiumNotification(
            "com.codeium",
            "Codeium",
            "Successfully logged in to Codeium!",
            NotificationType.INFORMATION)
        .notify(project)

    val codeiumStatusService = service<CodeiumStatusService>()
    codeiumStatusService.updateState(LanguageServer.CodeiumState.CODEIUM_STATE_SUCCESS)
  }

  fun logout() {
    PropertiesComponent.getInstance().unsetValue(PERSISTED_API_KEY_NAME)
    val languageServerService = service<LanguageServerService>()
    languageServerService.apiKey = ""
    loggedIn.set(false)

    val codeiumStatusService = service<CodeiumStatusService>()
    codeiumStatusService.updateState(
        LanguageServer.State.newBuilder()
            .setState(LanguageServer.CodeiumState.CODEIUM_STATE_WARNING)
            .setMessage("Please log in to Codeium to use the plugin.")
            .build())
  }

  fun provideAuthToken(project: Project, onSuccess: () -> Unit) {
    val scopes = "openid%20profile%20email"
    val urlSearchParams =
        "response_type=token&redirect_uri=jetbrains-show-auth-token&state=a&scope=$scopes&redirect_parameters_type=query"
    val websiteUrl = service<CodeiumStatusService>().getWebsiteUrl()
    val url = "$websiteUrl/profile?$urlSearchParams"

    // Show dialog to user to allow them to provide their auth token
    val dialog = ProvideAuthTokenDialog(url)
    if (dialog.showAndGet()) {
      val token = dialog.getAuthToken()

      CoroutineScope(Dispatchers.IO).launch {
        try {
          registerUser(token, project)
          project.service<ChatViewerWindowService>().maybeShowChatWindow()
          project.service<ChatViewerWindowService>().reload()
          onSuccess()
        } catch (e: Exception) {
          logger.warn("Failed to log in to Codeium", e)
          service<CodeiumStatusService>()
              .updateState(
                  LanguageServer.State.newBuilder()
                      .setState(LanguageServer.CodeiumState.CODEIUM_STATE_ERROR)
                      .setMessage("Failed to authenticate to Codeium")
                      .build())
        }
      }
    }
  }
}
