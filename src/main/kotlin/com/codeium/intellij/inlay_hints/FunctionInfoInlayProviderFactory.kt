/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.inlay_hints

import com.intellij.codeInsight.hints.*
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.currentOrDefaultProject
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class FunctionInfoInlayProviderFactory : InlayHintsProviderFactory {
  override fun getProvidersInfoForLanguage(language: Language): List<InlayHintsProvider<out Any>> {
    return listOf(FunctionInfoInlayProvider())
  }

  internal class FunctionInfoInlayProvider : InlayHintsProvider<NoSettings> {
    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector? {
      val file =
          PsiDocumentManager.getInstance(currentOrDefaultProject(editor.project))
              .getPsiFile(editor.document)
              ?: // TODO(rahul): Log some error.
          return null
      return FunctionInfoInlayHintsCollector(file, editor)
    }

    override val key = SettingsKey<NoSettings>(FunctionInfoInlayProvider::class.qualifiedName!!)
    override val name = "Codeium chat inlay hints"
    override val previewText = "Hints to generate Codeium Chat conversations."
    override fun createSettings() = NoSettings()

    override val isVisibleInSettings = false

    override fun isLanguageSupported(language: Language): Boolean {
      return true
    }

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
      return object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener) = JPanel()

        override val mainCheckboxText: String
          get() = "Show Codeium Chat Inlay Hints"
      }
    }
  }
}
