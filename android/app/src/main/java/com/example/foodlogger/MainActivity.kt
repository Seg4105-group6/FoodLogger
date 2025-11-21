package com.example.foodlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.foodlogger.databinding.ActivityMainBinding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// TODO: change this to your deployed AWS HTTPS endpoint before demo.
private const val BASE_URL = "http://10.0.2.2:8000/"

data class LogMealRequest(
    val total_calories_kcal: Double,
    val total_protein_g: Double,
    val total_carbs_g: Double,
    val total_fat_g: Double,
    val source_filename: String?,
    val items: List<MealItem>
)

data class LogMealResponse(
    val status: String,
    val meal_id: Int,
    val message: String
)

interface MealApiService {
    @Multipart
    @POST("analyze-meal")
    suspend fun analyzeMeal(@Part image: MultipartBody.Part): MealAnalysisResponse

    @POST("log-meal")
    suspend fun logMeal(@Body request: LogMealRequest): LogMealResponse

    @GET("logs/summary/day")
    suspend fun dailySummary(@Query("day") day: String): SummaryResponse

    @GET("logs/summary/rolling")
    suspend fun rollingSummary(@Query("days") days: Int): SummaryResponse

    @GET("logs")
    suspend fun listLogs(@Query("limit") limit: Int = 50): LogsResponse
}

data class MealItem(
    val name: String,
    val code: String,
    val estimated_volume_ml: Double,
    val estimated_weight_g: Double,
    val estimated_calories_kcal: Double,
    val estimated_protein_g: Double,
    val estimated_carbs_g: Double,
    val estimated_fat_g: Double,
    val confidence: Double
)

data class MealPipelineInfo(
    val version: String,
    val notes: String
)

data class MealAnalysisResponse(
    val items: List<MealItem>,
    val total_calories_kcal: Double,
    val total_protein_g: Double,
    val total_carbs_g: Double,
    val total_fat_g: Double,
    val pipeline: MealPipelineInfo?
)

data class SummaryResponse(
    val days: Int,
    val total_calories_kcal: Double,
    val total_protein_g: Double,
    val total_carbs_g: Double,
    val total_fat_g: Double
)

data class LogEntry(
    val id: Long,
    val created_at: String,
    val total_calories_kcal: Double,
    val total_protein_g: Double,
    val total_carbs_g: Double,
    val total_fat_g: Double,
    val source_filename: String?
)

data class LogsResponse(val items: List<LogEntry>)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var photoUri: Uri? = null
    private var photoFile: File? = null
    private var currentFilename: String? = null
    private val prefs by lazy {
        getSharedPreferences("FoodLoggerPrefs", MODE_PRIVATE)
    }

    private var isLoggedIn: Boolean
        get() = prefs.getBoolean("isLoggedIn", false)
        set(value) = prefs.edit().putBoolean("isLoggedIn", value).apply()

    private val items: MutableList<MealItem> = mutableListOf()

    private val job = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + job)
    private var currentDialog: AlertDialog? = null

    private val api: MealApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MealApiService::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showConsentDialogIfNeeded()
        setupLaunchers()
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        setupUi()
        setupTabs()
        
        // Handle intent extras for navigation
        val signOut = intent.getBooleanExtra("SIGN_OUT", false)
        if (signOut) {
            isLoggedIn = false
        }
        
        // Handle tab navigation
        when (intent.getStringExtra("TAB")) {
            "CAPTURE" -> {
                updateScreensForState(isLoggedIn)
                switchToCapture()
            }
            "RESULTS" -> {
                updateScreensForState(isLoggedIn)
                switchToResults()
            }
            "LOGIN" -> {
                updateScreensForState(isLoggedIn)
                switchToLogin()
            }
            else -> {
                // Always default to login screen on fresh start
                updateScreensForState(false)
                switchToLogin()
            }
        }
    }

    private fun setupUi() {
        // Login
        binding.buttonLogin.setOnClickListener {
            // Fake auth: just mark logged in and move to Capture tab.
            isLoggedIn = true
            updateScreensForState(true)
            switchToCapture()
        }

        binding.buttonHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.buttonSignOut.setOnClickListener {
            isLoggedIn = false
            updateScreensForState(false)
            switchToLogin()
        }

        binding.buttonExportLogin.setOnClickListener {
            exportCurrentScreenPng("login_screen")
        }

        // Capture
        binding.buttonCapture.setOnClickListener {
            requestCameraAndCapture()
        }

        binding.buttonImport.setOnClickListener {
            importLauncher.launch("image/*")
        }

        binding.buttonAnalyzeMeal.setOnClickListener {
            photoFile?.let { file ->
                analyzePhoto(file)
            } ?: run {
                Toast.makeText(this, "No photo to analyze", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonExportCapture.setOnClickListener {
            exportCurrentScreenPng("capture_screen")
        }

        // Results actions (removed toggle edit - items are always editable)

        binding.buttonAddItem.setOnClickListener {
            showEditItemDialog(null)
        }

        binding.buttonDeleteSelected.setOnClickListener {
            deleteSelectedItems()
        }

        binding.buttonLogMeal.setOnClickListener {
            logCurrentMeal()
        }

        binding.buttonDiscardMeal.setOnClickListener {
            discardCurrentMeal()
        }
    }

    private fun setupTabs() {
        binding.buttonTabLogin.setOnClickListener { switchToLogin() }
        binding.buttonTabCapture.setOnClickListener { switchToCapture() }
        binding.buttonTabResults.setOnClickListener { switchToResults() }

        binding.recyclerResults.adapter = ResultsAdapter(items,
            onItemClick = { index ->
                showEditItemDialog(index)
            })
    }

    private fun switchToLogin() {
        // Show/hide screens
        binding.layoutLogin.visibility = View.VISIBLE
        binding.layoutCapture.visibility = View.GONE
        binding.layoutResults.visibility = View.GONE
        
        // Make sure login button is visible when on login screen
        binding.buttonLogin.visibility = View.VISIBLE
        
        // Hide History and Sign Out buttons when on login screen
        binding.buttonHistory.visibility = View.GONE
        binding.buttonSignOut.visibility = View.GONE
        
        // Update tab selection
        binding.buttonTabLogin.isSelected = true
        binding.buttonTabCapture.isSelected = false
        binding.buttonTabResults.isSelected = false
    }

    private fun switchToCapture() {
        android.util.Log.d("MainActivity", "switchToCapture() called")
        // Hide all screens
        binding.layoutLogin.visibility = View.GONE
        binding.layoutCapture.visibility = View.VISIBLE
        binding.layoutResults.visibility = View.GONE
        
        // Explicitly hide login button to be extra sure
        binding.buttonLogin.visibility = View.GONE
        
        // Show History and Sign Out buttons when logged in
        binding.buttonHistory.visibility = View.VISIBLE
        binding.buttonSignOut.visibility = View.VISIBLE
        android.util.Log.d("MainActivity", "Set History button visibility to VISIBLE")
        android.util.Log.d("MainActivity", "History button visibility: ${binding.buttonHistory.visibility}")
        
        // Hide analyze button until photo is captured/imported
        binding.buttonAnalyzeMeal.visibility = View.GONE
        
        // Update tab selection
        binding.buttonTabLogin.isSelected = false
        binding.buttonTabCapture.isSelected = true
        binding.buttonTabResults.isSelected = false
    }

    private fun switchToResults() {
        // Hide all screens except results
        binding.layoutLogin.visibility = View.GONE
        binding.layoutCapture.visibility = View.GONE
        binding.layoutResults.visibility = View.VISIBLE
        
        // Explicitly hide login button
        binding.buttonLogin.visibility = View.GONE
        
        // Show History and Sign Out buttons when logged in
        binding.buttonHistory.visibility = View.VISIBLE
        binding.buttonSignOut.visibility = View.VISIBLE
        
        // Update tab selection
        binding.buttonTabLogin.isSelected = false
        binding.buttonTabCapture.isSelected = false
        binding.buttonTabResults.isSelected = true
    }

    private fun updateScreensForState(isLoggedIn: Boolean) {
        binding.buttonHistory.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.buttonSignOut.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        
        // Show/hide tab buttons based on login state
        binding.buttonTabLogin.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
        binding.buttonTabCapture.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.buttonTabResults.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        
        // Update login screen state
        binding.editEmail.isEnabled = !isLoggedIn
        binding.editPassword.isEnabled = !isLoggedIn
        binding.buttonLogin.isEnabled = !isLoggedIn
        binding.textLoginHint.text =
            if (isLoggedIn) {
                "Signed in as ${binding.editEmail.text}. You can now capture meals."
            } else {
                "Demo auth: clicking \"Sign in\" sets a fake token. Only relevant tabs show for each screen."
            }
    }

    private fun setupLaunchers() {
        cameraLauncher =
            registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if (success && photoUri != null) {
                    binding.imagePreview.setImageURI(photoUri)
                    // Show analyze button after capture
                    binding.buttonAnalyzeMeal.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "Camera cancelled.", Toast.LENGTH_SHORT).show()
                }
            }

        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                if (uri != null) {
                    binding.imagePreview.setImageURI(uri)
                    // Persist into a temp file for upload.
                    val temp = createImageFile()
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    photoFile = temp
                    // Show analyze button after import
                    binding.buttonAnalyzeMeal.visibility = View.VISIBLE
                }
            }

        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    openCamera()
                } else {
                    Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun requestCameraAndCapture() {
        val permission = Manifest.permission.CAMERA
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                openCamera()
            }

            shouldShowRequestPermissionRationale(permission) -> {
                if (!isFinishing && !isDestroyed) {
                    currentDialog?.dismiss()
                    currentDialog = AlertDialog.Builder(this)
                        .setTitle("Camera permission")
                        .setMessage("We need access to your camera to capture meal photos.")
                        .setPositiveButton("OK") { _, _ ->
                            permissionLauncher.launch(permission)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }

            else -> {
                permissionLauncher.launch(permission)
            }
        }
    }

    private fun openCamera() {
        val file = createImageFile()
        photoFile = file
        photoUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        cameraLauncher.launch(photoUri)
    }

    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        return File.createTempFile("MEAL_${timeStamp}_", ".jpg", storageDir)
    }

    private fun analyzePhoto(file: File) {
        uiScope.launch {
            Toast.makeText(this@MainActivity, "Uploading and analyzing...", Toast.LENGTH_SHORT)
                .show()

            val part = MultipartBody.Part.createFormData(
                name = "image",
                filename = file.name,
                body = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
            )

            try {
                val result = withContext(Dispatchers.IO) {
                    api.analyzeMeal(part)
                }
                currentFilename = file.name
                showResult(result)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showResult(response: MealAnalysisResponse) {
        items.clear()
        items.addAll(response.items)
        binding.recyclerResults.adapter?.notifyDataSetChanged()
        val totalsText =
            "Total: ${response.total_calories_kcal} kcal | P ${response.total_protein_g} g " +
                "| C ${response.total_carbs_g} g | F ${response.total_fat_g} g"
        binding.textTotals.text = totalsText

        // Also mirror the latest photo into the results preview if we have one.
        binding.imageResult.setImageDrawable(binding.imagePreview.drawable)
        switchToResults()

        // Removed textSessionSummary and textPipelineInfo - info is now in the totals display
    }

    private fun showConsentDialogIfNeeded() {
        val prefs = getSharedPreferences("foodlogger_prefs", MODE_PRIVATE)
        val accepted = prefs.getBoolean("consent_accepted", false)
        if (accepted) return

        if (!isFinishing && !isDestroyed) {
            currentDialog?.dismiss()
            currentDialog = AlertDialog.Builder(this)
                .setTitle("Privacy & Consent")
                .setMessage(
                    "This demo captures meal photos and sends them to a cloud service " +
                        "over HTTPS for calorie estimation.\n\n" +
                        "Photos are only used for this demonstration and not stored long term. " +
                        "Do not capture faces or personal information."
                )
                .setPositiveButton("I Agree") { _, _ ->
                    prefs.edit().putBoolean("consent_accepted", true).apply()
                }
                .setNegativeButton("Exit") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentDialog?.dismiss()
        currentDialog = null
        job.cancel()
    }

    private fun exportCurrentScreenPng(name: String) {
        val root = binding.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        root.draw(canvas)

        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        if (dir != null) {
            val file = File(dir, "${name}_${System.currentTimeMillis()}.png")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(this, "Exported PNG to ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Unable to access storage for export.", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteSelectedItems() {
        val adapter = binding.recyclerResults.adapter as? ResultsAdapter ?: return
        adapter.deleteSelected()
        updateTotalsFromItems()
    }

    private fun logCurrentMeal() {
        if (items.isEmpty()) {
            Toast.makeText(this, "No items to log", Toast.LENGTH_SHORT).show()
            return
        }

        uiScope.launch {
            try {
                // Calculate totals from current items
                var kcal = 0.0
                var protein = 0.0
                var carbs = 0.0
                var fat = 0.0
                items.forEach {
                    kcal += it.estimated_calories_kcal
                    protein += it.estimated_protein_g
                    carbs += it.estimated_carbs_g
                    fat += it.estimated_fat_g
                }

                val request = LogMealRequest(
                    total_calories_kcal = kcal,
                    total_protein_g = protein,
                    total_carbs_g = carbs,
                    total_fat_g = fat,
                    source_filename = currentFilename,
                    items = items
                )

                val response = withContext(Dispatchers.IO) {
                    api.logMeal(request)
                }

                Toast.makeText(
                    this@MainActivity,
                    "✓ Meal logged successfully! (ID: ${response.meal_id})",
                    Toast.LENGTH_LONG
                ).show()

                // Clear the current meal
                items.clear()
                currentFilename = null
                binding.recyclerResults.adapter?.notifyDataSetChanged()
                
                // Switch back to capture to take another photo
                switchToCapture()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Error logging meal: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun discardCurrentMeal() {
        if (isFinishing || isDestroyed) return
        
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Discard Meal?")
            .setMessage("Are you sure you want to discard this analysis? This cannot be undone.")
            .setPositiveButton("Discard") { _, _ ->
                items.clear()
                currentFilename = null
                binding.recyclerResults.adapter?.notifyDataSetChanged()
                Toast.makeText(this, "Meal discarded", Toast.LENGTH_SHORT).show()
                switchToCapture()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateTotalsFromItems() {
        var kcal = 0.0
        var protein = 0.0
        var carbs = 0.0
        var fat = 0.0
        items.forEach {
            kcal += it.estimated_calories_kcal
            protein += it.estimated_protein_g
            carbs += it.estimated_carbs_g
            fat += it.estimated_fat_g
        }
        binding.textTotals.text =
            "🔥 ${"%.0f".format(kcal)} kcal\n" +
            "💪 Protein: ${"%.1f".format(protein)} g\n" +
            "🍞 Carbs: ${"%.1f".format(carbs)} g\n" +
            "🥑 Fat: ${"%.1f".format(fat)} g"
    }

    private fun showEditItemDialog(index: Int?) {
        if (isFinishing || isDestroyed) return
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_item, null)
        val editItemName: EditText = dialogView.findViewById(R.id.editItemName)
        val editWeight: EditText = dialogView.findViewById(R.id.editWeight)
        val editCalories: EditText = dialogView.findViewById(R.id.editCalories)
        val editProtein: EditText = dialogView.findViewById(R.id.editProtein)
        val editCarbs: EditText = dialogView.findViewById(R.id.editCarbs)
        val editFat: EditText = dialogView.findViewById(R.id.editFat)

        if (index != null) {
            val item = items[index]
            editItemName.setText(item.name)
            editWeight.setText(item.estimated_weight_g.toString())
            editCalories.setText(item.estimated_calories_kcal.toString())
            editProtein.setText(item.estimated_protein_g.toString())
            editCarbs.setText(item.estimated_carbs_g.toString())
            editFat.setText(item.estimated_fat_g.toString())
        }

        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
            .setTitle(if (index == null) "Add item" else "Edit item")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val label = editItemName.text.toString().ifBlank { "Custom item" }
                val weight = editWeight.text.toString().toDoubleOrNull() ?: 0.0
                val kcal = editCalories.text.toString().toDoubleOrNull() ?: 0.0
                val protein = editProtein.text.toString().toDoubleOrNull() ?: 0.0
                val carbs = editCarbs.text.toString().toDoubleOrNull() ?: 0.0
                val fat = editFat.text.toString().toDoubleOrNull() ?: 0.0

                val base =
                    if (index != null) items[index] else MealItem(
                        name = label,
                        code = "custom",
                        estimated_volume_ml = 0.0,
                        estimated_weight_g = weight,
                        estimated_calories_kcal = kcal,
                        estimated_protein_g = protein,
                        estimated_carbs_g = carbs,
                        estimated_fat_g = fat,
                        confidence = 1.0
                    )

                val updated = base.copy(
                    name = label,
                    estimated_weight_g = weight,
                    estimated_calories_kcal = kcal,
                    estimated_protein_g = protein,
                    estimated_carbs_g = carbs,
                    estimated_fat_g = fat
                )

                if (index != null) {
                    items[index] = updated
                } else {
                    items.add(updated)
                }
                binding.recyclerResults.adapter?.notifyDataSetChanged()
                updateTotalsFromItems()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class ResultsAdapter(
        private val items: MutableList<MealItem>,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {

        private val selected = mutableSetOf<Int>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_result_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.textItemName.text = item.name
            holder.textWeight.text = "${"%.1f".format(item.estimated_weight_g)} g"
            holder.textCalories.text = "${"%.0f".format(item.estimated_calories_kcal)} kcal"
            holder.textProtein.text = "${"%.0f".format(item.estimated_protein_g)}g"
            holder.textCarbs.text = "${"%.0f".format(item.estimated_carbs_g)}g"
            holder.textFat.text = "${"%.0f".format(item.estimated_fat_g)}g"
            holder.checkboxSelect.isChecked = selected.contains(position)

            holder.itemView.setOnClickListener {
                onItemClick(position)
            }
            holder.checkboxSelect.setOnCheckedChangeListener(null)
            holder.checkboxSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected.add(position) else selected.remove(position)
            }
        }

        override fun getItemCount(): Int = items.size

        fun deleteSelected() {
            val toRemove = selected.sortedDescending()
            toRemove.forEach { idx ->
                if (idx in items.indices) {
                    items.removeAt(idx)
                }
            }
            selected.clear()
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkboxSelect: CheckBox = view.findViewById(R.id.checkboxSelect)
            val textItemName: TextView = view.findViewById(R.id.textItemName)
            val textWeight: TextView = view.findViewById(R.id.textWeight)
            val textCalories: TextView = view.findViewById(R.id.textCalories)
            val textProtein: TextView = view.findViewById(R.id.textProtein)
            val textCarbs: TextView = view.findViewById(R.id.textCarbs)
            val textFat: TextView = view.findViewById(R.id.textFat)
        }
    }
}


