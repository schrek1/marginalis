package dev.marginalis.plugin.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.LayeredIcon
import com.intellij.ui.RowIcon
import com.intellij.util.ui.EmptyIcon
import dev.marginalis.core.AggregateState
import dev.marginalis.core.AgreementSignal
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Mark
import dev.marginalis.core.MarkSubject
import dev.marginalis.core.StripeBadge
import dev.marginalis.core.Turn
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.SwingConstants

object MarginalisIcons {
    val SubmitRound = load("handBack")
    val Agree = load("agree")

    private val marks: Map<Mark, Icon> = Mark.all().associateWith { mark ->
        load("mark_${mark.subject.name.lowercase()}_${mark.intent?.name?.lowercase() ?: "none"}")
    }
    private val orphanFrame = load("markOrphanFrame")
    private val markInState = ConcurrentHashMap<Triple<Mark, AggregateState, Boolean>, Icon>()

    val LineMark = marks.getValue(Mark(MarkSubject.LINE, null))
    val ProjectMark = marks.getValue(Mark(MarkSubject.PROJECT, null))

    private val turnSignals = mapOf(Turn.USER_OWES to load("turnYou"), Turn.AGENT_OWES to load("turnAgent"))
    private val turnBadges = mapOf(Turn.USER_OWES to load("turnYouBadge"), Turn.AGENT_OWES to load("turnAgentBadge"))
    private val agreementSignals = mapOf(AgreementSignal.AGREED to load("turnAgreed"), AgreementSignal.AGENT_ON_IT to load("turnWorking"))
    private val turnSignalGap = EmptyIcon.create(3, 16)
    private val noTurnSignal = EmptyIcon.create(16)
    private val markWithTurn = ConcurrentHashMap<Triple<Icon, Turn?, AgreementSignal?>, Icon>()

    val Brand = load("marginalisToolWindow")
    private val toolWindowBadged = mapOf(
        StripeBadge.BLOCKER to BadgeIconSupplier(Brand).getErrorIcon(true),
        StripeBadge.AWAITING_YOU to badged(Brand, turnBadges.getValue(Turn.USER_OWES)),
    )
    private val liveBolt = load("liveBadge")
    private val toolWindowLive: Map<StripeBadge?, Icon> = mapOf(
        null to withStripeLiveBadge(Brand),
        StripeBadge.BLOCKER to withStripeLiveBadge(toolWindowBadged.getValue(StripeBadge.BLOCKER)),
        StripeBadge.AWAITING_YOU to LiveBadgeIcon(
            Brand,
            liveBolt,
            stripeLive(LiveBadgeIcon.Corner.BOTTOM_LEFT),
            alongside = badged(EmptyIcon.create(Brand), turnBadges.getValue(Turn.USER_OWES)),
        ),
    )
    private val gutterLive = LiveBadgeIcon.Place(LiveBadgeIcon.Corner.BOTTOM_RIGHT, sizeOfBox = 8f / 16, gapOfBox = 1.25f / 16)

    fun mark(mark: Mark): Icon = marks.getValue(mark)

    fun turnSignal(turn: Turn): Icon = turnSignals.getValue(turn)

    fun agreementSignal(signal: AgreementSignal): Icon = agreementSignals.getValue(signal)

    fun withLeadingTurnSignal(mark: Icon, turn: Turn?, agreement: AgreementSignal? = null): Icon =
        markWithTurn.computeIfAbsent(Triple(mark, turn, agreement)) {
            val signal = turn?.let(turnSignals::getValue) ?: agreement?.let(agreementSignals::getValue) ?: noTurnSignal
            RowIcon(mark, turnSignalGap, signal)
        }

    fun withTurnBadge(fileIcon: Icon, turn: Turn): Icon = badged(fileIcon, turnBadges.getValue(turn))

    fun toolWindow(badge: StripeBadge?, projectLive: Boolean): Icon =
        if (projectLive) toolWindowLive.getValue(badge) else badge?.let(toolWindowBadged::getValue) ?: Brand

    fun markOf(threads: List<CommentThread>, live: Boolean = false): Icon =
        markInState.computeIfAbsent(Triple(Mark.of(threads), AggregateState.of(threads), live)) { (mark, state, live) ->
            inState(marks.getValue(mark), state).let { if (live) withGutterLiveBadge(it) else it }
        }

    fun withGutterLiveBadge(mark: Icon): Icon = LiveBadgeIcon(mark, liveBolt, gutterLive)

    fun withStripeLiveBadge(toolWindowIcon: Icon): Icon = LiveBadgeIcon(toolWindowIcon, liveBolt, stripeLive(LiveBadgeIcon.Corner.BOTTOM_RIGHT))

    private fun inState(mark: Icon, state: AggregateState): Icon = when (state) {
        AggregateState.RESOLVED -> IconLoader.getTransparentIcon(mark, 0.33f)
        AggregateState.ORPHANED -> LayeredIcon(2).apply {
            setIcon(IconLoader.getTransparentIcon(mark, 0.55f), 0)
            setIcon(orphanFrame, 1)
        }
        AggregateState.OPEN_BLOCKER -> BadgeIconSupplier(mark).errorIcon
        AggregateState.UNREAD -> BadgeIconSupplier(mark).infoIcon
        AggregateState.OPEN -> mark
    }

    private fun badged(base: Icon, badge: Icon): Icon = LayeredIcon(2).apply {
        setIcon(base, 0)
        setIcon(badge, 1, SwingConstants.SOUTH_EAST)
    }

    private fun stripeLive(corner: LiveBadgeIcon.Corner) =
        LiveBadgeIcon.Place(corner, sizeOfBox = 9f / STRIPE_BOX, gapOfBox = 1.25f / STRIPE_BOX)

    private const val STRIPE_BOX = 20f

    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", MarginalisIcons::class.java)
}
