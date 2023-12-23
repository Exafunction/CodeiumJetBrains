/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.chat_server_client

import com.codeium.intellij.language_server.LanguageServerService
import com.google.protobuf.Timestamp
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import exa.chat_pb.Chat
import exa.chat_pb.Chat.ChatMessageIntent
import exa.chat_web_server_pb.ChatWebServer.WebServerRequest
import exa.codeium_common_pb.CodeiumCommon
import exa.language_server_pb.LanguageServer
import java.time.Instant
import java.util.*
import okhttp3.*
import okio.ByteString

/** Generate a nonce. */
fun getNonce(): String {
  val possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  return (1..32).map { possible.random() }.joinToString("")
}

/** ChatServerClient is a client for the chat server. */
object ChatServerClient {
  private val client = OkHttpClient()
  private var languageServerService: LanguageServerService = service<LanguageServerService>()
  private var ws: WebSocket
  private var connected = false

  private val logger = logger<ChatServerClient>()

  init {
    ws = establishConnection()
  }

  /** Get the full ws:// URL for the chat server. */
  fun getServerUrl(): String {
    return "ws://127.0.0.1:${languageServerService.chatWebServerPort}"
  }

  /** Get the full http:// URL for the chat client. */
  fun getClientUrl(): String {
    return "http://127.0.0.1:${languageServerService.chatClientPort}"
  }

  /** Whether the chat server is currently connected via WebSocket. */
  fun isConnected(): Boolean {
    return connected
  }

  /** Establish a WebSocket connection to the chat server. */
  fun establishConnection(): WebSocket {
    val request = Request.Builder().url("${getServerUrl()}/connect/ide").build()
    ws = client.newWebSocket(request, ChatWebSocketListener())
    return ws
  }

  /** Send a WebServerRequest to the chat server. */
  private fun sendWebServerRequest(message: WebServerRequest): Boolean {
    val byteArray = message.toByteArray()
    val binary = ByteString.of(*byteArray)
    return ws.send(binary)
  }

  /** Send a ChatMessageIntent to the chat server and return the message. */
  fun sendChatMessage(intent: ChatMessageIntent): Chat.ChatMessage {
    val currentTimestamp =
        Timestamp.newBuilder()
            .setSeconds(Instant.now().epochSecond)
            .setNanos(Instant.now().nano)
            .build()
    val messageID = "user-${UUID.randomUUID()}"

    val message =
        Chat.ChatMessage.newBuilder()
            .setMessageId(messageID)
            .setSource(CodeiumCommon.ChatMessageSource.CHAT_MESSAGE_SOURCE_USER)
            .setTimestamp(currentTimestamp)
            .setConversationId(getNonce())
            .setIntent(intent)
    val getChatMessageRequest =
        LanguageServer.GetChatMessageRequest.newBuilder()
            .setMetadata(languageServerService.getMetadata())
            .addChatMessages(message)
            .build()
    val request =
        WebServerRequest.newBuilder().setGetChatMessageRequest(getChatMessageRequest).build()

    // If the connection is not yet established, try to establish it.
    if (!isConnected()) {
      logger.debug("[ChatServerClient] Connection not yet established, trying to establish now")
      establishConnection()
    }

    // Check that the WS message was sent.
    logger.info("[ChatServerClient] Sending WebServerRequest")
    val sent = sendWebServerRequest(request)
    if (!sent) {
      logger.warn("[ChatServerClient] Failed to send WebServerRequest")
    }

    return message.build()
  }

  /** Event listener for the WebSocket connection. */
  class ChatWebSocketListener : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      connected = true
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      connected = false
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      logger<ChatServerClient>()
          .warn("[ChatServerClient] WebSocket connection failed with error: $t")
      connected = false
    }
  }
}
