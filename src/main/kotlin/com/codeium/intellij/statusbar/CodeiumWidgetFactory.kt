/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory

class CodeiumWidgetFactory : StatusBarEditorBasedWidgetFactory() {
  override fun getId(): String {
    return "CodeiumWidget"
  }

  override fun getDisplayName(): String {
    return "Codeium"
  }

  override fun createWidget(project: Project): StatusBarWidget {
    return CodeiumWidget(project)
  }

  override fun disposeWidget(widget: StatusBarWidget) {}
}
