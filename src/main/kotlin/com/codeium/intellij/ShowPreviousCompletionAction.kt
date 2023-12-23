/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class ShowPreviousCompletionAction : AnAction(), DumbAware {
  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(EDITOR) ?: return
    service<EditorManager>().showPreviousCompletionItem(editor)
  }
}
