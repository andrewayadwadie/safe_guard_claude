#!/usr/bin/env python3
"""
Create a Pre-trained Toxicity Model for SafeGuard

This script creates a lightweight toxicity classification model with
pre-trained weights based on known toxic patterns. The model is immediately
usable without additional training.

Requirements:
    pip install tensorflow numpy

Usage:
    python create_toxicity_model.py

Output:
    - text_classifier.tflite
    - vocab.txt
    - labels.txt
"""

import os
import numpy as np
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'

import tensorflow as tf

print(f"TensorFlow version: {tf.__version__}")

# Configuration
MAX_SEQ_LENGTH = 128
VOCAB_SIZE = 10000
EMBEDDING_DIM = 64
NUM_CLASSES = 7

LABELS = [
    "safe",           # Index 0
    "toxicity",       # Index 1
    "severe_toxicity",# Index 2
    "obscene",        # Index 3
    "threat",         # Index 4
    "insult",         # Index 5
    "sexual_explicit" # Index 6
]

# Toxic vocabulary with category associations
# Format: word -> (vocab_index, [category_indices with weights])
TOXIC_VOCABULARY = {
    # Padding and special tokens
    "[PAD]": (0, []),
    "[UNK]": (1, []),

    # Severe toxicity / threats (categories 2, 4)
    "kill": (100, [(2, 0.9), (4, 0.95)]),
    "murder": (101, [(2, 0.95), (4, 0.9)]),
    "die": (102, [(2, 0.7), (4, 0.8)]),
    "dead": (103, [(2, 0.6), (4, 0.7)]),
    "death": (104, [(2, 0.6), (4, 0.6)]),
    "shoot": (105, [(2, 0.85), (4, 0.9)]),
    "stab": (106, [(2, 0.9), (4, 0.9)]),
    "hurt": (107, [(2, 0.5), (4, 0.7)]),
    "attack": (108, [(2, 0.7), (4, 0.8)]),
    "bomb": (109, [(2, 0.95), (4, 0.95)]),
    "terrorist": (110, [(2, 0.9), (4, 0.85)]),
    "suicide": (111, [(2, 0.95), (4, 0.5)]),
    "suicidal": (112, [(2, 0.9), (4, 0.4)]),

    # Obscene / profanity (category 3)
    "fuck": (200, [(1, 0.9), (3, 0.95)]),
    "fucking": (201, [(1, 0.85), (3, 0.9)]),
    "fucker": (202, [(1, 0.9), (3, 0.95), (5, 0.7)]),
    "shit": (203, [(1, 0.7), (3, 0.85)]),
    "bitch": (204, [(1, 0.8), (3, 0.9), (5, 0.75)]),
    "bastard": (205, [(1, 0.75), (3, 0.8), (5, 0.6)]),
    "ass": (206, [(1, 0.5), (3, 0.7)]),
    "asshole": (207, [(1, 0.8), (3, 0.9), (5, 0.7)]),
    "damn": (208, [(1, 0.3), (3, 0.5)]),
    "crap": (209, [(1, 0.3), (3, 0.5)]),
    "dick": (210, [(1, 0.6), (3, 0.8), (6, 0.5)]),
    "cock": (211, [(1, 0.7), (3, 0.85), (6, 0.6)]),
    "penis": (212, [(3, 0.6), (6, 0.7)]),
    "vagina": (213, [(3, 0.5), (6, 0.6)]),
    "cunt": (214, [(1, 0.95), (3, 0.95), (5, 0.8)]),
    "whore": (215, [(1, 0.9), (3, 0.9), (5, 0.85), (6, 0.6)]),
    "slut": (216, [(1, 0.85), (3, 0.85), (5, 0.8), (6, 0.5)]),

    # Insults (category 5)
    "stupid": (300, [(1, 0.6), (5, 0.8)]),
    "idiot": (301, [(1, 0.7), (5, 0.85)]),
    "dumb": (302, [(1, 0.5), (5, 0.75)]),
    "moron": (303, [(1, 0.75), (5, 0.85)]),
    "retard": (304, [(1, 0.9), (5, 0.95)]),
    "retarded": (305, [(1, 0.85), (5, 0.9)]),
    "loser": (306, [(1, 0.6), (5, 0.8)]),
    "pathetic": (307, [(1, 0.6), (5, 0.75)]),
    "worthless": (308, [(1, 0.7), (5, 0.85)]),
    "ugly": (309, [(1, 0.5), (5, 0.7)]),
    "fat": (310, [(1, 0.4), (5, 0.6)]),
    "disgusting": (311, [(1, 0.5), (5, 0.65)]),
    "freak": (312, [(1, 0.5), (5, 0.7)]),
    "weirdo": (313, [(1, 0.4), (5, 0.6)]),
    "creep": (314, [(1, 0.5), (5, 0.7)]),
    "trash": (315, [(1, 0.6), (5, 0.75)]),
    "garbage": (316, [(1, 0.55), (5, 0.7)]),
    "hate": (317, [(1, 0.7), (5, 0.6)]),
    "hated": (318, [(1, 0.6), (5, 0.5)]),
    "despise": (319, [(1, 0.7), (5, 0.65)]),

    # Sexual explicit (category 6)
    "porn": (400, [(1, 0.7), (6, 0.95)]),
    "pornography": (401, [(1, 0.7), (6, 0.95)]),
    "xxx": (402, [(6, 0.95)]),
    "nsfw": (403, [(6, 0.9)]),
    "nude": (404, [(6, 0.85)]),
    "nudes": (405, [(6, 0.9)]),
    "naked": (406, [(6, 0.8)]),
    "sex": (407, [(6, 0.7)]),
    "sexual": (408, [(6, 0.75)]),
    "sexy": (409, [(6, 0.5)]),
    "horny": (410, [(6, 0.85)]),
    "orgasm": (411, [(6, 0.9)]),
    "masturbate": (412, [(6, 0.9)]),
    "masturbation": (413, [(6, 0.9)]),
    "hentai": (414, [(6, 0.95)]),
    "onlyfans": (415, [(6, 0.9)]),
    "hookup": (416, [(6, 0.7)]),
    "blowjob": (417, [(1, 0.7), (3, 0.8), (6, 0.95)]),
    "handjob": (418, [(1, 0.7), (3, 0.8), (6, 0.95)]),
    "boobs": (419, [(6, 0.7)]),
    "tits": (420, [(3, 0.6), (6, 0.75)]),
    "breasts": (421, [(6, 0.5)]),
    "pussy": (422, [(3, 0.85), (6, 0.85)]),
    "anal": (423, [(6, 0.85)]),
    "dildo": (424, [(6, 0.85)]),
    "vibrator": (425, [(6, 0.7)]),
    "fetish": (426, [(6, 0.75)]),
    "kinky": (427, [(6, 0.65)]),
    "erotic": (428, [(6, 0.7)]),
    "aroused": (429, [(6, 0.7)]),
    "climax": (430, [(6, 0.6)]),
    "cum": (431, [(3, 0.7), (6, 0.85)]),
    "ejaculate": (432, [(6, 0.85)]),

    # Racial/identity slurs (category 1 - toxicity, 5 - insult)
    "nigger": (500, [(1, 0.99), (2, 0.95), (5, 0.95)]),
    "nigga": (501, [(1, 0.85), (5, 0.7)]),
    "faggot": (502, [(1, 0.95), (2, 0.8), (5, 0.9)]),
    "fag": (503, [(1, 0.85), (5, 0.8)]),
    "dyke": (504, [(1, 0.8), (5, 0.75)]),
    "tranny": (505, [(1, 0.85), (5, 0.8)]),
    "chink": (506, [(1, 0.9), (5, 0.85)]),
    "spic": (507, [(1, 0.9), (5, 0.85)]),
    "wetback": (508, [(1, 0.9), (5, 0.85)]),
    "kike": (509, [(1, 0.95), (5, 0.9)]),
    "gook": (510, [(1, 0.9), (5, 0.85)]),

    # Drug-related (flag as toxic)
    "weed": (600, [(1, 0.4)]),
    "marijuana": (601, [(1, 0.4)]),
    "cocaine": (602, [(1, 0.6)]),
    "heroin": (603, [(1, 0.7)]),
    "meth": (604, [(1, 0.7)]),
    "ecstasy": (605, [(1, 0.5)]),
    "molly": (606, [(1, 0.5)]),
    "lsd": (607, [(1, 0.5)]),
    "overdose": (608, [(1, 0.6), (2, 0.7)]),
    "dealer": (609, [(1, 0.5)]),
    "drugs": (610, [(1, 0.4)]),
    "high": (611, []),  # Neutral - context dependent
    "stoned": (612, [(1, 0.4)]),
    "drunk": (613, [(1, 0.3)]),
    "wasted": (614, [(1, 0.3)]),

    # Grooming / predator phrases (severe toxicity)
    "secret": (700, [(2, 0.3)]),  # Context dependent
    "private": (701, []),
    "alone": (702, [(2, 0.2)]),
    "meet": (703, []),
    "picture": (704, []),
    "photo": (705, []),
    "camera": (706, []),
    "video": (707, []),
    "send": (708, []),
    "show": (709, []),

    # Bullying phrases
    "yourself": (800, []),  # Used in "kill yourself"
    "kys": (801, [(1, 0.95), (2, 0.95), (4, 0.9)]),  # Kill yourself abbreviation
    "nobody": (802, []),
    "everyone": (803, []),
    "hates": (804, [(1, 0.6), (5, 0.7)]),
    "loves": (805, []),
    "wants": (806, []),
    "cares": (807, []),

    # Common neutral words (needed for context)
    "you": (900, []),
    "your": (901, []),
    "i": (902, []),
    "me": (903, []),
    "my": (904, []),
    "we": (905, []),
    "they": (906, []),
    "the": (907, []),
    "a": (908, []),
    "is": (909, []),
    "are": (910, []),
    "was": (911, []),
    "be": (912, []),
    "to": (913, []),
    "of": (914, []),
    "and": (915, []),
    "in": (916, []),
    "that": (917, []),
    "have": (918, []),
    "it": (919, []),
    "for": (920, []),
    "not": (921, []),
    "on": (922, []),
    "with": (923, []),
    "he": (924, []),
    "she": (925, []),
    "at": (926, []),
    "this": (927, []),
    "but": (928, []),
    "from": (929, []),
    "or": (930, []),
    "an": (931, []),
    "will": (932, []),
    "all": (933, []),
    "would": (934, []),
    "there": (935, []),
    "their": (936, []),
    "what": (937, []),
    "so": (938, []),
    "if": (939, []),
    "about": (940, []),
    "go": (941, []),
    "get": (942, []),
    "should": (943, []),
    "just": (944, []),
    "dont": (945, []),
    "don't": (946, []),
    "tell": (947, []),
    "parent": (948, []),
    "parents": (949, []),
    "mom": (950, []),
    "dad": (951, []),
    "anyone": (952, []),
}


def create_embedding_matrix():
    """
    Create an embedding matrix where toxic words have embeddings
    that will activate the appropriate output categories.
    """
    print("Creating embedding matrix with toxicity patterns...")

    # Initialize with ZEROS - neutral words should not activate anything
    embedding_matrix = np.zeros((VOCAB_SIZE, EMBEDDING_DIM), dtype=np.float32)

    # Add tiny random noise only to prevent degenerate cases
    np.random.seed(42)
    embedding_matrix += np.random.randn(VOCAB_SIZE, EMBEDDING_DIM).astype(np.float32) * 0.001

    # For each toxic word, set embedding dimensions to encode toxicity
    # We use specific dimensions for each category
    category_dims = {
        1: list(range(0, 9)),    # toxicity: dims 0-8
        2: list(range(9, 18)),   # severe_toxicity: dims 9-17
        3: list(range(18, 27)),  # obscene: dims 18-26
        4: list(range(27, 36)),  # threat: dims 27-35
        5: list(range(36, 45)),  # insult: dims 36-44
        6: list(range(45, 54)),  # sexual_explicit: dims 45-53
    }

    for word, (idx, categories) in TOXIC_VOCABULARY.items():
        if idx < VOCAB_SIZE and categories:  # Only set for words with categories
            # First zero out the row to remove noise
            embedding_matrix[idx, :] = 0.0
            for cat_idx, weight in categories:
                if cat_idx in category_dims:
                    for dim in category_dims[cat_idx]:
                        embedding_matrix[idx, dim] = weight * 8.0  # Very strong signal for toxic words

    return embedding_matrix


def create_model_with_weights():
    """Create the model and set weights for toxicity detection."""
    print("Creating model architecture...")

    # Model architecture
    model = tf.keras.Sequential([
        tf.keras.layers.InputLayer(input_shape=(MAX_SEQ_LENGTH,), dtype=tf.int32),
        tf.keras.layers.Embedding(
            VOCAB_SIZE,
            EMBEDDING_DIM,
            input_length=MAX_SEQ_LENGTH,
            name='embedding'
        ),
        tf.keras.layers.GlobalAveragePooling1D(name='pooling'),
        tf.keras.layers.Dense(64, activation='relu', name='dense1'),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(32, activation='relu', name='dense2'),
        tf.keras.layers.Dense(NUM_CLASSES, activation='sigmoid', name='output')
    ])

    model.compile(
        optimizer='adam',
        loss='binary_crossentropy',
        metrics=['accuracy']
    )

    # Set embedding weights
    embedding_matrix = create_embedding_matrix()
    model.layers[0].set_weights([embedding_matrix])

    # Set dense layer weights to properly route embeddings to categories
    # Dense1: 64 input -> 64 output
    # This layer should pass through the category signals
    dense1_weights = np.eye(EMBEDDING_DIM, 64).astype(np.float32)  # Identity-like
    dense1_bias = np.zeros(64).astype(np.float32)
    model.layers[2].set_weights([dense1_weights, dense1_bias])

    # Dense2: 64 -> 32
    # Compress the 54 category dimensions into 32, grouping by category
    dense2_weights = np.zeros((64, 32), dtype=np.float32)
    dense2_bias = np.zeros(32).astype(np.float32)

    # Route category dimensions to compressed representation
    # Each category gets ~5 output dimensions
    for cat_idx in range(1, NUM_CLASSES):
        src_start = (cat_idx - 1) * 9  # 9 dims per category in embedding
        dst_start = (cat_idx - 1) * 5  # 5 dims per category in dense2 output
        for i in range(min(9, 64 - src_start)):
            for j in range(min(5, 32 - dst_start)):
                if src_start + i < 64 and dst_start + j < 32:
                    dense2_weights[src_start + i, dst_start + j] = 1.0  # Stronger routing

    model.layers[4].set_weights([dense2_weights, dense2_bias])

    # Output layer: 32 -> 7 (NUM_CLASSES)
    output_weights = np.zeros((32, NUM_CLASSES)).astype(np.float32)
    # Strong negative bias for all categories (default to not flagging)
    output_bias = np.array([2.0, -3.0, -3.0, -3.0, -3.0, -3.0, -3.0]).astype(np.float32)

    # Safe class: positive when no toxic signals
    for i in range(32):
        output_weights[i, 0] = -0.3  # Any toxic signal reduces safe

    # Route compressed dimensions to output categories
    for cat_idx in range(1, NUM_CLASSES):
        src_start = (cat_idx - 1) * 5
        for d in range(5):
            if src_start + d < 32:
                output_weights[src_start + d, cat_idx] = 4.0  # Very strong signal
                output_weights[src_start + d, 0] = -1.0  # Reduce safe more

    model.layers[5].set_weights([output_weights, output_bias])

    print("Model weights configured for toxicity detection")
    model.summary()

    return model


def test_model(model):
    """Test the model with sample inputs."""
    print("\n" + "="*60)
    print("Testing model predictions...")
    print("="*60)

    # Create simple tokenizer
    word_to_idx = {word: idx for word, (idx, _) in TOXIC_VOCABULARY.items()}

    def tokenize(text):
        tokens = []
        for word in text.lower().split():
            # Clean word
            word = ''.join(c for c in word if c.isalnum() or c == "'")
            idx = word_to_idx.get(word, 1)  # 1 = UNK
            tokens.append(idx)
        # Pad/truncate
        if len(tokens) < MAX_SEQ_LENGTH:
            tokens = tokens + [0] * (MAX_SEQ_LENGTH - len(tokens))
        else:
            tokens = tokens[:MAX_SEQ_LENGTH]
        return np.array([tokens], dtype=np.int32)

    test_cases = [
        "hello how are you today",
        "you are so stupid",
        "i will kill you",
        "send me nudes",
        "fuck you idiot",
        "kys nobody likes you",
        "don't tell your parents",
        "you should kill yourself",
    ]

    for text in test_cases:
        tokens = tokenize(text)
        predictions = model.predict(tokens, verbose=0)[0]

        print(f"\nText: '{text}'")
        print(f"  Predictions:")
        for i, (label, prob) in enumerate(zip(LABELS, predictions)):
            if prob > 0.1 or i == 0:  # Show safe and any prob > 0.1
                marker = "⚠️" if prob > 0.5 and i > 0 else "  "
                print(f"    {marker} {label}: {prob:.3f}")


def convert_to_tflite(model, output_path):
    """Convert model to TFLite format."""
    print(f"\nConverting to TFLite: {output_path}")

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    # Use float16 quantization for good balance of size/accuracy
    converter.target_spec.supported_types = [tf.float16]

    tflite_model = converter.convert()

    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    size_kb = os.path.getsize(output_path) / 1024
    print(f"Model saved: {size_kb:.1f} KB")

    return tflite_model


def create_vocab_file(output_path):
    """Create vocabulary file for Android tokenizer."""
    print(f"Creating vocabulary file: {output_path}")

    # Create ordered vocabulary
    vocab = [""] * VOCAB_SIZE
    for word, (idx, _) in TOXIC_VOCABULARY.items():
        if idx < VOCAB_SIZE:
            vocab[idx] = word

    # Fill empty slots
    for i in range(VOCAB_SIZE):
        if vocab[i] == "":
            vocab[i] = f"[UNUSED{i}]"

    with open(output_path, 'w') as f:
        for word in vocab:
            f.write(f"{word}\n")

    print(f"Vocabulary size: {VOCAB_SIZE}")


def create_labels_file(output_path):
    """Create labels file."""
    print(f"Creating labels file: {output_path}")

    with open(output_path, 'w') as f:
        for label in LABELS:
            f.write(f"{label}\n")


def main():
    print("="*60)
    print("SafeGuard Toxicity Model Creator")
    print("="*60)

    # Paths
    script_dir = os.path.dirname(os.path.abspath(__file__))
    assets_dir = os.path.join(os.path.dirname(script_dir), "app", "src", "main", "assets")
    os.makedirs(assets_dir, exist_ok=True)

    model_path = os.path.join(assets_dir, "text_classifier.tflite")
    vocab_path = os.path.join(assets_dir, "vocab.txt")
    labels_path = os.path.join(assets_dir, "labels.txt")

    print(f"\nOutput directory: {assets_dir}")

    # Create model
    model = create_model_with_weights()

    # Test model
    test_model(model)

    # Convert and save
    convert_to_tflite(model, model_path)
    create_vocab_file(vocab_path)
    create_labels_file(labels_path)

    print("\n" + "="*60)
    print("✅ Model files created successfully!")
    print("="*60)
    print(f"\nFiles:")
    print(f"  - {model_path}")
    print(f"  - {vocab_path}")
    print(f"  - {labels_path}")
    print(f"\nThe model is ready to use in the SafeGuard Android app.")
    print("It will work alongside the regex-based detection for better coverage.")


if __name__ == "__main__":
    main()
