/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij

import com.codeium.intellij.statusbar.CodeiumStatusService
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.currentOrDefaultProject
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import exa.language_server_pb.LanguageServer
import java.lang.ref.WeakReference

class EditorListener : EditorFactoryListener {
  private var documentChanged = false
  private var lastChangeTimestamp = 0L

  override fun editorCreated(event: EditorFactoryEvent) {
    val editor = event.editor

    val editorDisposable = Disposer.newDisposable("editorListener")
    EditorUtil.disposeWithEditor(editor, editorDisposable)
    editor.document.addDocumentListener(DocumentListener(WeakReference(editor)), editorDisposable)
    editor.caretModel.addCaretListener(EditorCaretListener(), editorDisposable)
    editor.selectionModel.addSelectionListener(EditorSelectionListener(), editorDisposable)

    val codeiumStatusService = service<CodeiumStatusService>()
    codeiumStatusService.updateState(LanguageServer.CodeiumState.CODEIUM_STATE_INACTIVE)
  }

  private inner class DocumentListener(val editorWeakRef: WeakReference<Editor>) :
      BulkAwareDocumentListener {

    override fun documentChangedNonBulk(event: DocumentEvent) {
      val editor = editorWeakRef.get() ?: return
      val project = editor.project ?: return
      if (project.isDisposed) return
      val editorManager = service<EditorManager>()
      if (!editorManager.isAvailable(editor)) {
        return
      }
      if (CommandProcessor.getInstance().currentCommandName == APPLY_COMPLETION_COMMAND_NAME) {
        return
      }
      val caretOffset = editor.caretModel.offset
      var changeOffset = event.offset + event.newLength
      val changeTimestamp = System.currentTimeMillis()
      val diff = changeTimestamp - lastChangeTimestamp
      if (diff < 10 && event.newLength == 1) {
        // Autocompleted brace, paren, or quote.
        changeOffset = caretOffset
      }
      this@EditorListener.documentChanged = true
      editorManager.editorModified(editor, changeOffset)
      lastChangeTimestamp = changeTimestamp
    }
  }

  private inner class EditorCaretListener : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) {
      val editor = event.editor
      val project = editor.project ?: return
      if (project.isDisposed) return
      val editorManager = service<EditorManager>()
      if (!editorManager.isAvailable(editor)) return
      if (this@EditorListener.documentChanged) {
        this@EditorListener.documentChanged = false
        return
      }
      editorManager.dismissSuggestions(editor)

      // Update function info cache.
      val file =
          PsiDocumentManager.getInstance(currentOrDefaultProject(editor.project))
              .getPsiFile(editor.document)
              ?: // TODO(rahul): Log some error.
          return
    }
  }

  private class EditorSelectionListener : SelectionListener {
    override fun selectionChanged(event: SelectionEvent) {
      val editor = event.editor
      val project = editor.project ?: return
      if (project.isDisposed) return
      val editorManager = service<EditorManager>()
      if (!editorManager.isAvailable(editor)) {
        return
      }
      editorManager.dismissSuggestions(editor)
    }
  }
}
