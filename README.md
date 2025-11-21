# FoodLogger

## Download

```bash
git clone https://github.com/your-repo/FoodLogger.git
cd FoodLogger
```

## Backend Setup

1. **Install dependencies:**
   ```bash
   cd backend
   pip install -r requirements.txt
   ```

2. **Run the server:**
   ```bash
   python run_dev.py
   ```
   Server starts at `http://0.0.0.0:8000`

## Android Setup

1. **Open in Android Studio:**
   - Launch Android Studio
   - Open the `android` directory

2. **Compile and run:**
   - Connect an Android device or start an emulator
   - Click the "Run" button (green play icon)
   - The app will compile, install, and launch automatically

3. **Configure backend connection:**
   - Update `BASE_URL` in `MainActivity.kt`, `HistoryActivity.kt`, and `DayDetailActivity.kt`:
     - For emulator: `http://10.0.2.2:8000`
     - For physical device: Your computer's local IP (e.g., `http://192.168.1.100:8000`)

## Requirements

- Python 3.12+
- Android Studio (latest version)
- Android device or emulator (API 26+)
