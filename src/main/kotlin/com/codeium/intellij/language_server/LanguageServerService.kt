/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.language_server

import com.codeium.intellij.getLanguage
import com.codeium.intellij.statusbar.CodeiumStatusService
import com.google.common.base.Utf8
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.currentOrDefaultProject
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import exa.codeium_common_pb.CodeiumCommon
import exa.language_server_pb.LanguageServer
import exa.language_server_pb.LanguageServerServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import java.awt.Point
import java.nio.CharBuffer
import java.nio.file.Paths
import kotlin.io.path.relativeToOrNull
import kotlinx.coroutines.*
import org.jetbrains.annotations.NotNull
import org.json.simple.JSONObject

const val HEARTBEAT_INTERVAL_MS = 5000.toLong()
const val MAX_OTHER_DOCS_UTF8_BYTES = 1 * 1024 * 1024 // 1MB
const val MAX_GET_FUNCTION_SIZE = 1 * 256 * 1024 // 256KB

class LanguageServerService : Disposable {
  private val logger = logger<LanguageServerService>()
  private var languageServerStarted = false
  private var channel: ManagedChannel? = null
  private var stub: LanguageServerServiceGrpcKt.LanguageServerServiceCoroutineStub? = null
  var apiKey = ""
  private val sessionId = java.util.UUID.randomUUID().toString()
  private var requestId = 0L
  var port = 0

  var chatClientPort = 0
  var chatWebServerPort = 0

  fun languageServerStarted(grpcPort: Int) {
    port = grpcPort
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    stub = LanguageServerServiceGrpcKt.LanguageServerServiceCoroutineStub(channel!!)
    languageServerStarted = true

    // Get the ports for the chat client and web server.
    runBlocking { refreshProcessPorts() }

    // Periodically send heartbeats to language server.
    CoroutineScope(Dispatchers.IO).launch {
      while (true) {
        if (languageServerReady()) {
          val metadata = getMetadata()
          try {
            val request = LanguageServer.HeartbeatRequest.newBuilder().setMetadata(metadata).build()
            stub!!.heartbeat(request)
          } catch (e: Exception) {
            logger.warn("Error sending heartbeat", e)
          }
        }
        delay(HEARTBEAT_INTERVAL_MS)
      }
    }
  }

  fun languageServerStarted(): Boolean {
    return languageServerStarted
  }

  private fun languageServerReady(): Boolean {
    return languageServerStarted && apiKey.isNotEmpty()
  }

  // Refresh the ports for the chat client and web server.
  suspend fun refreshProcessPorts(): LanguageServer.GetProcessesResponse {
    val processPorts = getProcessPorts()
    chatWebServerPort = processPorts.chatWebServerPort
    chatClientPort = processPorts.chatClientPort
    return processPorts
  }

  fun getMetadata(): CodeiumCommon.Metadata {
    val codeiumStatusService = service<CodeiumStatusService>()
    if (apiKey.isEmpty()) {
      codeiumStatusService.updateState(
          LanguageServer.State.newBuilder()
              .setState(LanguageServer.CodeiumState.CODEIUM_STATE_ERROR)
              .setMessage("Not logged into Codeium")
              .build())
      throw Exception("API key is empty")
    }

    val version = codeiumStatusService.getVersion()
    return CodeiumCommon.Metadata.newBuilder()
        .setExtensionName("jetbrains")
        .setExtensionVersion(version)
        .setIdeName("jetbrains")
        .setIdeVersion(
            ApplicationNamesInfo.getInstance().fullProductNameWithEdition +
                " " +
                ApplicationInfo.getInstance().fullVersion)
        .setApiKey(apiKey)
        .setLocale(com.intellij.DynamicBundle.getLocale().language)
        .setRequestId(++requestId)
        .setSessionId(sessionId)
        .build()
  }

  suspend fun getCompletions(
      editor: Editor,
      file: PsiFile,
      editorOptions: CodeiumCommon.EditorOptions,
      offset: Int
  ): List<LanguageServer.CompletionItem> {
    if (!languageServerReady()) return emptyList()
    val editorDocument = editor.document
    val text = editorDocument.text
    if (offset > text.length) {
      logger.warn("offset $offset is greater than text length $text.length")
      return emptyList()
    }
    val utf8ByteOffset = Utf8.encodedLength(CharBuffer.wrap(text, 0, offset))
    val document =
        LanguageServer.Document.newBuilder()
            .setAbsolutePath(file.virtualFile.path)
            .setRelativePath(getRelativePath(editor, file.virtualFile))
            .setText(text)
            .setEditorLanguage(file.language.id)
            .setLanguage(getLanguage(file.language.id))
            .setCursorOffset(utf8ByteOffset.toLong())
            .setLineEnding("\n") // Document.replaceString crashes with \r
            .build()
    val otherDocuments =
        try {
          getOtherDocuments(editor, file)
        } catch (e: Exception) {
          logger.warn("Error getting other documents", e)
          emptyList()
        }
    val metadata = getMetadata()
    val request =
        LanguageServer.GetCompletionsRequest.newBuilder()
            .setDocument(document)
            .setMetadata(metadata)
            .setEditorOptions(editorOptions)
            .addAllOtherDocuments(otherDocuments)
            .build()

    val codeiumStatusService = service<CodeiumStatusService>()
    return try {
      val response = stub!!.getCompletions(request)
      codeiumStatusService.updateState(response.state)
      response.completionItemsList
    } catch (ce: CancellationException) {
      logger.info("Completions request cancelled")
      emptyList()
    } catch (e: StatusException) {
      // Check if it was an AbortError
      if (e.status.code == Status.Code.ABORTED) {
        logger.warn("Received AbortError from language server")
        service<CodeiumStatusService>().showAbortErrorNotification(e.status, editor)
        return emptyList()
      }
      logger.warn("Error getting completions", e)
      service<CodeiumStatusService>()
          .updateState(
              LanguageServer.State.newBuilder()
                  .setState(LanguageServer.CodeiumState.CODEIUM_STATE_ERROR)
                  .setMessage("Disconnected from Codeium Server")
                  .build())
      emptyList()
    }
  }

  suspend fun addTrackedWorkspace(workspace: String) {
    if (!languageServerReady()) return
    try {
      val requestBuilder = LanguageServer.AddTrackedWorkspaceRequest.newBuilder()
      requestBuilder.setWorkspace(workspace)
      stub!!.addTrackedWorkspace(requestBuilder.build())
    } catch (e: Exception) {
      logger.warn("Error adding tracked workspace", e)
    }
  }

  suspend fun removeTrackedWorkspace(workspace: String) {
    if (!languageServerReady()) return
    try {
      val requestBuilder = LanguageServer.RemoveTrackedWorkspaceRequest.newBuilder()
      requestBuilder.setWorkspace(workspace)
      stub!!.removeTrackedWorkspace(requestBuilder.build())
    } catch (e: Exception) {
      logger.warn("Error removing tracked workspace", e)
    }
  }

  suspend fun refreshContextForFileEditorEvent(
      source: FileEditorManager,
  ) {
    if (!languageServerReady()) return
    try {
      val request = prepareContextRefreshRequest(source)
      stub!!.refreshContextForIdeAction(request)
    } catch (e: Exception) {
      logger.warn("Error refreshing context", e)
    }
  }

  private fun prepareContextRefreshRequest(
      source: @NotNull FileEditorManager
  ): LanguageServer.RefreshContextForIdeActionRequest {
    val requestBuilder = LanguageServer.RefreshContextForIdeActionRequest.newBuilder()
    val activeEditor = source.selectedTextEditor
    if (activeEditor != null) {
      val file =
          ApplicationManager.getApplication()
              .runReadAction(
                  Computable {
                    PsiDocumentManager.getInstance(currentOrDefaultProject(activeEditor.project))
                        .getPsiFile(activeEditor.document)
                  })
      if (file != null) {
        // Get the current visible range of the Active Document
        var visibleRangeBuilder = LanguageServer.Range.newBuilder()
        ApplicationManager.getApplication()
            .invokeAndWait(
                Runnable {
                  val area = activeEditor.scrollingModel.visibleArea
                  val logicalStart = activeEditor.xyToLogicalPosition(Point(area.x, area.y))
                  val logicalEnd =
                      activeEditor.xyToLogicalPosition(
                          Point(area.x + area.width, area.y + area.height))
                  visibleRangeBuilder
                      .setStartPosition(
                          LanguageServer.DocumentPosition.newBuilder()
                              .setRow(logicalStart.line.toLong())
                              .setCol(logicalStart.column.toLong())
                              .build())
                      .setEndPosition(
                          LanguageServer.DocumentPosition.newBuilder()
                              .setRow(logicalEnd.line.toLong())
                              .setCol(logicalEnd.column.toLong())
                              .build())
                })
        val visibleRange = visibleRangeBuilder.build()
        val document =
            LanguageServer.Document.newBuilder()
                .setAbsolutePath(file.virtualFile.path)
                .setRelativePath(getRelativePath(activeEditor, file.virtualFile))
                .setText(activeEditor.document.text)
                .setEditorLanguage(file.language.id)
                .setLanguage(getLanguage(file.language.id))
                .setVisibleRange(visibleRange)
                .setLineEnding("\n") // Document.replaceString crashes with \r
                .build()
        requestBuilder.setActiveDocument(document)
      }
    }
    requestBuilder.addAllOpenDocumentFilepaths(
        source.openFiles.mapNotNull {
          if ((activeEditor == null) ||
              (it != FileDocumentManager.getInstance().getFile(activeEditor.document))) {
            it.path
          } else {
            null
          }
        })
    val project = source.project
    // TODO(nmoy) use repo root instead of project.
    requestBuilder.addAllWorkspacePaths(listOf(project.basePath))
    return requestBuilder.build()
  }

  private fun getOtherDocuments(editor: Editor, file: PsiFile): List<LanguageServer.Document> {
    return ReadAction.compute<List<LanguageServer.Document>, Exception> {
      var size = 0
      val openDocuments =
          FileEditorManager.getInstance(file.project).openFiles.mapNotNull { openFile ->
            if (size < MAX_OTHER_DOCS_UTF8_BYTES && openFile != file.virtualFile) {
              val openFileDocument = FileDocumentManager.getInstance().getDocument(openFile)
              if (openFileDocument != null) {
                val openFileText = openFileDocument.text
                size += openFileText.length
                LanguageServer.Document.newBuilder()
                    .setAbsolutePath(openFile.path)
                    .setRelativePath(getRelativePath(editor, openFile))
                    .setText(openFileText)
                    .setEditorLanguage(file.language.id)
                    .setLanguage(getLanguage(file.language.id))
                    .setLineEnding("\n")
                    .build()
              } else {
                null
              }
            } else {
              null
            }
          }
      openDocuments
    }
  }

  private fun getRelativePath(editor: Editor, file: VirtualFile): String {
    val basePathStr = editor.project?.basePath ?: return file.path
    val basePath = Paths.get(basePathStr)
    return try {
      file.toNioPath().relativeToOrNull(basePath)?.toString() ?: file.path
    } catch (e: UnsupportedOperationException) {
      file.path
    }
  }

  suspend fun acceptCompletion(completionId: String) {
    if (!languageServerReady()) return
    val metadata = getMetadata()
    val request =
        LanguageServer.AcceptCompletionRequest.newBuilder()
            .setMetadata(metadata)
            .setCompletionId(completionId)
            .build()
    try {
      stub!!.acceptCompletion(request)
    } catch (e: Exception) {
      logger.warn("Error accepting completion", e)
      service<CodeiumStatusService>()
          .updateState(
              LanguageServer.State.newBuilder()
                  .setState(LanguageServer.CodeiumState.CODEIUM_STATE_ERROR)
                  .setMessage("Disconnected from Codeium Server")
                  .build())
    }
  }

  suspend fun getAuthToken(uuid: String): String {
    if (!languageServerStarted) return ""
    val request = LanguageServer.GetAuthTokenRequest.newBuilder().build()
    val response = stub!!.getAuthToken(request)
    return response.authToken
  }

  suspend fun recordEvent(event: CodeiumCommon.Event) {
    if (!languageServerStarted) return
    val request =
        LanguageServer.RecordEventRequest.newBuilder()
            .setEvent(event)
            .setMetadata(getMetadata())
            .build()
    try {
      stub!!.recordEvent(request)
    } catch (e: Exception) {
      logger.warn("Error recording event", e)
    }
  }

  override fun dispose() {
    channel?.shutdownNow()
  }

  suspend fun getFunctions(editor: Editor, file: PsiFile): List<CodeiumCommon.FunctionInfo> {
    if (!languageServerReady()) {
      logger.warn("Language server not ready")
      return emptyList()
    }
    val editorDocument = editor.document
    val text = editorDocument.text
    if (text.length > MAX_GET_FUNCTION_SIZE) {
      return emptyList()
    }
    val document =
        LanguageServer.Document.newBuilder()
            .setAbsolutePath(file.virtualFile.path)
            .setRelativePath(getRelativePath(editor, file.virtualFile))
            .setText(text)
            .setEditorLanguage(file.language.id)
            .setLanguage(getLanguage(file.language.id))
            .setLineEnding("\n") // Document.replaceString crashes with \r
            .build()
    val request = LanguageServer.GetFunctionsRequest.newBuilder().setDocument(document).build()

    return try {
      val response = stub!!.getFunctions(request)
      response.functionCapturesList
    } catch (ce: CancellationException) {
      logger.info("Get functions request cancelled")
      emptyList()
    } catch (e: StatusException) {
      // Check if it was an AbortError
      if (e.status.code == Status.Code.ABORTED) {
        logger.warn("Received AbortError from language server")
        return emptyList()
      }
      logger.warn("Error getting function infos", e)
      emptyList()
    }
  }

  suspend fun getUserStatus(): CodeiumCommon.UserStatus {
    if (!languageServerReady()) {
      return CodeiumCommon.UserStatus.newBuilder().build()
    }
    val request =
        LanguageServer.GetUserStatusRequest.newBuilder().setMetadata(getMetadata()).build()
    return try {
      val response = stub!!.getUserStatus(request)
      response.userStatus
    } catch (e: StatusException) {
      logger.warn("Error getting user status", e)
      CodeiumCommon.UserStatus.newBuilder().build()
    }
  }

  private suspend fun getProcessPorts(): LanguageServer.GetProcessesResponse {
    if (!languageServerStarted) {
      logger.warn("Unable to get process ports...language server not started")
      return LanguageServer.GetProcessesResponse.newBuilder().build()
    }
    val request = LanguageServer.GetProcessesRequest.newBuilder().build()
    return try {
      val response = stub!!.getProcesses(request)
      response
    } catch (e: StatusException) {
      logger.warn("Error getting process ports", e)
      LanguageServer.GetProcessesResponse.newBuilder().build()
    }
  }
}
