/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.auth

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PopupAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class LogoutAction : AnAction(), PopupAction, DumbAware {
  override fun actionPerformed(e: AnActionEvent) {
    service<CodeiumAuthService>().logout()
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = service<CodeiumAuthService>().loggedIn.get()
    e.presentation.text = "Log out from Codeium"
  }
}
