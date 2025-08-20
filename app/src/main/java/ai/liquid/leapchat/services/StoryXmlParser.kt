package ai.liquid.leapchat.services

import ai.liquid.leapchat.models.*
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader

class StoryXmlParser {

    private val TAG = "StoryXmlParser"

    fun parseStoryResponse(xmlContent: String): Story {
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(xmlContent))
            }

            var characters = mutableListOf<StoryCharacter>()
            var settings = mutableListOf<StorySetting>()
            var plot: StoryPlot? = null
            var conclusion = ""
            var fullText = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "character" -> characters.add(parseCharacter(parser))
                            "setting" -> settings.add(parseSetting(parser))
                            "plot" -> plot = parsePlot(parser)
                            "conclusion" -> conclusion = parseConclusion(parser)
                            "story" -> fullText = parseFullText(parser)
                        }
                    }
                }
                eventType = parser.next()
            }

            Story(
                characters = characters,
                settings = settings,
                plot = plot,
                conclusion = conclusion,
                fullText = fullText
            )
        } catch (e: XmlPullParserException) {
            Log.e(TAG, "Error parsing XML", e)
            Story() // Return empty story on error
        } catch (e: IOException) {
            Log.e(TAG, "IO Error parsing XML", e)
            Story() // Return empty story on error
        }
    }

    private fun parseCharacter(parser: XmlPullParser): StoryCharacter {
        val name = parser.getAttributeValue(null, "name") ?: ""
        val role = parser.getAttributeValue(null, "role") ?: ""
        val traitsAttr = parser.getAttributeValue(null, "traits")
        val traits = traitsAttr?.split(",")?.map { it.trim() } ?: emptyList()

        return StoryCharacter(name, role, traits)
    }

    private fun parseSetting(parser: XmlPullParser): StorySetting {
        val id = parser.getAttributeValue(null, "id") ?: ""
        val name = parser.getAttributeValue(null, "name") ?: ""
        val time = parser.getAttributeValue(null, "time") ?: ""
        val mood = parser.getAttributeValue(null, "mood") ?: ""

        return StorySetting(id, name, time, mood)
    }

    private fun parsePlot(parser: XmlPullParser): StoryPlot {
        var problem = ""
        var twists = mutableListOf<StoryTwist>()

        var eventType = parser.next()
        while (eventType != XmlPullParser.END_TAG || parser.name != "plot") {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "problem" -> problem = parseText(parser)
                        "twist" -> twists.add(parseTwist(parser))
                    }
                }
            }
            eventType = parser.next()
        }

        return StoryPlot(problem, twists)
    }

    private fun parseTwist(parser: XmlPullParser): StoryTwist {
        val setting = parser.getAttributeValue(null, "setting") ?: ""
        val description = parseText(parser)
        return StoryTwist(setting, description)
    }

    private fun parseConclusion(parser: XmlPullParser): String {
        return parseText(parser)
    }

    private fun parseFullText(parser: XmlPullParser): String {
        return parseText(parser)
    }

    private fun parseText(parser: XmlPullParser): String {
        var text = ""
        if (parser.next() == XmlPullParser.TEXT) {
            text = parser.text
            parser.nextTag()
        }
        return text
    }
}


