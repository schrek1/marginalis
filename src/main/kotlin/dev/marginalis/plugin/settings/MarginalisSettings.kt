package dev.marginalis.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.marginalis.core.ListWhileInFront
import dev.marginalis.core.People
import dev.marginalis.core.ProjectTabPrefs
import dev.marginalis.core.ReadWhen

// App-level, not per project: navigation consent and display name follow the person.
@Service
@State(name = "MarginalisSettings", storages = [Storage("marginalis.xml")])
class MarginalisSettings : PersistentStateComponent<MarginalisSettings.State> {

    class State {
        var navigationEnabled: Boolean = true

        var displayName: String = ""

        var githubLogin: String = ""

        var githubNicknames: MutableMap<String, String> = LinkedHashMap()

        @get:XCollection(style = XCollection.Style.v2)
        var people: MutableList<PersonState> = ArrayList()

        var userPicture: String = ""

        var showGithubAvatars: Boolean = true

        var timeFormat: String = TimeFormat.AUTO.stored

        var walkthroughAutoAdvance: Boolean = true

        var notifyOnAgentReply: Boolean = true

        var liveByDefault: Boolean = false

        var projectTabList: String = ListWhileInFront.LIVE.stored

        var expandOnYourMove: Boolean = true

        var projectTabReadWhen: String = ReadWhen.EXPANDED_IN_FRONT.stored

        var groupRelayedConversation: Boolean = true
    }

    @Tag("person")
    class PersonState {
        @get:Attribute
        var identity: String = ""

        @get:Attribute
        var kind: String = People.Kind.PERSON.stored

        @get:Attribute
        var nickname: String = ""

        @get:Attribute
        var picture: String = ""

        fun toRow(): People.Row? = People.Row.normalizedOrNull(identity, kindOf(kind), nickname, picture)

        companion object {
            fun of(row: People.Row): PersonState = PersonState().apply {
                identity = row.identity
                kind = row.kind.stored
                nickname = row.nickname
                picture = row.picture.orEmpty()
            }

            private fun kindOf(stored: String): People.Kind =
                People.Kind.entries.firstOrNull { it.stored.equals(stored, ignoreCase = true) } ?: People.Kind.PERSON
        }
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        rows = People.migrated(state.githubNicknames, rows)
        state.githubNicknames = LinkedHashMap()
    }

    var rows: List<People.Row>
        get() = state.people.mapNotNull(PersonState::toRow)
        set(value) {
            state.people = value.mapTo(ArrayList(), PersonState::of)
        }

    val people: People
        get() = People(rows)

    var timeFormat: TimeFormat
        get() = TimeFormat.fromStored(state.timeFormat)
        set(value) {
            state.timeFormat = value.stored
        }

    var listWhileInFront: ListWhileInFront
        get() = ListWhileInFront.fromStored(state.projectTabList)
        set(value) {
            state.projectTabList = value.stored
        }

    var readWhen: ReadWhen
        get() = ReadWhen.fromStored(state.projectTabReadWhen)
        set(value) {
            state.projectTabReadWhen = value.stored
        }

    val projectTabPrefs: ProjectTabPrefs
        get() = ProjectTabPrefs(
            list = listWhileInFront,
            expandOnYourMove = state.expandOnYourMove,
            readWhen = readWhen,
            groupRelayed = state.groupRelayedConversation,
        )

    companion object {
        fun getInstance(): MarginalisSettings =
            ApplicationManager.getApplication().getService(MarginalisSettings::class.java)
    }
}

private val People.Kind.stored: String get() = name.lowercase()
