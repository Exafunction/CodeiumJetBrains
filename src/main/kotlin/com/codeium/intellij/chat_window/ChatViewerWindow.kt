/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.chat_window

import com.codeium.intellij.chat_server_client.ChatServerClient
import com.codeium.intellij.language_server.LanguageServerService
import com.codeium.intellij.statusbar.CodeiumStatusService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefJSQuery
import exa.codeium_common_pb.CodeiumCommon
import java.net.URLEncoder
import javax.swing.JComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun convertToUrlSearchParams(params: Map<String, String>): String {
  val searchParams = StringBuilder()
  for ((key, value) in params) {
    val encodedKey = URLEncoder.encode(key, "UTF-8")
    val encodedValue = URLEncoder.encode(value, "UTF-8")
    searchParams.append("$encodedKey=$encodedValue&")
  }
  return searchParams.toString().dropLast(1)
}

class ChatViewerWindow(
    private val project: Project,
) : Disposable {

  private val browser: JBCefBrowser =
      JBCefBrowserBuilder()
          .setEnableOpenDevToolsMenuItem(true)
          .setOffScreenRendering(false)
          .setUrl("http://127.0.0.1:3000")
          .build()
  private val queryMessenger = JBCefJSQuery.create(browser as JBCefBrowserBase)
  private val browserLoadedState: MutableStateFlow<Boolean> = MutableStateFlow(false)

  private val codeiumStatusService: CodeiumStatusService = service<CodeiumStatusService>()
  private var languageServerService: LanguageServerService = service<LanguageServerService>()
  private val logger = logger<ChatViewerWindow>()

  init {
    browser.setPageBackgroundColor("#fff")

    // If true, even location.reload() calls will open a browser window.
    browser.setOpenLinksInExternalBrowser(false)

    // Initialize the browser URL.
    CoroutineScope(Dispatchers.IO).launch { loadClient() }

    // Listen for messages from the browser.
    queryMessenger.addHandler { msg ->
      when (msg) {
        "ready" -> {
          browserLoadedState.value = true
        }
      }
      null
    }
  }

  val content: JComponent
    get() = browser.component

  /** Load the Chat client and attach the appropriate URL search params. */
  fun loadClient() {
    var metadata: CodeiumCommon.Metadata = CodeiumCommon.Metadata.newBuilder().build()
    try {
      // Will throw an error if the API key is empty.
      metadata = languageServerService.getMetadata()
    } catch (e: Exception) {
      logger.warn("Unable to load metadata from the language server: $e")
    }

    runBlocking {
      try {
        val ports = languageServerService.refreshProcessPorts()
        logger.info("Refreshed process ports: $ports")
      } catch (e: Exception) {
        logger.warn("Unable to refresh process ports: $e")
      }
    }

    val searchParams =
        mapOf(
            "api_key" to languageServerService.apiKey,
            "extension_name" to metadata.extensionName,
            "extension_version" to metadata.extensionVersion,
            "ide_name" to metadata.ideName,
            "ide_version" to metadata.ideVersion,
            "locale" to metadata.locale,
            "ide_telemetry_enabled" to "true",
            "app_name" to "Jetbrains",
            "web_server_url" to ChatServerClient.getServerUrl(),
            "has_dev_extension" to "false",
            "has_enterprise_extension" to codeiumStatusService.hasEnterprisePlugin().toString(),
        )

    val url = "${ChatServerClient.getClientUrl()}?${convertToUrlSearchParams(searchParams)}"
    logger.info("Loading Chat client: $url")
    browser.loadHTML(
        """
        <body>
          <iframe
            src="$url"
            style="position:fixed; top:0px; left:0px; bottom:0px; right:0px; width:100%; height:100%; border:none; margin:0; padding:0; overflow:hidden; z-index:999999;"
            name="chat-client-iframe">
              Your browser doesn't support iframes
          </iframe>
          <script>
            (function() {
                window.intellij = {
                    message(msg) {
                        ${queryMessenger.inject("msg")}
                    }
                }
                window.intellij.message("ready");
             })()
          </script>
        </body>
      """
            .trimIndent())
  }

  /** Reload the Chat client. */
  fun reload() {
    browserLoadedState.value = false
    loadClient()
  }

  override fun dispose() {
    browser.dispose()
  }
}
