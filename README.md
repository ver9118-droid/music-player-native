# 무한 음악 플레이어 — 안드로이드 네이티브 버전

> **다운로드**: [Releases 페이지](https://github.com/ver9118-droid/music-player-native/releases) 에서
> 최신 `app-release.apk` (v3.1.0) 를 받으세요. (설치: 파일 선택 → "알 수 없는 앱 설치 허용")

데스크톱 `무한 음악 플레이어 v1.6.1` 의 모바일 버전입니다.
이전 버전(Kivy + buildozer, APK 60MB, 파이썬 런타임)을 **완전 재작성** 했습니다.

| 항목 | 이전 (Kivy) | 현재 (네이티브) |
|---|---|---|
| 앱 시작 | 파이썬+OpenGL 초기화로 1.5~3초 | AOT 네이티브, **0.3초 이내** |
| APK 크기 | 약 60MB | 약 8MB |
| 백그라운드 재생 | p4a arbitrary service | `MediaSessionService` (공식 방식) |
| 차량 블루투스 | jnius 로 MediaSession 직접 조작 | `MediaSession` 기본 구현 (표준 호환) |
| 전화 통화 처리 | 오디오 포커스만 | 오디오 포커스 + `TelephonyCallback` 직접 감지 |
| 오디오 엔진 | `MediaPlayer` | **ExoPlayer (Media3)** |

## 핵심 요구사항 대응

### 1. 로딩이 빠르다
- 파이썬/카피/Kivy 를 전부 제거 → AOT 컴파일된 네이티브 코드만 실행
- `Application.onCreate` 에서 하는 일이 설정/캐시 초기화뿐
- 창 배경색 = 앱 배경색 (별도 스플래시 화면 없음 → 첫 프레임 즉시 표시)
- 마지막으로 본 차트를 캐시해 두어 **재실행하면 목록이 즉시 뜬다**
  (네트워크 응답은 백그라운드에서 도착하는 대로 교체)

### 2. 백그라운드 재���이 된다
- `MediaSessionService` + 포그라운드 서비스 (`foregroundServiceType=mediaPlayback`)
- 홈/다른 앱/화면 끄기/앱 강제 종료 후에도 계속 재생
- 상태바 알림(재생·일시정지·이전·다음) 표시

### 3. 전화가 울리면 간섭하지 않는다 (노래와 전화가 겹치지 않음)
- ExoPlayer 오디오 포커스(`handleAudioFocus=true`)
  - 통화/다른 앱이 소리를 내면 **자동 일시정지**
  - 짧은 알림(내비게이션 등)에는 **자동 음량 낮춤(duck)**
  - 이어폰을 뽑으면(`AUDIO_BECOMING_NOISY`) **자동 일시정지**
- `CallGuard` 로 전화 상태를 직접 감시
  - 벨소리/통화 시작 → **즉시 일시정지** (사용자가 듣던 상태인지 기록)
  - 통화 종료 → 원래 듣던 상태였다면 **자동 재개**
  - `READ_PHONE_STATE` 권한을 거부해도 오디오 포커스로 동일하게 동작

### 4. 자동차 블루투스로 재생/조작한다
- `MediaSession` 기반 → AVRCP 표준 compliant
  - 차량 화면에 **곡명 · 아티스트 · 앨범 · 앨범아트** 표시
  - 스티어링훠/키패드로 **재생 · 일시정지 · 이전 · 다음 · 탐색**
  - 알림에서 차량 화면을 누르면 앱으로 이동 (`setSessionActivity`)
- 차량 연결 방법: 안드로이드 설정 → 블루투스 → 차량 연결 → 오디오 소스에서
  "무한 음악 플레이어" 선택

## 음원 소스
- **멜론 TOP100**, **지니 장르차트 10종** (HTML 파싱)
- **한국대중음악 장르 8종** (유튜브 검색)
- 재생은 유튜브 InnerTube API 로 직접 스트림 URL 을 받아 ExoPlayer 가 재생
  (별도 다운로드/변환 불필요 → 준비 시간 1~2초)

## 구조

```
app/src/main/java/org/music/infiniteplayer/
├── MainActivity.kt            진입점 (권한 요청 + 서비스 시작)
├── App.kt                     Application (초기화 최소)
├── core/
│   ├── Prefs.kt               설정/즐겨찾기/차트 캐시
│   ├── Net.kt                 경량 HTTP 클라이언트 (gzip 대응)
│   ├── Text.kt                HTML 엔티티/태그 정리
│   └── Images.kt              앨범아트 메모리+디스크 캐시
├── data/
│   ├── Track.kt               곡 모델
│   ├── Sources.kt             FLO 스타일 차트/장르/상황 소스
│   ├── ChartRepository.kt     플로리스트 로더 (유튜브 검색 기반)
│   ├── YoutubeRepository.kt   InnerTube 검색 + 오디오/영상 스트림 해석
├── playback/
│   ├── PlaybackService.kt     MediaSessionService + 재생목록 윈도우 관리
│   ├── CallGuard.kt           통화 감시
│   └── PlayerBus.kt           UI ↔ 서비스 통로
└── ui/
    ├── RootScreen.kt          화면 전환
    ├── Screens.kt             FLO 홈 / 지금재생 / 설정
    ├── TrackVideo.kt          차량 연결 시 노래 영상
    ├── Components.kt          공통 컴포넌트
    └── Theme.kt               다크 테마
```

## FLO 스타일 (v3.1.0)

- 홈 상단 탭 **플로 차트 / 장르 / 상황**
- 연령 필터 **전체 연령 / 1020대 / 3040대 / 5060대**
- 장르 26종 · 상황 34종 카드 그리드 (누르면 해당 플레이리스트 로드)
- 차량 블루투스 연결 시 **노래에 맞는 영상**을 지금 재생 화면에 표시 (음소거)
- 가사 기능은 제거했습니다

## 빌드

```bash
./gradlew :app:assembleRelease
```

CI(`main` 브랜치 push)는 자동으로 APK 를 만들어 GitHub Actions 아티팩트로 올립니다.

서명키(`keystore/release.jks`)는 CI 에서 자동 생성되며, 없으면 debug 키로 빌드됩니다.
