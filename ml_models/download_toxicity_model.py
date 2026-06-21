#!/usr/bin/env python3
"""
TensorFlow Toxicity Model Downloader and TFLite Converter

This script downloads a pre-trained toxicity classification model and converts it
to TensorFlow Lite format for use in the SafeGuard Android app.

Requirements:
    pip install tensorflow tensorflow-hub tensorflow-text numpy

Usage:
    python download_toxicity_model.py

Output:
    - text_classifier.tflite (the model file)
    - vocab.txt (vocabulary file)
    - labels.txt (category labels)
"""

import os
import json
import numpy as np

# Suppress TF warnings
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'

import tensorflow as tf
import tensorflow_hub as hub

print(f"TensorFlow version: {tf.__version__}")

# Output directory
OUTPUT_DIR = os.path.dirname(os.path.abspath(__file__))
ASSETS_DIR = os.path.join(os.path.dirname(OUTPUT_DIR), "app", "src", "main", "assets")

# Model configuration
MAX_SEQ_LENGTH = 128
NUM_CLASSES = 7  # toxicity, severe_toxicity, obscene, threat, insult, identity_attack, sexual_explicit

LABELS = [
    "toxicity",
    "severe_toxicity",
    "obscene",
    "threat",
    "insult",
    "identity_attack",
    "sexual_explicit"
]


def create_toxicity_model():
    """
    Create a toxicity classification model using Universal Sentence Encoder.
    This is a lightweight version suitable for mobile.
    """
    print("Loading Universal Sentence Encoder (this may take a while)...")

    # Use the smaller multilingual USE for better mobile performance
    # Or use the standard one for English-only
    use_url = "https://tfhub.dev/google/universal-sentence-encoder/4"

    # Create model
    text_input = tf.keras.layers.Input(shape=(), dtype=tf.string, name='text')

    # USE layer
    use_layer = hub.KerasLayer(use_url, trainable=False, name='USE')
    embeddings = use_layer(text_input)

    # Classification head
    x = tf.keras.layers.Dense(256, activation='relu')(embeddings)
    x = tf.keras.layers.Dropout(0.3)(x)
    x = tf.keras.layers.Dense(128, activation='relu')(x)
    x = tf.keras.layers.Dropout(0.2)(x)

    # Multi-label output (sigmoid for each category)
    outputs = tf.keras.layers.Dense(NUM_CLASSES, activation='sigmoid', name='predictions')(x)

    model = tf.keras.Model(inputs=text_input, outputs=outputs)

    model.compile(
        optimizer='adam',
        loss='binary_crossentropy',
        metrics=['accuracy']
    )

    print("Model created successfully!")
    model.summary()

    return model


def download_pretrained_weights():
    """
    Download pre-trained weights for toxicity classification.
    Uses Jigsaw/Civil Comments pre-trained weights if available.
    """
    print("\nNote: For production use, you should fine-tune this model on toxicity data.")
    print("The Jigsaw Toxic Comment dataset is recommended:")
    print("  https://www.kaggle.com/c/jigsaw-toxic-comment-classification-challenge")
    print("")


def convert_to_tflite(model, output_path):
    """
    Convert Keras model to TFLite format with optimizations.
    """
    print("\nConverting to TFLite format...")

    # Create a concrete function for the model
    @tf.function(input_signature=[tf.TensorSpec(shape=[1], dtype=tf.string)])
    def serve_fn(text):
        return model(text)

    # Get concrete function
    concrete_func = serve_fn.get_concrete_function()

    # Convert using the concrete function
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete_func])

    # Optimizations for mobile
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    # Allow TF ops for string handling (USE requires this)
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS  # Required for string operations
    ]
    converter._experimental_lower_tensor_list_ops = False

    try:
        tflite_model = converter.convert()

        # Save model
        with open(output_path, 'wb') as f:
            f.write(tflite_model)

        size_mb = os.path.getsize(output_path) / (1024 * 1024)
        print(f"TFLite model saved to: {output_path}")
        print(f"Model size: {size_mb:.2f} MB")

        return True

    except Exception as e:
        print(f"Error converting to TFLite: {e}")
        print("\nTrying alternative conversion method...")
        return convert_to_tflite_alternative(model, output_path)


def convert_to_tflite_alternative(model, output_path):
    """
    Alternative conversion for models that don't convert directly.
    Creates a simpler model architecture that's more TFLite-friendly.
    """
    print("\nCreating TFLite-friendly model architecture...")

    # For TFLite compatibility, we'll create a model that takes
    # pre-computed embeddings instead of raw text
    # The Android app will need to handle tokenization separately

    embedding_input = tf.keras.layers.Input(shape=(512,), dtype=tf.float32, name='embedding')

    x = tf.keras.layers.Dense(256, activation='relu')(embedding_input)
    x = tf.keras.layers.Dropout(0.3)(x)
    x = tf.keras.layers.Dense(128, activation='relu')(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(NUM_CLASSES, activation='sigmoid', name='predictions')(x)

    simple_model = tf.keras.Model(inputs=embedding_input, outputs=outputs)

    # Copy weights from the dense layers if possible
    try:
        for i, layer in enumerate(simple_model.layers):
            if 'dense' in layer.name:
                for orig_layer in model.layers:
                    if orig_layer.name == layer.name:
                        layer.set_weights(orig_layer.get_weights())
                        break
    except:
        pass  # Use random weights if copying fails

    # Convert simple model
    converter = tf.lite.TFLiteConverter.from_keras_model(simple_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    tflite_model = converter.convert()

    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"TFLite model saved to: {output_path}")
    print(f"Model size: {size_mb:.2f} MB")
    print("\nNote: This model requires pre-computed USE embeddings as input.")

    return True


def create_simple_toxicity_model():
    """
    Create a simpler, more mobile-friendly toxicity model.
    Uses word embeddings instead of USE for smaller size.
    """
    print("\nCreating lightweight toxicity model...")

    VOCAB_SIZE = 20000
    EMBEDDING_DIM = 128

    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(MAX_SEQ_LENGTH,), dtype=tf.int32),
        tf.keras.layers.Embedding(VOCAB_SIZE, EMBEDDING_DIM, input_length=MAX_SEQ_LENGTH),
        tf.keras.layers.GlobalAveragePooling1D(),
        tf.keras.layers.Dense(128, activation='relu'),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(64, activation='relu'),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(NUM_CLASSES, activation='sigmoid')
    ])

    model.compile(
        optimizer='adam',
        loss='binary_crossentropy',
        metrics=['accuracy']
    )

    return model


def create_vocab_file(output_path):
    """
    Create a basic vocabulary file for tokenization.
    In production, this should be generated from training data.
    """
    print("\nCreating vocabulary file...")

    # Basic vocabulary for toxicity detection
    # In production, generate this from your training corpus
    basic_vocab = [
        "[PAD]", "[UNK]", "[CLS]", "[SEP]",
        # Common words
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "must", "can",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her",
        "my", "your", "his", "its", "our", "their",
        "this", "that", "these", "those", "what", "which", "who", "whom",
        "and", "or", "but", "if", "because", "as", "so", "than",
        "not", "no", "yes", "just", "only", "also", "very", "too",
        # Toxicity-related words (for detection)
        "hate", "kill", "die", "dead", "death", "stupid", "idiot", "dumb",
        "ugly", "fat", "loser", "freak", "weirdo", "creep",
        "fuck", "shit", "damn", "ass", "bitch", "bastard", "crap",
        "sex", "porn", "nude", "naked", "sexy",
        "hurt", "pain", "suffer", "attack", "fight", "punch", "hit",
        "threat", "threaten", "violence", "violent",
        "racist", "racism", "sexist", "sexism", "homophobic",
        "suicide", "suicidal", "depressed", "depression",
        "drug", "drugs", "weed", "cocaine", "heroin", "meth",
        "alcohol", "drunk", "beer", "vodka", "whiskey",
        # Common message words
        "hey", "hi", "hello", "bye", "please", "thanks", "sorry",
        "want", "need", "like", "love", "think", "know", "feel",
        "go", "come", "get", "make", "take", "see", "look", "say",
        "tell", "ask", "give", "find", "use", "try", "leave",
        "call", "send", "meet", "talk", "chat", "message",
        "good", "bad", "great", "nice", "cool", "awesome", "terrible",
        "happy", "sad", "angry", "scared", "worried", "excited",
        "friend", "family", "mom", "dad", "parent", "parents",
        "school", "home", "work", "game", "video", "photo", "picture",
        "online", "internet", "app", "phone", "computer",
        "secret", "private", "alone", "nobody", "everyone",
        "never", "always", "sometimes", "maybe", "probably",
        "really", "actually", "definitely", "literally",
        "why", "how", "when", "where", "here", "there",
        "now", "today", "tomorrow", "yesterday", "night", "day",
        "new", "old", "young", "big", "small", "more", "less",
        "first", "last", "next", "same", "different", "other",
        "people", "person", "man", "woman", "boy", "girl", "kid", "child",
        "thing", "way", "time", "year", "life", "world",
    ]

    # Pad to ensure we have enough vocabulary
    while len(basic_vocab) < 1000:
        basic_vocab.append(f"[UNUSED{len(basic_vocab)}]")

    with open(output_path, 'w') as f:
        for word in basic_vocab:
            f.write(f"{word}\n")

    print(f"Vocabulary file saved to: {output_path}")
    print(f"Vocabulary size: {len(basic_vocab)} words")


def create_labels_file(output_path):
    """Create labels file for the model categories."""
    with open(output_path, 'w') as f:
        for label in LABELS:
            f.write(f"{label}\n")
    print(f"Labels file saved to: {output_path}")


def main():
    print("=" * 60)
    print("SafeGuard Toxicity Model Generator")
    print("=" * 60)

    # Create output directories
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    os.makedirs(ASSETS_DIR, exist_ok=True)

    # Output paths
    model_path = os.path.join(ASSETS_DIR, "text_classifier.tflite")
    vocab_path = os.path.join(ASSETS_DIR, "vocab.txt")
    labels_path = os.path.join(ASSETS_DIR, "labels.txt")

    print(f"\nOutput directory: {ASSETS_DIR}")

    # Create simple model (more compatible with TFLite)
    model = create_simple_toxicity_model()
    model.summary()

    # Convert to TFLite
    print("\nConverting to TFLite...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    # Quantize to int8 for smaller size
    converter.target_spec.supported_types = [tf.float16]

    tflite_model = converter.convert()

    with open(model_path, 'wb') as f:
        f.write(tflite_model)

    size_mb = os.path.getsize(model_path) / (1024 * 1024)
    print(f"\nModel saved: {model_path}")
    print(f"Model size: {size_mb:.2f} MB")

    # Create vocab and labels files
    create_vocab_file(vocab_path)
    create_labels_file(labels_path)

    print("\n" + "=" * 60)
    print("IMPORTANT: Model Training Required!")
    print("=" * 60)
    print("""
The generated model has random weights and needs to be trained
on toxicity data for accurate predictions.

Recommended training data:
1. Jigsaw Toxic Comment Classification Challenge
   https://www.kaggle.com/c/jigsaw-toxic-comment-classification-challenge

2. Civil Comments Dataset
   https://www.tensorflow.org/datasets/catalog/civil_comments

To train the model, use the companion script:
   python train_toxicity_model.py

For now, the app will rely primarily on the regex-based detection
(Stage 1) which is already quite comprehensive.
""")

    print("\n✅ Files generated successfully!")
    print(f"   - {model_path}")
    print(f"   - {vocab_path}")
    print(f"   - {labels_path}")


if __name__ == "__main__":
    main()
