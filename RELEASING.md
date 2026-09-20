# リリース手順

タグ（`v*`）を push すると、GitHub Actions（`.github/workflows/release.yaml`）が、APK をビルドして、
GitHub Release を作り、APK を添付します。リリースノート（変更履歴）は、前のリリースからマージされた PR を、
GitHub が自動で並べて作ります。

## 手順

1. **バージョンを上げる PR を出して、マージする。** `app/build.gradle.kts` の `versionName` を、これから打つタグから
   `v` を除いたものにする（`v1.0.1` なら `"1.0.1"`）。`versionCode` も、前のリリースより大きくする。
2. **`master` を最新にして、タグを打つ。** タグは、`master` に含まれるコミットに打つ（そうでないと、リリースは失敗する）。

   ```console
   git switch master && git pull
   git tag -s v1.0.1 -m "v1.0.1"   # 署名しないなら -a
   git push origin v1.0.1
   ```

3. Actions の「Release」の実行が終わると、Releases に、`voicevox-tts-engine-v1.0.1.apk` が添付されます。

- **プレリリース:** `v1.1.0-alpha.1` のように、`-` を含むタグは、プレリリースになります（`versionName` も同じ文字列にする）。
- **タグを間違えたとき:** リリースを作る前に失敗するので、`git push --delete origin <タグ>` でタグを消して、直してから打ち直す。
- **リリースを作らずに、ビルドだけ試す:** Actions の「Release」を、`workflow_dispatch`（手動実行）で実行する。

## 注意

- **APK は、デバッグ鍵で署名されます。** 鍵はビルドのたびに変わるので、前のリリースの上には、更新インストールできません
  （いったんアンインストールが必要で、アプリのデータは消えます）。更新できるようにするには、固定の鍵
  （keystore）を Secrets に登録して、その鍵で署名する必要があります。
- APK には、音声モデル（VVM）が含まれます。配布するときの利用規約とクレジット表記は、`README.md` を参照してください。
