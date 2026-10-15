package dev.marginalis.plugin.testing

class FoundationsTest : MarginalisTestCase() {

    fun testAgentPingsOverHttp() {
        assertEquals("ok", Agent(project).get("ping")["status"]!!.asString)
    }

    fun testInterleavedFixtureSeedsEighteenStepsWithOneResolved() {
        val fixture = WalkthroughFixture.interleaved()
        fixture.writeInto(myFixture)
        fixture.postBy(Agent(project))
        assertEquals(18, store.threads.all().size)
        assertEquals(1, store.threads.all().count { it.status is dev.marginalis.core.ThreadStatus.Resolved })
    }

    fun testDeepFixtureSeedsThirtyThreeStepsWithNineResolved() {
        val fixture = WalkthroughFixture.deep()
        fixture.writeInto(myFixture)
        fixture.postBy(Agent(project))
        assertEquals(33, store.threads.all().size)
        assertEquals(9, store.threads.all().count { it.status is dev.marginalis.core.ThreadStatus.Resolved })
    }
}
