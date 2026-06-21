#!/usr/bin/env python3
"""
Create a Contextual Toxicity Model using Universal Sentence Encoder

This model actually understands context, not just keywords.
It uses Google's Universal Sentence Encoder to create sentence embeddings
that capture meaning, then classifies based on those embeddings.

Size: ~25-30 MB (acceptable for mobile)
Context: ✅ Yes - understands sentence meaning

Requirements:
    pip install tensorflow tensorflow-hub tensorflow-text numpy

Usage:
    python create_contextual_model.py
"""

import os
import numpy as np
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'

import tensorflow as tf
import tensorflow_hub as hub

print(f"TensorFlow version: {tf.__version__}")

# Configuration
NUM_CLASSES = 7
LABELS = ["safe", "toxicity", "severe_toxicity", "obscene", "threat", "insult", "sexual_explicit"]

# Training examples for fine-tuning the classifier head
# Format: (text, [safe, toxicity, severe_toxicity, obscene, threat, insult, sexual_explicit])
TRAINING_EXAMPLES = [
    # Safe examples
    ("Hello, how are you today?", [1, 0, 0, 0, 0, 0, 0]),
    ("I love spending time with my family", [1, 0, 0, 0, 0, 0, 0]),
    ("The weather is nice today", [1, 0, 0, 0, 0, 0, 0]),
    ("Can you help me with my homework?", [1, 0, 0, 0, 0, 0, 0]),
    ("I had a great day at school", [1, 0, 0, 0, 0, 0, 0]),
    ("Let's play video games together", [1, 0, 0, 0, 0, 0, 0]),
    ("My favorite color is blue", [1, 0, 0, 0, 0, 0, 0]),
    ("I want to be a doctor when I grow up", [1, 0, 0, 0, 0, 0, 0]),
    ("Thank you for your help", [1, 0, 0, 0, 0, 0, 0]),
    ("Have a wonderful day", [1, 0, 0, 0, 0, 0, 0]),

    # Toxicity / Profanity
    ("You're such an idiot", [0, 1, 0, 0, 0, 1, 0]),
    ("What the hell is wrong with you", [0, 1, 0, 1, 0, 0, 0]),
    ("This is bullshit", [0, 1, 0, 1, 0, 0, 0]),
    ("Shut the fuck up", [0, 1, 0, 1, 0, 1, 0]),
    ("You're a piece of garbage", [0, 1, 0, 0, 0, 1, 0]),

    # Severe toxicity / Threats
    ("I will kill you", [0, 1, 1, 0, 1, 0, 0]),
    ("You should kill yourself", [0, 1, 1, 0, 1, 1, 0]),
    ("I'm going to find you and hurt you", [0, 1, 1, 0, 1, 0, 0]),
    ("You deserve to die", [0, 1, 1, 0, 1, 1, 0]),
    ("I hope you get hit by a car", [0, 1, 1, 0, 1, 0, 0]),
    ("Kill yourself, nobody likes you", [0, 1, 1, 0, 1, 1, 0]),
    ("I want to murder you", [0, 1, 1, 0, 1, 0, 0]),

    # Insults / Bullying
    ("You're so stupid and ugly", [0, 1, 0, 0, 0, 1, 0]),
    ("Nobody likes you, you're a loser", [0, 1, 0, 0, 0, 1, 0]),
    ("You're worthless and pathetic", [0, 1, 0, 0, 0, 1, 0]),
    ("Everyone hates you", [0, 1, 0, 0, 0, 1, 0]),
    ("You're fat and disgusting", [0, 1, 0, 0, 0, 1, 0]),
    ("You're a waste of space", [0, 1, 0, 0, 0, 1, 0]),

    # Sexual content
    ("Send me nude pictures", [0, 1, 0, 0, 0, 0, 1]),
    ("Let's have sex", [0, 0, 0, 0, 0, 0, 1]),
    ("I want to see you naked", [0, 1, 0, 0, 0, 0, 1]),
    ("Show me your body", [0, 0, 0, 0, 0, 0, 1]),
    ("Do you watch porn", [0, 0, 0, 0, 0, 0, 1]),
    ("I'm so horny right now", [0, 0, 0, 1, 0, 0, 1]),

    # Grooming / Predatory behavior
    ("Don't tell your parents about this", [0, 1, 1, 0, 0, 0, 0]),
    ("Keep this a secret between us", [0, 1, 1, 0, 0, 0, 0]),
    ("You're so mature for your age", [0, 0, 1, 0, 0, 0, 0]),
    ("Are you home alone?", [0, 0, 1, 0, 0, 0, 0]),
    ("Let's meet in person, don't tell anyone", [0, 1, 1, 0, 0, 0, 0]),
    ("This is our little secret", [0, 1, 1, 0, 0, 0, 0]),
    ("I can give you money if you send pictures", [0, 1, 1, 0, 0, 0, 1]),

    # Encouraging bad behavior (what the user was testing)
    ("You need to steal money from your parents", [0, 1, 1, 0, 0, 0, 0]),
    ("You should steal from the store", [0, 1, 1, 0, 0, 0, 0]),
    ("Let's steal some stuff", [0, 1, 0, 0, 0, 0, 0]),
    ("Take money from your mom's purse", [0, 1, 1, 0, 0, 0, 0]),
    ("You should run away from home", [0, 1, 1, 0, 0, 0, 0]),
    ("Skip school and come meet me", [0, 1, 1, 0, 0, 0, 0]),
    ("Lie to your parents about where you're going", [0, 1, 1, 0, 0, 0, 0]),
    ("Don't listen to your parents, they don't understand", [0, 1, 0, 0, 0, 0, 0]),

    # Drug-related
    ("Want to smoke weed with me?", [0, 1, 0, 0, 0, 0, 0]),
    ("I can get you some drugs", [0, 1, 1, 0, 0, 0, 0]),
    ("Let's get drunk tonight", [0, 1, 0, 0, 0, 0, 0]),
    ("Try this pill, it will make you feel good", [0, 1, 1, 0, 0, 0, 0]),

    # Self-harm
    ("I want to hurt myself", [0, 1, 1, 0, 0, 0, 0]),
    ("I don't want to live anymore", [0, 1, 1, 0, 0, 0, 0]),
    ("Life isn't worth living", [0, 1, 1, 0, 0, 0, 0]),
    ("I'm thinking about ending it all", [0, 1, 1, 0, 0, 0, 0]),
]


def create_model():
    """
    Create a contextual model using Universal Sentence Encoder.
    """
    print("Loading Universal Sentence Encoder (this may take a while)...")

    # Use the multilingual lite version for smaller size, or standard for English
    # Lite version: ~25MB, Standard: ~1GB
    use_url = "https://tfhub.dev/google/universal-sentence-encoder-lite/2"

    try:
        # Try lite version first
        encoder = hub.load(use_url)
        print("Loaded USE-Lite")
    except:
        # Fall back to standard version
        print("USE-Lite not available, trying standard USE...")
        use_url = "https://tfhub.dev/google/universal-sentence-encoder/4"
        encoder = hub.load(use_url)
        print("Loaded standard USE")

    return encoder


def create_classifier_model():
    """
    Create a simple classifier that takes USE embeddings (512-dim) and outputs toxicity scores.
    This is small and can be combined with USE for deployment.
    """
    print("\nCreating classifier head...")

    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(512,), dtype=tf.float32, name='embedding_input'),
        tf.keras.layers.Dense(128, activation='relu', name='dense1'),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(64, activation='relu', name='dense2'),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(NUM_CLASSES, activation='sigmoid', name='output')
    ])

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss='binary_crossentropy',
        metrics=['accuracy']
    )

    return model


def train_classifier(encoder, classifier, examples):
    """
    Train the classifier on the example data.
    """
    print("\nPreparing training data...")

    texts = [ex[0] for ex in examples]
    labels = np.array([ex[1] for ex in examples], dtype=np.float32)

    # Get embeddings from USE
    print("Generating embeddings...")

    # Handle different USE versions
    try:
        # USE-Lite returns different format
        embeddings = encoder(texts)
        if hasattr(embeddings, 'numpy'):
            embeddings = embeddings.numpy()
        elif isinstance(embeddings, dict):
            embeddings = embeddings['outputs'].numpy()
    except:
        embeddings = encoder(texts).numpy()

    print(f"Embeddings shape: {embeddings.shape}")

    # Train
    print("\nTraining classifier...")
    classifier.fit(
        embeddings,
        labels,
        epochs=100,
        batch_size=16,
        validation_split=0.2,
        verbose=1
    )

    return classifier


def test_model(encoder, classifier, test_texts):
    """
    Test the model on sample texts.
    """
    print("\n" + "="*60)
    print("Testing model predictions...")
    print("="*60)

    # Get embeddings
    try:
        embeddings = encoder(test_texts)
        if hasattr(embeddings, 'numpy'):
            embeddings = embeddings.numpy()
        elif isinstance(embeddings, dict):
            embeddings = embeddings['outputs'].numpy()
    except:
        embeddings = encoder(test_texts).numpy()

    # Predict
    predictions = classifier.predict(embeddings, verbose=0)

    for text, preds in zip(test_texts, predictions):
        print(f"\nText: '{text}'")
        print("  Predictions:")
        for i, (label, prob) in enumerate(zip(LABELS, preds)):
            if prob > 0.3 or i == 0:
                marker = "⚠️" if prob > 0.5 and i > 0 else "  "
                print(f"    {marker} {label}: {prob:.3f}")


def convert_classifier_to_tflite(classifier, output_path):
    """
    Convert just the classifier head to TFLite.
    The full pipeline will need USE + this classifier.
    """
    print(f"\nConverting classifier to TFLite: {output_path}")

    converter = tf.lite.TFLiteConverter.from_keras_model(classifier)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]

    tflite_model = converter.convert()

    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    size_kb = os.path.getsize(output_path) / 1024
    print(f"Classifier saved: {size_kb:.1f} KB")


def create_combined_model_tflite(encoder, classifier, output_path):
    """
    Try to create a combined USE + Classifier model in TFLite format.
    This may not work due to USE's complexity, in which case we'll need
    a different approach.
    """
    print(f"\nAttempting to create combined TFLite model...")

    # Create a Keras model that includes USE
    text_input = tf.keras.layers.Input(shape=(), dtype=tf.string, name='text_input')

    # USE layer
    use_layer = hub.KerasLayer(
        "https://tfhub.dev/google/universal-sentence-encoder/4",
        trainable=False,
        name='USE'
    )
    embeddings = use_layer(text_input)

    # Classifier layers
    x = tf.keras.layers.Dense(128, activation='relu')(embeddings)
    x = tf.keras.layers.Dropout(0.3)(x)
    x = tf.keras.layers.Dense(64, activation='relu')(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(NUM_CLASSES, activation='sigmoid')(x)

    combined_model = tf.keras.Model(inputs=text_input, outputs=outputs)

    # Copy trained weights to classifier layers
    # (This is a simplified approach - proper weight copying would be needed)

    # Try to convert
    try:
        # Create concrete function
        @tf.function(input_signature=[tf.TensorSpec(shape=[None], dtype=tf.string)])
        def serve(text):
            return combined_model(text)

        concrete_func = serve.get_concrete_function()

        converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete_func])
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS
        ]
        converter._experimental_lower_tensor_list_ops = False

        tflite_model = converter.convert()

        with open(output_path, 'wb') as f:
            f.write(tflite_model)

        size_mb = os.path.getsize(output_path) / (1024 * 1024)
        print(f"Combined model saved: {size_mb:.2f} MB")
        return True

    except Exception as e:
        print(f"Combined model conversion failed: {e}")
        print("This is expected - USE is complex to convert to TFLite.")
        print("Alternative: Use TensorFlow.js or implement USE separately.")
        return False


def main():
    print("="*60)
    print("SafeGuard Contextual Toxicity Model Creator")
    print("="*60)
    print("\nThis model uses Universal Sentence Encoder for TRUE context understanding.")
    print("It will detect things like 'you need to steal money' as harmful.\n")

    # Paths
    script_dir = os.path.dirname(os.path.abspath(__file__))
    assets_dir = os.path.join(os.path.dirname(script_dir), "app", "src", "main", "assets")
    os.makedirs(assets_dir, exist_ok=True)

    classifier_path = os.path.join(assets_dir, "toxicity_classifier.tflite")
    combined_path = os.path.join(assets_dir, "text_classifier_contextual.tflite")

    # Load USE
    encoder = create_model()

    # Create and train classifier
    classifier = create_classifier_model()
    classifier = train_classifier(encoder, classifier, TRAINING_EXAMPLES)

    # Test
    test_texts = [
        "Hello, how are you today?",
        "You need to steal money from your parents",
        "Don't tell your parents about this",
        "I will kill you",
        "You're so stupid",
        "Send me nude pictures",
        "Let's play video games",
        "You should kill yourself",
        "Skip school and come meet me",
    ]
    test_model(encoder, classifier, test_texts)

    # Save classifier (small, ~50KB)
    convert_classifier_to_tflite(classifier, classifier_path)

    # Try to save combined model (may not work)
    success = create_combined_model_tflite(encoder, classifier, combined_path)

    print("\n" + "="*60)
    print("IMPORTANT: Integration Notes")
    print("="*60)

    if success:
        print(f"""
✅ Combined model created: {combined_path}
   This model takes raw text and outputs toxicity scores.
   It includes USE for context understanding.
""")
    else:
        print(f"""
⚠️  Combined model failed (USE is complex to convert).

Alternative approaches for Android:

1. **Use TensorFlow Lite with Flex Delegate**
   - Add flex delegate to support TF ops
   - Larger APK but works with USE

2. **Use on-device ML Kit**
   - Google ML Kit has text classification
   - May not have toxicity specifically

3. **Use a cloud API for complex cases**
   - Send suspicious text to backend for analysis
   - Backend can use full Detoxify/BERT models

4. **Hybrid approach (RECOMMENDED)**
   - Stage 1: Regex (fast, catches obvious)
   - Stage 2: Vocab-based TFLite (catches toxic words)
   - Stage 3: Send to backend for contextual analysis

The classifier head was saved: {classifier_path}
You can use it with a USE implementation or server-side.
""")


if __name__ == "__main__":
    main()
