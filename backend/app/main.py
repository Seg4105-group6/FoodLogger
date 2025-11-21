from datetime import date

from fastapi import FastAPI, File, UploadFile, HTTPException, Query, Body
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .pipeline import run_baseline_pipeline
from .db import (
    create_meal_log_from_result,
    daily_summary,
    rolling_summary,
    list_logs,
    history_by_day,
    update_meal_log,
    delete_meal_log,
    get_meal_log,
)

app = FastAPI(
    title="FoodLogger VBM Backend",
    description="Baseline pipeline for visual balanced meal proof-of-concept.",
    version="0.1.0",
)

# CORS: allow local Android emulator / device during development.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # tighten in production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class UpdateMealRequest(BaseModel):
    total_calories_kcal: float
    total_protein_g: float
    total_carbs_g: float
    total_fat_g: float


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/analyze-meal")
async def analyze_meal(image: UploadFile = File(...)) -> JSONResponse:
    """
    Accepts a meal photo, runs a mocked ML pipeline, and returns
    a structured breakdown of items and calories.
    NOTE: This does NOT save to the database - user must explicitly log it.
    """
    if not image.content_type or not image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="Uploaded file must be an image")

    image_bytes = await image.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="Empty image uploaded")

    result = run_baseline_pipeline(image_bytes=image_bytes, filename=image.filename)

    return JSONResponse(content=result)


class LogMealRequest(BaseModel):
    total_calories_kcal: float
    total_protein_g: float
    total_carbs_g: float
    total_fat_g: float
    source_filename: str | None = None
    items: list[dict] = []


@app.post("/log-meal")
async def log_meal(request: LogMealRequest):
    """
    Explicitly log a meal to the database with its items.
    This is called when the user confirms they want to save the meal.
    """
    result = {
        "total_calories_kcal": request.total_calories_kcal,
        "total_protein_g": request.total_protein_g,
        "total_carbs_g": request.total_carbs_g,
        "total_fat_g": request.total_fat_g,
        "items": request.items,
    }
    
    meal_id = create_meal_log_from_result(result, filename=request.source_filename)
    
    return {
        "status": "logged",
        "meal_id": meal_id,
        "message": "Meal logged successfully"
    }


@app.get("/logs/summary/day")
async def get_daily_summary(day: date = Query(..., description="UTC date, e.g. 2025-11-20")):
    summary = daily_summary(day)
    return summary.to_dict()


@app.get("/logs/summary/rolling")
async def get_rolling_summary(
    days: int = Query(7, ge=1, le=30, description="Number of days to look back (default 7)")
):
    summary = rolling_summary(days=days)
    return summary.to_dict()


@app.get("/logs")
async def get_logs(limit: int = Query(50, ge=1, le=365)):
    """
    Return recent logged meals with their totals, most recent first.
    """
    return {"items": list_logs(limit=limit)}


@app.get("/logs/history")
async def get_history(
    start: str = Query(..., description="Start date YYYY-MM-DD"),
    days: int = Query(7, ge=1, le=30, description="Number of days"),
):
    """
    Return per-day history rows for [start, start+days).
    Each row: { date, meals, kcal, protein_g, carbs_g, fat_g }
    """
    try:
        start_date = date.fromisoformat(start)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid date format; use YYYY-MM-DD")

    rows = history_by_day(start_date=start_date, days=days)
    return {"history": [r.to_dict() for r in rows]}


@app.put("/logs/{log_id}")
async def update_meal(log_id: int, request: UpdateMealRequest):
    """
    Update a meal log's nutrition totals.
    """
    meal = get_meal_log(log_id)
    if not meal:
        raise HTTPException(status_code=404, detail="Meal log not found")
    
    update_meal_log(
        log_id=log_id,
        calories=request.total_calories_kcal,
        protein=request.total_protein_g,
        carbs=request.total_carbs_g,
        fat=request.total_fat_g,
    )
    
    # Return updated meal
    updated_meal = get_meal_log(log_id)
    return {
        "id": updated_meal["id"],
        "created_at": updated_meal["created_at"],
        "total_calories_kcal": updated_meal["total_calories_kcal"],
        "total_protein_g": updated_meal["total_protein_g"],
        "total_carbs_g": updated_meal["total_carbs_g"],
        "total_fat_g": updated_meal["total_fat_g"],
        "source_filename": updated_meal["source_filename"],
    }


@app.delete("/logs/{log_id}")
async def delete_meal(log_id: int):
    """
    Delete a meal log.
    """
    meal = get_meal_log(log_id)
    if not meal:
        raise HTTPException(status_code=404, detail="Meal log not found")
    
    delete_meal_log(log_id)
    return {"status": "deleted", "id": log_id}


