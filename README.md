# Piyingxi shadowing tool

Piyingxi (皮影戏) is a specialist shadowing tool for tonal language study. 

<img width="1920" height="1048" alt="piyingxi-screenshot" src="https://github.com/user-attachments/assets/3a020d3a-c10b-4b7e-a57a-40f30a1cff8b" />

Piyingxi integrates audio shadowing capabilities with pitch and tone visualisation to address the needs of tonal language learners in the classroom and in independent study. It is uniquely suited to those studying tonal languages, allowing visual insight and feedback into the actual tones of the language under study, rather than an alternative reliance on canonical tone defintions or abstracted phonological and phonetic tone descriptions. Piyingxi is a useful tool for studying Chinese dialects in particular, for which tone study remains a laregely insurmountable barrier for the non-native student.

## Installation

Piyingxi is written in Java and is designed to be built from source on a Linux machine with JDK from terminal using:

cd ~/piyingxi

javac -cp jlayer-1.0.1.4.jar:flatlaf-3.5.4.jar Piyingxi.java

jar cfm piyingxi.jar manifest.txt *.class

