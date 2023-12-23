package com.codeium.intellij

import com.codeium.intellij.language_server.LanguageServerService
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.annotations.NotNull

class CodeiumVisibleAreaListener : VisibleAreaListener {
  private var languageServer = service<LanguageServerService>()
  override fun visibleAreaChanged(@NotNull e: VisibleAreaEvent) {
    val project = e.editor.project
    if (project != null) {
      CoroutineScope(Dispatchers.IO).launch {
        languageServer.refreshContextForFileEditorEvent(FileEditorManager.getInstance(project))
      }
    }
  }
}

// Tracks files open across all editors (tabs) and the currently selected tab.
// To the currently selected tab, also attaches a "visibleAreaListener" that tracks
// what range in that document is currently visible to the user.
// If any of the above things changes, it will trigger a RefreshContextForIdeAction
// request to the Language Server.
class CodeiumFileEditorManagerListener : FileEditorManagerListener {
  private var languageServer = service<LanguageServerService>()
  private var trackedEditor_: AtomicReference<Editor?> = AtomicReference(null)
  private var visibleAreaListener_ = CodeiumVisibleAreaListener()
  private fun maybeUpdateTrackedEditor(activeEditor: Editor) {
    var currentEditor = this.trackedEditor_.get()
    if (activeEditor == currentEditor) {
      return
    }
    currentEditor?.scrollingModel?.removeVisibleAreaListener(this.visibleAreaListener_)
    activeEditor.scrollingModel.addVisibleAreaListener(this.visibleAreaListener_)
    trackedEditor_.set(activeEditor)
  }

  override fun fileOpened(source: @NotNull FileEditorManager, file: @NotNull VirtualFile) {
    val selectedEditor = source.selectedTextEditor
    if (selectedEditor != null) {
      this.maybeUpdateTrackedEditor(selectedEditor)
    }
    CoroutineScope(Dispatchers.IO).launch {
      languageServer.refreshContextForFileEditorEvent(source)
    }
  }

  override fun fileClosed(source: @NotNull FileEditorManager, file: @NotNull VirtualFile) {
    val selectedEditor = source.selectedTextEditor
    if (selectedEditor != null) {
      this.maybeUpdateTrackedEditor(selectedEditor)
    }
    CoroutineScope(Dispatchers.IO).launch {
      languageServer.refreshContextForFileEditorEvent(source)
    }
  }

  override fun selectionChanged(source: @NotNull FileEditorManagerEvent) {
    val textEditor = source.manager.selectedTextEditor
    if (textEditor != null) {
      this.maybeUpdateTrackedEditor(textEditor)
    }
    CoroutineScope(Dispatchers.IO).launch {
      languageServer.refreshContextForFileEditorEvent(source.manager)
    }
  }
}
