/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.Nullable

class AppSettingsConfigurable : Configurable {
  private var codeiumSettingsComponent: AppSettingsComponent? = null

  // A default constructor with no arguments is required because this implementation
  // is registered as an applicationConfigurable EP

  // A default constructor with no arguments is required because this implementation
  // is registered as an applicationConfigurable EP
  override fun getDisplayName(): @Nls(capitalization = Nls.Capitalization.Title) String {
    return "Codeium Settings"
  }

  override fun getPreferredFocusedComponent(): JComponent? {
    return codeiumSettingsComponent?.preferredFocusedComponent
  }

  @Nullable
  override fun createComponent(): JComponent? {
    codeiumSettingsComponent = AppSettingsComponent()
    return codeiumSettingsComponent?.panel
  }

  override fun isModified(): Boolean {
    val settings: AppSettingsState = AppSettingsState.instance
    return (codeiumSettingsComponent?.chatInlayEnabled != settings.chatInlayEnabled ||
            codeiumSettingsComponent?.indexingEnabled != settings.indexingEnabled ||
            codeiumSettingsComponent?.indexingMaxFileCount != settings.indexingMaxFileCount ||
            codeiumSettingsComponent?.enableInComments != settings.enableInComments
            )
  }

  override fun apply() {
    val settings: AppSettingsState = AppSettingsState.instance
    settings.chatInlayEnabled = codeiumSettingsComponent!!.chatInlayEnabled
    settings.indexingEnabled = codeiumSettingsComponent!!.indexingEnabled
    settings.indexingMaxFileCount = codeiumSettingsComponent!!.indexingMaxFileCount
    settings.enableInComments = codeiumSettingsComponent!!.enableInComments
  }

  override fun reset() {
    val settings: AppSettingsState = AppSettingsState.instance
    codeiumSettingsComponent!!.chatInlayEnabled = settings.chatInlayEnabled
    codeiumSettingsComponent!!.indexingEnabled = settings.indexingEnabled
    codeiumSettingsComponent!!.indexingMaxFileCount = settings.indexingMaxFileCount
    codeiumSettingsComponent!!.enableInComments = settings.enableInComments
  }

  override fun disposeUIResources() {
    codeiumSettingsComponent = null
  }

  /** Supports creating and managing a [JPanel] for the Settings Dialog. */
  class AppSettingsComponent {
    val panel: JPanel
    private val chatInlayEnabledCheckBox = JBCheckBox("Enable Chat Inlay Hints ")
    private val indexingEnabledCheckBox = JBCheckBox("Enable Codeium Indexing (Requires Reload)")
    private val indexingMaxFileCountTextField = JBTextField("5000")
    private val enableInCommentsCheckBox = JBCheckBox("If true, Codeium will provide autocomplete suggestions in comments.")

    init {
      indexingMaxFileCountTextField.setToolTipText(
        "If indexing is enabled, we will only attempt to index workspaces that have up to this many files. This file count ignores .gitignore and binary files. Raising this limit from default may lead to performance issues. Values 0 or below will be treated as unlimited."
      )
      panel =
        FormBuilder.createFormBuilder()
          .addComponent(chatInlayEnabledCheckBox, 1)
          .addComponent(indexingEnabledCheckBox, 1)
          .addLabeledComponent(
            "Indexing Max Workspace Size (File Count)", indexingMaxFileCountTextField, 1
          )
          .addComponent(enableInCommentsCheckBox, 1)
          .addComponentFillVertically(JPanel(), 0)
          .getPanel()
    }

    val preferredFocusedComponent: JComponent
      get() = chatInlayEnabledCheckBox

    var chatInlayEnabled: Boolean
      get() = chatInlayEnabledCheckBox.isSelected
      set(newStatus) {
        chatInlayEnabledCheckBox.isSelected = newStatus
      }
    var indexingEnabled: Boolean
      get() = indexingEnabledCheckBox.isSelected
      set(newStatus) {
        indexingEnabledCheckBox.isSelected = newStatus
      }
    var indexingMaxFileCount: Int
      get() = indexingMaxFileCountTextField.getText().toInt()
      set(newValue) {
        indexingMaxFileCountTextField.setText(newValue.toString())
      }
    var enableInComments: Boolean
      get() = enableInCommentsCheckBox.isSelected
      set(newStatus) {
        enableInCommentsCheckBox.isSelected = newStatus
      }
  }
}
