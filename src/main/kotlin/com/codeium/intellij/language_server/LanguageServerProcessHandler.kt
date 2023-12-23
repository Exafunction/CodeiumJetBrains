/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.language_server

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.util.io.delete
import java.nio.file.Path

class LanguageServerProcessHandler(commandLine: GeneralCommandLine, managerDir: Path) :
    KillableProcessHandler(commandLine) {
  private val logger = logger<LanguageServerProcessHandler>()

  init {
    addProcessListener(
        object : ProcessListener {
          override fun startNotified(event: ProcessEvent) {
            logger.info("Language server started")
          }

          override fun processTerminated(event: ProcessEvent) {
            // Clean up the manager and flock directories
            managerDir.delete(true)
            logger.info("Language server terminated")
          }

          override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            logger.info("[Language Server]: ${event.text.trim()}")
          }
        })
  }
}
