/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.auth

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class ProvideAuthTokenAction : AnAction(), DumbAware {
  override fun actionPerformed(e: AnActionEvent) {
    e.project ?: return
    service<CodeiumAuthService>().provideAuthToken(e.project!!) {}
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = !(service<CodeiumAuthService>().loggedIn.get())
    e.presentation.text = "Provide Auth Token to Codeium"
  }
}
