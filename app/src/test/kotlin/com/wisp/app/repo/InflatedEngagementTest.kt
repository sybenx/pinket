package com.wisp.app.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real engagement (reactions + reposts) captured from Primal, relay.ditto.pub and syb.lol
 * on 2026-10-27 for posts on the nostrarchives trending feed. Times are seconds from the
 * first engagement; each row is one account.
 */
class InflatedEngagementTest {

    private data class Row(val reactions: Int, val reactedAt: Long?, val repostedAt: Long?)

    private fun isInflated(rows: List<Row>): Boolean {
        val keyed = rows.mapIndexed { i, row -> "pk$i" to row }
        return InflatedEngagement.isInflated(
            reactions = keyed.filter { it.second.reactions > 0 }.associate { it.first to it.second.reactions },
            reactedAt = keyed.mapNotNull { (pk, row) -> row.reactedAt?.let { pk to it } }.toMap(),
            repostedAt = keyed.mapNotNull { (pk, row) -> row.repostedAt?.let { pk to it } }.toMap()
        )
    }

    @Test
    fun `one account sending 101 reactions is inflated`() {
        assertTrue(isInflated(oneAccount))
    }

    @Test
    fun `reactors who repost in lockstep are inflated`() {
        assertTrue(isInflated(backPage))
        assertTrue(isInflated(blaster))
    }

    @Test
    fun `an ordinary popular post is not inflated`() {
        assertFalse(isInflated(damusRelease))
    }

    @Test
    fun `a friend double-tapping a small post is not inflated`() {
        assertFalse(isInflated(listOf(Row(3, 0L, null), Row(1, 10L, null), Row(1, 20L, null))))
    }

    @Test
    fun `a small group that likes and reposts together is not inflated`() {
        assertFalse(isInflated(List(10) { Row(1, it * 60L, it * 60L + 5) }))
    }

    // oneAccount: 8ac8b1124108e60f1768d52917771a6d11f5031d362edeea277efa3d2b077da3
    private val oneAccount = listOf(
        Row(1, 26495L, null),
        Row(101, 0L, null)
    )

    // backPage: 000002ac199f302e6b0102de8266c26d0d66e56ea7d455f86b2aa72447ea8b84
    private val backPage = listOf(
        Row(1, 28969L, 29035L),
        Row(1, 28033L, null),
        Row(1, 25424L, 25431L),
        Row(1, 24502L, null),
        Row(1, 21987L, 21980L),
        Row(1, 21490L, 21502L),
        Row(1, 21434L, 21446L),
        Row(1, 20254L, null),
        Row(1, 20224L, null),
        Row(1, 20182L, 20191L),
        Row(1, 20099L, 20124L),
        Row(1, 20037L, 20049L),
        Row(1, 19991L, 20000L),
        Row(1, 18694L, 18707L),
        Row(1, 14929L, 14939L),
        Row(1, 12916L, 13954L),
        Row(1, 13473L, 13486L),
        Row(1, 13146L, 13139L),
        Row(1, 13112L, 13121L),
        Row(1, 12420L, 12434L),
        Row(1, 11279L, null),
        Row(1, 10746L, null),
        Row(1, 10576L, null),
        Row(1, 10526L, 10517L),
        Row(1, 10408L, 10403L),
        Row(1, 9441L, 9431L),
        Row(1, 9141L, 9134L),
        Row(1, 8983L, null),
        Row(1, 7427L, 8914L),
        Row(1, 8776L, 8766L),
        Row(1, 8508L, 8522L),
        Row(1, 8288L, 8300L),
        Row(1, 8118L, 8135L),
        Row(1, 7559L, 7720L),
        Row(1, 7465L, 7443L),
        Row(1, 7164L, null),
        Row(1, 7134L, 7143L),
        Row(1, 7105L, 7095L),
        Row(1, 6960L, 6978L),
        Row(1, 6760L, 6772L),
        Row(1, 6216L, 6228L),
        Row(1, 6228L, null),
        Row(1, 6178L, null),
        Row(1, 4748L, 4761L),
        Row(1, 3972L, 3986L),
        Row(1, 3916L, 3925L),
        Row(1, 3778L, 3789L),
        Row(1, 1259L, 1263L),
        Row(1, 33L, 0L)
    )

    // blaster: 8746781e596c41b62655d073b6f88a433202cbf782a34f59716251452a306be6
    private val blaster = listOf(
        Row(1, 126671L, 126673L),
        Row(1, 96693L, null),
        Row(1, 71427L, 71491L),
        Row(0, null, 63758L),
        Row(1, 62440L, null),
        Row(1, 61616L, null),
        Row(1, 60720L, 61109L),
        Row(1, 60624L, 60673L),
        Row(0, null, 58136L),
        Row(0, null, 57011L),
        Row(1, 52999L, 53056L),
        Row(1, 51678L, null),
        Row(1, 49160L, null),
        Row(1, 44077L, null),
        Row(1, 39829L, null),
        Row(1, 38538L, 38552L),
        Row(1, 35940L, 35952L),
        Row(1, 35518L, 35532L),
        Row(1, 35515L, 35516L),
        Row(1, 31585L, 31605L),
        Row(1, 31315L, 31369L),
        Row(1, 29667L, 29676L),
        Row(1, 28600L, null),
        Row(1, 27942L, null),
        Row(1, 25798L, null),
        Row(3, 24089L, 24164L),
        Row(1, 23883L, 23893L),
        Row(1, 21798L, 21815L),
        Row(1, 19988L, 20001L),
        Row(1, 19119L, 19143L),
        Row(1, 17747L, 17764L),
        Row(1, 17110L, 17123L),
        Row(1, 17119L, 17111L),
        Row(1, 16751L, null),
        Row(1, 16574L, null),
        Row(1, 16326L, 16346L),
        Row(1, 15999L, 15988L),
        Row(1, 14610L, 14753L),
        Row(1, 14411L, 14401L),
        Row(1, 12778L, 12787L),
        Row(1, 11087L, 11097L),
        Row(1, 11073L, 11088L),
        Row(1, 11024L, 11042L),
        Row(1, 11023L, 11035L),
        Row(1, 10888L, 10902L),
        Row(1, 10397L, 10405L),
        Row(1, 10338L, 10350L),
        Row(1, 10288L, null),
        Row(1, 9792L, 9802L),
        Row(0, null, 9768L),
        Row(1, 9670L, 9678L),
        Row(1, 8668L, 8679L),
        Row(1, 5885L, 5896L),
        Row(1, 5813L, 5823L),
        Row(1, 4122L, 4142L),
        Row(1, 4047L, 4036L),
        Row(1, 3848L, 3938L),
        Row(1, 3825L, 3842L),
        Row(1, 3401L, 3413L),
        Row(1, 2931L, 2942L),
        Row(1, 2447L, null),
        Row(1, 2372L, 2384L),
        Row(1, 2230L, 2222L),
        Row(1, 2003L, 2006L),
        Row(1, 1610L, null),
        Row(1, 1435L, null),
        Row(1, 357L, null),
        Row(1, 340L, 329L),
        Row(1, 246L, 235L),
        Row(1, 160L, 198L),
        Row(1, 108L, 121L),
        Row(1, 107L, 114L),
        Row(1, 93L, 104L),
        Row(1, 49L, 64L),
        Row(1, 0L, 19L),
        Row(1, 77196L, null),
        Row(1, 49321L, null),
        Row(0, null, 37921L),
        Row(1, 16189L, null),
        Row(1, 13446L, null)
    )

    // damusRelease: 9c76e8d9f1eda694622876fda1a63111a329c3e0a5d9726e57227d8d0249e3e2
    private val damusRelease = listOf(
        Row(1, 60529L, null),
        Row(1, 60016L, 60014L),
        Row(1, 23532L, null),
        Row(1, 19778L, null),
        Row(1, 19717L, null),
        Row(1, 17844L, null),
        Row(1, 9964L, null),
        Row(1, 9500L, null),
        Row(1, 6028L, null),
        Row(1, 666L, null),
        Row(1, 260L, null),
        Row(1, 58038L, null),
        Row(1, 43195L, null),
        Row(1, 8269L, null),
        Row(1, 5340L, null),
        Row(1, 854L, null),
        Row(1, 791L, null),
        Row(1, 527L, null),
        Row(1, 91L, null),
        Row(1, 0L, null)
    )

}
