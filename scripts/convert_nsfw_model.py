#!/usr/bin/env python3
"""
NSFW Model Converter for SafeGuard Android App

This script downloads GantMan's MobileNet V2 NSFW model and converts it to TFLite format.

Usage:
    python convert_nsfw_model.py

Requirements:
    pip install tensorflow requests

Output:
    - nsfw_classifier.tflite (copied to app/src/main/assets/)
"""

import os
import sys
import zipfile
import requests
import shutil

# Check TensorFlow availability
try:
    import tensorflow as tf
    print(f"TensorFlow version: {tf.__version__}")
except ImportError:
    print("ERROR: TensorFlow not installed!")
    print("Install with: pip install tensorflow")
    sys.exit(1)

# Configuration
# SourceForge mirror (GantMan's original S3 bucket is now restricted)
MODEL_URL = "https://sourceforge.net/projects/nsfw-detection-ml.mirror/files/1.1.0/nsfw_mobilenet_v2_140_224.zip/download"
MODEL_ZIP = "nsfw_mobilenet_v2_140_224.zip"
MODEL_DIR = "mobilenet_v2_140_224"  # Actual directory name inside the zip
MODEL_FILE = "saved_model.h5"
PREBUILT_TFLITE = "saved_model.tflite"  # Pre-converted TFLite in the zip
OUTPUT_FILE = "nsfw_classifier.tflite"

# Paths
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
ASSETS_DIR = os.path.join(PROJECT_DIR, "app", "src", "main", "assets")


def download_model():
    """Download the model from SourceForge mirror."""
    if os.path.exists(MODEL_ZIP):
        print(f"[OK] Model zip already exists: {MODEL_ZIP}")
        return True

    print(f"[...] Downloading model from SourceForge...")
    print(f"     URL: {MODEL_URL}")
    print(f"     (This may take a few minutes, ~142MB)")

    try:
        # SourceForge requires following redirects
        response = requests.get(MODEL_URL, stream=True, allow_redirects=True)
        response.raise_for_status()

        total_size = int(response.headers.get('content-length', 0))
        downloaded = 0

        with open(MODEL_ZIP, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                f.write(chunk)
                downloaded += len(chunk)
                if total_size > 0:
                    percent = (downloaded / total_size) * 100
                    print(f"\r     Progress: {percent:.1f}% ({downloaded // 1024 // 1024}MB)", end="")

        print(f"\n[OK] Downloaded: {MODEL_ZIP}")
        return True

    except Exception as e:
        print(f"\n[ERROR] Download failed: {e}")
        return False


def extract_model():
    """Extract the zip file."""
    model_path = os.path.join(MODEL_DIR, MODEL_FILE)
    if os.path.exists(model_path):
        print(f"[OK] Model already extracted: {model_path}")
        return True

    print(f"[...] Extracting {MODEL_ZIP}...")

    try:
        with zipfile.ZipFile(MODEL_ZIP, 'r') as zip_ref:
            zip_ref.extractall(".")
        print(f"[OK] Extracted to: {MODEL_DIR}/")
        return True

    except Exception as e:
        print(f"[ERROR] Extraction failed: {e}")
        return False


def convert_to_tflite():
    """Convert Keras model to TFLite format, or use pre-built if available."""
    prebuilt_path = os.path.join(MODEL_DIR, PREBUILT_TFLITE)
    model_path = os.path.join(MODEL_DIR, MODEL_FILE)

    # Check if pre-built TFLite exists (faster, no conversion needed)
    if os.path.exists(prebuilt_path):
        print(f"[OK] Found pre-built TFLite model: {prebuilt_path}")
        try:
            shutil.copy2(prebuilt_path, OUTPUT_FILE)
            size_mb = os.path.getsize(OUTPUT_FILE) / (1024 * 1024)
            print(f"[OK] Copied pre-built TFLite: {OUTPUT_FILE} ({size_mb:.2f} MB)")
            return True
        except Exception as e:
            print(f"[WARN] Could not copy pre-built, will convert: {e}")

    # Fall back to conversion from Keras model
    if not os.path.exists(model_path):
        print(f"[ERROR] Model file not found: {model_path}")
        return False

    print(f"[...] Loading Keras model: {model_path}")

    try:
        # Load the Keras model
        model = tf.keras.models.load_model(model_path)
        print(f"[OK] Model loaded successfully")
        print(f"     Input shape: {model.input_shape}")
        print(f"     Output shape: {model.output_shape}")

        # Convert to TFLite with optimization
        print(f"[...] Converting to TFLite (with quantization)...")
        converter = tf.lite.TFLiteConverter.from_keras_model(model)

        # Apply optimizations for smaller size and faster inference
        converter.optimizations = [tf.lite.Optimize.DEFAULT]

        # Optional: Full integer quantization (even smaller but may reduce accuracy)
        # converter.target_spec.supported_types = [tf.int8]

        tflite_model = converter.convert()

        # Save the TFLite model
        with open(OUTPUT_FILE, 'wb') as f:
            f.write(tflite_model)

        size_mb = os.path.getsize(OUTPUT_FILE) / (1024 * 1024)
        print(f"[OK] TFLite model saved: {OUTPUT_FILE} ({size_mb:.2f} MB)")
        return True

    except Exception as e:
        print(f"[ERROR] Conversion failed: {e}")
        import traceback
        traceback.print_exc()
        return False


def copy_to_assets():
    """Copy the TFLite model to Android assets folder."""
    if not os.path.exists(OUTPUT_FILE):
        print(f"[ERROR] TFLite model not found: {OUTPUT_FILE}")
        return False

    # Ensure assets directory exists
    os.makedirs(ASSETS_DIR, exist_ok=True)

    dest_path = os.path.join(ASSETS_DIR, OUTPUT_FILE)

    print(f"[...] Copying to Android assets...")
    print(f"     From: {OUTPUT_FILE}")
    print(f"     To:   {dest_path}")

    try:
        shutil.copy2(OUTPUT_FILE, dest_path)
        print(f"[OK] Model copied to assets folder")
        return True

    except Exception as e:
        print(f"[ERROR] Copy failed: {e}")
        return False


def create_labels_file():
    """Create labels file for the model."""
    labels = ["drawings", "hentai", "neutral", "porn", "sexy"]
    labels_path = os.path.join(ASSETS_DIR, "nsfw_labels.txt")

    print(f"[...] Creating labels file: {labels_path}")

    try:
        with open(labels_path, 'w') as f:
            for label in labels:
                f.write(f"{label}\n")
        print(f"[OK] Labels file created")
        print(f"     Categories: {labels}")
        return True

    except Exception as e:
        print(f"[ERROR] Failed to create labels: {e}")
        return False


def cleanup():
    """Clean up temporary files."""
    print(f"[...] Cleaning up temporary files...")

    try:
        if os.path.exists(MODEL_ZIP):
            os.remove(MODEL_ZIP)
        if os.path.exists(MODEL_DIR):
            shutil.rmtree(MODEL_DIR)
        print(f"[OK] Cleanup complete")
    except Exception as e:
        print(f"[WARN] Cleanup failed: {e}")


def main():
    print("=" * 60)
    print("  NSFW Model Converter for SafeGuard")
    print("  GantMan's MobileNet V2 -> TFLite")
    print("=" * 60)
    print()

    # Change to script directory
    os.chdir(SCRIPT_DIR)

    # Step 1: Download
    if not download_model():
        sys.exit(1)

    # Step 2: Extract
    if not extract_model():
        sys.exit(1)

    # Step 3: Convert
    if not convert_to_tflite():
        sys.exit(1)

    # Step 4: Copy to assets
    if not copy_to_assets():
        sys.exit(1)

    # Step 5: Create labels
    create_labels_file()

    # Step 6: Cleanup (optional)
    cleanup()

    print()
    print("=" * 60)
    print("  SUCCESS!")
    print("=" * 60)
    print()
    print(f"  Model saved to: {ASSETS_DIR}/nsfw_classifier.tflite")
    print()
    print("  GantMan's model categories:")
    print("    0: drawings  (safe)")
    print("    1: hentai    (inappropriate)")
    print("    2: neutral   (safe)")
    print("    3: porn      (inappropriate)")
    print("    4: sexy      (inappropriate)")
    print()
    print("  TFLiteImageClassifier.kt has been updated to match these labels.")
    print()


if __name__ == "__main__":
    main()
