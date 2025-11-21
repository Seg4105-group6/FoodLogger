from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, date, timedelta
from pathlib import Path
from typing import Dict, Any, List

from sqlalchemy import (
    Column,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    create_engine,
    func,
)
from sqlalchemy.orm import declarative_base, Session, relationship


DB_PATH = Path(__file__).resolve().parent.parent / "data" / "foodlogs.db"
DB_PATH.parent.mkdir(parents=True, exist_ok=True)

engine = create_engine(f"sqlite:///{DB_PATH}", future=True, echo=False)
Base = declarative_base()


class MealLog(Base):
    __tablename__ = "meal_logs"

    id = Column(Integer, primary_key=True, autoincrement=True)
    created_at = Column(DateTime, nullable=False, index=True)
    total_calories_kcal = Column(Float, nullable=False)
    total_protein_g = Column(Float, nullable=False)
    total_carbs_g = Column(Float, nullable=False)
    total_fat_g = Column(Float, nullable=False)
    source_filename = Column(String, nullable=True)
    
    # Relationship to meal items
    items = relationship("MealItem", back_populates="meal", cascade="all, delete-orphan")


class MealItem(Base):
    __tablename__ = "meal_items"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    meal_id = Column(Integer, ForeignKey("meal_logs.id"), nullable=False)
    label = Column(String, nullable=False)
    weight_g = Column(Float, nullable=False)
    calories_kcal = Column(Float, nullable=False)
    protein_g = Column(Float, nullable=False)
    carbs_g = Column(Float, nullable=False)
    fat_g = Column(Float, nullable=False)
    
    # Relationship back to meal
    meal = relationship("MealLog", back_populates="items")


Base.metadata.create_all(bind=engine)


def create_meal_log_from_result(result: Dict[str, Any], filename: str | None) -> int:
    """
    Persist one pipeline result into the SQLite database with items.
    Returns the ID of the created meal log.
    """
    with Session(engine) as session:
        log = MealLog(
            created_at=datetime.now(),
            total_calories_kcal=float(result.get("total_calories_kcal", 0.0)),
            total_protein_g=float(result.get("total_protein_g", 0.0)),
            total_carbs_g=float(result.get("total_carbs_g", 0.0)),
            total_fat_g=float(result.get("total_fat_g", 0.0)),
            source_filename=filename,
        )
        session.add(log)
        session.flush()  # Get the ID
        
        # Add items if present
        items = result.get("items", [])
        for item_data in items:
            item = MealItem(
                meal_id=log.id,
                label=item_data.get("name", item_data.get("label", "Unknown")),
                weight_g=float(item_data.get("estimated_weight_g", 0.0)),
                calories_kcal=float(item_data.get("estimated_calories_kcal", 0.0)),
                protein_g=float(item_data.get("estimated_protein_g", 0.0)),
                carbs_g=float(item_data.get("estimated_carbs_g", 0.0)),
                fat_g=float(item_data.get("estimated_fat_g", 0.0)),
            )
            session.add(item)
        
        session.commit()
        return log.id


@dataclass
class Summary:
    days: int
    total_calories_kcal: float
    total_protein_g: float
    total_carbs_g: float
    total_fat_g: float

    def to_dict(self) -> Dict[str, Any]:
        return {
            "days": self.days,
            "total_calories_kcal": round(self.total_calories_kcal, 1),
            "total_protein_g": round(self.total_protein_g, 1),
            "total_carbs_g": round(self.total_carbs_g, 1),
            "total_fat_g": round(self.total_fat_g, 1),
        }


def _summary_between(start: datetime, end: datetime) -> Summary:
    with Session(engine) as session:
        row = (
            session.query(
                func.coalesce(func.sum(MealLog.total_calories_kcal), 0.0),
                func.coalesce(func.sum(MealLog.total_protein_g), 0.0),
                func.coalesce(func.sum(MealLog.total_carbs_g), 0.0),
                func.coalesce(func.sum(MealLog.total_fat_g), 0.0),
            )
            .filter(MealLog.created_at >= start, MealLog.created_at < end)
            .one()
        )

    calories, protein, carbs, fat = [float(x or 0.0) for x in row]
    days = (end.date() - start.date()).days
    return Summary(
        days=days,
        total_calories_kcal=calories,
        total_protein_g=protein,
        total_carbs_g=carbs,
        total_fat_g=fat,
    )


def daily_summary(target_date: date) -> Summary:
    start = datetime.combine(target_date, datetime.min.time())
    end = start + timedelta(days=1)
    return _summary_between(start, end)


def rolling_summary(days: int, until: date | None = None) -> Summary:
    if until is None:
        until = datetime.now().date()
    end = datetime.combine(until + timedelta(days=1), datetime.min.time())
    start = end - timedelta(days=days)
    return _summary_between(start, end)


def list_logs(limit: int = 50) -> List[Dict[str, Any]]:
    with Session(engine) as session:
        rows: List[MealLog] = (
            session.query(MealLog)
            .order_by(MealLog.created_at.desc())
            .limit(limit)
            .all()
        )
        
        result = []
        for row in rows:
            items = [
                {
                    "label": item.label,
                    "weight_g": item.weight_g,
                    "calories_kcal": item.calories_kcal,
                    "protein_g": item.protein_g,
                    "carbs_g": item.carbs_g,
                    "fat_g": item.fat_g,
                }
                for item in row.items
            ]
            result.append({
                "id": row.id,
                "created_at": row.created_at.isoformat() + "Z",
                "total_calories_kcal": row.total_calories_kcal,
                "total_protein_g": row.total_protein_g,
                "total_carbs_g": row.total_carbs_g,
                "total_fat_g": row.total_fat_g,
                "source_filename": row.source_filename,
                "items": items,
            })
        return result


@dataclass
class DayHistoryRow:
    date: str  # YYYY-MM-DD
    meals: int
    total_calories_kcal: float
    total_protein_g: float
    total_carbs_g: float
    total_fat_g: float

    def to_dict(self) -> Dict[str, Any]:
        return {
            "date": self.date,
            "meals": self.meals,
            "kcal": round(self.total_calories_kcal, 0),
            "protein_g": round(self.total_protein_g, 0),
            "carbs_g": round(self.total_carbs_g, 0),
            "fat_g": round(self.total_fat_g, 0),
        }


def history_by_day(start_date: date, days: int = 7) -> List[DayHistoryRow]:
    """
    Returns one row per day for [start_date, start_date+days).
    Each row: date, meal count, total kcal/P/C/F for that day.
    """
    with Session(engine) as session:
        results = []
        for i in range(days):
            current = start_date + timedelta(days=i)
            day_start = datetime.combine(current, datetime.min.time())
            day_end = day_start + timedelta(days=1)

            row = (
                session.query(
                    func.count(MealLog.id),
                    func.coalesce(func.sum(MealLog.total_calories_kcal), 0.0),
                    func.coalesce(func.sum(MealLog.total_protein_g), 0.0),
                    func.coalesce(func.sum(MealLog.total_carbs_g), 0.0),
                    func.coalesce(func.sum(MealLog.total_fat_g), 0.0),
                )
                .filter(MealLog.created_at >= day_start, MealLog.created_at < day_end)
                .one()
            )

            count, kcal, protein, carbs, fat = row
            results.append(
                DayHistoryRow(
                    date=current.isoformat(),
                    meals=int(count or 0),
                    total_calories_kcal=float(kcal or 0.0),
                    total_protein_g=float(protein or 0.0),
                    total_carbs_g=float(carbs or 0.0),
                    total_fat_g=float(fat or 0.0),
                )
            )
        return results


def get_meal_log(log_id: int) -> Dict[str, Any] | None:
    """Get a single meal log by ID."""
    with Session(engine) as session:
        log = session.query(MealLog).filter(MealLog.id == log_id).first()
        if not log:
            return None
        
        items = [
            {
                "label": item.label,
                "weight_g": item.weight_g,
                "calories_kcal": item.calories_kcal,
                "protein_g": item.protein_g,
                "carbs_g": item.carbs_g,
                "fat_g": item.fat_g,
            }
            for item in log.items
        ]
        
        return {
            "id": log.id,
            "created_at": log.created_at.isoformat() + "Z",
            "total_calories_kcal": log.total_calories_kcal,
            "total_protein_g": log.total_protein_g,
            "total_carbs_g": log.total_carbs_g,
            "total_fat_g": log.total_fat_g,
            "source_filename": log.source_filename,
            "items": items,
        }


def update_meal_log(
    log_id: int, calories: float, protein: float, carbs: float, fat: float
) -> None:
    """Update a meal log's nutrition totals."""
    with Session(engine) as session:
        log = session.query(MealLog).filter(MealLog.id == log_id).first()
        if log:
            log.total_calories_kcal = calories
            log.total_protein_g = protein
            log.total_carbs_g = carbs
            log.total_fat_g = fat
            session.commit()


def delete_meal_log(log_id: int) -> None:
    """Delete a meal log."""
    with Session(engine) as session:
        log = session.query(MealLog).filter(MealLog.id == log_id).first()
        if log:
            session.delete(log)
            session.commit()


