# FoodLogger – Visual Balanced Meal (VBM) System

**Course:** SEG 4105  
**Client:** Digital Health Inc
**Date:** November 20, 2025
---

## Overview

FoodLogger is a smartphone-based Visual Balanced Meal (VBM) system that demonstrates a complete workflow for logging meals from images. The system consists of:

1. **Android Mobile App** – Captures meal photos, uploads to backend, displays analysis results and history
2. **Python Backend (FastAPI)** – Processes images with a baseline ML pipeline (mocked), stores meal logs in SQLite, provides REST API

This Proof of Concept (PoC) demonstrates:
- ✅ Obtaining a photo from a smartphone's camera
- ✅ Uploading the photo to a web service
- ✅ Processing the photo with **real machine learning** (Vision Transformer trained on Food101 dataset)
- ✅ Sending structured data back to the smartphone
- ✅ Storing meal logs in a database
- ✅ Displaying daily summaries and 7-day history
---

## Features

### Android App

#### 1. **Login Screen**
- Email/password fields (demo authentication)
- "Sign in" button (fake auth, no backend validation)
- "Export PNG (Login)" button to save screenshot

#### 2. **Capture Screen**
- Camera capture button (uses device camera)
- Import from gallery button
- Image preview
- "Analyze Meal" button to upload image to backend
- "Export PNG (Capture)" button to save screenshot

#### 3. **Results Screen**
- Displays meal analysis results in an editable table:
  - Food item label
  - Estimated weight (g)
  - Calories (kcal)
  - Protein (g)
  - Carbs (g)
  - Fat (g)
- **Toggle Edit** button to enable/disable editing
- **Add Item** button to manually add food items
- **Delete Selected** button to remove checked items
- Live-updating totals bar at the bottom
- "Export PNG (Results)" button to save screenshot

#### 4. **History Screen** (NEW!)
- 7-day table view with columns: Date / Meals / Kcal / P / C / F
- "Earlier" and "Later" buttons to navigate through weeks
- "Export PNG (History)" button to save screenshot
- Top navigation tabs: History / Daily / Scan / Analysis / Sign out

### Backend API

#### Endpoints

1. **`GET /health`**
   - Health check endpoint
   - Returns: `{ "status": "ok" }`

2. **`POST /analyze-meal`**
   - Accepts: `multipart/form-data` with `image` field
   - Processes image with mocked ML pipeline
   - Persists meal log to database
   - Returns:
     ```json
     {
       "items": [
         {
           "label": "grilled_chicken_breast",
           "estimated_volume_ml": 150.0,
           "estimated_weight_g": 157.5,
           "estimated_calories_kcal": 260.3,
           "estimated_protein_g": 48.8,
           "estimated_carbs_g": 0.0,
           "estimated_fat_g": 5.7,
           "confidence": 0.85
         },
         ...
       ],
       "total_calories_kcal": 850.5,
       "total_protein_g": 68.9,
       "total_carbs_g": 95.2,
       "total_fat_g": 10.8,
       "pipeline_version": "baseline-v0.1.0",
       "timestamp": "2025-11-20T12:34:56.789Z"
     }
     ```

3. **`GET /logs/summary/day?day=YYYY-MM-DD`**
   - Returns daily summary for a specific date
   - Returns:
     ```json
     {
       "days": 1,
       "total_calories_kcal": 1850.5,
       "total_protein_g": 120.3,
       "total_carbs_g": 200.1,
       "total_fat_g": 45.2
     }
     ```

4. **`GET /logs/summary/rolling?days=7`**
   - Returns rolling summary for the last N days
   - Returns same format as `/logs/summary/day`

5. **`GET /logs?limit=50`**
   - Returns recent meal logs (most recent first)
   - Returns:
     ```json
     {
       "items": [
         {
           "id": 1,
           "created_at": "2025-11-20T12:34:56.789Z",
           "total_calories_kcal": 850.5,
           "total_protein_g": 68.9,
           "total_carbs_g": 95.2,
           "total_fat_g": 10.8,
           "source_filename": "meal_photo.jpg"
         },
         ...
       ]
     }
     ```

6. **`GET /logs/history?start=YYYY-MM-DD&days=7`** (NEW!)
   - Returns per-day history rows for [start, start+days)
   - Returns:
     ```json
     {
       "history": [
         {
           "date": "2025-11-14",
           "meals": 3,
           "kcal": 1850,
           "protein_g": 120,
           "carbs_g": 200,
           "fat_g": 45
         },
         ...
       ]
     }
     ```

####  Real ML Pipeline (NEW!)

The system now uses **real machine learning** for food detection:

1. **ML Model**: Vision Transformer (ViT) trained on Food101 dataset
   - Model: `nateraw/food` from Hugging Face
   - 101 food categories supported
   - Real confidence scores from the model

2. **Detection Stage**: 
   - Image is preprocessed and fed to the neural network
   - Model outputs top-3 predictions with confidence scores
   - Supports proteins (chicken, steak, salmon), carbs (rice, pasta, bread), vegetables (broccoli, salad), and more

3. **Nutrition Estimation**: 
   - Detected foods are matched to USDA nutrition database
   - Serving sizes estimated based on typical portions
   - Calculates calories, protein, carbs, and fat

4. **Fallback Mode**: 
   - If ML libraries aren't installed, automatically falls back to mock detection
   - Seamless degradation ensures the app always works

**See `backend/ML_INTEGRATION.md` for detailed documentation.**

#### Database

- **SQLite** database stored in `backend/data/foodlogs.db`
- **MealLog** table:
  - `id` (primary key)
  - `created_at` (datetime, indexed)
  - `total_calories_kcal` (float)
  - `total_protein_g` (float)
  - `total_carbs_g` (float)
  - `total_fat_g` (float)
  - `source_filename` (string, nullable)

---

## Setup & Installation

### Backend Setup

1. **Install Python 3.12+**

2. **Install dependencies and run:**
   ```bash
   cd backend
   pip install -r requirements.txt
   python run_dev.py
   ```
   
   **Note**: First run downloads ML model (~350MB). The app uses real AI for food detection.
   Server starts at `http://0.0.0.0:8000`

### Android Setup

1. **Install Android Studio** (latest version)

2. **Open the `android` directory in Android Studio**

3. **Sync Gradle** (Android Studio will prompt you)

4. **Run the app:**
   - Connect an Android device or start an emulator
   - Click "Run" (green play button)
   - The app will install and launch

5. **Backend Connection:**
   - The app is configured to connect to `http://10.0.2.2:8000` (Android emulator localhost)
   - If using a physical device, update `BASE_URL` in `MainActivity.kt` and `HistoryActivity.kt` to your computer's local IP address (e.g., `http://192.168.1.100:8000`)

---

## Usage

### 1. Start Backend Server

```bash
cd backend
python run_dev.py
```

### 2. Launch Android App

- Open Android Studio
- Run the app on an emulator or physical device

### 3. Demo Workflow

1. **Login:**
   - Enter any email/password (demo auth, not validated)
   - Click "Sign in"
   - You'll be redirected to the Capture screen

2. **Capture Meal:**
   - Click "Capture Photo" to take a picture with the camera
   - OR click "Import from Gallery" to select an existing image
   - Click "Analyze Meal" to upload to backend
   - Wait for analysis results

3. **View Results:**
   - Switch to "Results" tab
   - See the meal breakdown table with food items and macros
   - Click "Toggle Edit" to enable editing
   - Click "Add Item" to manually add food items
   - Check items and click "Delete Selected" to remove them
   - Totals update automatically

4. **View History:**
   - Click "History" button in the top-right
   - See 7-day table with Date / Meals / Kcal / P / C / F
   - Click "Earlier" or "Later" to navigate through weeks
   - Click "Export PNG (History)" to save a screenshot

5. **Export Screenshots:**
   - Any screen can be exported as PNG
   - Screenshots are saved to device storage
   - Toast notification shows the file path

---

## Technical Details

### Android

- **Language:** Kotlin
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)
- **Architecture:** Single Activity with tab-based navigation
- **Networking:** Retrofit 2 + OkHttp 3 + Gson
- **UI:** Material Design Components, ConstraintLayout, RecyclerView
- **Coroutines:** Kotlin Coroutines for async operations
- **Permissions:**
  - `CAMERA` – for capturing meal photos
  - `INTERNET` – for API communication
  - `WRITE_EXTERNAL_STORAGE` (SDK ≤28) – for exporting screenshots

### Backend

- **Language:** Python 3.12+
- **Framework:** FastAPI 0.111.0
- **Server:** Uvicorn (ASGI)
- **Database:** SQLite + SQLAlchemy 2.0.31
- **CORS:** Enabled for local development (all origins allowed)
- **Logging:** HTTP request/response logging via OkHttp interceptor

---

## Project Charter Compliance

This PoC demonstrates all requirements from the Project Charter:

✅ **Capture meal photos from smartphone camera**  
✅ **Upload photos to a web service**  
✅ **Process photos with a baseline ML pipeline** (mocked detection, volume estimation, calorie calculation)  
✅ **Send structured data back to smartphone**  
✅ **Display results in a user-friendly interface**  
✅ **Store meal logs in a database**  
✅ **Provide daily and rolling summaries**  
✅ **7-day history view with navigation**  
✅ **Export screenshots as PNG**  
✅ **Works on Android devices**

---

## Future Enhancements

### Completed ✅
- ✅ **Real ML model for food recognition** – Now using Vision Transformer (Food101)

### Planned for Future
- Object detection with bounding boxes (YOLO) for multiple items
- Improved volume estimation using depth sensors or reference objects
- Fine-tuning ML model on custom food dataset
- User authentication and personalized profiles
- Cloud deployment (AWS, GCP, Azure)
- iOS app
- App Store / Play Store release
- Advanced analytics and insights
- Meal planning and recommendations
- Integration with fitness trackers

---

## Team

- **Project Manager:** Kyro
- **ML Lead:** Reyaan
- **Mobile Lead:** Jessica
- **Backend Lead:** Nicholas

