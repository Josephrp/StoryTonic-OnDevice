package ai.liquid.leapchat.services

import ai.liquid.leap.LeapClient
import ai.liquid.leap.message.MessageResponse
import ai.liquid.leapchat.models.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class StoryGenerationService(
    private val modelRunner: ai.liquid.leap.ModelRunner?, // Model runner from LeapClient.loadModel() or null for mock
    private val xmlParser: StoryXmlParser
) {
    fun isMockMode(): Boolean = modelRunner == null

    private val TAG = "StoryGenerationService"

    init {
        if (isMockMode()) {
            Log.w(TAG, "⚠️  StoryGenerationService initialized in MOCK MODE - no real model provided")
        } else {
            Log.d(TAG, "✅ StoryGenerationService initialized with real Leap SDK model")
        }
    }

    suspend fun generateCompleteStory(prompt: String): StoryGenerationResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting story generation for prompt: $prompt")

            // Step 1: Generate story outline
            val outlinePrompt = createOutlinePrompt(prompt)
            Log.d(TAG, "Generating outline...")
            val outlineXml = generateWithLeapModel(outlinePrompt, 1024, expectXml = true)
            Log.d(TAG, "Outline generated: $outlineXml")

            // Step 2: Parse outline for information
            val outlineStory = xmlParser.parseStoryResponse(outlineXml)
            Log.d(TAG, "Outline parsed: ${outlineStory.characters.size} characters, ${outlineStory.settings.size} settings")

            // Step 3: Iteratively generate story sections
            val completeStory = generateStorySectionsIteratively(outlineStory, prompt)
            Log.d(TAG, "Story generation completed")

            StoryGenerationResult.Success(completeStory)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating story", e)
            StoryGenerationResult.Error("Failed to generate story: ${e.message}")
        }
    }

    private suspend fun generateStorySectionsIteratively(outlineStory: Story, originalPrompt: String): Story = coroutineScope {
        var currentStory = outlineStory
        val sections = mutableListOf<String>()

        // Generate initial story section
        Log.d(TAG, "Generating initial story section...")
        val firstSectionPrompt = createFirstSectionPrompt(outlineStory, originalPrompt)
        val firstSection = generateWithLeapModel(firstSectionPrompt, 2048, expectXml = false)
        sections.add(firstSection)
        Log.d(TAG, "First section generated (${firstSection.length} chars): ${firstSection.take(100)}...")

        // Generate subsequent sections iteratively
        val maxSections = 5 // Limit to prevent infinite loops
        var sectionCount = 1

        while (sectionCount < maxSections) {
            Log.d(TAG, "Generating section ${sectionCount + 1}...")

            // Remove last section and generate next section
            val previousSections = sections.joinToString("\n\n")
            val nextSectionPrompt = createNextSectionPrompt(
                outlineStory,
                previousSections,
                originalPrompt,
                sectionCount + 1
            )

            val nextSection = generateWithLeapModel(nextSectionPrompt, 2048, expectXml = false)
            sections.add(nextSection)
            Log.d(TAG, "Section $sectionCount generated (${nextSection.length} chars): ${nextSection.take(100)}...")

            sectionCount++

            // Check if we should continue (look for conclusion indicators)
            if (nextSection.contains("The End", ignoreCase = true) ||
                nextSection.contains("conclusion", ignoreCase = true) ||
                nextSection.contains("finally", ignoreCase = true)) {
                break
            }
        }

        // Combine all sections into complete story
        val fullText = sections.joinToString("\n\n")
        Log.d(TAG, "Combined story text: $fullText")

        // Return story with the complete narrative
        currentStory.copy(fullText = fullText)
    }

    private suspend fun generateWithLeapModel(prompt: String, maxTokens: Int, expectXml: Boolean = false): String {
        return try {
            if (isMockMode()) {
                // Return a mock response to allow the app to compile and run
                Log.w(TAG, "⚠️  USING MOCK MODE - Model not loaded properly")
                Log.d(TAG, "Generating mock response for prompt: $prompt (expectXml: $expectXml)")
                val mockResponse = createMockStoryResponse(expectXml)
                Log.d(TAG, "Mock generation completed")
                mockResponse
            } else {
                // Use the actual Leap SDK
                Log.d(TAG, "✅ Using actual Leap SDK for prompt: $prompt")

                val conversation = modelRunner!!.createConversation()
                val responseBuilder = StringBuilder()

                // Collect the flow response
                conversation.generateResponse(prompt).collect { messageResponse: MessageResponse ->
                    when (messageResponse) {
                        is MessageResponse.Chunk -> {
                            val chunkText = messageResponse.text
                            responseBuilder.append(chunkText)
                            Log.d(TAG, "📝 Received chunk: ${chunkText.take(50)}...")
                        }
                        is MessageResponse.Complete -> {
                            Log.d(TAG, "🎉 Generation completed")
                        }
                        is MessageResponse.ReasoningChunk -> {
                            val reasoningText = messageResponse.reasoning
                            Log.d(TAG, "🧠 Received reasoning: ${reasoningText.take(50)}...")
                        }
                        is MessageResponse.FunctionCalls -> {
                            Log.d(TAG, "🔧 Received function calls: ${messageResponse.functionCalls.size} calls")
                        }
                    }
                }

                val finalResponse = responseBuilder.toString()
                Log.d(TAG, "✅ Leap SDK generation completed, response length: ${finalResponse.length}")
                finalResponse
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in generation", e)
            "Error: ${e.message}. The Leap SDK integration needs to be completed."
        }
    }

    private fun createMockStoryResponse(expectXml: Boolean): String {
        return if (expectXml) {
            // Return XML structure for outline generation
            """
                <story>
                    <characters>
                        <character name="Cat in Hat" role="protagonist" traits="playful,adventurous,curious"/>
                        <character name="White Rabbit" role="guide" traits="timely,worried,nervous"/>
                    </characters>
                    <settings>
                        <setting id="1" name="Cozy Bedroom" time="night" mood="peaceful"/>
                        <setting id="2" name="Wonderland" time="twilight" mood="mysterious"/>
                    </settings>
                    <plot>
                        <problem>Getting lost on the way to Neverland</problem>
                        <twists>
                            <twist setting="2">Meeting the Cheshire Cat who offers cryptic advice</twist>
                        </twists>
                    </plot>
                    <conclusion>Returning home with magical memories and new friends</conclusion>
                </story>
            """.trimIndent()
        } else {
            // Return narrative text for story section generation
            """
                In a cozy bedroom on a quiet night, a mischievous Cat in Hat sat perched on the edge of a bed. The room was filled with the soft glow of a nightlight, casting playful shadows on the walls. The Cat's eyes sparkled with adventure as he looked at his sleeping young friend.

                "Oh, what fun we could have!" the Cat whispered, his hat tilted at a jaunty angle. "But first, we need to find our way to Neverland!"

                Suddenly, a nervous White Rabbit burst through an imaginary door, checking his pocket watch frantically. "We're late! We're terribly late!" he exclaimed, twitching with worry. The Cat grinned and extended a paw. "Perfect! A guide who knows the way. Let's go on an adventure!"

                Together, they tumbled into a swirling portal, leaving the safety of the bedroom behind. The world around them transformed into the mysterious realm of Wonderland, where anything was possible and adventure awaited at every turn.
            """.trimIndent()
        }
    }

    private fun createOutlinePrompt(prompt: String): String {
        return """
            You are a creative storyteller. Generate a story outline based on this prompt: "$prompt"

            Please respond with an XML structure containing:
            - Characters with names, roles, and traits
            - Settings with IDs, names, times, and moods
            - Plot with problem and twists
            - Conclusion summary

            Format your response as:
            <story>
                <characters>
                    <character name="Character Name" role="protagonist/antagonist" traits="trait1,trait2,trait3"/>
                </characters>
                <settings>
                    <setting id="1" name="Setting Name" time="time of day" mood="atmosphere"/>
                </settings>
                <plot>
                    <problem>Main conflict or problem</problem>
                    <twists>
                        <twist setting="setting_id">Plot twist description</twist>
                    </twists>
                </plot>
                <conclusion>How the story ends</conclusion>
            </story>
        """.trimIndent()
    }

    private fun createFirstSectionPrompt(outlineStory: Story, originalPrompt: String): String {
        val characters = outlineStory.characters.joinToString(", ") { "${it.name} (${it.role})" }
        val settings = outlineStory.settings.joinToString(", ") { it.name }
        val problem = outlineStory.plot?.problem ?: "an adventure"

        return """
            Write the first section of a story based on this outline:

            Original prompt: "$originalPrompt"
            Main characters: $characters
            Settings: $settings
            Main problem/conflict: $problem

            Write an engaging opening section that introduces the characters, setting, and sets up the main conflict.
            Make it vivid and captivating. Aim for 200-400 words.

            IMPORTANT: Respond with narrative text only. Do not include any XML tags or formatting.
            Just write the story content directly.
        """.trimIndent()
    }

    private fun createNextSectionPrompt(
        outlineStory: Story,
        previousSections: String,
        originalPrompt: String,
        sectionNumber: Int
    ): String {
        val conclusion = outlineStory.conclusion

        return """
            Continue the story based on the outline and previous sections.

            Original prompt: "$originalPrompt"
            Story conclusion should lead to: "$conclusion"

            Previous sections:
            $previousSections

            Write section $sectionNumber of the story. Continue developing the plot, characters, and conflicts.
            If this should be the final section, include "The End" and wrap up the story.
            Aim for 200-400 words.

            IMPORTANT: Respond with narrative text only. Do not include any XML tags or formatting.
            Just write the story content directly.
        """.trimIndent()
    }
}


