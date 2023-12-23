/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.inlay_hints

import com.codeium.intellij.chat_server_client.ChatServerClient
import com.codeium.intellij.chat_window.CODEIUM_CHAT_ID
import com.codeium.intellij.chat_window.ChatViewerWindowService
import com.codeium.intellij.isInjectedText
import com.codeium.intellij.language_server.LanguageServerService
import com.codeium.intellij.settings.AppSettingsState
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import exa.chat_pb.Chat.*
import exa.codeium_common_pb.CodeiumCommon
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.runBlocking

/** Collect hints (ie. Code Lenses) for each function declaration. */
fun getByteOffset(sourceCode: String, startLine: Int, startColumn: Int): Int? {
  val lines = sourceCode.split("\n")

  if (startLine <= 0 || startLine > lines.size || startColumn <= 0) {
    return null // Invalid start line or column
  }

  var byteOffset = 0
  for (lineIndex in 0 until startLine - 1) {
    byteOffset += lines[lineIndex].length + 1 // Add 1 for the newline character
  }

  byteOffset += startColumn - 1 // Adjust for 0-based indexing of characters

  return byteOffset
}

@Suppress("UnstableApiUsage")
class FunctionInfoInlayHintsCollector(private val file: PsiFile, private val editor: Editor) :
    FactoryInlayHintsCollector(editor) {
  /** Collect hints (ie. Code Lenses) for each function declaration. */
  private var collectedHints = mutableSetOf<Int>()
  private var lock = ReentrantLock()

  override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
    if (isInjectedText(element)) {
      return false
    }
    val languageServerService = service<LanguageServerService>()
    if (!languageServerService.languageServerStarted()) {
      return false
    }

    if (editor.project?.service<ChatViewerWindowService>()?.chatSupportedInIde() != true) {
      return false
    }

    if (!AppSettingsState.instance.chatInlayEnabled) {
      return false
    }

    var functionInfos: List<CodeiumCommon.FunctionInfo>? = null
    runBlocking {
      try {
        functionInfos = languageServerService.getFunctions(editor, file)
      } catch (e: Exception) {
        return@runBlocking
      }
    }
    if (functionInfos == null || functionInfos!!.isEmpty()) {
      return false
    }
    for (functionInfo in functionInfos!!) {
      // Get the byte offset of the function node name.
      val byteOffset =
          getByteOffset(file.text, functionInfo.startLine + 1, functionInfo.startCol + 1)
              ?: continue

      lock.lock()
      try {
        if (collectedHints.contains(byteOffset)) {
          continue
        }
        collectedHints.add(byteOffset)
      } finally {
        lock.unlock()
      }

      val refactorPresentation =
          factory.referenceOnHover(factory.smallText("Refactor")) { _, _ ->
            println("[Codeium] Refactor function `${functionInfo.nodeName}`")

            // Open the Codeium chat tool window.
            ToolWindowManager.getInstance(file.project).getToolWindow(CODEIUM_CHAT_ID)?.show()

            // Get the user's refactor instructions.
            val presets = getRefactorPresets(functionInfo.language)
            val suggestions = presets.map { preset -> preset.label }
            val result =
                Messages.showEditableChooseDialog(
                    "Input your refactor instructions or choose from a list of common options",
                    "Refactor Code",
                    null,
                    suggestions.toTypedArray(),
                    suggestions[0],
                    null)

            // User canceled the refactor.
            if (result.isNullOrEmpty()) {
              return@referenceOnHover
            }

            // Get the refactor instructions regardless of whether the user selected a preset.
            var preset = presets.firstOrNull { it.label == result }
            var instructions = result
            if (preset != null) {
              if (!preset.value.isNullOrEmpty()) {
                instructions = preset.value
              } else {
                instructions = preset.label
              }
            }
            if (instructions.isNullOrEmpty()) {
              return@referenceOnHover
            }

            // Construct and send the chat message request.
            var messageIntent =
                ChatMessageIntent.newBuilder()
                    .setFunctionRefactor(
                        IntentFunctionRefactor.newBuilder()
                            .setFunctionInfo(functionInfo)
                            .setFilePath(file.virtualFile.path)
                            .setLanguage(functionInfo.language)
                            .setRefactorDescription(instructions))
                    .build()
            if (isUnitTest(instructions)) {
              messageIntent =
                  ChatMessageIntent.newBuilder()
                      .setFunctionUnitTests(
                          IntentFunctionUnitTests.newBuilder()
                              .setFunctionInfo(functionInfo)
                              .setFilePath(file.virtualFile.path)
                              .setLanguage(functionInfo.language))
                      .build()
            }
            if (!ChatServerClient.isConnected()) {
              ChatServerClient.establishConnection()
            }
            ChatServerClient.sendChatMessage(messageIntent)
          }

      val explainPresentation =
          factory.referenceOnHover(factory.smallText("Explain")) { _, _ ->
            println("[Codeium] Explain function `${functionInfo.nodeName}`")

            // Open the Codeium chat tool window.
            ToolWindowManager.getInstance(file.project).getToolWindow(CODEIUM_CHAT_ID)?.show()

            // Construct and send the chat message request.
            val messageIntent =
                ChatMessageIntent.newBuilder()
                    .setExplainFunction(
                        IntentFunctionExplain.newBuilder()
                            .setFunctionInfo(functionInfo)
                            .setFilePath(file.virtualFile.path)
                            .setLanguage(functionInfo.language))
                    .build()
            if (!ChatServerClient.isConnected()) {
              ChatServerClient.establishConnection()
            }
            ChatServerClient.sendChatMessage(messageIntent)
          }
      val docstringPresentation =
          factory.referenceOnHover(factory.smallText("Docstring")) { _, _ ->
            println("[Codeium] Generate docstring for function `${functionInfo.nodeName}`")

            // Open the Codeium chat tool window.
            ToolWindowManager.getInstance(file.project).getToolWindow(CODEIUM_CHAT_ID)?.show()

            // Construct and send the chat message request.
            val messageIntent =
                ChatMessageIntent.newBuilder()
                    .setFunctionDocstring(
                        IntentFunctionDocstring.newBuilder()
                            .setFunctionInfo(functionInfo)
                            .setFilePath(file.virtualFile.path)
                            .setLanguage(functionInfo.language))
                    .build()
            if (!ChatServerClient.isConnected()) {
              ChatServerClient.establishConnection()
            }
            ChatServerClient.sendChatMessage(messageIntent)
          }

      var presentations =
          arrayOf(
              factory.text(functionInfo.leadingWhitespace),
              factory.smallText("✨ Codeium:  "),
              refactorPresentation,
              factory.text("  "),
              explainPresentation)
      if (functionInfo.docstring == null || functionInfo.docstring.isEmpty()) {
        presentations += arrayOf(factory.text("  "), docstringPresentation)
      }

      val seq = factory.seq(*presentations)
      sink.addBlockElement(
          byteOffset,
          relatesToPrecedingText = false,
          showAbove = true,
          priority = 0,
          presentation = seq)
    }

    // Don't continue to explore other elements because we are parsing the entire text file
    return false
  }
}
