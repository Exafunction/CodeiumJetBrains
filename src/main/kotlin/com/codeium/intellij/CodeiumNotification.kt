/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.impl.NotificationFullContent
import com.intellij.openapi.util.NlsContexts.NotificationContent
import com.intellij.openapi.util.NlsContexts.NotificationTitle

class CodeiumNotification(
    groupId: String,
    title: @NotificationTitle String,
    content: @NotificationContent String,
    type: NotificationType
) : Notification(groupId, title, content, type), NotificationFullContent
