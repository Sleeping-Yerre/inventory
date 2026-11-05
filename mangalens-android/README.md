# MangaLens Android

일본어 만화를 화면 캡처로 번역하고 원문 위치 위에 한국어를 표시하는 개인용 Android 앱입니다.

[설치 APK 받기](https://github.com/Sleeping-Yerre/inventory/raw/refs/heads/main/downloads/MangaLens-Android.apk) · [사용 안내](./사용안내.md) · [검증 기록](./검증기록.md)

- 작은 이동식 번역바: 번역·중지, 숨김/표시, 지우기, 설정, 종료.
- 모니터 대신 휴대폰 화면 전체를 한 번 캡처하고 일본어 → 한국어로 번역합니다.
- ChatGPT 로그인 및 요금제 사용 허용, 계정 모델 목록, 추론 수준 선택을 제공합니다. 일반 유료 API 연결은 없습니다.
- 원문 글줄을 감싸는 흰 바탕과 한글을 표시하며, 원문·번역·필요한 설명·처리 결과를 별도로 확인합니다.
- 앱 시작 시 GitHub의 업데이트 정보를 확인합니다. 새 APK의 SHA-256·패키지 이름·버전·서명을 확인한 뒤 Android 설치 화면을 엽니다.

Android 8 이상을 대상으로 하며 Android 17 가상 기기에서 설치와 36개 자체 검사를 확인했습니다. Galaxy S23의 설치와 ChatGPT 연결은 사용자 보고로 확인했으며, 실제 화면 캡처·번역 표시는 후속 확인 중입니다. 첫 시험판입니다.

## 빌드

Windows PowerShell, Java 21, Android SDK Platform 37.0 / Build Tools 37.0.0이 필요합니다. 외부 앱 라이브러리와 Gradle 의존성은 없습니다.

```powershell
./build.ps1 -SdkRoot 'C:/Android/sdk' -JavaRoot 'C:/Java/jdk-21'
```

기본 출력은 `dist/MangaLens-Android.apk`입니다. 로컬 도구를 `.tools/java`, `.tools/sdk`에 준비했다면 인자를 생략할 수 있습니다. 서명키는 최초 빌드 시 `.signing/mangalens-release.jks`에 만들고 비밀번호는 Windows DPAPI로 보호합니다. 이 폴더를 GitHub에 올리지 않습니다. 같은 앱의 업데이트에는 같은 서명키가 필요합니다.

```powershell
./build.ps1 -VersionCode 2 -VersionName 0.1.1
./package-release.ps1
```

버전 코드는 배포마다 증가시켜야 합니다. APK와 업데이트 정보는 저장소 루트의 `downloads/`, `updates/android.json`에 함께 반영합니다. 자동 빌드용 수동 실행 워크플로는 저장소 루트 `.github/workflows/mangalens-android-release.yml`에 있습니다. 최초 APK와 같은 서명키를 비공개 Actions secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`에 설정한 경우에만 사용할 수 있습니다. 초기 배포는 로컬에서 서명한 APK를 사용합니다.

개발용 검사는 버릴 수 있는 Android 17 에뮬레이터에만 실행합니다.

```powershell
./run-tests.ps1 -Device emulator-5554
```

실제 GPT 요청을 보내지 않습니다. 테스트 APK는 배포 앱에 포함하지 않습니다.
