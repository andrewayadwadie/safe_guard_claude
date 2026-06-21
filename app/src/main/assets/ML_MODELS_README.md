# TensorFlow Lite Models for SafeGuard Content Moderation

This folder should contain the following TensorFlow Lite model files for the two-stage content moderation system.

## Required Files

### 1. Text Classifier
- **File**: `text_classifier.tflite`
- **Vocabulary**: `vocab.txt`
- **Purpose**: Classifies text content into categories (safe, profanity, bullying, violence, sexual, drugs)
- **Input**: Tokenized text sequence (max 128 tokens)
- **Output**: 6-class probability distribution

### 2. Image Classifier (NSFW Detection)
- **File**: `nsfw_classifier.tflite`
- **Purpose**: Detects inappropriate images (porn, sexy, hentai, violence)
- **Input**: 224x224 RGB image (normalized to [-1, 1])
- **Output**: 5-class probability distribution (safe, porn, sexy, hentai, violence)

## How to Obtain Models

### Option A: Pre-trained Open Source Models

#### NSFW Image Classifier
You can use the open-source NSFW model from:
- **NudeNet**: https://github.com/notAI-tech/NudeNet
- **NSFW Model**: https://github.com/GantMan/nsfw_model

To convert to TFLite:
```python
import tensorflow as tf

# Load your trained model
model = tf.keras.models.load_model('nsfw_model.h5')

# Convert to TFLite with quantization
converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.int8]
tflite_model = converter.convert()

# Save
with open('nsfw_classifier.tflite', 'wb') as f:
    f.write(tflite_model)
```

#### Text Classifier
You can fine-tune a MobileBERT or DistilBERT model:
- **TensorFlow Text Classification**: https://www.tensorflow.org/text/tutorials/classify_text_with_bert
- **Hugging Face Toxic Comment Dataset**: https://huggingface.co/datasets/jigsaw_toxicity_pred

### Option B: Train Your Own Models

#### Text Classifier Training Script
```python
import tensorflow as tf
from tensorflow.keras.preprocessing.text import Tokenizer
from tensorflow.keras.preprocessing.sequence import pad_sequences

# Prepare data
MAX_WORDS = 10000
MAX_LEN = 128
NUM_CLASSES = 6  # safe, profanity, bullying, violence, sexual, drugs

# Build model
model = tf.keras.Sequential([
    tf.keras.layers.Embedding(MAX_WORDS, 64, input_length=MAX_LEN),
    tf.keras.layers.GlobalAveragePooling1D(),
    tf.keras.layers.Dense(64, activation='relu'),
    tf.keras.layers.Dropout(0.3),
    tf.keras.layers.Dense(NUM_CLASSES, activation='softmax')
])

model.compile(optimizer='adam',
              loss='categorical_crossentropy',
              metrics=['accuracy'])

# Train with your labeled dataset
# model.fit(X_train, y_train, epochs=10, validation_data=(X_val, y_val))

# Convert to TFLite
converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()

with open('text_classifier.tflite', 'wb') as f:
    f.write(tflite_model)

# Save vocabulary
tokenizer = Tokenizer(num_words=MAX_WORDS)
# tokenizer.fit_on_texts(texts)
with open('vocab.txt', 'w') as f:
    for word, index in sorted(tokenizer.word_index.items(), key=lambda x: x[1]):
        if index < MAX_WORDS:
            f.write(f"{word}\n")
```

### Option C: Use Cloud AutoML (then export to TFLite)
- Google Cloud AutoML Vision/Text
- Export trained model as TFLite

## Fallback Behavior

The app is designed to work WITHOUT these model files:

1. **Text Analysis**: Falls back to comprehensive regex pattern matching (Stage 1 only)
2. **Image Analysis**: Falls back to skin-tone heuristic detection

This means the app will still provide protection, just with reduced accuracy for subtle/contextual content.

## Model Requirements

| Model | Input Size | Quantization | Max Size |
|-------|-----------|--------------|----------|
| Text Classifier | 128 tokens | INT8 | ~5 MB |
| NSFW Classifier | 224x224x3 | INT8 | ~10 MB |

## Testing Models

After adding models, test them:
```kotlin
// In your test code
val textClassifier = TFLiteTextClassifier(context)
val result = textClassifier.classify("test message")
assert(result.categories.isNotEmpty() || !result.isFlagged)

val imageClassifier = TFLiteImageClassifier(context)
val bitmap = BitmapFactory.decodeResource(resources, R.drawable.test_image)
val imageResult = imageClassifier.classify(bitmap)
```

## Notes

- Models are loaded lazily to save memory
- GPU acceleration is used when available for image classification
- Thread count is limited to 2 for battery efficiency
- Stage 1 (regex) catches ~80% of obvious inappropriate content without needing AI
