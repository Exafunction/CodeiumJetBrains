/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij

import com.codeium.intellij.auth.CodeiumAuthService
import com.codeium.intellij.language_server.LanguageServerService
import com.codeium.intellij.statusbar.CodeiumStatusService
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.project.currentOrDefaultProject
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import exa.codeium_common_pb.CodeiumCommon
import exa.language_server_pb.LanguageServer
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val APPLY_COMPLETION_COMMAND_NAME = "Apply Codeium Suggestion"

fun isInjectedText(file: PsiElement): Boolean {
  val virtualFile = file.containingFile.virtualFile ?: return false
  if (virtualFile is VirtualFileWindow) {
    return true
  }
  return false
}

class EditorManager {
  private val inlays = WeakHashMap<Editor, MutableList<Inlay<CompletionInlayRenderer>>>()
  private val completionItemsList = WeakHashMap<Editor, List<LanguageServer.CompletionItem>>()
  private val shownCompletionItemsIndex = WeakHashMap<Editor, Int>()
  private val completionJobs = WeakHashMap<Editor, Job>()

  fun areInlaysShown(editor: Editor): Boolean {
    return shownCompletionItemsIndex.containsKey(editor)
  }

  fun isAvailable(editor: Editor): Boolean {
    return !editor.isDisposed && editor.project != null
  }

  fun applyCompletionFeedback(editor: Editor, skipApply: Boolean = false): Boolean {
    val completionItemIndex = shownCompletionItemsIndex[editor] ?: return false
    val completionItem = completionItemsList[editor]?.get(completionItemIndex) ?: return false
    val completion = completionItem.completion
    val range = completionItem.range

    if (!skipApply) {
      // Send accept completion to the language server.
      val languageServerService = service<LanguageServerService>()
      CoroutineScope(Dispatchers.IO).launch {
        languageServerService.acceptCompletion(completion.completionId)
      }
    }
    // TODO(prem): Telemetry for explicit reject.

    // Clean up inlay and to-be-inserted completion state.
    completionItemsList.remove(editor)
    shownCompletionItemsIndex.remove(editor)
    disposeInlays(editor)

    if (skipApply) {
      return true
    }

    // Insert the completion in the editor.
    WriteCommandAction.runWriteCommandAction(
        editor.project,
        APPLY_COMPLETION_COMMAND_NAME,
        "Codeium",
        {
          if (editor.project?.isDisposed == true) return@runWriteCommandAction
          val document = editor.document
          val startOffset = numUtf8BytesToNumCodeUnits(document.text, range.startOffset.toInt())
          val endOffset = numUtf8BytesToNumCodeUnits(document.text, range.endOffset.toInt())
          document.replaceString(
              startOffset, endOffset, completion.text + completionItem.suffix.text)
          val newCaretOffset =
              (startOffset +
                  completion.text.length +
                  completionItem.suffix.text.length +
                  completionItem.suffix.deltaCursorOffset.toInt())
          editor.caretModel.moveToOffset(newCaretOffset)
          editorModified(editor, newCaretOffset)
        })
    return true
  }

  private fun readyToSuggestCompletions(editor: Editor): Boolean {
    // Check if autocomplete window is open (this also binds to the tab key)
    if (LookupManager.getActiveLookup(editor) != null) {
      return false
    }
    // Check if there are multiple cursors
    if (editor.caretModel.caretCount > 1) {
      return false
    }
    // Check if anything is highlighted
    if (editor.selectionModel.hasSelection()) {
      return false
    }
    return true
  }

  fun editorModified(editor: Editor, offset: Int) {
    // Cancel previous completion job
    cancelCompletionRequests(editor)

    // Dismiss previous suggestions
    dismissSuggestions(editor)

    if (!readyToSuggestCompletions(editor)) {
      return
    }

    // Check if Codeium is disabled for this language
    val codeiumStatusService = service<CodeiumStatusService>()
    val file =
        PsiDocumentManager.getInstance(currentOrDefaultProject(editor.project))
            .getPsiFile(editor.document)
            ?: // TODO(rahul): Log some error.
        return
    if (isInjectedText(file)) {
      return
    }
    if (!codeiumStatusService.isCodeiumEnabled(file.language.id)) {
      return
    }

    // Check if user is logged in
    val codeiumAuthService = service<CodeiumAuthService>()
    if (!codeiumAuthService.loggedIn.get()) {
      return
    }

    codeiumStatusService.updateState(LanguageServer.CodeiumState.CODEIUM_STATE_PROCESSING)
    val languageServerService = service<LanguageServerService>()
    val editorOptions = getEditorOptions(editor)
    val completionJob =
        CoroutineScope(Dispatchers.IO).launch {
          val completionItems =
              languageServerService.getCompletions(editor, file, editorOptions, offset)
          if (completionItems.isEmpty()) return@launch
          completionItemsList[editor] = completionItems
          displayCompletionItem(editor, 0)
        }
    completionJobs[editor] = completionJob
  }

  private fun displayCompletionItem(editor: Editor, index: Int) {
    val completionItems = completionItemsList[editor] ?: return
    if (index < 0 || index >= completionItems.size) return
    val completionItem = completionItems[index]
    if (completionItem.completionPartsCount == 0) return
    val inlayModel = editor.inlayModel
    ApplicationManager.getApplication().runReadAction {
      val renderers = getCompletionInlayRenderers(editor, completionItem)
      ApplicationManager.getApplication().invokeLater {
        if (editor.project?.isDisposed == true) return@invokeLater
        if (!readyToSuggestCompletions(editor)) return@invokeLater
        disposeInlays(editor)
        shownCompletionItemsIndex[editor] = index

        renderers.forEach { renderer ->
          val rendererOffset = renderer.getOffset()
          val inlay =
              when (renderer.getInlayType()) {
                InlayType.INLINE -> {
                  inlayModel.addInlineElement(rendererOffset, true, renderer)
                }
                InlayType.BLOCK -> {
                  inlayModel.addBlockElement(rendererOffset, true, false, 0, renderer)
                }
                InlayType.AFTER_LINE -> {
                  inlayModel.addAfterLineEndElement(rendererOffset, true, renderer)
                }
              }
          if (inlay != null) {
            inlays.getOrPut(editor) { mutableListOf() }.add(inlay)
          }
        }
      }
    }
  }
  fun showNextCompletionItem(editor: Editor) {
    val completionItems = completionItemsList[editor] ?: return
    val shownIndex = shownCompletionItemsIndex[editor] ?: return
    val nextIndex = (shownIndex + 1) % completionItems.size
    displayCompletionItem(editor, nextIndex)
  }
  fun showPreviousCompletionItem(editor: Editor) {
    val completionItems = completionItemsList[editor] ?: return
    val shownIndex = shownCompletionItemsIndex[editor] ?: return
    val nextIndex = (shownIndex - 1 + completionItems.size) % completionItems.size
    displayCompletionItem(editor, nextIndex)
  }

  private fun getCompletionInlayRenderers(
      editor: Editor,
      completionItem: LanguageServer.CompletionItem
  ): List<CompletionInlayRenderer> {
    val renderers = mutableListOf<CompletionInlayRenderer>()

    for (completionPart in completionItem.completionPartsList) {
      val offset = numUtf8BytesToNumCodeUnits(editor.document.text, completionPart.offset.toInt())

      when (completionPart.type) {
        LanguageServer.CompletionPartType.COMPLETION_PART_TYPE_INLINE -> {
          val renderer =
              CompletionInlayRenderer(listOf(completionPart.text), InlayType.INLINE, offset)
          renderers.add(renderer)
        }
        LanguageServer.CompletionPartType.COMPLETION_PART_TYPE_BLOCK -> {
          val renderer =
              CompletionInlayRenderer(completionPart.text.split("\n"), InlayType.BLOCK, offset)
          renderers.add(renderer)
        }
        else -> {
          // Do nothing
        }
      }
    }

    return renderers
  }

  private fun getEditorOptions(editor: Editor): CodeiumCommon.EditorOptions {
    val settings = editor.settings
    return CodeiumCommon.EditorOptions.newBuilder()
        .setInsertSpaces(!settings.isUseTabCharacter(editor.project))
        .setTabSize(settings.getTabSize(editor.project).toLong())
        .build()
  }

  fun dismissSuggestions(editor: Editor) {
    shownCompletionItemsIndex.remove(editor)
    completionItemsList.remove(editor)
    disposeInlays(editor)
  }

  private fun disposeInlays(editor: Editor) {
    inlays[editor]?.forEach { Disposer.dispose(it) }
    inlays.remove(editor)
  }

  private fun cancelCompletionRequests(editor: Editor) {
    completionJobs[editor]?.cancel()
  }
}

private fun numUtf8BytesToNumCodeUnits(text: String, numUtf8Bytes: Int): Int {
  if (numUtf8Bytes == 0) {
    return 0
  }
  var curNumCodeUnits = 0
  var curNumUtf8Bytes = 0
  // https://stackoverflow.com/a/1527891/832056
  for (codePoint in text.codePoints()) {
    curNumUtf8Bytes += numUtf8BytesForCodePoint(codePoint)
    curNumCodeUnits += Character.charCount(codePoint)
    if (curNumUtf8Bytes >= numUtf8Bytes) {
      break
    }
  }
  return curNumCodeUnits
}

private fun numUtf8BytesForCodePoint(codePointValue: Int): Int {
  if (codePointValue < 0x80) {
    return 1
  }
  if (codePointValue < 0x800) {
    return 2
  }
  if (codePointValue < 0x10000) {
    return 3
  }
  return 4
}
