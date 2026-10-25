# Piyingxi spectrogram shadowing tool

Piyingxi (皮影戏) is a specialist shadowing tool for tonal language study. 

Piyingxi integrates audio shadowing capabilities with pitch and tone visualisation to address the needs of tonal language learners in the classroom and in independent study. It is specifically suited to those studying Chinese languages, allowing visual insight and feedback into the actual tones of each character-associated syllable, rather than an alternative reliance on canonical tone defintions or abstracted phonological tone descriptions. Piyingxi is a useful tool for studying Chinese languages and lesser-documented dialects in particular, for which tone study remains a largely insurmountable barrier for the non-native student, but can also be used for the study of any tonal language.

<img width="1920" height="1048" alt="piyingxi-screenshot" src="https://github.com/user-attachments/assets/3a020d3a-c10b-4b7e-a57a-40f30a1cff8b" />

## Features

- auto-segmentation of audio files for listening and shadowing practice
- subtitle panel per segment that can be shown or hidden
- auto and manual recording modes for shadowing audio with accompanying aural and visual feedback
- spectrogram and algorithmic tone contour visualisation for comparison of source and shadowed tone realisation
- pitch and octave adjustment to align different voice pitches during speaker comparison
- manual contour draw tool for missed vocal pulses 

## Installation

Piyingxi is written in Java and is designed to be built from source on a Linux machine with JDK from terminal using:

cd ~/piyingxi

javac -cp jlayer-1.0.1.4.jar:flatlaf-3.5.4.jar Piyingxi.java

jar cfm piyingxi.jar manifest.txt *.class

## Project background

This project was born out of a need for a shadowing tool for Chinese language learners, one that specifically allows for spectral analysis of tone within the shadowing environment. The Windows and Android-only software WorkAudioBook was the original inspiration for this project, as it is no longer supported on newer machines, and in the development of Piyingxi the opportunity was taken to address the unique needs of the tonal language learner who adopts the shadowing technique for their pronunciation practice.    
