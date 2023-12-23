/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.chat_window

import com.codeium.intellij.icons.CodeiumIcons
import com.codeium.intellij.statusbar.CodeiumStatusService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import java.net.URL
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

const val CODEIUM_CHAT_ID = "Codeium Chat"

class ChatViewerWindowService(val project: Project) {
  private var chatSupportedInIde: Boolean? = null

  private val chatViewerWindow =
      try {
        ChatViewerWindow(project)
      } catch (e: IllegalStateException) {
        logger<ChatViewerWindowService>().error(e)
        chatSupportedInIde = false
        null
      }

  fun chatSupportedInIde(): Boolean {
    val codeiumStatusService = service<CodeiumStatusService>()
    if (chatSupportedInIde == null) {
      chatSupportedInIde =
          if (codeiumStatusService.hasEnterprisePlugin()) {
            // Check if chat is enabled from the enterprise portal.
            val portalUrl = codeiumStatusService.getWebsiteUrl()
            val responseStream =
                URL("$portalUrl/api/chat_enabled").openConnection().getInputStream()
            val response = responseStream.readBytes()
            responseStream.close()
            response.contentEquals("true".toByteArray())
          } else {
            true
          }
    }
    return chatSupportedInIde!!
  }
  fun reload() {
    chatViewerWindow?.reload()
  }
  fun maybeShowChatWindow() {
    if (chatSupportedInIde()) {
      val toolWindowManager = ToolWindowManager.getInstance(project)
      if (toolWindowManager.toolWindowIdSet.contains(CODEIUM_CHAT_ID)) return
      toolWindowManager.invokeLater {
        val toolWindow =
            toolWindowManager.registerToolWindow(
                id = CODEIUM_CHAT_ID, anchor = ToolWindowAnchor.RIGHT, canCloseContent = true)
        toolWindow.setIcon(CodeiumIcons.CodeiumIcon)
        val chatViewerWindow = project.service<ChatViewerWindowService>().chatViewerWindow
        toolWindow.component.parent.add(chatViewerWindow?.content)

        // Listen for when the tool window is opened and closed.
        toolWindow.component.addAncestorListener(
            object : AncestorListener {

              // Listen for when the chat window is opened to ensure metadata is properly hydrated.
              override fun ancestorAdded(event: AncestorEvent?) {
                reload()
              }

              override fun ancestorRemoved(event: AncestorEvent?) {}
              override fun ancestorMoved(event: AncestorEvent?) {}
            })
      }
    }
  }
}
