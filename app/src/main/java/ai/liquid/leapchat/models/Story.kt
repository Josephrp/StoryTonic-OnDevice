package ai.liquid.leapchat.models

data class Story(
    val characters: List<StoryCharacter> = emptyList(),
    val settings: List<StorySetting> = emptyList(),
    val plot: StoryPlot? = null,
    val conclusion: String = "",
    val fullText: String = ""
)

data class StoryCharacter(
    val name: String,
    val role: String,
    val traits: List<String> = emptyList()
)

data class StorySetting(
    val id: String,
    val name: String,
    val time: String = "",
    val mood: String = ""
)

data class StoryPlot(
    val problem: String = "",
    val twists: List<StoryTwist> = emptyList()
)

data class StoryTwist(
    val setting: String = "",
    val description: String = ""
)

sealed class StoryGenerationResult {
    data class Success(val story: Story) : StoryGenerationResult()
    data class Error(val message: String) : StoryGenerationResult()
    object Loading : StoryGenerationResult()
}


