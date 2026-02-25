"""
auditX — Financial Statement Analysis Engine (Production Fixed Version)
Mobile + Web compatible API responses (Android Safe JSON)
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Dict, Any

from flask import Flask, request, render_template, redirect, url_for, flash
from werkzeug.utils import secure_filename

from financial_mapper.pipeline import FinancialMappingPipeline
from financial_mapper.config import MatchingConfig, PipelineConfig, ValidationConfig
from web.ratio_calculator import RatioCalculator

# -------------------------------------------------------
# App Setup
# -------------------------------------------------------

app = Flask(__name__)
app.secret_key = "auditx-secret"

app.config["MAX_CONTENT_LENGTH"] = 16 * 1024 * 1024
app.config["UPLOAD_FOLDER"] = Path("/tmp")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

ALLOWED_EXTENSIONS = {"csv", "json", "xlsx", "xls", "txt"}

# -------------------------------------------------------
# Pipeline Setup
# -------------------------------------------------------

pipeline = FinancialMappingPipeline(
    config=PipelineConfig(
        matching=MatchingConfig(
            fuzzy_threshold=70,
            fuzzy_ambiguity_delta=5,
            strict_mode=False,
        ),
        validation=ValidationConfig(
            required_fields=[],
            error_on_duplicate=False,
        ),
        log_level=logging.WARNING,
    )
)

calculator = RatioCalculator()

# -------------------------------------------------------
# Helpers
# -------------------------------------------------------

def allowed_file(filename: str) -> bool:
    return "." in filename and filename.rsplit(".", 1)[1].lower() in ALLOWED_EXTENSIONS


# ⭐ VERY IMPORTANT FOR ANDROID COMPATIBILITY
def flatten_extracted_values(mapped_data: Dict[str, Any]) -> Dict[str, float]:
    """
    Convert pipeline output into:
    {
        "Revenue": 10000,
        "Net Profit": 5000
    }

    This prevents Retrofit JSON parsing crashes.
    """

    result = {}

    for key, value in mapped_data.items():

        try:
            if isinstance(value, dict):

                if "value" in value and value["value"] is not None:
                    result[key] = float(value["value"])
                else:
                    # fallback to first numeric value
                    for v in value.values():
                        if isinstance(v, (int, float)):
                            result[key] = float(v)
                            break

            elif isinstance(value, (int, float)):
                result[key] = float(value)

        except Exception:
            continue

    return result


def safe_ratio_dict(ratios: Dict[str, Any]) -> Dict[str, Any]:
    """
    Convert ratios to JSON safe format that matches RatioItem structure.
    Removes None values to prevent Kotlin deserialization issues.
    """

    safe = {}

    for category, items in ratios.items():

        safe[category] = {}

        for name, ratio in items.items():

            # Filter out None values
            clean_ratio = {
                k: v for k, v in {
                    "value": ratio.get("value"),
                    "percentage": ratio.get("percentage"),
                    "days": ratio.get("days"),
                    "formula": ratio.get("formula"),
                }.items() if v is not None
            }

            safe[category][name] = clean_ratio

    return safe


# -------------------------------------------------------
# File Parsing
# -------------------------------------------------------

def parse_uploaded_file(filepath: Path) -> Dict[str, Any]:

    ext = filepath.suffix.lower()

    if ext == ".csv":
        return pipeline.map_csv(filepath)

    if ext == ".json":
        return pipeline.map_json(filepath)

    if ext in (".xlsx", ".xls"):
        return pipeline.map_excel(filepath, year_index=None)

    if ext == ".txt":
        try:
            return pipeline.map_csv(filepath)
        except Exception:
            return pipeline.map_json(filepath)

    raise ValueError("Unsupported file type")


# -------------------------------------------------------
# Web Interface Routes
# -------------------------------------------------------

@app.route("/")
def index():
    """Landing page with upload form."""
    return render_template("index.html")


@app.route("/upload", methods=["POST"])
def upload_file():
    """Handle file upload and redirect to results."""
    if "file" not in request.files:
        flash("No file uploaded", "error")
        return redirect(url_for("index"))

    file = request.files["file"]

    if file.filename == "":
        flash("No file selected", "error")
        return redirect(url_for("index"))

    if not allowed_file(file.filename):
        flash(
            f"Invalid file type. Allowed: {', '.join(ALLOWED_EXTENSIONS)}",
            "error",
        )
        return redirect(url_for("index"))

    try:
        filename = secure_filename(file.filename)
        filepath = app.config["UPLOAD_FOLDER"] / filename
        file.save(filepath)

        year_results = parse_uploaded_file(filepath)

        # Check if multi-year or single-year result
        is_multi_year = len(year_results) > 1 or (len(year_results) == 1 and "single" not in year_results)

        if is_multi_year:
            # Multi-year: calculate ratios for each year
            all_ratios = {}
            all_mapped = {}
            for year, result in year_results.items():
                all_mapped[year] = result.mapped_dict()
                all_ratios[year] = calculator.calculate_all_ratios(all_mapped[year])

            filepath.unlink(missing_ok=True)

            return render_template(
                "results_multi_year.html",
                year_results=year_results,
                all_mapped=all_mapped,
                all_ratios=all_ratios,
                filename=filename,
                years=sorted(year_results.keys()),
            )
        else:
            # Single-year
            result = year_results.get("single") or list(year_results.values())[0]
            mapped_data = result.mapped_dict()
            ratios = calculator.calculate_all_ratios(mapped_data)

            filepath.unlink(missing_ok=True)

            return render_template(
                "results.html",
                result=result,
                ratios=ratios,
                filename=filename,
            )

    except Exception as e:
        logger.exception("Error processing upload")
        flash(f"Error processing file: {str(e)}", "error")
        return redirect(url_for("index"))


# -------------------------------------------------------
# API
# -------------------------------------------------------

@app.route("/api/parse", methods=["POST"])
def api_parse():

    if "file" not in request.files:
        return {"success": False, "error": "No file uploaded"}, 400

    file = request.files["file"]

    if file.filename == "":
        return {"success": False, "error": "No file selected"}, 400

    if not allowed_file(file.filename):
        return {
            "success": False,
            "error": "Invalid file type"
        }, 400

    try:
        filename = secure_filename(file.filename)
        filepath = app.config["UPLOAD_FOLDER"] / filename

        file.save(filepath)

        pipeline_result = parse_uploaded_file(filepath)

        filepath.unlink(missing_ok=True)

        # -----------------------------
        # Detect Multi Year
        # -----------------------------

        is_multi_year = (
            isinstance(pipeline_result, dict)
            and all(hasattr(v, "mapped_dict") for v in pipeline_result.values())
            and len(pipeline_result) > 1
        )

        # -----------------------------
        # Multi Year Response
        # -----------------------------

        if is_multi_year:

            all_extracted = {}
            all_ratios = {}
            multi_year_data = {}

            years_list = sorted(pipeline_result.keys())

            for year, result in pipeline_result.items():

                mapped = result.mapped_dict()
                extracted = flatten_extracted_values(mapped)
                ratios = safe_ratio_dict(calculator.calculate_all_ratios(mapped))

                all_extracted[year] = extracted
                all_ratios[year] = ratios

                # Build YearData structure for Kotlin
                multi_year_data[year] = {
                    "extracted_values": extracted,
                    "ratios": ratios,
                    "mappings": [m.to_dict() for m in result.mappings],
                    "unmapped": list(result.unmapped),
                    "warnings": list(result.validation_warnings),
                    "errors": list(result.validation_errors)
                }

            return {
                "success": True,
                "multi_year": True,
                "years": years_list,
                "extracted_values": all_extracted,  # For backward compatibility
                "ratios": all_ratios,  # For backward compatibility
                "multi_year_data": multi_year_data,  # For Kotlin
                "mappings": [],
                "unmapped": [],
                "warnings": [],
                "errors": []
            }, 200

        # -----------------------------
        # Single Year Response
        # -----------------------------

        result = (
            pipeline_result.get("single")
            if isinstance(pipeline_result, dict)
            else pipeline_result
        )

        mapped_data = result.mapped_dict()

        ratios = calculator.calculate_all_ratios(mapped_data)

        return {
            "success": True,
            "multi_year": False,
            "years": ["Year"],
            "extracted_values": flatten_extracted_values(mapped_data),
            "ratios": safe_ratio_dict(ratios),
            "mappings": [m.to_dict() for m in result.mappings],
            "unmapped": list(result.unmapped),
            "warnings": list(result.validation_warnings),
            "errors": list(result.validation_errors)
        }, 200

    except Exception as e:
        logger.exception("API Error")

        return {
            "success": False,
            "error": str(e),
            "multi_year": False,
            "years": [],
            "extracted_values": {},
            "ratios": {}
        }, 500


# -------------------------------------------------------
# Main
# -------------------------------------------------------

@app.route("/api/sample", methods=["GET"])
def api_sample():
    """
    Returns sample response data for testing Android app.
    Shows the exact JSON structure your Kotlin classes expect.
    """
    return {
        "success": True,
        "multi_year": False,
        "years": ["2024"],
        "extracted_values": {
            "Revenue": 500000.0,
            "Net Sales": 500000.0,
            "Net Profit": 80000.0,
            "Total Assets": 1000000.0,
            "Current Assets": 300000.0,
            "Current Liabilities": 150000.0,
            "Inventory": 50000.0,
            "Cash and Cash Equivalents": 25000.0
        },
        "ratios": {
            "Liquidity": {
                "Current Ratio": {
                    "value": 2.0,
                    "formula": "Current Assets / Current Liabilities",
                    "interpretation": "Ability to pay short-term obligations"
                },
                "Quick Ratio": {
                    "value": 1.67,
                    "formula": "(Current Assets - Inventory) / Current Liabilities",
                    "interpretation": "Ability with liquid assets only"
                },
                "Cash Ratio": {
                    "value": 0.17,
                    "formula": "Cash / Current Liabilities"
                }
            },
            "Profitability": {
                "Net Profit Margin": {
                    "value": 0.16,
                    "percentage": 16.0,
                    "formula": "Net Profit / Revenue × 100"
                },
                "Return on Assets": {
                    "value": 0.08,
                    "percentage": 8.0,
                    "formula": "Net Profit / Total Assets × 100"
                }
            }
        },
        "mappings": [],
        "unmapped": [],
        "warnings": [],
        "errors": []
    }, 200

@app.route("/")
def home():
    return {
        "status": "auditX server running",
        "message": "Use /api/health to check server status",
        "endpoints": ["/api/parse", "/api/health"]
    }
@app.route("/api/health", methods=["GET"])
def api_health():
    """Health check endpoint for Android app."""
    return {
        "status": "online",
        "version": "1.0.0",
        "api": "/api/parse",
        "methods": ["POST"]
    }, 200

if __name__ == "__main__":
    print("=" * 60)
    print("auditX Server Running")
    print("http://localhost:5000")
    print("=" * 60)

    app.run(host="0.0.0.0", port=5000, debug=True)

# Export for Vercel
handler = app