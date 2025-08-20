package ai.liquid.leapchat

import ai.liquid.leap.LeapClient
import ai.liquid.leap.LeapModelLoadingException
import ai.liquid.leap.message.MessageResponse
import ai.liquid.leap.downloader.LeapModelDownloader
import ai.liquid.leap.downloader.LeapDownloadableModel
import ai.liquid.leapchat.models.Story
import ai.liquid.leapchat.models.StoryGenerationResult
import ai.liquid.leapchat.services.StoryGenerationService
import ai.liquid.leapchat.services.StoryXmlParser
import ai.liquid.leapchat.views.StoryDisplay
import ai.liquid.leapchat.R
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    // Model constants - using the same model as in the GitHub example
    private val MODEL_SLUG = "lfm2-350m"
    private val QUANTIZATION_SLUG = "lfm2-350m-20250710-8da4w"

    // The generation job instance
    private var job: Job? = null

    // The model runner instance (return type of LeapClient.loadModel())
    private val model: MutableLiveData<ai.liquid.leap.ModelRunner> by lazy {
        MutableLiveData<ai.liquid.leap.ModelRunner>()
    }

    // Current story state
    private val currentStory: MutableLiveData<Story?> by lazy {
        MutableLiveData<Story?>()
    }

    // Whether generation is ongoing
    private val isGeneratingStory: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>(false)
    }

    // Story generation service
    private lateinit var storyGenerationService: StoryGenerationService
    private val xmlParser = StoryXmlParser()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent { MainContent() }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }



    /**
     * The composable of the main activity content
     */
    @Composable
    fun MainContent() {
        val modelInstance by model.observeAsState()
        val story by currentStory.observeAsState()
        val isGenerating by isGeneratingStory.observeAsState(false)
        var storyPrompt by remember { mutableStateOf("") }
        val context = LocalContext.current

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    NavigationBarDefaults.windowInsets.union(
                        WindowInsets.ime
                    )
                ),
            topBar = {
                if (modelInstance == null) {
                    ModelLoadingIndicator(modelInstance) { onError, onStatusChange ->
                        loadBundledModel(onError, onStatusChange)
                    }
                }
            },
            bottomBar = {
                if (modelInstance != null) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = storyPrompt,
                            onValueChange = { storyPrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isGenerating,
                            label = { Text("Story Prompt") },
                            placeholder = { Text("Enter story prompt (e.g., 'A cat in a hat goes to Neverland')") },
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Button(
                                onClick = {
                                    job?.cancel()
                                    isGeneratingStory.value = false
                                },
                                enabled = isGenerating
                            ) {
                                Text("Stop")
                            }
                            Button(
                                onClick = {
                                    if (storyPrompt.isNotBlank()) {
                                        generateStory(storyPrompt)
                                    } else {
                                        Toast.makeText(context, "Please enter a story prompt", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !isGenerating && storyPrompt.isNotBlank()
                            ) {
                                Text("Generate Story")
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                when {
                    modelInstance == null -> {
                        // Model loading is handled in topBar
                    }
                    story != null -> {
                        StoryDisplay(story = story!!)
                    }
                    isGenerating -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text(
                                    text = "Generating story...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Enter a story prompt below to generate a creative story",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Load the model using Leap SDK's built-in downloader.
     */
    private fun loadBundledModel(onError: (Throwable) -> Unit, onStatusChange: (String) -> Unit) {
        lifecycleScope.launch {
            try {
                onStatusChange("Resolving model information...")

                // Resolve the model to download
                val modelToUse = LeapDownloadableModel.resolve(MODEL_SLUG, QUANTIZATION_SLUG)
                if (modelToUse == null) {
                    throw LeapModelLoadingException("Model $QUANTIZATION_SLUG not found in Leap Model Library!")
                }

                onStatusChange("Creating model downloader...")
                val modelDownloader = LeapModelDownloader(this@MainActivity)

                // Start the download
                modelDownloader.requestDownloadModel(modelToUse)

                // Wait for download to complete
                var isModelAvailable = false
                while (!isModelAvailable) {
                    val status = modelDownloader.queryStatus(modelToUse)
                    when (status) {
                        is LeapModelDownloader.ModelDownloadStatus.NotOnLocal -> {
                            onStatusChange("Model is not downloaded. Waiting for downloading...")
                        }
                        is LeapModelDownloader.ModelDownloadStatus.DownloadInProgress -> {
                            if (status.totalSizeInBytes > 0) {
                                val progress = status.downloadedSizeInBytes.toDouble() / status.totalSizeInBytes
                                onStatusChange("Downloading the model: ${String.format("%.2f", progress * 100.0)}%")
                            } else {
                                onStatusChange("Downloading the model...")
                            }
                        }
                        is LeapModelDownloader.ModelDownloadStatus.Downloaded -> {
                            isModelAvailable = true
                        }
                    }
                    kotlinx.coroutines.delay(500) // Wait 500ms before checking again
                }

                // Get the downloaded model file
                val modelFile = modelDownloader.getModelFile(modelToUse)
                Log.d(TAG, "Model downloaded to: ${modelFile.absolutePath} (size: ${modelFile.length()} bytes)")

                onStatusChange("Loading model: ${modelFile.path}")
                try {
                    val loadedModelRunner = LeapClient.loadModel(modelFile.path)
                    model.value = loadedModelRunner
                    storyGenerationService = StoryGenerationService(loadedModelRunner, xmlParser)
                    Log.d(TAG, "Model loaded successfully with Leap SDK")
                    onStatusChange("Model loaded successfully!")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load model with Leap SDK", e)
                    onError(e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in model loading process", e)
                onError(e)
            }
        }
    }



    /**
     * Generate a story from the given prompt.
     */
    private fun generateStory(prompt: String) {
        val currentModel = model.value
        if (currentModel == null && !storyGenerationService.isMockMode()) {
            Log.e(TAG, "Model not loaded")
            Toast.makeText(this, "Model not loaded yet. Please wait for model to finish loading.", Toast.LENGTH_LONG).show()
            return
        }

        if (prompt.isBlank()) {
            Toast.makeText(this, "Please enter a story prompt", Toast.LENGTH_SHORT).show()
            return
        }

        isGeneratingStory.value = true
        job = lifecycleScope.launch {
            try {
                Log.d(TAG, "Starting story generation for prompt: $prompt")
                val result = storyGenerationService.generateCompleteStory(prompt)
                when (result) {
                    is StoryGenerationResult.Success -> {
                        currentStory.value = result.story
                        Log.d(TAG, "Story generated successfully")
                        Toast.makeText(this@MainActivity, "Story generated successfully!", Toast.LENGTH_SHORT).show()
                    }
                    is StoryGenerationResult.Error -> {
                        Log.e(TAG, "Story generation failed: ${result.message}")
                        val errorMessage = result.message ?: "Unknown error occurred"
                        Toast.makeText(this@MainActivity, "Generation failed: $errorMessage", Toast.LENGTH_LONG).show()
                    }
                    is StoryGenerationResult.Loading -> {
                        Log.w(TAG, "Unexpected loading state in final result")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating story", e)
                val errorMessage = e.message ?: "Unknown error occurred"
                Toast.makeText(this@MainActivity, "Error: $errorMessage", Toast.LENGTH_LONG).show()
            } finally {
                isGeneratingStory.value = false
            }
        }
    }
}

/**
 * The screen to show when the app is loading the model.
 * Adapted for the new ai.liquid.leap.ModelRunner type.
 */
@Composable
fun ModelLoadingIndicator(
    modelState: ai.liquid.leap.ModelRunner?, // Model runner instance
    loadModelAction: (onError: (e: Throwable) -> Unit, onStatusChange: (String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var modelLoadingStatusText by remember { mutableStateOf(context.getString(R.string.loading_model_content)) }
    LaunchedEffect(modelState) {
        if (modelState == null) {
            loadModelAction({ error ->
                modelLoadingStatusText =
                    context.getString(R.string.loading_model_fail_content, error.message ?: "Unknown error")
            }, { status ->
                modelLoadingStatusText = status
            })
        }
    }
    Box(Modifier
        .padding(4.dp)
        .fillMaxSize(1.0f), contentAlignment = Alignment.Center) {
        Text(modelLoadingStatusText, style = MaterialTheme.typography.titleSmall)
    }
}