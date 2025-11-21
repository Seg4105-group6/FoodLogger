package com.example.foodlogger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.foodlogger.databinding.ActivityDayDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.*

private const val BASE_URL = "http://10.0.2.2:8000/"

data class MealItemDetail(
    val label: String,
    val weight_g: Double,
    val calories_kcal: Double,
    val protein_g: Double,
    val carbs_g: Double,
    val fat_g: Double
)

data class MealLogItem(
    val id: Int,
    val created_at: String,
    val total_calories_kcal: Double,
    val total_protein_g: Double,
    val total_carbs_g: Double,
    val total_fat_g: Double,
    val source_filename: String?,
    val items: List<MealItemDetail>?
)

data class MealsResponse(
    val items: List<MealLogItem>
)

data class UpdateMealRequest(
    val total_calories_kcal: Double,
    val total_protein_g: Double,
    val total_carbs_g: Double,
    val total_fat_g: Double
)

interface DayDetailApiService {
    @GET("logs")
    suspend fun getLogs(@Query("limit") limit: Int = 100): MealsResponse
    
    @PUT("logs/{id}")
    suspend fun updateMeal(@Path("id") id: Int, @Body request: UpdateMealRequest): MealLogItem
    
    @DELETE("logs/{id}")
    suspend fun deleteMeal(@Path("id") id: Int)
}

class DayDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDayDetailBinding
    private val job = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + job)

    private val meals = mutableListOf<MealLogItem>()
    private lateinit var selectedDate: String
    private var currentDialog: AlertDialog? = null

    private val api: DayDetailApiService by lazy {
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
            .create(DayDetailApiService::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDayDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedDate = intent.getStringExtra("DATE") ?: ""
        binding.textSelectedDate.text = selectedDate

        binding.recyclerMeals.layoutManager = LinearLayoutManager(this)
        binding.recyclerMeals.adapter = MealAdapter(meals) { meal ->
            showEditMealDialog(meal)
        }

        binding.buttonBack.setOnClickListener {
            finish()
        }

        loadDayDetails()
    }

    private fun loadDayDetails() {
        uiScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.getLogs(limit = 100)
                }

                if (isFinishing || isDestroyed) return@launch

                // Filter meals for the selected date
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val mealsForDay = response.items.filter { meal ->
                    try {
                        // Parse the ISO timestamp and extract the date
                        val mealDate = meal.created_at.substring(0, 10) // Get YYYY-MM-DD part
                        mealDate == selectedDate
                    } catch (e: Exception) {
                        false
                    }
                }

                meals.clear()
                meals.addAll(mealsForDay)
                binding.recyclerMeals.adapter?.notifyDataSetChanged()

                // Update summary
                if (meals.isEmpty()) {
                    // No empty state view in new design, just show 0 values
                    updateSummary(0.0, 0.0, 0.0, 0.0)
                } else {
                    
                    val totalCalories = meals.sumOf { it.total_calories_kcal }
                    val totalProtein = meals.sumOf { it.total_protein_g }
                    val totalCarbs = meals.sumOf { it.total_carbs_g }
                    val totalFat = meals.sumOf { it.total_fat_g }
                    
                    updateSummary(totalCalories, totalProtein, totalCarbs, totalFat)
                }

            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@DayDetailActivity,
                        "Error loading meals: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateSummary(calories: Double, protein: Double, carbs: Double, fat: Double) {
        binding.textDayCalories.text = calories.toInt().toString()
        binding.textDayProtein.text = protein.toInt().toString()
        binding.textDayCarbs.text = carbs.toInt().toString()
        binding.textDayFat.text = fat.toInt().toString()
    }

    private fun showEditMealDialog(meal: MealLogItem) {
        if (isFinishing || isDestroyed) return
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_meal, null)
        val editCalories: EditText = dialogView.findViewById(R.id.editMealCalories)
        val editProtein: EditText = dialogView.findViewById(R.id.editMealProtein)
        val editCarbs: EditText = dialogView.findViewById(R.id.editMealCarbs)
        val editFat: EditText = dialogView.findViewById(R.id.editMealFat)

        editCalories.setText(meal.total_calories_kcal.toInt().toString())
        editProtein.setText(meal.total_protein_g.toInt().toString())
        editCarbs.setText(meal.total_carbs_g.toInt().toString())
        editFat.setText(meal.total_fat_g.toInt().toString())

        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Edit Meal")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val calories = editCalories.text.toString().toDoubleOrNull() ?: meal.total_calories_kcal
                val protein = editProtein.text.toString().toDoubleOrNull() ?: meal.total_protein_g
                val carbs = editCarbs.text.toString().toDoubleOrNull() ?: meal.total_carbs_g
                val fat = editFat.text.toString().toDoubleOrNull() ?: meal.total_fat_g
                
                updateMeal(meal.id, calories, protein, carbs, fat)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                confirmDeleteMeal(meal.id)
            }
            .show()
    }

    private fun confirmDeleteMeal(mealId: Int) {
        if (isFinishing || isDestroyed) return
        
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Delete Meal")
            .setMessage("Are you sure you want to delete this meal?")
            .setPositiveButton("Delete") { _, _ ->
                deleteMeal(mealId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateMeal(id: Int, calories: Double, protein: Double, carbs: Double, fat: Double) {
        uiScope.launch {
            try {
                val request = UpdateMealRequest(calories, protein, carbs, fat)
                withContext(Dispatchers.IO) {
                    api.updateMeal(id, request)
                }
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@DayDetailActivity, "Meal updated", Toast.LENGTH_SHORT).show()
                    loadDayDetails() // Refresh
                }
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@DayDetailActivity,
                        "Error updating meal: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun deleteMeal(id: Int) {
        uiScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    api.deleteMeal(id)
                }
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@DayDetailActivity, "Meal deleted", Toast.LENGTH_SHORT).show()
                    loadDayDetails() // Refresh
                }
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@DayDetailActivity,
                        "Error deleting meal: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentDialog?.dismiss()
        currentDialog = null
        job.cancel()
    }

    private class MealAdapter(
        private val meals: List<MealLogItem>,
        private val onMealClick: (MealLogItem) -> Unit
    ) : RecyclerView.Adapter<MealAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_meal_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val meal = meals[position]
            
            // Parse timestamp and format time
            try {
                val timestamp = meal.created_at.replace("Z", "+00:00")
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                inputFormat.timeZone = TimeZone.getTimeZone("UTC")
                val date = inputFormat.parse(timestamp)
                
                val outputFormat = SimpleDateFormat("h:mm a", Locale.US)
                holder.textMealTime.text = if (date != null) outputFormat.format(date) else "Unknown time"
            } catch (e: Exception) {
                holder.textMealTime.text = "Unknown time"
            }

            // Display meal items
            val items = meal.items
            if (items != null && items.isNotEmpty()) {
                val itemNames = items.joinToString(", ") { it.label }
                holder.textMealItems.text = itemNames
                holder.textMealItems.visibility = View.VISIBLE
            } else {
                holder.textMealItems.visibility = View.GONE
            }

            holder.textMealCalories.text = meal.total_calories_kcal.toInt().toString()
            holder.textMealProtein.text = "${meal.total_protein_g.toInt()}g"
            holder.textMealCarbs.text = "${meal.total_carbs_g.toInt()}g"
            holder.textMealFat.text = "${meal.total_fat_g.toInt()}g"
            
            holder.itemView.setOnClickListener {
                onMealClick(meal)
            }
        }

        override fun getItemCount(): Int = meals.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textMealTime: TextView = view.findViewById(R.id.textMealTime)
            val textMealItems: TextView = view.findViewById(R.id.textMealItems)
            val textMealCalories: TextView = view.findViewById(R.id.textMealCalories)
            val textMealProtein: TextView = view.findViewById(R.id.textMealProtein)
            val textMealCarbs: TextView = view.findViewById(R.id.textMealCarbs)
            val textMealFat: TextView = view.findViewById(R.id.textMealFat)
        }
    }
}

