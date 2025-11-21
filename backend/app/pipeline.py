"""
ML pipeline for the Visual Balanced Meal system.

This pipeline integrates real machine learning models for food detection,
with fallback to mock data if models are unavailable.

Stages:
1. detect_food_items        -> ML-based object detection (Food101 model)
2. estimate_volumes         -> heuristic volume per item
3. convert_volume_to_weight -> density lookup
4. compute_calories         -> nutrition lookup

The goal is to demonstrate end-to-end feasibility with real ML capabilities.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Dict, Any
import random
import logging

from .ml_model import get_model, estimate_nutrition

logger = logging.getLogger(__name__)


@dataclass
class FoodItemDetection:
    label: str
    confidence: float


DENSITY_TABLE_G_PER_ML: Dict[str, float] = {
    "grilled_chicken_breast": 1.05,
    "steamed_rice": 0.85,
    "broccoli": 0.6,
    "mixed_salad": 0.3,
}

CALORIE_TABLE_KCAL_PER_100G: Dict[str, float] = {
    "grilled_chicken_breast": 165.0,
    "steamed_rice": 130.0,
    "broccoli": 55.0,
    "mixed_salad": 35.0,
}

DISPLAY_NAMES: Dict[str, str] = {
    "grilled_chicken_breast": "Grilled chicken breast",
    "steamed_rice": "Steamed rice",
    "broccoli": "Broccoli",
    "mixed_salad": "Mixed salad",
}

# Very rough macros per 100 g for demo purposes.
MACROS_TABLE_PER_100G: Dict[str, Dict[str, float]] = {
    "grilled_chicken_breast": {"protein": 31.0, "carbs": 0.0, "fat": 3.6},
    "steamed_rice": {"protein": 2.7, "carbs": 28.0, "fat": 0.3},
    "broccoli": {"protein": 3.7, "carbs": 7.0, "fat": 0.4},
    "mixed_salad": {"protein": 1.5, "carbs": 3.0, "fat": 0.2},
}


def mock_detect_food_items() -> List[FoodItemDetection]:
    """
    Pretend to run an ML detector and return 2–3 plausible items.
    """
    candidates = [
        FoodItemDetection("grilled_chicken_breast", 0.78),
        FoodItemDetection("steamed_rice", 0.72),
        FoodItemDetection("broccoli", 0.68),
        FoodItemDetection("mixed_salad", 0.65),
    ]
    random.shuffle(candidates)
    # Choose between 2 and 3 items to keep response interesting but simple.
    n = random.randint(2, 3)
    return candidates[:n]


def estimate_volumes_ml(detections: List[FoodItemDetection]) -> Dict[str, float]:
    """
    Assign a rough volume per detection. In a real system this would use
    image geometry and auto-calibration; here we assign heuristic volumes.
    """
    base_volumes = {
        "grilled_chicken_breast": 120.0,
        "steamed_rice": 180.0,
        "broccoli": 90.0,
        "mixed_salad": 150.0,
    }
    volumes: Dict[str, float] = {}
    for d in detections:
        v = base_volumes.get(d.label, 100.0)
        # +/- 10% jitter to avoid identical numbers every time
        jitter = random.uniform(0.9, 1.1)
        volumes[d.label] = round(v * jitter, 1)
    return volumes


def convert_volume_to_weight_g(volumes_ml: Dict[str, float]) -> Dict[str, float]:
    weights: Dict[str, float] = {}
    for label, vol_ml in volumes_ml.items():
        density = DENSITY_TABLE_G_PER_ML.get(label, 1.0)
        weights[label] = round(vol_ml * density, 1)
    return weights


def compute_calories_kcal(weights_g: Dict[str, float]) -> Dict[str, float]:
    calories: Dict[str, float] = {}
    for label, weight_g in weights_g.items():
        per_100 = CALORIE_TABLE_KCAL_PER_100G.get(label, 100.0)
        calories[label] = round(weight_g * per_100 / 100.0, 1)
    return calories


def compute_macros_grams(weights_g: Dict[str, float]) -> Dict[str, Dict[str, float]]:
    """
    Compute protein / carbs / fat grams per item, based on weight
    and a simple per-100 g lookup table.
    """
    result: Dict[str, Dict[str, float]] = {}
    for label, weight_g in weights_g.items():
        per100 = MACROS_TABLE_PER_100G.get(
            label, {"protein": 5.0, "carbs": 10.0, "fat": 2.0}
        )
        factor = weight_g / 100.0
        result[label] = {
            "protein_g": round(per100["protein"] * factor, 1),
            "carbs_g": round(per100["carbs"] * factor, 1),
            "fat_g": round(per100["fat"] * factor, 1),
        }
    return result


def run_baseline_pipeline(image_bytes: bytes, filename: str | None = None) -> Dict[str, Any]:
    """
    Entry point for the ML-powered food detection pipeline.

    Uses a real pre-trained Food101 model for food recognition,
    with fallback to mock data if the model is unavailable.
    
    Args:
        image_bytes: Raw image data from the uploaded photo
        filename: Optional filename for tracking
        
    Returns:
        Complete meal analysis with detected items and nutrition totals
    """
    try:
        logger.info("Starting food detection pipeline")
        
        # Get the ML model instance
        model = get_model()
        
        # Detect food items using ML model
        detected_items = model.detect_food_items(image_bytes, top_k=3)
        logger.info(f"Detected {len(detected_items)} food items")
        
        # Estimate nutrition for detected items
        nutrition_result = estimate_nutrition(detected_items)
        
        # Add pipeline metadata
        result = {
            **nutrition_result,
            "pipeline": {
                "version": "ml-powered-1.0",
                "model": "nateraw/food (Food101)",
                "notes": "Real ML-based food recognition using Vision Transformer trained on Food101 dataset.",
                "capabilities": {
                    "food_detection": "Pre-trained on 101 food categories",
                    "confidence_scoring": "Model provides confidence scores for predictions",
                    "multi_item_detection": "Can detect multiple food items in a single image",
                },
                "assumptions": {
                    "serving_size": "Estimated based on typical portions for detected foods",
                    "nutrition_database": "Uses USDA nutritional data for common foods",
                    "visible_only": "Only visually detectable ingredients considered",
                },
            },
            "source": {"filename": filename},
        }
        
        logger.info(f"Pipeline complete: {len(result['items'])} items, {result['total_calories_kcal']} kcal")
        return result
        
    except Exception as e:
        logger.error(f"Error in pipeline: {e}")
        # Fallback to mock data if anything goes wrong
        logger.warning("Falling back to mock detection")
        return run_fallback_pipeline(filename)


def run_fallback_pipeline(filename: str | None = None) -> Dict[str, Any]:
    """
    Fallback pipeline using mock detection when ML model is unavailable.
    Maintains the same output format as the ML pipeline.
    """
    detections = mock_detect_food_items()
    volumes = estimate_volumes_ml(detections)
    weights = convert_volume_to_weight_g(volumes)
    calories = compute_calories_kcal(weights)
    macros = compute_macros_grams(weights)

    items: List[Dict[str, Any]] = []
    total_calories = 0.0
    total_protein = 0.0
    total_carbs = 0.0
    total_fat = 0.0

    for det in detections:
        vol_ml = volumes[det.label]
        w_g = weights[det.label]
        kcal = calories[det.label]
        macro = macros[det.label]

        total_calories += kcal
        total_protein += macro["protein_g"]
        total_carbs += macro["carbs_g"]
        total_fat += macro["fat_g"]

        items.append(
            {
                "name": DISPLAY_NAMES.get(det.label, det.label),
                "code": det.label,
                "estimated_volume_ml": vol_ml,
                "estimated_weight_g": w_g,
                "estimated_calories_kcal": kcal,
                "estimated_protein_g": macro["protein_g"],
                "estimated_carbs_g": macro["carbs_g"],
                "estimated_fat_g": macro["fat_g"],
                "confidence": round(det.confidence, 2),
            }
        )

    return {
        "items": items,
        "total_calories_kcal": round(total_calories, 1),
        "total_protein_g": round(total_protein, 1),
        "total_carbs_g": round(total_carbs, 1),
        "total_fat_g": round(total_fat, 1),
        "pipeline": {
            "version": "fallback-mock-0.1",
            "notes": "Fallback mock pipeline - ML model not available.",
        },
        "source": {"filename": filename},
    }


