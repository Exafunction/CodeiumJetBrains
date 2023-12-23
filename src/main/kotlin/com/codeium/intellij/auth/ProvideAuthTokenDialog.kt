package com.codeium.intellij.auth

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class ProvideAuthTokenDialog(url: String) : DialogWrapper(true) {

  private val urlLabel = getUrlLabel(url)
  private val authTokenTextField = JBTextField()

  init {
    title = "Codeium: Provide Auth Token"
    init()
  }

  private fun getUrlLabel(url: String): JBLabel {
    // JetBrains refuses to insert a line break in the middle of a URL, so we need to add
    // a break ourselves. We insert the break right after the unused `state` query parameter
    // so that when the user copies the URL the extra space inserted as a result of the line
    // break won't affect the authentication flow.
    val breakIndex = url.indexOf("state=a")
    val lineOne = url.substring(0, breakIndex + "state=a".length - 1)
    val lineTwo = url.substring(breakIndex + "state=a".length - 1)
    return JBLabel("<br>$lineOne<br>$lineTwo<br><br>").setCopyable(true)
  }

  override fun createCenterPanel(): JComponent {
    val dialogPanel = JPanel(BorderLayout())

    val label = JBLabel("To authenticate to Codeium, visit this URL to get your token:")
    dialogPanel.add(label, BorderLayout.NORTH)
    urlLabel.maximumSize = Dimension(10, 10)
    dialogPanel.add(urlLabel, BorderLayout.CENTER)

    val textField = JPanel(BorderLayout())
    textField.add(JBLabel("Token:"), BorderLayout.WEST)
    textField.add(authTokenTextField, BorderLayout.CENTER)
    dialogPanel.add(textField, BorderLayout.SOUTH)

    return dialogPanel
  }

  fun getAuthToken(): String {
    return authTokenTextField.text
  }
}
