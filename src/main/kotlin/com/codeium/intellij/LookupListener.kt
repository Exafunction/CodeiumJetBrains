/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.openapi.components.service

class LookupListener : LookupManagerListener {
  override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
    if (newLookup != null && oldLookup == null) {
      val editor = newLookup.editor
      val editorManager = service<EditorManager>()
      editorManager.dismissSuggestions(editor)
    } else if (newLookup == null && oldLookup != null) {
      val editor = oldLookup.editor
      val editorManager = service<EditorManager>()
      editorManager.editorModified(editor, editor.caretModel.offset)
    }
  }
}
