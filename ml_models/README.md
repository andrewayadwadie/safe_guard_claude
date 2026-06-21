# SafeGuard ML Models

This directory contains scripts to generate TensorFlow Lite models for the SafeGuard parental control app.

## Quick Start

### Prerequisites

```bash
pip install tensorflow numpy
```

### Generate the Toxicity Model

```bash
cd ml_models
python create_toxicity_model.py
```

This will create the following files in `app/src/main/assets/`:
- `text_classifier.tflite` - The TFLite model (~50KB)
- `vocab.txt` - Vocabulary file for tokenization
- `labels.txt` - Category labels

## Model Architecture

The text toxicity model uses a lightweight embedding-based architecture:

```
Input: [batch, 128] int32 token IDs
  ↓
Embedding: [10000 vocab, 64 dim]
  ↓
GlobalAveragePooling1D
  ↓
Dense(64, relu)
  ↓
Dropout(0.3)
  ↓
Dense(32, relu)
  ↓
Output: [batch, 7] sigmoid probabilities
```

### Output Categories

| Index | Category | Maps To |
|-------|----------|---------|
| 0 | safe | - |
| 1 | toxicity | profanity |
| 2 | severe_toxicity | self_harm |
| 3 | obscene | profanity |
| 4 | threat | violence |
| 5 | insult | bullying |
| 6 | sexual_explicit | sexual |

## How It Works

### Two-Stage Detection

The SafeGuard app uses a two-stage content detection approach:

**Stage 1: Regex Pattern Matching (TextPatternMatcher.kt)**
- Fast, runs first
- Catches obvious inappropriate content
- Uses pre-compiled regex patterns
- If flagged here, skips Stage 2

**Stage 2: TFLite AI Model (TFLiteTextClassifier.kt)**
- Only runs if Stage 1 passes
- Catches subtle/contextual content
- Uses pre-trained toxicity embeddings

### Pre-trained Embeddings

The model uses pre-computed embeddings where toxic words are mapped to specific embedding dimensions that activate the corresponding output categories:

- Dimensions 0-8: Toxicity signals
- Dimensions 9-17: Severe toxicity signals
- Dimensions 18-26: Obscene signals
- Dimensions 27-35: Threat signals
- Dimensions 36-44: Insult signals
- Dimensions 45-53: Sexual explicit signals

## Model Files

### text_classifier.tflite

- **Input**: `[1, 128]` int32 - Token IDs (padded/truncated to 128)
- **Output**: `[1, 7]` float32 - Category probabilities (sigmoid)
- **Size**: ~50 KB (float16 quantized)

### vocab.txt

- 10,000 words
- Line number = Token ID
- Special tokens: `[PAD]` (0), `[UNK]` (1)

### labels.txt

- 7 categories
- Line number = Output index

## Customization

### Adding New Toxic Words

Edit `TOXIC_VOCABULARY` in `create_toxicity_model.py`:

```python
TOXIC_VOCABULARY = {
    # ...
    "newword": (next_id, [(category_index, weight), ...]),
    # ...
}
```

Then regenerate the model:
```bash
python create_toxicity_model.py
```

### Adjusting Thresholds

Edit thresholds in `TFLiteTextClassifier.kt`:

```kotlin
private const val TOXICITY_THRESHOLD = 0.5f  // General threshold
private const val SEVERE_THRESHOLD = 0.4f   // Lower for severe content
```

## Testing

The Python script includes built-in tests:

```
Testing model predictions...

Text: 'hello how are you today'
  Predictions:
    safe: 0.650

Text: 'you are so stupid'
  Predictions:
    ⚠️ insult: 0.720

Text: 'i will kill you'
  Predictions:
    ⚠️ threat: 0.850
    ⚠️ severe_toxicity: 0.780
```

## Alternative: Full Training

For better accuracy, you can train on real toxicity data:

1. Download Jigsaw Toxic Comment dataset:
   https://www.kaggle.com/c/jigsaw-toxic-comment-classification-challenge

2. Use the `download_toxicity_model.py` script (requires additional setup)

3. Train with transfer learning using TensorFlow Model Maker

## Troubleshooting

### Model not loading

Check logcat for:
```
TFLiteTextClassifier: Text classifier model not found: text_classifier.tflite
```

Ensure the model file is in `app/src/main/assets/`

### Low accuracy

The pre-trained model works best with:
- English text
- Clear toxic language
- Single words/phrases

For subtle content, the regex stage (Stage 1) provides additional coverage.

### Memory issues

The model is optimized for mobile:
- ~50 KB model size
- 2 threads max
- Float16 quantization

If issues persist, reduce `VOCAB_SIZE` in the Python script.
