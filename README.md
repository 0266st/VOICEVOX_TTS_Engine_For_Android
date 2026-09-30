# VOICEVOX TTS Engine for Android

**日本語**
## 説明
このソフトウェアはAndroidの読み上げをVOICEVOXに置き換えるソフトウェアです。VOICEVOX COREのAndroid用AARファイルを使用しています。このソフトウェアはMITライセンスで提供されています。
デフォルトでは冥鳴ひまりの音声が使用されます。

## ビルド方法
VOICEVOX COREのJavaパッケージ・VOICEVOX ONNX Runtime・音声モデルは、リポジトリには含めていません。ビルドの前に次のタスクで取得します。

```console
./gradlew :app:downloadVoicevox
./gradlew assembleDebug
```

取得したファイルは次の場所に置かれます（どれもgitの管理対象外）。

- VOICEVOX CORE: `~/.m2/repository`（`mavenLocal()` から解決されます）
- VOICEVOX ONNX Runtime: `app/src/main/jniLibs/`
- 音声モデル: `app/src/main/res/raw/model.vvm`（[voicevox_vvm](https://github.com/VOICEVOX/voicevox_vvm/releases) の1.vvm）

## ファイル等について (res/raw)
open_jtalk_dict.zipは、OpenJTalkの辞書です。

**English**
## Introduction
This is software that replaces Android reading with VOICEVOX, using VOICEVOX CORE AAR files for Android. This software is provided under the MIT license.
By default, the voice of "Himari Meimei" is used.

## Build
The VOICEVOX CORE Java packages, VOICEVOX ONNX Runtime and the voice model are not checked in. Fetch them before building:

```console
./gradlew :app:downloadVoicevox
./gradlew assembleDebug
```

## Files (res/raw)
model.vvm is 1.vvm from [voicevox_vvm](https://github.com/VOICEVOX/voicevox_vvm/releases) (downloaded by `downloadVoicevox`).

## 各種権利表記
VOICEVOX：冥鳴ひまり 

VOICEVOX CORE: https://github.com/VOICEVOX/voicevox_core (MIT License)

OpenJTalk: https://open-jtalk.sourceforge.net/
Copyright (c) 2009, Nara Institute of Science and Technology, Japan.

ONNX Runtime: https://github.com/microsoft/onnxruntime
VOICEVOX ONNX Runtime: https://github.com/VOICEVOX/onnxruntime-builder

Gson: https://github.com/google/gson
