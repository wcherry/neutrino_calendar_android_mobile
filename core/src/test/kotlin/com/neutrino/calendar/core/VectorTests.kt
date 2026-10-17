package com.neutrino.calendar.core

import com.neutrino.calendar.core.logic.EventDayRange
import com.neutrino.calendar.core.logic.RecurrenceExpander
import com.neutrino.calendar.core.logic.RepeatRule
import com.neutrino.calendar.core.logic.SmartAdd
import com.neutrino.calendar.core.logic.SmartAddResult
import com.neutrino.calendar.core.logic.SmartDate
import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.EventWire
import com.neutrino.calendar.core.model.ServerDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Holds the Kotlin port to the web, case by case, with the vectors the iOS app is held to. None is
 * written by hand: `recurrence_vectors.json` runs the web's own `expandRecurringEvents` and
 * `eventDayRange`; the other two are copies of the web's own fixtures. A failure here means the
 * clients would draw an event on different days, write different rules, or make different tasks.
 */
class VectorTests {

    private fun json(name: String): JsonObject =
        CalendarJson.parseToJsonElement(javaClass.getResource("/$name")!!.readText()).jsonObject

    private fun JsonElement?.str(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.content

    private fun event(e: JsonElement): CalendarEvent = CalendarEvent.from(CalendarJson.decodeFromJsonElement(EventWire.serializer(), e))!!

    @Test
    fun expansionMatchesTheWeb() {
        val fixture = json("recurrence_vectors.json")
        val context = CalendarContext(ZoneId.of(fixture["timeZone"].str()!!))
        val cases = fixture["expansions"]!!.jsonArray
        assertTrue(cases.size >= 19)
        for (c in cases.map { it.jsonObject }) {
            val actual = RecurrenceExpander.expand(
                listOf(event(c["event"]!!)), ServerDate.parse(c["from"].str())!!, ServerDate.parse(c["to"].str())!!, context,
            ).map { "${ServerDate.format(it.start)} → ${ServerDate.format(it.end)}" }
            val expected = c["occurrences"]!!.jsonArray.map {
                val o = it.jsonObject
                "${ServerDate.format(ServerDate.parse(o["startTime"].str())!!)} → ${ServerDate.format(ServerDate.parse(o["endTime"].str())!!)}"
            }
            // Order too: the web's order is the order it pushes occurrences in.
            assertEquals(c["name"].str(), expected, actual)
        }
    }

    @Test
    fun dayRangesMatchTheWeb() {
        val fixture = json("recurrence_vectors.json")
        val context = CalendarContext(ZoneId.of(fixture["timeZone"].str()!!))
        val cases = fixture["dayRanges"]!!.jsonArray
        assertTrue(cases.size >= 8)
        for (c in cases.map { it.jsonObject }) {
            val e = event(c["event"]!!)
            val range = EventDayRange.of(e.start, e.end, e.allDay, context)
            assertEquals("${c["name"].str()}: first", c["first"].str(), range.first.toString())
            assertEquals("${c["name"].str()}: last", c["last"].str(), range.last.toString())
        }
    }

    private fun ruleOf(o: JsonObject): RepeatRule {
        val end = o["end"]!!.jsonObject
        return RepeatRule(
            RepeatRule.Frequency.valueOf(o["freq"].str()!!),
            o["interval"]!!.jsonPrimitive.int,
            o["byDay"].str(),
            when (end["kind"].str()) {
                "on" -> RepeatRule.End.On(LocalDate.parse(end["date"].str()))
                "after" -> RepeatRule.End.After(end["count"]!!.jsonPrimitive.int)
                else -> RepeatRule.End.Never
            },
            o["extra"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun repeatRulesReadAndWriteAsTheWebDoes() {
        val fixture = json("repeat_rule_vectors.json")
        for (c in fixture["parse"]!!.jsonArray.map { it.jsonObject }) {
            val context = RepeatRule.Context(c["allDay"]!!.jsonPrimitive.boolean, ZoneId.of(c["timeZone"].str()!!))
            val parsed = RepeatRule.parse(c["rule"].str()!!, context)
            val expected = c["expected"]
            if (expected == null || expected is JsonNull) {
                assertNull(c["name"].str(), parsed)
                continue
            }
            assertEquals(c["name"].str(), ruleOf(expected.jsonObject), parsed)
            assertEquals(c["name"].str(), c["rebuilt"].str(), parsed!!.rule(context))
        }
        for (c in fixture["build"]!!.jsonArray.map { it.jsonObject }) {
            val context = RepeatRule.Context(c["allDay"]!!.jsonPrimitive.boolean, ZoneId.of(c["timeZone"].str()!!))
            assertEquals(c["name"].str(), c["expected"].str(), ruleOf(c["rule"]!!.jsonObject).rule(context))
        }
    }

    @Test
    fun smartAddParsesEveryLineAsTheWebDoes() {
        val fixture = json("smart_add_vectors.json")
        val context = CalendarJson.decodeFromJsonElement(SmartAdd.Context.serializer(), fixture["context"]!!)
        val cases = fixture["cases"]!!.jsonArray
        assertTrue(cases.size >= 70)
        for (c in cases.map { it.jsonObject }) {
            val input = c["input"].str()!!
            val e = c["expect"]!!.jsonObject
            fun date(key: String) = e[key]?.takeIf { it !is JsonNull }?.let { CalendarJson.decodeFromJsonElement(SmartDate.serializer(), it) }
            val expected = SmartAddResult(
                title = e["title"].str() ?: input,
                due = date("due"),
                start = date("start"),
                priority = e["priority"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
                tags = e["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                recurrenceRule = e["recurrenceRule"].str(),
                repeatAfterCompletion = e["repeatAfterCompletion"]?.jsonPrimitive?.boolean ?: false,
                estimateMinutes = e["estimateMinutes"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
                location = e["location"].str(),
                note = e["note"].str(),
            )
            assertEquals(input, expected, SmartAdd.parse(input, context))
        }
    }

    @Test
    fun repeatDescriptionsParseBack() {
        val fixture = json("smart_add_vectors.json")
        for (c in fixture["cases"]!!.jsonArray.map { it.jsonObject }) {
            val e = c["expect"]!!.jsonObject
            val rule = e["recurrenceRule"].str() ?: continue
            val after = e["repeatAfterCompletion"]?.jsonPrimitive?.boolean ?: false
            val described = SmartAdd.describeRepeat(rule, after)
            assertEquals(described, rule to after, SmartAdd.parseRepeat(described))
        }
    }

    /** The copies here are the web's, byte for byte; skipped without the sibling checkout. */
    @Test
    fun fixturesAreTheWebsCopies() {
        val web = File("../../neutrino/web/apps/web/src/app/(apps)/calendar")
        val pairs = mapOf("smart_add_vectors.json" to "smartAddFixtures.json", "repeat_rule_vectors.json" to "repeatRuleFixtures.json")
        for ((ours, theirs) in pairs) {
            val file = File(web, theirs)
            if (!file.exists()) continue
            assertEquals("run scripts/sync_vectors.sh", file.readText(), javaClass.getResource("/$ours")!!.readText())
        }
    }
}
