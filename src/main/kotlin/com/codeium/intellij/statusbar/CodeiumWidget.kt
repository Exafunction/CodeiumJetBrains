/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.statusbar

import com.codeium.intellij.auth.*
import com.codeium.intellij.icons.CodeiumIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedStatusBarPopup
import com.intellij.psi.PsiManager
import com.intellij.ui.AnimatedIcon
import exa.language_server_pb.LanguageServer
import java.util.concurrent.TimeUnit

class CodeiumWidget(project: Project) : EditorBasedStatusBarPopup(project, false) {
  override fun ID(): String {
    return "CodeiumWidget"
  }

  override fun getWidgetState(file: VirtualFile?): WidgetState {
    val psiFile = file?.let { PsiManager.getInstance(project).findFile(it) }
    val languageId = psiFile?.language?.id ?: "*"
    if (!service<CodeiumStatusService>().isCodeiumEnabled(languageId)) {
      val tooltipText = "Click to enable Codeium"
      val icon = CodeiumIcons.CodeiumIconWarning
      val state = WidgetState(tooltipText, "", true)
      state.icon = icon
      return state
    }

    val state = service<CodeiumStatusService>().getState()
    val tooltipText =
        if (state?.message.isNullOrEmpty()) {
          "Codeium " +  service<CodeiumStatusService>().getVersion()
        } else {
          state?.message!!
        }
    val icon =
        when (state?.state ?: LanguageServer.CodeiumState.CODEIUM_STATE_UNSPECIFIED) {
          LanguageServer.CodeiumState.CODEIUM_STATE_INACTIVE -> CodeiumIcons.CodeiumIcon
          LanguageServer.CodeiumState.CODEIUM_STATE_SUCCESS -> CodeiumIcons.CodeiumIcon
          LanguageServer.CodeiumState.CODEIUM_STATE_PROCESSING -> AnimatedIcon.Default.INSTANCE
          LanguageServer.CodeiumState.CODEIUM_STATE_WARNING -> CodeiumIcons.CodeiumIconWarning
          LanguageServer.CodeiumState.CODEIUM_STATE_ERROR -> CodeiumIcons.CodeiumIconError
          else -> CodeiumIcons.CodeiumIcon
        }
    val widgetState = WidgetState(tooltipText, "", true)
    widgetState.icon = icon
    return widgetState
  }

  private fun getActionGroup(dataContext: DataContext): DefaultActionGroup {
    val actionGroup = DefaultActionGroup()
    if (service<CodeiumAuthService>().loggedIn.get()) {
      actionGroup.add(LogoutAction())
    } else {
      actionGroup.add(LoginAction())
      // Don't show the "Enable/Disable Codeium" actions if the user is not logged in
      return actionGroup
    }
    val codeiumStatusService = service<CodeiumStatusService>()
    actionGroup.addSeparator()
    val file = dataContext.getData(PSI_FILE)
    val languageId = file?.language?.id
    val language = file?.language?.displayName

    if (codeiumStatusService.isCodeiumEnabled("*") &&
        (languageId == null || codeiumStatusService.isCodeiumEnabled(languageId))) {
      actionGroup.add(DisableCodeiumAction())
    } else if (!codeiumStatusService.isCodeiumEnabled("*")) {
      actionGroup.add(EnableCodeiumAction())
    }
    if (languageId != null && language != null) {
      if (codeiumStatusService.isCodeiumEnabled(languageId)) {
        actionGroup.add(DisableCodeiumAction(languageId, language))
      } else if (codeiumStatusService.isCodeiumEnabled("*") &&
          !codeiumStatusService.isCodeiumEnabled(languageId)) {
        actionGroup.add(EnableCodeiumAction(languageId, language))
      }
    }
    return actionGroup
  }

  override fun createPopup(context: DataContext): ListPopup? {
    val dataContext =
        DataManager.getInstance().dataContextFromFocusAsync.blockingGet(100, TimeUnit.MILLISECONDS)
            ?: return null
    val actionGroup = getActionGroup(dataContext)
    return JBPopupFactory.getInstance()
        .createActionGroupPopup(
            "Codeium",
            actionGroup,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false)
  }

  override fun createInstance(project: Project): StatusBarWidget {
    return CodeiumWidget(project)
  }

  override fun dispose() {}
}
