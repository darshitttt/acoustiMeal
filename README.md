# AcoustiMeal: On-Device Meal Duration Estimation Using Audio Tagging

AcoustiMeal is an Android application designed to estimate meal timing and duration using on-device audio analysis. It leverages a pre-trained audio tagging model (E-PANN) to detect meal-related acoustic events such as cutlery sounds, dish handling, and chewing. All processing is performed locally to preserve user privacy.

This repository contains the complete source code for the demo presented in our paper.

- If you want to download the APK directly, you can download it from [here](https://drive.google.com/file/d/1a1MY6i05AA5rPJ2lkrwkK_eViz25WPsi/view?usp=drive_link)
- If you want to use the source code to rebuild the app from scratch and extend it, you will need to download the ONNX version of the EPANNs model from [here](https://drive.google.com/file/d/1sttPURJ7AdaZxyGGrIqZoRe2F1MzBZS-/view?usp=drive_link)

---

## Overview

Estimating meal duration plays a crucial role in understanding eating behaviour and its relationship to chronic diseases. AcoustiMeal offers a discreet and privacy-preserving approach for capturing mealtime activity using audio tags.

The app workflow:

1. **Recording Setup**  
   Users select a two-hour time window during which a meal is expected. This avoids continuous background recording.

2. **Recording Control**  
   If the recording window is active, audio recording begins immediately; otherwise it starts automatically when the window begins. Users can stop it at any time.

3. **Real-Time Processing**  
   Audio is downsampled and processed on-device using E-PANN.  
   Predictions are generated every 2 seconds using a 500 ms hop size.  
   Detected audio tags and confidence scores are displayed in real time.

4. **Meal Duration Estimation**  
   The app looks for meal-related tags such as:
   - *Cutlery/silverware*  
   - *Dishes, pots, pans*  
   - *Chewing/mastication*  

   The first occurrence marks the **meal start**.  
   The **meal end** is estimated when no meal-related tags appear for 5 minutes.

5. **Data Storage**  
   After the session, the estimated meal duration is saved automatically to the phone’s `Documents` directory.

Optional: The app can store all predicted tags for offline analysis.

---

## Features

- Fully on-device audio inference using E-PANN  
- Privacy-preserving (no raw audio stored)  
- Real-time audio tag predictions  
- Automatic meal start/end detection  
- Export of estimated meal durations  
- Optional logging of all predictions  

---

## Contact

For any questions or contributions, feel free to open an issue or submit a pull request.

---

**AcoustiMeal** aims to support research in Health Psychology by enabling scalable, in-the-wild analysis of mealtime behaviour. The code is released to encourage reproducibility and extension in future studies.
