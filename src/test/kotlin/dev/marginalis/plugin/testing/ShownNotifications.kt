package dev.marginalis.plugin.testing

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil

class ShownNotifications(project: Project, disposable: Disposable) : AbstractList<Notification>() {

    private val shown = mutableListOf<Notification>()

    init {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        project.messageBus.connect(disposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "Marginalis") shown += notification
                }
            },
        )
    }

    override val size: Int get() = settled().size

    override fun get(index: Int): Notification = settled()[index]

    private fun settled(): List<Notification> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return shown
    }
}
