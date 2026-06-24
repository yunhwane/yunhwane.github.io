---
layout: post
title: "닉네임 100개 뽑았더니 70개가 중복 — SKIP LOCKED로 풀(pool) 동시성 잡기"
date: 2026-06-24 20:00:00 +0900
categories: [backend]
tags: [postgresql, concurrency, skip-locked, lock, jpa]
---

## 들어가며: 자동 배정인데 같은 닉네임이 자꾸 나온다

회원가입 시 닉네임을 자동으로 하나씩 배정하는 기능이 있었습니다. 구조는 단순합니다. 미리 만들어 둔 닉네임 풀 테이블이 있고, 거기서 아직 안 쓴 행 하나를 골라 `is_used`를 켜서 돌려주는 식입니다.

```sql
create table nickname_pool (
    id        bigserial primary key,
    nickname  varchar(40) not null unique,
    is_used   boolean     not null default false
);
```

```java
@Transactional
public String assign() {
    NicknamePool row = repository.findFirstByIsUsedFalse()   // (1) 안 쓴 행 하나 조회
            .orElseThrow(() -> new IllegalStateException("남은 닉네임 없음"));
    row.markUsed();                                          // (2) is_used = true
    return row.getNickname();
}
```

혼자 눌러볼 때는 멀쩡합니다. 그런데 **가입 트래픽이 몰리면 같은 닉네임이 여러 명에게 배정**됩니다. `nickname` 컬럼에 unique 제약이 있으니 DB가 막아주긴 하지만, 그 시점엔 이미 `DataIntegrityViolationException`이 터지고 가입 플로우가 깨집니다.

"동시성 문제겠지"라는 심증은 있었지만, 심증으로 글을 쓸 수는 없으니 먼저 **재현**부터 했습니다.

## 1. 테스트로 문제를 눈으로 확인하기

스레드 100개가 동시에 `assign()`을 호출하게 하고, 돌려받은 닉네임 중 **서로 다른 것이 몇 개인지** 셌습니다.

```java
@Test
void 동시에_100명이_배정받으면_중복이_생긴다() throws Exception {
    int threads = 100;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    Set<String> assigned = ConcurrentHashMap.newKeySet();

    for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
            ready.countDown();
            start.await();               // 동시에 출발시키기
            assigned.add(service.assign());
            return null;
        });
    }

    ready.await();
    start.countDown();                   // 출발 신호
    pool.shutdown();
    pool.awaitTermination(10, SECONDS);

    // 100번 배정했으면 서로 다른 닉네임도 100개여야 한다
    assertThat(assigned).hasSize(100);
}
```

결과는 처참했습니다.

```
expected size: 100 but was: 30
```

서로 다른 닉네임이 **30개**, 즉 70번은 이미 누가 받은 닉네임을 또 받은 겁니다. 심증이 물증이 됐습니다.

## 2. 왜 중복이 생기나 — 조회와 갱신 사이의 틈

원인은 전형적인 **검사 후 실행(check-then-act)** 경합입니다. `(1)` 조회와 `(2)` 갱신이 한 덩어리가 아니라서, 둘 사이에 다른 트랜잭션이 끼어듭니다.

```
시간 →
T1:  SELECT ... is_used=false → id=1 반환
T2:  SELECT ... is_used=false → id=1 반환   ← T1이 아직 커밋 전이라 똑같이 보임
T1:  UPDATE id=1 SET is_used=true
T2:  UPDATE id=1 SET is_used=true            ← 같은 행, 같은 닉네임
```

기본 격리 수준(Read Committed)에서 `SELECT`는 잠금을 걸지 않습니다. 그래서 여러 트랜잭션이 **똑같이 "1번이 비어있네"** 라고 읽고, 다 같이 1번을 집어갑니다. 누가 먼저 커밋했는지는 아무도 모릅니다.

여기서 선택지가 갈렸습니다.

## 3. 선택지: 분산락이냐, DB 락이냐

서버가 여러 대였기 때문에 처음엔 **분산락**(Redis/Redisson)이 먼저 떠올랐습니다. "다중 서버니까 애플리케이션 레벨에서 락을 잡아야 하는 거 아닌가?" 하는 생각이었죠. 하지만 정리해 보니 이 문제에는 과한 도구였습니다.

| | 분산락 (Redisson 등) | `FOR UPDATE` (대기) | `FOR UPDATE SKIP LOCKED` |
|---|---|---|---|
| 추가 인프라 | Redis 필요 | 없음 | 없음 |
| 동시성 | 배정 전체가 **직렬화** | 같은 행 대기, 사실상 직렬화 | 행마다 독립, **병렬** |
| 다중 서버 | OK | OK (락이 DB에 있음) | OK (락이 DB에 있음) |
| 빈 행 못 찾을 때 | — | 앞 트랜잭션 끝날 때까지 **대기** | 대기 없이 **다음 행**으로 |

핵심 깨달음 두 가지였습니다.

**첫째, "다중 서버 = 분산락"이 아니다.** 분산락이 필요한 이유는 보통 *공유 자원이 DB 밖에 있을 때*(예: 외부 API 호출 횟수 제한)입니다. 그런데 이 문제의 공유 자원은 `nickname_pool` 테이블, 즉 **이미 PostgreSQL 안**에 있습니다. DB가 거는 행 잠금은 어느 서버에서 접속하든 동일하게 적용되므로, 다중 서버여도 DB 락 하나로 충분합니다. Redis를 새로 끌어올 이유가 없었습니다.

**둘째, 이건 "풀에서 안 쓴 항목 하나 꺼내기"라는 큐 소비 패턴이다.** 행들끼리는 서로 독립적입니다. 1번을 누가 가져가든 2번을 가져가는 데는 아무 지장이 없습니다. 그렇다면 모두를 한 줄로 세워 직렬화하는 분산락이나 일반 `FOR UPDATE`는 낭비입니다. **잠긴 행은 건너뛰고 비어 있는 다음 행을 집으면** 됩니다. 이게 정확히 `SKIP LOCKED`가 하는 일입니다.

그래서 **PostgreSQL의 `FOR UPDATE SKIP LOCKED`** 를 택했습니다.

## 4. SKIP LOCKED는 무슨 일을 하나

`SELECT ... FOR UPDATE`는 읽은 행에 쓰기 잠금을 겁니다. 여기에 `SKIP LOCKED`를 붙이면, **이미 다른 트랜잭션이 잠근 행은 결과에서 빼고** 잠기지 않은 행만 가져옵니다. 대기하지 않습니다.

```
T1:  SELECT ... is_used=false ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED → id=1 잠금
T2:  SELECT ... is_used=false ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED → 1은 잠겨서 건너뜀 → id=2
T3:  SELECT ...                                                          → 1,2 건너뜀 → id=3
```

각 트랜잭션이 **서로 다른 행**을 손에 쥐고 시작하므로, 애초에 같은 행을 두고 다툴 일이 없습니다. 대기도 없으니 처리량도 거의 안 깎입니다. 풀/큐/잡 디스패치에서 흔히 쓰는 패턴입니다.

## 5. 고친 코드

Spring Data JPA에서는 네이티브 쿼리로 깔끔하게 표현됩니다.

```java
public interface NicknamePoolRepository extends JpaRepository<NicknamePool, Long> {

    @Query(value = """
            select * from nickname_pool
            where is_used = false
            order by id
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<NicknamePool> findFirstAvailableForUpdate();
}
```

```java
@Transactional
public String assign() {
    NicknamePool row = repository.findFirstAvailableForUpdate()
            .orElseThrow(() -> new IllegalStateException("남은 닉네임 없음"));
    row.markUsed();          // 더티 체킹으로 is_used = true
    return row.getNickname();
}
```

> 주의 두 가지.
> - `SKIP LOCKED`는 **트랜잭션 안**에서만 의미가 있습니다. 잠금은 트랜잭션이 끝날 때 풀리므로 `@Transactional`이 반드시 있어야 하고, 조회→갱신→커밋이 한 트랜잭션에 묶여야 합니다.
> - JPA의 `@Lock(PESSIMISTIC_WRITE)`만으로는 `SKIP LOCKED`가 안 나옵니다(대기하는 일반 `FOR UPDATE`가 됩니다). `SKIP LOCKED`는 위처럼 네이티브 쿼리로 명시했습니다. *(Hibernate 6의 `Timeout.skipLocked()` 등 방언별 옵션도 있지만, 의도가 쿼리에 그대로 보이는 네이티브 쪽이 읽기 편했습니다.)*

## 6. 다시 테스트

같은 100스레드 테스트를 다시 돌렸습니다.

```
expected size: 100 — passed
```

100번 배정에 서로 다른 닉네임 100개. 중복이 사라졌습니다. unique 제약에 기대 예외를 받아내던 방어선이, 이제는 **애초에 충돌이 안 나는** 구조로 바뀌었습니다.

## 마치며

- **문제의 정체부터 테스트로 못 박자.** "동시성 문제 같다"는 심증을 100/30이라는 숫자로 바꾸고 나니, 고친 뒤에도 같은 잣대로 검증할 수 있었습니다.
- **"다중 서버 = 분산락"은 반사적 오답일 수 있다.** 공유 자원이 이미 DB 안에 있다면, DB의 행 잠금이 모든 서버에 동일하게 적용됩니다. 인프라를 늘리기 전에 자원이 어디 있는지부터 봐야 했습니다.
- **풀에서 항목 꺼내기는 직렬화할 필요가 없다.** 행이 독립적이라면 `SKIP LOCKED`로 각자 다른 행을 집게 해서, 정합성과 처리량을 동시에 챙길 수 있습니다.

도구를 고르기 전에 "이 자원이 어디 있고, 행끼리 정말 경합하는가"를 먼저 물었다면 분산락은 후보에도 못 올랐을 겁니다. 결국 가장 가벼운 답이 가장 맞는 답이었습니다.
