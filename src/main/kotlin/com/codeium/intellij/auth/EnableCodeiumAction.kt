/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.auth

import com.codeium.intellij.isWellSupportedLanguage
import com.codeium.intellij.statusbar.CodeiumStatusService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PopupAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class EnableCodeiumAction(private val languageId: String = "*", private val language: String = "") :
    AnAction(), PopupAction, DumbAware {
  override fun actionPerformed(e: AnActionEvent) {
    val codeiumStatusService = service<CodeiumStatusService>()
    if (languageId != "*" && !codeiumStatusService.isCodeiumEnabled("*")) {
      // You cannot enable Codeium for a specific language if it is disabled for all languages
      return
    }
    if (!codeiumStatusService.isCodeiumEnabled(languageId)) {
      codeiumStatusService.toggleCodeiumEnabled(languageId)
    }
  }

  override fun update(e: AnActionEvent) {
    val experimental = if (isWellSupportedLanguage(languageId)) "" else " (experimental)"
    e.presentation.text =
        if (languageId == "*") {
          "Enable Codeium Globally"
        } else {
          "Enable Codeium for $language$experimental"
        }
  }
}
