# 澶囪鎼瓙 v1.0.0

鍙戝竷鏃ユ湡锛歿{DATE}}

杩欐槸鈥滄暀妗堣璁″鏅鸿兘浣撴敮鎸佺郴缁熲€濈殑棣栦釜鍐荤粨婕旂ず鐗堟湰銆傝鐗堟湰浠ュ凡缁忓畬鎴愪竴閿惎鍔ㄥ拰鏁撮摼楠屾敹鐨?`LessonAgentPlatform-Release` 涓哄敮涓€浠ｇ爜鍩虹嚎銆?
## 鏍稿績鑳藉姏

- 缁熶竴 Web 宸ヤ綔鍙般€佹垜鐨勬暀妗堛€佷换鍔¤褰曚笌宸ヤ綔娴佸叆鍙ｃ€?- DOCX 鏁欐瀵煎叆銆佹鏂囬瑙堛€佺粺涓€ `lessonId/versionId` 鐗堟湰绠＄悊銆?- F1 鏅鸿兘璇勪环锛氭暀妗堣瘎鍒嗐€丄BC 绛夌骇銆佷竴绾?浜岀骇缁村害銆侀浄杈惧浘鍙婃壒娉ㄧ粨鏋溿€?- F2 璁捐淇敼锛氭暀妗堢敓鎴愩€佸凡鏈夋暀妗堜紭鍖栥€佹暀甯堝鏍稿拰鏂扮増鏈繚瀛樸€?- F3 璇惧爞鎺ㄦ紨锛歊EAL 妯″瀷銆丮anager 鍔ㄦ€佽皟搴︺€佸畬鏁磋鍫備簨浠躲€侀棶棰樺垎鏋愬拰缁撴灉鍥炲啓銆?- F4 鍗忓悓鍏辨瀽锛氶€愯妭寤鸿銆佷氦浜掍慨璁€佺増鏈啿绐佷繚鎶ゅ拰缁撴灉鍐欏洖銆?- 鍏叡闂姹狅細姹囬泦 F1/F3/F4 闂锛屽苟涓?F2/F4 鎻愪緵涓婁笅鏂囥€?- Windows/Linux 瀹夎璇存槑锛屼互鍙婂惎鍔ㄣ€佸仠姝€佸仴搴锋鏌ヨ剼鏈€?
## 宸插喕缁撶殑鍏抽敭绾︽潫

- F1 浣跨敤褰撳墠 `System-v1.2`锛屼笉寰楃敤鏃?`System-v1.0` 鎴栨棫 `platform_api.py` 瑕嗙洊銆?- F3 姝ｅ紡閾句娇鐢ㄥ紓姝?Session API 鍜屽姩鎬佽鍫傦紝涓嶄娇鐢ㄥ浐瀹氬洓杞綔涓哄畬鎴愭潯浠躲€?- F2 MySQL/Flyway銆佺粺涓€鏁欐鐗堟湰銆丼toragePort 鍜屽叕鍏遍棶棰樿〃涓烘寮忔暟鎹摼璺€?- F4 鐨?`MAX_CONTEXT_TOKENS` 鍙戝竷妯℃澘璁句负 `96000`锛涜繖鍙槸涓婁笅鏂囦繚鎶ら绠楋紝涓嶆敼鍙?Agent 鎴栨ā鍨嬮€昏緫銆?- 鐢ㄦ埛鏈€鍒濅笂浼犵殑 DOCX 涓庡綋鍓嶇増鏈寮忓鍑烘枃浠跺垎寮€淇濆瓨銆?
## 瀹夎涓庡惎鍔?
1. 瑙ｅ帇婧愮爜鍖呫€?2. 鎸?`docs/INSTALL_WINDOWS.md` 鎴?`docs/INSTALL_LINUX.md` 瀹夎杩愯鐜銆?3. 灏?`.env.example` 澶嶅埗涓?`.env`锛屽～鍐?API Key銆丮ySQL 鍜屽唴閮?Token銆?4. Windows 鎵ц锛?
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1
   ```

5. 鍋ュ悍妫€鏌ワ細

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\health-check.ps1
   ```

6. 榛樿鍓嶇鍦板潃锛歚http://127.0.0.1:5177`銆?
## 杩愯鐜

- Windows 11 鎴栧吋瀹?Linux 鍙戣鐗?- Python 3.11/3.12锛堝悇妯″潡鐙珛铏氭嫙鐜锛?- JDK 17 鎴栨洿楂樼増鏈?- Node.js 22 鎴栧吋瀹圭増鏈?- MySQL 8
- Maven Wrapper锛堝凡闅忔簮鐮佹彁渚涳級

## 瀹夊叏璇存槑

婧愮爜鍖呬笉鍖呭惈锛?
- `.env`銆丄PI Key 鎴栨暟鎹簱瀵嗙爜锛?- Python 铏氭嫙鐜鍜?`node_modules`锛?- MySQL 鏁版嵁銆丼QLite 杩愯鏁版嵁锛?- 杩愯鏃ュ織銆佷换鍔′骇鐗╁拰涓存椂鏂囦欢锛?- Maven/鍓嶇鏋勫缓缂撳瓨銆?
璇峰嬁灏嗙湡瀹?`.env` 涓婁紶鑷?GitHub銆備笅杞藉悗鍙娇鐢ㄥ悓鐩綍鐨?`.sha256` 鏂囦欢楠岃瘉鍘嬬缉鍖呭畬鏁存€с€?
## 宸茬煡闄愬埗

- 褰撳墠姝ｅ紡瀵煎叆鍏ュ彛浠?DOCX 涓轰富锛孭DF OCR 鏈撼鍏ユ湰娆″喕缁撹寖鍥淬€?- REAL 妯″瀷鑳藉姏渚濊禆澶栭儴妯″瀷鏈嶅姟銆丄PI 棰濆害鍜岀綉缁滅姸鎬併€?- 瓒呴暱鎴栫粨鏋勫紓甯哥殑 DOCX 浠嶅彲鑳介渶瑕佷汉宸ユ鏌ョ珷鑺備笌琛ㄦ牸瑙ｆ瀽缁撴灉銆?- `MAX_CONTEXT_TOKENS=96000` 鏀惧浜?F4 鏈湴棰勭畻锛屼絾涓嶈兘绐佺牬妯″瀷鏈嶅姟鑷韩鐨勪笂涓嬫枃绐楀彛銆?
## SHA-256 楠岃瘉

Windows PowerShell锛?
```powershell
Get-FileHash .\LessonAgentPlatform-v1.0.0-source.zip -Algorithm SHA256
```

Linux锛?
```bash
sha256sum LessonAgentPlatform-v1.0.0-source.zip
```
