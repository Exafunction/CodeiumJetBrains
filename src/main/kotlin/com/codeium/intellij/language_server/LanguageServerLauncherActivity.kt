/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.language_server

import com.codeium.intellij.CodeiumWorkspaceTracker
import com.codeium.intellij.EditorManager
import com.codeium.intellij.auth.CodeiumAuthService
import com.codeium.intellij.chat_window.ChatViewerWindowService
import com.codeium.intellij.settings.AppSettingsState
import com.codeium.intellij.statusbar.CodeiumStatusService
import com.intellij.codeInsight.editorActions.TabOutScopesTracker
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions.*
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.io.createDirectories
import com.intellij.util.io.isFile
import com.intellij.util.system.CpuArch
import exa.codeium_common_pb.CodeiumCommon
import exa.codeium_common_pb.CodeiumCommon.UserStatus
import exa.language_server_pb.LanguageServer
import exa.seat_management_pb.SeatManagement
import exa.seat_management_pb.SeatManagementServiceGrpcKt
import io.grpc.ManagedChannelBuilder
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32C
import java.util.zip.CheckedInputStream
import java.util.zip.GZIPInputStream
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlinx.coroutines.*

const val MODEL_MD5 = "9c0694567290725d9dcba14ade58e297"

const val MANAGER_DIR_PORT_TIMEOUT_SEC = 60.toLong()
const val CODEIUM_DEV_MODE_ENV_VAR = "CODEIUM_DEV_MODE"
val MANAGER_DIR = Path("codeium", "manager")

// Do not manually edit.
const val LANGUAGE_SERVER_SHA = "3056e905a51e39039573c9a3623a1746671cdf15"
// Do not manually edit.
const val LANGUAGE_SERVER_VERSION = "1.6.13"
// Do not manually edit.
val LANGUAGE_SERVER_CRC32C =
    mapOf(
        "language_server_linux_x64" to "5d41b738",
        "language_server_linux_arm" to "41c1c5e8",
        "language_server_macos_x64" to "2ca088a3",
        "language_server_macos_arm" to "5b07b749",
        "language_server_windows_x64.exe" to "2a14aa6c",
    )

const val AcceptCompletionActionId = "com.codeium.intellij.AcceptCompletionAction"
const val DismissCompletionActionId = "com.codeium.intellij.DismissCompletionAction"

fun isAcceptCompletionActionMapped(): Boolean {
  return KeymapUtil.getFirstKeyboardShortcutText(AcceptCompletionActionId).isNotEmpty()
}

fun isDismissCompletionActionMapped(): Boolean {
  return KeymapUtil.getFirstKeyboardShortcutText(DismissCompletionActionId).isNotEmpty()
}

class EditorActionTabHandler(old: EditorActionHandler) : EditorActionHandler() {
  private val oldHandler = old
  override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
    if (!isAcceptCompletionActionMapped() &&
        editor.caretModel.caretCount == 1 &&
        !editor.selectionModel.hasSelection() &&
        service<EditorManager>().applyCompletionFeedback(editor))
        return
    oldHandler.execute(editor, caret, dataContext)
  }

  override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean {
    return (!isAcceptCompletionActionMapped() && service<EditorManager>().areInlaysShown(editor)) ||
        oldHandler.isEnabled(editor, caret, dataContext)
  }
}

class EditorActionTabOutHandler(old: EditorActionHandler) : EditorActionHandler() {
  private val oldHandler = old
  override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
    if (!isAcceptCompletionActionMapped() &&
        editor.caretModel.caretCount == 1 &&
        !editor.selectionModel.hasSelection() &&
        service<EditorManager>().applyCompletionFeedback(editor))
        return
    oldHandler.execute(editor, caret, dataContext)
  }

  override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean {
    val caretOffset: Int = caret.offset
    val isTabOut = TabOutScopesTracker.getInstance().hasScopeEndingAt(editor, caretOffset)
    val hasInlay = service<EditorManager>().areInlaysShown(editor)
    val isAcceptCompletionActionMapped = isAcceptCompletionActionMapped()
    return isTabOut || (hasInlay && !isAcceptCompletionActionMapped)
  }
}

class EditorActionEscapeHandler(old: EditorActionHandler) : EditorActionHandler() {
  private val oldHandler = old
  override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
    if (!isDismissCompletionActionMapped() &&
        service<EditorManager>().applyCompletionFeedback(editor, true)) {
      return
    }
    oldHandler.execute(editor, caret, dataContext)
  }

  override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean {
    return (!isDismissCompletionActionMapped() &&
        service<EditorManager>().areInlaysShown(editor)) ||
        oldHandler.isEnabled(editor, caret, dataContext)
  }
}

class LanguageServerLauncherActivity : StartupActivity.Background {
  private var startedLanguageServer = false
  private val logger = logger<LanguageServerLauncherActivity>()

  private fun registerEditorActionListener() {
    val editorActionManager = EditorActionManager.getInstance()
    val oldTabHandler = editorActionManager.getActionHandler(ACTION_EDITOR_TAB)
    editorActionManager.setActionHandler(ACTION_EDITOR_TAB, EditorActionTabHandler(oldTabHandler))

    val oldBraceOrQuoteOutHandler = editorActionManager.getActionHandler(ACTION_BRACE_OR_QUOTE_OUT)
    editorActionManager.setActionHandler(
        ACTION_BRACE_OR_QUOTE_OUT, EditorActionTabOutHandler(oldBraceOrQuoteOutHandler))

    val oldEscapeHandler = editorActionManager.getActionHandler(ACTION_EDITOR_ESCAPE)
    editorActionManager.setActionHandler(
        ACTION_EDITOR_ESCAPE, EditorActionEscapeHandler(oldEscapeHandler))
  }

  override fun runActivity(project: Project) {
    registerEditorActionListener()
    CoroutineScope(Dispatchers.IO).launch {
      val codeiumAuthService = service<CodeiumAuthService>()

      CoroutineScope(Dispatchers.IO).launch { codeiumAuthService.maybeShowLoginPrompt(project) }

      if (startedLanguageServer) {
        project.service<ChatViewerWindowService>().maybeShowChatWindow()
        return@launch
      }

      val codeiumStatusService = service<CodeiumStatusService>()
      val pluginPath = Path(PathManager.getPluginsPath()).resolve("codeium")

      val languageServerBinary =
          if (SystemInfoRt.isLinux && CpuArch.isIntel64()) {
            "language_server_linux_x64"
          } else if (SystemInfoRt.isLinux && CpuArch.isArm64()) {
            "language_server_linux_arm"
          } else if (SystemInfoRt.isMac && CpuArch.isIntel64()) {
            "language_server_macos_x64"
          } else if (SystemInfoRt.isMac && CpuArch.isArm64()) {
            "language_server_macos_arm"
          } else if (SystemInfoRt.isWindows && CpuArch.isIntel64()) {
            "language_server_windows_x64.exe"
          } else {
            logger.warn("Unsupported platform")
            codeiumStatusService.updateState(
                LanguageServer.State.newBuilder()
                    .setState(LanguageServer.CodeiumState.CODEIUM_STATE_ERROR)
                    .setMessage("Unsupported platform")
                    .build())
            return@launch
          }
      val languageServerPath = pluginPath.resolve(Path(LANGUAGE_SERVER_SHA, languageServerBinary))
      val downloadPath =
          pluginPath.resolve(Path(LANGUAGE_SERVER_SHA, "$languageServerBinary.download"))
      if (!Files.exists(languageServerPath, LinkOption.NOFOLLOW_LINKS)) {
        codeiumStatusService.updateState(
            LanguageServer.State.newBuilder()
                .setState(LanguageServer.CodeiumState.CODEIUM_STATE_PROCESSING)
                .setMessage("Downloading language server")
                .build())
        try {
          var extensionBaseUrl = "https://github.com/Exafunction/codeium/releases/download"
          if (codeiumStatusService.hasEnterprisePlugin()) {
            val portalUrl = codeiumStatusService.getWebsiteUrl()
            try {
              extensionBaseUrl = URL("$portalUrl/api/extension_base_url").readText().trim('/')
            } catch (e: Exception) {
              logger.warn(
                  "Failed to fetch extension base URL at $portalUrl/api/extension_base_url", e)
            }
          }

          languageServerPath.parent.createDirectories()
          downloadPath.deleteIfExists()
          val url =
              "$extensionBaseUrl/language-server-v${LANGUAGE_SERVER_VERSION}/${languageServerBinary}.gz"
          logger.warn("Downloading language server from $url")
          val urlStream = URL(url).openStream()
          val crc32cStream = CheckedInputStream(urlStream, CRC32C())
          GZIPInputStream(crc32cStream).use { input -> Files.copy(input, downloadPath) }
          val computedChecksum = crc32cStream.checksum.value.toString(16).padStart(8, '0')
          val checksum = LANGUAGE_SERVER_CRC32C[languageServerBinary]
          if (computedChecksum != checksum) {
            throw java.io.IOException("CRC32C mismatch: $computedChecksum != $checksum")
          }
          Files.move(downloadPath, languageServerPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
          logger.warn("Failed to download language server", e)
          codeiumStatusService.updateState(
              LanguageServer.State.newBuilder()
                  .setState(LanguageServer.CodeiumState.CODEIUM_STATE_ERROR)
                  .setMessage("Failed to download language server")
                  .build())
          return@launch
        }
        codeiumStatusService.updateState(LanguageServer.CodeiumState.CODEIUM_STATE_SUCCESS)
      }
      // Make the binary executable
      languageServerPath.toFile().setExecutable(true)

      // Create manager directory
      val managerDir = Files.createTempDirectory("").toAbsolutePath().resolve(MANAGER_DIR)
      managerDir.createDirectories()

      val apiServerUrl = codeiumStatusService.getApiServerUrl()

      val cmdArgs =
          mutableListOf(
              languageServerPath.toString(),
              "--api_server_url",
              apiServerUrl,
              "--manager_dir",
              managerDir.toString(),
              "--enable_chat_web_server",
              "--enable_chat_client",
          )
      // Check if we are in dev mode.
      if (System.getenv(CODEIUM_DEV_MODE_ENV_VAR) != null) {
        cmdArgs.add("--dev_mode")
      }
      if (codeiumStatusService.hasEnterprisePlugin()) {
        cmdArgs.add("--portal_url")
        cmdArgs.add(codeiumStatusService.getWebsiteUrl())
        cmdArgs.add("--enterprise_mode")
      }

      if (codeiumAuthService.isLoggedIn()) {
        val channel = ManagedChannelBuilder.forAddress("server.codeium.com", 443).build()
        val stub = SeatManagementServiceGrpcKt.SeatManagementServiceCoroutineStub(channel!!)
        var userStatus: UserStatus? = null
        try {
          userStatus =
              stub
                  .getUserStatus(
                      SeatManagement.GetUserStatusRequest.newBuilder()
                          .setMetadata(service<LanguageServerService>().getMetadata())
                          .build())
                  .userStatus
        } catch (e: Exception) {
          logger.warn("Error reading user status", e)
        }
        channel.shutdown()
        val isTeams =
            (userStatus != null) &&
                (userStatus.teamId.isNotEmpty() &&
                    userStatus.teamStatus.equals(
                        CodeiumCommon.UserTeamStatus.USER_TEAM_STATUS_APPROVED))
        if (isTeams) {
          cmdArgs.add("--teams_mode")
          service<CodeiumAuthService>().isTeams = true
        }
      }
      // Create database directory in ~/.codeium/database
      val homeDirectory = System.getProperty("user.home")
      val databaseDir = Path(homeDirectory, ".codeium", "database", MODEL_MD5)
      databaseDir.createDirectories()
      cmdArgs.add("--database_dir")
      cmdArgs.add(databaseDir.toString())

      // Search is enabled by default in Teams.
      if (AppSettingsState.instance.indexingEnabled || service<CodeiumAuthService>().isTeams) {
        cmdArgs.add("--enable_local_search")
        cmdArgs.add("--enable_index_service")
        cmdArgs.add("--search_max_workspace_file_count")
        cmdArgs.add(AppSettingsState.instance.indexingMaxFileCount.toString())
      }

      val commandLine = GeneralCommandLine(cmdArgs)
      val languageServerProcessHandler = LanguageServerProcessHandler(commandLine, managerDir)
      languageServerProcessHandler.startNotify()
      Disposer.register(service<LanguageServerService>()) {
        languageServerProcessHandler.destroyProcess()
      }

      // Periodically check if the port file exists
      try {
        withTimeout(MANAGER_DIR_PORT_TIMEOUT_SEC * 1000) {
          while (true) {
            val portFile =
                managerDir.listDirectoryEntries().firstOrNull { f -> f.isFile() }?.fileName
            if (portFile != null) {
              val port = portFile.toString().toInt()
              service<LanguageServerService>().languageServerStarted(port)
              service<CodeiumAuthService>().setAuthPort(port)
              project.service<ChatViewerWindowService>().maybeShowChatWindow()
              startedLanguageServer = true
              break
            }
            delay(100)
          }
        }
      } catch (ex: CancellationException) {
        codeiumStatusService.updateState(
            LanguageServer.State.newBuilder()
                .setState(LanguageServer.CodeiumState.CODEIUM_STATE_ERROR)
                .setMessage(
                    "Language server port file not found after $MANAGER_DIR_PORT_TIMEOUT_SEC seconds")
                .build())
      }
      if (startedLanguageServer) {
        service<CodeiumWorkspaceTracker>().updateRootsForProject(project)
      }
    }
  }
}
