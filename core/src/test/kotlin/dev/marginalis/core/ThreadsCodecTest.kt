package dev.marginalis.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreadsCodecTest {

    @Test
    fun `full round trip preserves everything`() {
        val agent = Author.Agent("Claude", id = "claude-1")
        val user = Author.User("Muhammad")
        val t = CommentThread("src/a.py", 7, "def f():", order = 2, walkthrough = "A")
        t.addMessage(Message(agent, "**bold** question"))
        t.addMessage(Message(user, "answer"))
        t.resolve(user)

        val decoded = ThreadsCodec.decode(ThreadsCodec.encode(listOf(t))).single()

        assertEquals(t.id, decoded.id)
        assertEquals("src/a.py", decoded.file)
        assertEquals(7, decoded.line)
        assertEquals("def f():", decoded.anchorText)
        assertEquals(2, decoded.order)
        assertEquals("A", decoded.walkthrough)
        assertEquals(t.createdAt, decoded.createdAt)
        val status = decoded.status
        assertIs<ThreadStatus.Resolved>(status)
        assertEquals(user, status.by)

        assertEquals(2, decoded.messages.size)
        val (m1, m2) = decoded.messages
        assertEquals(agent, m1.author)
        assertEquals("**bold** question", m1.body)
        assertEquals(setOf("claude-1"), m1.seenBy)
        assertEquals(user, m2.author)
        assertFalse(m2.seenByAnyAgent)
    }

    @Test
    fun `pre-rename files with kind HUMAN load as User`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t1","file":"a.py","line":3,"anchor_text":"x = 1",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z",
              "messages":[{"id":"m1","author":{"kind":"HUMAN","name":"Muhammad"},
                "body":"hello","created_at":"2026-07-18T12:00:01Z","seen_by_agent":false}]
            }]}
        """.trimIndent()
        val thread = ThreadsCodec.decode(legacy).single()
        val author = thread.messages.single().author
        assertIs<Author.User>(author)
        assertEquals("Muhammad", author.displayName)
    }

    @Test
    fun `pre-multi-agent seen_by_agent bit maps to the anonymous Agent key`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t3","file":"a.py","line":3,"anchor_text":"x = 1",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z",
              "messages":[{"id":"m1","author":{"kind":"USER","name":"Muhammad"},
                "body":"read","created_at":"2026-07-18T12:00:01Z","seen_by_agent":true},
               {"id":"m2","author":{"kind":"USER","name":"Muhammad"},
                "body":"unread","created_at":"2026-07-18T12:00:02Z","seen_by_agent":false}]
            }]}
        """.trimIndent()
        val messages = ThreadsCodec.decode(legacy).single().messages
        assertEquals(setOf("Agent"), messages[0].seenBy)
        assertFalse(messages[1].seenByAnyAgent)
    }

    @Test
    fun `pre-rename files with tour key load as walkthrough`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t2","file":"a.py","line":3,"anchor_text":"x = 1","order":1,"tour":"B",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z","messages":[]
            }]}
        """.trimIndent()
        assertEquals("B", ThreadsCodec.decode(legacy).single().walkthrough)
    }

    @Test
    fun `orphaned and open statuses survive the trip`() {
        val open = CommentThread("a.py", 1, "x")
        val orphaned = CommentThread("b.py", 2, "y").also { it.markOrphaned() }
        val decoded = ThreadsCodec.decode(ThreadsCodec.encode(listOf(open, orphaned)))
        assertIs<ThreadStatus.Open>(decoded[0].status)
        assertIs<ThreadStatus.Orphaned>(decoded[1].status)
    }

    @Test
    fun `segment survives the trip and its absence stays absent`() {
        val spanned = CommentThread("a.py", 1, "prev, curr = 0, 1", segment = Segment("curr", prefix = "prev, ", suffix = " ="))
        val plain = CommentThread("b.py", 2, "y")
        val decoded = ThreadsCodec.decode(ThreadsCodec.encode(listOf(spanned, plain)))
        assertEquals(Segment("curr", prefix = "prev, ", suffix = " ="), decoded[0].segment)
        assertEquals(null, decoded[1].segment)
    }

    @Test
    fun `severity survives the trip and its absence stays absent`() {
        val blocker = CommentThread("a.py", 1, "x", severity = Severity.BLOCKER)
        val nit = CommentThread("b.py", 2, "y", severity = Severity.NIT)
        val plain = CommentThread("c.py", 3, "z")
        val decoded = ThreadsCodec.decode(ThreadsCodec.encode(listOf(blocker, nit, plain)))
        assertEquals(Severity.BLOCKER, decoded[0].severity)
        assertEquals(Severity.NIT, decoded[1].severity)
        assertEquals(null, decoded[2].severity)
    }

    @Test
    fun `intent survives the trip, at any rung and beside any severity`() {
        val guidanceBlocker = CommentThread("a.py", 1, "x", severity = Severity.BLOCKER, intent = Intent.GUIDANCE)
        val question = CommentThread(file = null, line = null, anchorText = null, intent = Intent.QUESTION)
        val plain = CommentThread("c.py", 3, "z")
        val decoded = ThreadsCodec.decode(ThreadsCodec.encode(listOf(guidanceBlocker, question, plain)))

        assertEquals(Intent.GUIDANCE, decoded[0].intent)
        assertEquals(Severity.BLOCKER, decoded[0].severity)
        assertEquals(Intent.QUESTION, decoded[1].intent)
        assertEquals(null, decoded[1].severity)
        assertTrue(decoded[1].isProjectLevel)
        assertEquals(null, decoded[2].intent)
    }

    @Test
    fun `unknown intent values load as unmarked, not as failure`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t9","file":"a.py","line":3,"anchor_text":"x = 1","intent":"EPIPHANY",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z","messages":[]
            }]}
        """.trimIndent()
        assertEquals(null, ThreadsCodec.decode(legacy).single().intent)
    }

    @Test
    fun `pre-intent files load as ordinary comments`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t10","file":"a.py","line":3,"anchor_text":"x = 1","severity":"NIT",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z","messages":[]
            }]}
        """.trimIndent()
        val thread = ThreadsCodec.decode(legacy).single()
        assertEquals(null, thread.intent)
        assertEquals(Severity.NIT, thread.severity)
    }

    @Test
    fun `unknown severity values load as unmarked, not as failure`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t5","file":"a.py","line":3,"anchor_text":"x = 1","severity":"CRITICAL",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z","messages":[]
            }]}
        """.trimIndent()
        assertEquals(null, ThreadsCodec.decode(legacy).single().severity)
    }

    @Test
    fun `pre-segment files load as whole-line threads`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t4","file":"a.py","line":3,"anchor_text":"x = 1",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z","messages":[]
            }]}
        """.trimIndent()
        assertEquals(null, ThreadsCodec.decode(legacy).single().segment)
    }

    @Test
    fun `a file-level thread writes no anchor and reads back without one`() {
        val fileLevel = CommentThread("src/a.py", line = null, anchorText = null, severity = Severity.NIT, order = 1)
        fileLevel.addMessage(Message(Author.Agent("Claude", id = "claude-1"), "this module needs a README"))

        val encoded = ThreadsCodec.encode(listOf(fileLevel))
        assertFalse(encoded.contains("\"line\""))
        assertFalse(encoded.contains("\"anchor_text\""))

        val decoded = ThreadsCodec.decode(encoded).single()
        assertTrue(decoded.isFileLevel)
        assertEquals(null, decoded.line)
        assertEquals(null, decoded.anchorText)
        assertEquals(Severity.NIT, decoded.severity)
        assertEquals(1, decoded.order)
        assertEquals("this module needs a README", decoded.messages.single().body)
    }

    @Test
    fun `a file-level thread's provenance segment survives the trip without an anchor`() {
        val sparkedBy = Segment("curr", prefix = "prev, ", suffix = " =")
        val fileLevel = CommentThread("src/a.py", line = null, anchorText = null, segment = sparkedBy)

        val encoded = ThreadsCodec.encode(listOf(fileLevel))
        assertFalse(encoded.contains("\"line\""))
        assertTrue(encoded.contains("\"segment\""))

        val decoded = ThreadsCodec.decode(encoded).single()
        assertTrue(decoded.isFileLevel)
        assertEquals(sparkedBy, decoded.segment)
    }

    @Test
    fun `a project-level thread writes no file at all and reads back without one`() {
        val aboutTheProject = CommentThread(
            file = null, line = null, anchorText = null,
            order = 1, walkthrough = "A", severity = Severity.BLOCKER,
        )
        aboutTheProject.addMessage(Message(Author.User("Muhammad"), "we never settled on error handling"))

        val encoded = ThreadsCodec.encode(listOf(aboutTheProject))
        assertFalse(encoded.contains("\"file\""))
        assertFalse(encoded.contains("\"line\""))

        val decoded = ThreadsCodec.decode(encoded).single()
        assertTrue(decoded.isProjectLevel)
        assertFalse(decoded.isFileLevel)
        assertEquals(null, decoded.file)
        assertEquals(null, decoded.line)
        assertEquals(1, decoded.order)
        assertEquals("A", decoded.walkthrough)
        assertEquals(Severity.BLOCKER, decoded.severity)
    }

    @Test
    fun `a persisted line with no anchor text is still a line thread, not a file-level one`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t6","file":"a.py","line":3,
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z","messages":[]
            }]}
        """.trimIndent()
        val thread = ThreadsCodec.decode(legacy).single()
        assertFalse(thread.isFileLevel)
        assertEquals(3, thread.line)
        assertEquals("", thread.anchorText)
    }

    @Test
    fun `the cursor survives the trip untouched by rehydration`() {
        val t = CommentThread("a.py", 1, "x")
        t.addMessage(Message(Author.User("Muhammad"), "hello"))
        val moved = t.updatedAt

        val decoded = ThreadsCodec.decode(ThreadsCodec.encode(listOf(t))).single()
        assertEquals(moved, decoded.updatedAt)
        assertTrue(decoded.updatedAt >= decoded.createdAt)
    }

    @Test
    fun `pre-cursor files derive updated_at from what they do remember`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t7","file":"a.py","line":3,"anchor_text":"x = 1",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z",
              "messages":[{"id":"m1","author":{"kind":"USER","name":"Muhammad"},
                "body":"hi","created_at":"2026-07-18T12:00:05Z","seen_by":[]}]
            },{
              "id":"t8","file":"a.py","line":9,"anchor_text":"y = 2",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z","messages":[]
            }]}
        """.trimIndent()
        val (withMessage, silent) = ThreadsCodec.decode(legacy)
        assertEquals(Instant.parse("2026-07-18T12:00:05Z"), withMessage.updatedAt)
        assertEquals(Instant.parse("2026-07-18T12:00:00Z"), silent.updatedAt)
    }

    @Test
    fun `agent identity survives the trip`() {
        val t = CommentThread("a.py", 1, "x")
        t.addMessage(Message(Author.Agent("Some Other Agent", id = "soa-42"), "hi"))
        val decoded = ThreadsCodec.decode(ThreadsCodec.encode(listOf(t))).single()
        val author = decoded.messages.single().author
        assertIs<Author.Agent>(author)
        assertEquals("soa-42", author.id)
    }

    @Test
    fun `the project's last hand back survives the trip`() {
        val at = Instant.parse("2026-09-27T10:15:30Z")

        val encoded = ThreadsCodec.encode(listOf(CommentThread("a.py", 1, "x")), handedBackAt = at)
        val document = ThreadsCodec.decodeDocument(encoded)

        assertEquals(at, document.handedBackAt)
        assertEquals(1, document.threads.size)
    }

    @Test
    fun `a project never handed back writes nothing, and older files read as never`() {
        val encoded = ThreadsCodec.encode(emptyList())

        assertFalse(encoded.contains("handed_back_at"))
        assertEquals(null, ThreadsCodec.decodeDocument(encoded).handedBackAt)
        assertEquals(null, ThreadsCodec.decodeDocument("""{"version":1,"threads":[]}""").handedBackAt)
    }

    @Test
    fun `an unreadable hand back time reads as never, and the threads still load`() {
        val encoded = ThreadsCodec.encode(listOf(CommentThread("a.py", 1, "x")))
            .replace("{\"version\":1,", "{\"version\":1,\"handed_back_at\":\"yesterday-ish\",")

        val document = ThreadsCodec.decodeDocument(encoded)

        assertEquals(null, document.handedBackAt)
        assertEquals(1, document.threads.size)
    }

    @Test
    fun `each message's addressee survives the trip, and an unaddressed one stays unaddressed`() {
        val t = CommentThread("a.py", 1, "x")
        t.addMessage(Message(Author.User("Muhammad"), "for review", to = Addressee.Agent("claude-review")))
        t.addMessage(Message(Author.Agent("Claude", "claude-review"), "for you", to = Addressee.User))
        t.addMessage(Message(Author.User("Muhammad"), "for everyone"))

        val encoded = ThreadsCodec.encode(listOf(t))
        val decoded = ThreadsCodec.decode(encoded).single()

        assertEquals(listOf(Addressee.Agent("claude-review"), Addressee.User, null), decoded.messages.map { it.to })
        assertEquals(2, Regex("\"to\"").findAll(encoded).count())
    }

    @Test
    fun `pre-addressing files and blank addressees load as broadcast`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t1","file":"a.py","line":3,"anchor_text":"x = 1",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z",
              "messages":[
                {"id":"m1","author":{"kind":"USER","name":"Muhammad"},"body":"old","created_at":"2026-07-18T12:00:01Z"},
                {"id":"m2","author":{"kind":"USER","name":"Muhammad"},"body":"odd","created_at":"2026-07-18T12:00:02Z","to":""}
              ]
            }]}
        """.trimIndent()

        assertEquals(listOf(null, null), ThreadsCodec.decode(legacy).single().messages.map { it.to })
    }

    @Test
    fun `an agreement survives the trip, and ordinary messages write no flag`() {
        val agent = Author.Agent("Claude", "claude-main")
        val t = CommentThread("a.py", 1, "x")
        t.addMessage(Message(agent, "Rename it?"))
        t.addMessage(Message.agreement(by = Author.User("Muhammad"), with = agent))

        val encoded = ThreadsCodec.encode(listOf(t))
        val decoded = ThreadsCodec.decode(encoded).single()

        assertEquals(listOf(false, true), decoded.messages.map { it.agrees })
        assertEquals(1, Regex("\"agrees\"").findAll(encoded).count())
    }

    @Test
    fun `the agent's answer to an Agree survives the trip, and a thread without one writes none`() {
        val agent = Author.Agent("Claude", "claude-main")
        val answered = CommentThread("a.py", 1, "x")
        answered.addMessage(Message(agent, "Rename it?"))
        answered.addMessage(Message.agreement(by = Author.User("Muhammad"), with = agent))
        answered.answerAgreement(by = agent, state = AgentState.WORKING)
        val unanswered = CommentThread("a.py", 2, "y").also { it.addMessage(Message(agent, "Hm?")) }

        val encoded = ThreadsCodec.encode(listOf(answered, unanswered))
        val decoded = ThreadsCodec.decode(encoded)

        assertEquals(listOf(AgentState.WORKING, null), decoded.map { it.agentState })
        assertEquals(1, Regex("\"agent_state\"").findAll(encoded).count())
    }

    @Test
    fun `an unknown stored agent state loads as none, and the thread still loads`() {
        val stored = """
            {"threads": [{"id": "t1", "file": "a.py", "line": 1, "anchor_text": "x", "status": "OPEN",
              "created_at": "2026-01-01T00:00:00Z", "agent_state": "pondering", "messages": []}]}
        """.trimIndent()

        assertEquals(null, ThreadsCodec.decode(stored).single().agentState)
    }

    @Test
    fun `what the user has read survives the trip`() {
        val agent = Author.Agent("Claude", "claude-main")
        val read = CommentThread("a.py", 1, "x", intent = Intent.FYI).also {
            it.addMessage(Message(agent, "Lovely."))
            it.markReadByUser()
        }
        val unread = CommentThread("a.py", 2, "y", intent = Intent.FYI).also { it.addMessage(Message(agent, "Neat.")) }

        val (readBack, unreadBack) = ThreadsCodec.decode(ThreadsCodec.encode(listOf(read, unread)))

        assertEquals(listOf(true), readBack.messages.map { it.readByUser })
        assertEquals(listOf(false), unreadBack.messages.map { it.readByUser })
        assertEquals(null, readBack.turn())
    }

    @Test
    fun `pre-fyi files load with nothing agreed and agent words unread by the user`() {
        val legacy = """
            {"version":1,"threads":[{
              "id":"t1","file":"a.py","line":3,"anchor_text":"x = 1","intent":"FINDING",
              "status":"OPEN","created_at":"2026-07-18T12:00:00Z",
              "messages":[
                {"id":"m1","author":{"kind":"AGENT","name":"Claude","id":"claude-main"},"body":"bug","created_at":"2026-07-18T12:00:01Z","seen_by":["claude-main"]},
                {"id":"m2","author":{"kind":"USER","name":"Muhammad"},"body":"ok","created_at":"2026-07-18T12:00:02Z","seen_by":[]}
              ]
            }]}
        """.trimIndent()

        val messages = ThreadsCodec.decode(legacy).single().messages

        assertEquals(listOf(false, false), messages.map { it.agrees })
        assertEquals(listOf(false, true), messages.map { it.readByUser })
    }

    @Test
    fun `an fyi's label survives the trip, and its absence stays absent`() {
        val labelled = CommentThread("a.py", 1, "x", intent = Intent.FYI, label = "praise")
        val bare = CommentThread("a.py", 2, "y", intent = Intent.FYI)

        val encoded = ThreadsCodec.encode(listOf(labelled, bare))
        val (labelledBack, bareBack) = ThreadsCodec.decode(encoded)

        assertEquals("praise", labelledBack.label)
        assertEquals(null, bareBack.label)
        assertEquals(1, Regex("\"label\"").findAll(encoded).count())
    }

    @Test
    fun `an invalid stored label loads as none, and the thread still loads`() {
        val stored = """
            {"version":1,"threads":[
              {"id":"t1","file":"a.py","intent":"FYI","label":"not_a label!","status":"OPEN",
               "created_at":"2026-07-18T12:00:00Z","messages":[]},
              {"id":"t2","file":"a.py","intent":"FINDING","label":"praise","status":"OPEN",
               "created_at":"2026-07-18T12:00:00Z","messages":[]}
            ]}
        """.trimIndent()

        assertEquals(listOf(null, null), ThreadsCodec.decode(stored).map { it.label })
    }

    @Test
    fun `an fyi stored with a severity loads without one`() {
        val stored = """
            {"version":1,"threads":[
              {"id":"t1","file":"a.py","intent":"FYI","severity":"BLOCKER","status":"OPEN",
               "created_at":"2026-07-18T12:00:00Z","messages":[]}
            ]}
        """.trimIndent()

        val thread = ThreadsCodec.decode(stored).single()

        assertEquals(Intent.FYI, thread.intent)
        assertEquals(null, thread.severity)
    }

    @Test
    fun `a relayed message keeps its GitHub source through the trip, and an ordinary one writes none`() {
        val agent = Author.Agent("Claude", "claude-main")
        val relayed = Relayed(
            Relayed.Source.GITHUB, "99", "https://github.com/o/r/pull/7#discussion_r99", "Mona", "octocat",
            bot = true, avatarUrl = "https://a/x.png",
        )
        val t = CommentThread("a.py", 1, "x").also {
            it.addMessage(Message(agent, "from GitHub", relayed = relayed))
            it.addMessage(Message(agent, "mine"))
        }

        val encoded = ThreadsCodec.encode(listOf(t))
        val decoded = ThreadsCodec.decode(encoded).single()

        assertEquals(listOf(relayed, null), decoded.messages.map { it.relayed })
        assertEquals(1, Regex("\"relayed\"").findAll(encoded).count())
    }

    @Test
    fun `a stored relay that no longer parses still loads as relayed, and survives the next save`() {
        val stored = """
            {"version":1,"threads":[{
              "id":"t1","file":"a.py","status":"OPEN","created_at":"2026-07-18T12:00:00Z",
              "messages":[
                {"id":"m1","author":{"kind":"AGENT","name":"Claude","id":"claude-main"},"body":"hi",
                 "created_at":"2026-07-18T12:00:01Z","seen_by":["claude-main"],"relayed":{"source":"gitlab"}}
              ]
            }]}
        """.trimIndent()

        val loaded = ThreadsCodec.decode(stored).single()
        val resaved = ThreadsCodec.decode(ThreadsCodec.encode(listOf(loaded))).single()

        assertEquals(Turn.USER_OWES, loaded.turn())
        assertEquals(loaded.messages.single().relayed, resaved.messages.single().relayed)
        assertEquals(true, resaved.messages.single().relayed != null)
    }

    @Test
    fun `a stored message that both addresses someone and is relayed loads as relayed, addressing no one`() {
        val stored = """
            {"version":1,"threads":[{
              "id":"t1","file":"a.py","status":"OPEN","created_at":"2026-07-18T12:00:00Z",
              "messages":[
                {"id":"m1","author":{"kind":"AGENT","name":"Claude","id":"claude-main"},"body":"hi",
                 "created_at":"2026-07-18T12:00:01Z","seen_by":["claude-main"],"to":"user",
                 "relayed":{"source":"github","comment_id":"7","url":"https://github.com/o/r/pull/1#discussion_r7","name":"Mona","login":"octocat"}}
              ]
            }]}
        """.trimIndent()

        val message = ThreadsCodec.decode(stored).single().messages.single()

        assertEquals("7", message.relayed?.commentId)
        assertEquals(null, message.to)
    }

    @Test
    fun `one broken thread or message never costs the rest of the file`() {
        val stored = """
            {"version":1,"threads":[
              {"id":"broken","file":"a.py","status":"OPEN","created_at":"not a time","messages":[]},
              {"file":"a.py","status":"OPEN","created_at":"2026-07-18T12:00:00Z","messages":[]},
              {"id":"t2","file":"a.py","status":"OPEN","created_at":"2026-07-18T12:00:00Z",
               "messages":[
                 {"id":"m1","author":{"kind":"USER","name":"Muhammad"},"created_at":"2026-07-18T12:00:01Z"},
                 {"id":"m2","author":{"kind":"USER","name":"Muhammad"},"body":"kept","created_at":"2026-07-18T12:00:02Z"},
                 "not a message"
               ]},
              "not a thread"
            ]}
        """.trimIndent()

        val threads = ThreadsCodec.decode(stored)

        assertEquals(listOf("t2"), threads.map { it.id })
        assertEquals(listOf("kept"), threads.single().messages.map { it.body })
    }
}
