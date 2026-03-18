---
layout: post
title: "코드 테이블 로컬 캐시 사용하기"
date: 2026-03-18 18:00:00 +0900
categories: [backend]
tags: [spring-boot, caffeine, cache, java]
---

## 문제 상황: API마다 반복되는 코드 테이블 조회

회사에서 공통 코드 테이블을 사용하면서 한 가지 불편함을 느꼈습니다. 성별, 상태값, 카테고리 같은 코드성 데이터를 조회하는 로직이 **API마다 반복**되고 있었습니다.

상품 조회 API에서도 코드를 조회하고, 사용자 정보 API에서도 같은 코드를 조회하고, 목록 API에서도 또 조회합니다. 쿼리를 살펴보니 코드 테이블을 JOIN하거나 서브쿼리로 가져오는 **중복 코드**가 여러 Repository에 흩어져 있었습니다.

```
[상품 조회 API] → SELECT ... JOIN code_table → 상태 코드 조회
[사용자 API]   → SELECT ... JOIN code_table → 성별 코드 조회
[목록 API]     → SELECT ... JOIN code_table → 카테고리 코드 조회
```

매 요청마다 거의 변하지 않는 같은 데이터를 DB에서 반복 조회하고, 그 조회 로직이 여기저기 중복되어 있는 상황이었습니다.

코드 테이블의 특성을 다시 생각해보면 이건 불필요한 낭비입니다.

- 데이터가 **거의 변하지 않는다** — 운영자가 수동으로 변경할 때만 바뀜
- 데이터 양이 **적다** — 수백 건 이내
- **조회 빈도가 매우 높다** — 거의 모든 API에서 사용
- **중복 쿼리가 발생한다** — 여러 Repository에 코드 조회 로직이 산재

캐시에 올려두고 한 곳에서 관리하면 DB 부하도 줄이고, 중복 코드도 제거할 수 있겠다고 판단했습니다.

## 개발 제약 사항: Redis 없이 여러 서버의 캐시 동기화

우리 서비스 구조에서 가장 큰 제약은 **Front Office(사용자 서비스)와 Back Office(관리자)가 별도 서버로 운영**되고, 각각 여러 인스턴스가 떠 있다는 점이었습니다.

```
[Front Office 서버 1] ──┐
[Front Office 서버 2] ──┤
[Back Office  서버 1] ──┼── 모두 같은 코드 테이블을 사용
[Back Office  서버 2] ──┘
```

일반적으로 이런 환경에서는 **Redis 같은 중앙 캐시 서버**를 두고 모든 서버가 같은 캐시를 바라보게 합니다. 하지만 우리는 Redis를 사용하지 않는 환경이었습니다.

그렇다면 **로컬 캐시를 선택할 수밖에 없는데, 데이터 동기화를 어떻게 할 것인가?** 이것이 가장 핵심적인 문제였습니다. Back Office에서 관리자가 코드를 변경하면, Front Office 서버들의 로컬 캐시에도 반영이 되어야 합니다. 서버마다 독립적인 캐시를 갖고 있으니, 아무런 장치 없이는 변경 사항이 전파되지 않습니다.

이 문제의 해결 방향은 뒤에서 다루겠지만, 결론부터 말하면 **주기적 Warm-Up(3분 간격)**으로 모든 서버가 DB를 기준으로 캐시를 갱신하는 방식을 택했습니다. 실시간 동기화 대신 "최대 3분 내 반영"이라는 트레이드오프를 받아들인 것입니다. 코드 테이블은 관리자가 수동으로 변경하는 데이터이기 때문에, 이 정도의 지연은 충분히 허용 가능했습니다.

## 왜 로컬 캐시인가?

Redis를 사용하지 않는 환경이라는 제약도 있었지만, 코드 테이블의 특성을 따져보면 오히려 로컬 캐시가 더 나은 선택이었습니다.

| 비교 항목 | Redis (리모트 캐시) | Caffeine (로컬 캐시) |
|----------|-------------------|---------------------|
| 네트워크 비용 | 요청마다 네트워크 I/O 발생 | 없음 (JVM 힙 메모리) |
| 응답 속도 | ~1ms | ~ns (나노초) |
| 인프라 의존성 | Redis 서버 필요 | 없음 |
| 데이터 일관성 | 서버 간 즉시 공유 | 서버별 개별 관리 (주기적 동기화) |

코드 테이블은 **변경 빈도가 낮고**, **데이터 크기가 작으며**, **읽기 비율이 압도적**입니다. Redis를 도입하면 인프라 관리 비용이 추가되는 반면, 로컬 캐시는 네트워크 홉 없이 JVM 메모리에서 바로 읽으므로 성능상으로도 유리합니다. 동기화 지연이라는 단점은 주기적 갱신으로 충분히 커버할 수 있었습니다.

### 왜 Caffeine인가?

Java 진영의 로컬 캐시 라이브러리 중 **Caffeine**을 선택한 이유는 다음과 같습니다.

- **성능**: Window TinyLFU 알고리즘 기반으로 Guava Cache 대비 높은 히트율
- **자동 로딩**: `LoadingCache`를 제공하여 캐시 미스 시 자동으로 데이터를 로딩
- **Spring 공식 지원**: `spring-boot-starter-cache`에서 Caffeine을 공식 CacheManager로 지원
- **풍부한 설정**: 만료 정책, 최대 크기, 통계 수집 등을 세밀하게 제어 가능

## 구현하기

### 의존성 추가

```gradle
// build.gradle
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
```

### 캐시 설정 정의

캐시 타입별로 설정값을 관리하면 여러 종류의 캐시를 일관되게 다룰 수 있습니다.

```java
@Getter
public enum CacheType {
    CODE(500, Duration.ofHours(24));

    private final int maxSize;
    private final Duration refreshDuration;

    CacheType(int maxSize, Duration refreshDuration) {
        this.maxSize = maxSize;
        this.refreshDuration = refreshDuration;
    }

    public <K, V> LoadingCache<K, V> createLoadingCache(
            CacheLoader<K, V> loader) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .refreshAfterWrite(refreshDuration)
                .recordStats()
                .build(loader);
    }
}
```

`CacheType`을 enum으로 정의하면 캐시가 추가될 때마다 상수만 추가하면 됩니다. `recordStats()`를 넣어두면 히트율, 미스율 등을 모니터링할 수 있습니다.

### 캐시 Repository 구현

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class CachedCodeRepository {

    private final CodeRepository codeRepository;
    private LoadingCache<String, Code> cache;

    @PostConstruct
    public void init() {
        this.cache = CacheType.CODE.createLoadingCache(key -> {
            String[] parts = key.split("::", 2);
            if (parts.length != 2) return null;
            return codeRepository
                    .findByGroupNameAndCodeName(parts[0], parts[1])
                    .map(CodeEntity::toDomain)
                    .orElse(null);
        });

        warmUp();
        log.info("Code cache initialized - maxSize: {}, refresh: {}m",
                CacheType.CODE.getMaxSize(),
                CacheType.CODE.getRefreshDuration().toMinutes());
    }

    public Code findByGroupAndName(String groupName, String codeName) {
        if (codeName == null || codeName.isBlank()) return null;
        String key = groupName + "::" + codeName;
        return cache.get(key);
    }
}
```

핵심 포인트를 살펴보겠습니다.

**키 설계**: `그룹명::코드명` 형태의 복합 키를 사용합니다. 예를 들어 `GENDER::MALE` 처럼 하나의 문자열로 코드를 식별합니다.

**LoadingCache**: 캐시 미스가 발생하면 `CacheLoader`를 호출하여 자동으로 DB에서 로딩합니다. 같은 키에 대해 동시에 여러 요청이 들어와도 **한 번만 로딩**됩니다(thundering herd 방지).

**왜 `AsyncLoadingCache`가 아닌가?**: Caffeine은 `AsyncLoadingCache`도 제공하지만, 이 케이스에서는 동기 `LoadingCache`로 충분합니다. warm-up으로 미리 전체 데이터를 적재하기 때문에 실제 운영 중 캐시 미스가 거의 발생하지 않고, Spring MVC 기반이라 `CompletableFuture`를 리액티브하게 활용할 일도 없습니다. `AsyncLoadingCache`는 WebFlux 같은 리액티브 스택에서 non-blocking 파이프라인에 태울 때 더 적합합니다.

### Warm-Up: 애플리케이션 시작 시 캐시 채우기

```java
public void warmUp() {
    Map<String, Code> newEntries = new HashMap<>();

    List<String> groupNames = codeRepository.findAllGroupNames();
    for (String groupName : groupNames) {
        List<CodeEntity> codes = codeRepository.findByGroupName(groupName);
        for (CodeEntity entity : codes) {
            String key = groupName + "::" + entity.getCodeName();
            newEntries.put(key, entity.toDomain());
        }
    }

    // 삭제된 코드는 캐시에서 제거
    Set<String> currentKeys = cache.asMap().keySet();
    Set<String> newKeys = newEntries.keySet();
    currentKeys.stream()
            .filter(key -> !newKeys.contains(key))
            .forEach(cache::invalidate);

    // 새로운 데이터로 캐시 갱신
    cache.putAll(newEntries);
    log.debug("Code cache warmed up: {} items", cache.estimatedSize());
}
```

Warm-Up은 두 가지 역할을 합니다.

1. **애플리케이션 시작 시** 모든 코드를 미리 로딩하여 첫 요청부터 캐시 히트가 되도록 합니다
2. **주기적 갱신 시** DB에서 삭제된 코드는 캐시에서도 제거하여 데이터 정합성을 유지합니다

단순히 `putAll`만 하면 DB에서 삭제된 코드가 캐시에 남아있게 됩니다. `currentKeys`와 `newKeys`를 비교하여 차집합을 `invalidate`하는 부분이 중요합니다.

### 주기적 캐시 갱신 (TTL 3분)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheRefreshScheduler {

    private final CachedCodeRepository cachedCodeRepository;

    @Scheduled(fixedRate = 180_000) // 3분
    public void refreshCaches() {
        try {
            cachedCodeRepository.warmUp();
        } catch (Exception e) {
            log.error("Failed to refresh Code cache", e);
        }
    }
}
```

`refreshAfterWrite`만으로는 **요청이 들어와야 갱신이 트리거**됩니다. 스케줄러로 3분마다 `warmUp()`을 호출하면 요청 유무와 관계없이 캐시가 최신 상태를 유지합니다.

갱신 주기를 3분으로 잡은 이유는 코드 테이블의 변경이 **운영자의 수동 작업**으로만 발생하기 때문입니다. 실시간 반영이 아닌 "수 분 내 반영"이면 충분하고, 너무 짧으면 불필요한 DB 부하가 발생합니다.

> 각 캐시의 `warmUp()`을 try-catch로 감싸는 것이 중요합니다. 하나의 캐시 갱신이 실패해도 다른 캐시에 영향을 주지 않도록 격리합니다.

## 전체 흐름

```
[애플리케이션 시작]
    └─ @PostConstruct → warmUp() → DB 전체 조회 → 캐시 적재

[API 요청]
    └─ findByGroupAndName("GENDER", "MALE")
        └─ cache hit → 즉시 반환 (ns 단위)
        └─ cache miss → CacheLoader → DB 조회 → 캐시 적재 → 반환

[3분마다]
    └─ @Scheduled → warmUp()
        └─ DB 전체 조회 → 신규 코드 추가, 삭제된 코드 제거
```

## 주의할 점

### 1. 메모리 사용량 관리

로컬 캐시는 JVM 힙 메모리를 사용합니다. `maximumSize`를 반드시 설정하고, 코드 테이블의 예상 크기를 고려해야 합니다. 설정하지 않으면 메모리 누수로 이어질 수 있습니다.

### 2. 다중 인스턴스 환경

서버가 여러 대라면 각 인스턴스가 **독립적인 캐시**를 갖게 됩니다. 코드 변경 후 최대 3분간 서버마다 다른 데이터를 반환할 수 있습니다. 코드 테이블의 특성상 이 정도는 허용 가능하지만, 실시간 일관성이 필요한 데이터라면 로컬 캐시는 적합하지 않습니다.

### 3. Cache Stampede 방지

`LoadingCache`는 같은 키에 대한 동시 요청을 하나로 합쳐줍니다. 하지만 `warmUp()` 시 대량 DB 조회가 발생하므로, 코드 데이터가 매우 많다면 warm-up 자체의 부하도 고려해야 합니다.

### 4. null 처리

존재하지 않는 코드를 조회하면 `null`이 캐시에 저장될 수 있습니다. Caffeine은 기본적으로 null value를 허용하지 않으므로 `CacheLoader`에서 null을 반환하면 해당 키는 캐시되지 않습니다. 매번 DB를 조회하는 negative lookup이 발생할 수 있으니, 의도적으로 빈 객체를 반환하는 것도 고려해볼 수 있습니다.

### 5. 갱신 실패 시 기존 캐시 유지

`warmUp()` 내부에서 DB 조회가 실패하면 Exception이 발생하고, try-catch에 의해 기존 캐시가 그대로 유지됩니다. 이는 의도된 동작으로, 일시적인 DB 장애가 서비스 장애로 이어지지 않도록 합니다.

## 마무리

코드 테이블처럼 **변경이 적고, 크기가 작고, 조회가 잦은** 데이터에는 로컬 캐시가 효과적입니다. Caffeine의 `LoadingCache`와 주기적 warm-up을 조합하면, 거의 제로에 가까운 지연시간으로 코드를 조회하면서도 데이터 정합성을 유지할 수 있습니다.

핵심을 정리하면 다음과 같습니다.

- 코드 테이블은 **로컬 캐시**가 적합하다 (네트워크 비용 제거)
- **Caffeine**은 높은 히트율과 비동기 로딩을 지원한다
- **Warm-Up**으로 콜드 스타트를 방지하고, **스케줄러**로 주기적 갱신한다
- 삭제된 데이터의 **캐시 무효화**를 잊지 말자
- 다중 인스턴스 환경에서의 **일시적 불일치**를 허용할 수 있는 데이터에만 적용하자
