<img width="1174" height="379" alt="piyingxi-github-3" src="https://github.com/user-attachments/assets/bce7ade3-2ff2-4ccd-a67c-7ae31ffbea64" />

# piyingxi audio shadowing tool

piyingxi (譬影戏) is a specialist language shadowing tool currently in development for tonal language study in the classroom. 

piyingxi integrates audio shadowing capabilities with pitch and tone visualisation to address the needs of tonal language learners in the classroom. Students and instructors can observe visualised tones and make recordings that can be plotted to an overlapping contour line for instant and objective visual and aural feedback.

piyingxi is specifically designed for the study of Chinese languages, allowing visual insight and feedback into the actual tones of each character-associated syllable, rather than an alternative reliance on canonical tone defintions or abstracted phonological tone descriptions. The aim is to develop a useful tool for studying Chinese languages and lesser-documented dialects in particular, for which tone study remains a largely insurmountable barrier for the non-native student, but can also be used for the study of any tonal language and is intended to be adapted for independent study usage in the near future.

The latest alpha of piyingxi integrates a basic version of the qTA model (quantitative Target Approximation, see Xu, Y. & Prom-on, S. (2010-present year). qTAtrainer.praat. Available from: http://www.homepages.ucl.ac.uk/~uclyyix/qTAtrainer/) for synthetic tone contour generation. This can be very helpful for seeing how stress and focus manipulate overall sentence-level tone contour:

<img width="1646" height="748" alt="Screenshot From 2026-11-17 14-15-45" src="https://github.com/user-attachments/assets/f123829a-926f-48d2-997c-60e4b057bcf5" />

*Blue line shows tone contour drawn from spectrogram analysis of audio, orange-dotted line shows synthetic contour using the Target Approximation model.*

## Features

- auto-segmentation of audio files for listening and shadowing practice
- subtitle panel per segment that can be shown or hidden
- auto and manual recording modes for shadowing audio with accompanying aural and visual feedback
- spectrogram and algorithmic tone contour visualisation for comparison of source and shadowed tone realisation
- pitch and octave adjustment to align different voice pitches during speaker comparison
- manual contour draw tool for missed vocal pulses and octave shift bar for easy octave correction
- initial integration of the qTA model

<img width="1646" height="748" alt="Screenshot From 2026-11-17 14-16-33" src="https://github.com/user-attachments/assets/eabda26e-00d2-4f7a-928d-07e11faa9c1f" />

*Toggled visibility of the phonetic analysis window for focused audio shadowing sessions. Subtitles can also be toggled on/off for further focus. Piyingxi also has a built in shadowing session timer (top left).*

## Installation

### Requirements

- JDK (needs `javac`, not just the JRE)

On Debian:

```bash
sudo apt update
sudo apt install default-jdk git
```

### Build and run

```bash
git clone https://github.com/tomsima/piyingxi.git
cd piyingxi

# Compile
javac -cp "jlayer-1.0.1.4.jar:flatlaf-3.5.4.jar" Piyingxi.java

# Run
java -cp ".:jlayer-1.0.1.4.jar:flatlaf-3.5.4.jar" Piyingxi
```

### Optional: build a runnable jar

```bash
cat > manifest.txt << 'EOF'
Main-Class: Piyingxi
Class-Path: jlayer-1.0.1.4.jar flatlaf-3.5.4.jar

EOF
jar cfm piyingxi.jar manifest.txt *.class
java -jar piyingxi.jar
```

## Project background

This project was born out of a need for a shadowing tool for Chinese language learners, one that specifically allows for spectral analysis of tone within the shadowing environment. The Windows and Android-only software WorkAudioBook was the original inspiration for this project, as it is no longer supported on newer machines, and in the development of piyingxi the opportunity was taken to address the unique needs of the tonal language learner who adopts the shadowing technique for their pronunciation practice.    
