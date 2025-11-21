package com.example.foodlogger

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.foodlogger.databinding.ActivityHistoryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val BASE_URL = "http://18.219.234.57:8000/"

data class HistoryRow(
    val date: String,
    val meals: Int,
    val kcal: Double,
    val protein_g: Double,
    val carbs_g: Double,
    val fat_g: Double
)

data class HistoryResponse(
    val history: List<HistoryRow>
)

interface HistoryApiService {
    @GET("logs/history")
    suspend fun getHistory(
        @Query("start") start: String,
        @Query("days") days: Int
    ): HistoryResponse
}

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val job = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + job)

    private val historyRows = mutableListOf<HistoryRow>()
    private var currentStartDate: LocalDate = LocalDate.now().minusDays(6)

    private val api: HistoryApiService by lazy {
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
            .create(HistoryApiService::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = HistoryAdapter(historyRows) { date ->
            val intent = Intent(this, DayDetailActivity::class.java)
            intent.putExtra("DATE", date)
            startActivity(intent)
        }

        setupUi()
        loadHistory()
    }

    private fun setupUi() {
        binding.buttonBackToMain.setOnClickListener {
            finish() // Just go back to MainActivity
        }

        binding.buttonEarlier.setOnClickListener {
            currentStartDate = currentStartDate.minusDays(7)
            loadHistory()
        }

        binding.buttonLater.setOnClickListener {
            currentStartDate = currentStartDate.plusDays(7)
            loadHistory()
        }

        binding.buttonExportHistory.setOnClickListener {
            exportCurrentScreenPng("history_screen")
        }
    }

    private fun loadHistory() {
        uiScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.getHistory(
                        start = currentStartDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        days = 7
                    )
                }
                
                if (isFinishing || isDestroyed) return@launch
                
                historyRows.clear()
                historyRows.addAll(response.history)
                binding.recyclerHistory.adapter?.notifyDataSetChanged()
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@HistoryActivity,
                        "Error loading history: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private class HistoryAdapter(
        private val rows: List<HistoryRow>,
        private val onItemClick: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = rows[position]
            holder.textDate.text = row.date
            holder.textMeals.text = row.meals.toString()
            holder.textKcal.text = "%.1f".format(row.kcal)
            holder.textProtein.text = "%.1f".format(row.protein_g)
            holder.textCarbs.text = "%.1f".format(row.carbs_g)
            holder.textFat.text = "%.1f".format(row.fat_g)
            
            holder.itemView.setOnClickListener {
                onItemClick(row.date)
            }
        }

        override fun getItemCount(): Int = rows.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textDate: TextView = view.findViewById(R.id.textDate)
            val textMeals: TextView = view.findViewById(R.id.textMeals)
            val textKcal: TextView = view.findViewById(R.id.textKcal)
            val textProtein: TextView = view.findViewById(R.id.textProtein)
            val textCarbs: TextView = view.findViewById(R.id.textCarbs)
            val textFat: TextView = view.findViewById(R.id.textFat)
        }
    }
}

