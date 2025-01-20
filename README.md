# Face Detection App README

This guide will help you run the mobile app on your device and customize it by replacing or updating the model.

---

## Cloning the Repository

To get started with the app, clone the repository to your local machine:

1. Open a terminal or command prompt.
2. Run the following command to clone the repository:
   ```bash
   git clone https://github.com/yourusername/your-repo-name.git
## How to Run the App

1. Navigate to the following folder in your project:
   ```bash
     app/build/outputs/apk/debug
  2. Locate the `app-debug.apk` file.
3. Download the `app-debug.apk` file to your phone.
4. Install the APK on your phone. You may need to allow installation from unknown sources in your phone's settings.
5. Once installed, open the app and grant it permission to use the **camera** and **storage**.
6. You can now use the app!

---

## How to Change the Model

To replace or update the model used by the app:

1. Convert your TensorFlow model to TensorFlow Lite (`.tflite`) format. Use the following code snippet to convert a TensorFlow model to `.tflite`:

```python
import tensorflow as tf
```

# Load your TensorFlow model
```python
model = tf.keras.models.load_model('your_model.h5')
```

# Convert the model to TensorFlow Lite format
```python
converter = tf.lite.TFLiteConverter.from_keras_model(model)
tflite_model = converter.convert()
```

# Save the converted model
```python
with open('model.tflite', 'wb') as f:
    f.write(tflite_model)
```

## Replace the Existing Model

To replace or update the model used by the app:

1. Navigate to the app's assets folder:
   ```bash
     app/src/main/assets
2. Delete the existing `.tflite` file.
3. Add your new `.tflite` file to the folder.

---

## Update Class Labels

To ensure the app recognizes the new model's output classes:

1. Open the `class_labels.txt` file located in:
   ```bash
     app/src/main/assets
2. Add or modify the labels to match the new model's output classes.

---

## Adding New People to the Model

To add new people to the model:

1. Train the model on the new dataset, including the new people.
2. Convert the updated model to `.tflite` format (as described above).
3. Replace the existing `.tflite` model in the `app/src/main/assets` folder.
4. Update the `class_labels.txt` file in the same folder to include the names of the new people.

---

## Permissions

The app requires the following permissions:
- **Camera**: To capture images or video for processing.
- **Storage**: To save or load files, such as models or output data.

---

## Troubleshooting

- If the app crashes or doesn't work after replacing the model, ensure that:
- The `.tflite` model is correctly converted and compatible with TensorFlow Lite.
- The `class_labels.txt` file matches the output classes of the new model.
- If the app doesn't install, ensure that your phone allows installation from unknown sources and has the storage and camera permission.

