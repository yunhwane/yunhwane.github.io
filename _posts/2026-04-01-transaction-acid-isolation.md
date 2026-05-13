---
layout: post
title: "트랜잭션 다시 보기: ACID와 격리수준이 실제로 막아주는 것"
date: 2026-04-01 18:00:00 +0900
categories: [backend]
tags: [transaction, database, isolation-level, acid, spring]
---

## 도입: "그냥 @Transactional 붙이면 되는 거 아니에요?"

신입 시절의 저였다면 그렇게 대답했을 겁니다. 메서드 위에 `@Transactional` 붙이면 알아서 잘 돌아간다고 생각했고, 실제로 대부분의 경우엔 잘 돌아갔습니다.

문제는 어느 날 운영 환경에서 **포인트가 두 배로 차감되는 이슈**가 올라왔을 때였습니다. 같은 사용자가 짧은 시간에 두 번 결제 요청을 보냈는데, 두 트랜잭션 모두 차감 전 잔액을 읽어 갔고, 결국 한 번만 차감되어야 할 포인트가 두 번 차감되었습니다.

`@Transactional`은 분명 붙어 있었습니다. 그런데 왜 막지 못했을까요?

답을 찾기 위해 트랜잭션을 처음부터 다시 정리해 봤습니다. ACID가 실제로 보장하는 것과 보장하지 않는 것, 그리고 격리수준이 어떤 문제를 어디까지 막아주는지 — 이 글은 그 학습 노트입니다.

## ACID: 네 가지 약속

트랜잭션은 보통 ACID 네 글자로 요약됩니다. 한 번쯤 외워본 적은 있지만, 각각이 **실제로 무엇을 보장하는지** 헷갈리기 쉽습니다.

### A — Atomicity (원자성)

트랜잭션 내의 작업은 **전부 성공하거나 전부 실패**합니다. 중간에 실패하면 이전 상태로 되돌아갑니다.

```
BEGIN;
  UPDATE account SET balance = balance - 10000 WHERE id = 1;  -- 출금
  UPDATE account SET balance = balance + 10000 WHERE id = 2;  -- 입금
COMMIT;
```

출금만 성공하고 입금이 실패하면 출금도 취소됩니다. 흔히 "송금 예시"로 등장하는 그 보장입니다.

원자성은 **롤백 메커니즘**으로 구현됩니다. DB는 변경 전 데이터를 별도 영역(언두 로그 등)에 기록해 두고, 실패 시 그 기록으로 되돌립니다.

### C — Consistency (일관성)

트랜잭션이 끝나면 DB가 **정의된 제약조건을 만족하는 상태**여야 합니다. 외래키, NOT NULL, UNIQUE 같은 제약이 모두 유효해야 한다는 뜻입니다.

이 부분은 사실 DB 혼자 책임지지 않습니다. **애플리케이션 레벨에서의 비즈니스 규칙**도 일관성에 포함됩니다. "잔액은 음수가 될 수 없다" 같은 규칙은 DB 제약으로 걸 수도 있지만, 보통 서비스 코드에서 검사합니다.

### I — Isolation (격리성)

동시에 실행되는 트랜잭션들이 **서로의 작업을 보지 못하게** 막아주는 보장입니다. 이 글의 핵심 주제이기도 합니다.

격리수준에 따라 "얼마나 막을 것인지"가 달라지고, 막지 않을수록 성능은 좋아지지만 이상 현상이 발생합니다.

### D — Durability (지속성)

커밋된 트랜잭션은 **시스템 장애가 나도 살아남아야** 합니다. 보통 디스크에 WAL(Write-Ahead Log)을 먼저 쓰고 데이터 페이지를 나중에 반영하는 방식으로 보장됩니다.

서버가 갑자기 꺼져도, 커밋 응답을 받은 트랜잭션은 재기동 후 복구됩니다.

## 격리성을 더 들여다보기: 무엇이 새어 나가는가

도입부에서 말한 포인트 중복 차감은 **격리성** 문제입니다. ACID 중에서 가장 자주 마주치는 이슈가 격리성이고, 격리수준 설정 하나로 동작이 크게 달라집니다.

격리성을 깨는 대표적인 이상 현상은 세 가지입니다.

### 1. Dirty Read (더티 리드)

**커밋되지 않은 데이터를 읽는** 현상입니다.

```
[T1] UPDATE account SET balance = 0 WHERE id = 1;   -- 아직 커밋 안 함
[T2] SELECT balance FROM account WHERE id = 1;      -- 0을 읽음
[T1] ROLLBACK;
```

T2는 존재한 적 없는 값을 읽었습니다. T1이 롤백되면 "0이었던 적"은 사실상 없었는데, T2는 그걸 보고 의사결정을 했습니다.

### 2. Non-Repeatable Read (반복 불가능한 읽기)

**같은 행을 두 번 읽었는데 값이 다른** 현상입니다.

```
[T1] SELECT balance FROM account WHERE id = 1;     -- 10000
[T2] UPDATE account SET balance = 5000 WHERE id = 1;
[T2] COMMIT;
[T1] SELECT balance FROM account WHERE id = 1;     -- 5000
```

T1 입장에서는 자기가 시작한 트랜잭션 동안 같은 데이터가 변해버린 셈입니다. 보고서 집계 같은 작업에서 치명적입니다.

### 3. Phantom Read (팬텀 리드)

**같은 조건으로 두 번 조회했는데 행 개수가 달라지는** 현상입니다.

```
[T1] SELECT * FROM orders WHERE user_id = 1;       -- 3건
[T2] INSERT INTO orders (user_id, ...) VALUES (1, ...);
[T2] COMMIT;
[T1] SELECT * FROM orders WHERE user_id = 1;       -- 4건
```

Non-Repeatable Read는 "기존 행이 바뀌는" 문제, Phantom Read는 "새 행이 끼어드는" 문제입니다. 구분이 중요합니다.

## 격리수준 4단계: 무엇을 어디까지 막는가

ANSI SQL은 격리수준을 네 단계로 정의합니다. 위로 갈수록 엄격해지고, 동시성은 떨어집니다.

| 격리수준 | Dirty Read | Non-Repeatable Read | Phantom Read |
|---------|-----------|--------------------|--------------|
| READ UNCOMMITTED | 발생 | 발생 | 발생 |
| READ COMMITTED | 차단 | 발생 | 발생 |
| REPEATABLE READ | 차단 | 차단 | 발생 (DB에 따라 차단) |
| SERIALIZABLE | 차단 | 차단 | 차단 |

### READ UNCOMMITTED

말 그대로 **커밋되지 않은 것도 읽습니다**. 실무에서 거의 안 씁니다.

### READ COMMITTED

**커밋된 것만 읽습니다**. PostgreSQL, Oracle의 기본값입니다.

Dirty Read는 막아주지만, 트랜잭션 도중에 다른 트랜잭션이 커밋하면 그 변경은 바로 보이게 됩니다. Non-Repeatable Read가 발생하는 이유입니다.

### REPEATABLE READ

트랜잭션이 시작한 시점의 **스냅샷을 끝까지 유지**합니다. MySQL InnoDB의 기본값입니다.

같은 행을 여러 번 읽어도 값이 변하지 않습니다. 다만 ANSI 표준상 Phantom Read는 여전히 가능하다고 정의되어 있는데, **MySQL InnoDB는 갭 락(Gap Lock)으로 Phantom까지 차단**합니다. DB마다 동작이 다르니 사용 중인 엔진의 동작을 확인하는 게 안전합니다.

### SERIALIZABLE

트랜잭션을 **마치 순차 실행한 것처럼** 보이게 만듭니다. 가장 안전하지만 가장 느립니다. 충돌이 잦아지면 락 대기나 직렬화 실패가 늘어납니다.

## 다시 그 사건: 무엇을 놓쳤는가

처음 이야기로 돌아가 봅시다. 포인트 중복 차감은 왜 발생했을까요?

```java
@Transactional
public void usePoint(Long userId, int amount) {
    User user = userRepository.findById(userId);   // 1) 잔액 읽기
    if (user.getPoint() < amount) {
        throw new NotEnoughPointException();
    }
    user.setPoint(user.getPoint() - amount);       // 2) 차감
    userRepository.save(user);                     // 3) 저장
}
```

두 요청이 거의 동시에 들어오면 이런 일이 생깁니다.

```
[T1] 잔액 읽기 → 10000
[T2] 잔액 읽기 → 10000
[T1] 검사 통과, 차감해서 0으로 저장
[T2] 검사 통과, 차감해서 0으로 저장
```

격리수준이 REPEATABLE READ여도 이건 막히지 않습니다. **두 트랜잭션이 서로의 변경을 보기 전에 모두 읽었고**, 각자 자기 스냅샷 기준으로는 정상 동작이었기 때문입니다.

이걸 **Lost Update**라고 부릅니다. 격리수준만으로는 막을 수 없는, 별도 처리가 필요한 패턴입니다.

해결 방법은 보통 둘 중 하나입니다.

**비관적 락 — SELECT FOR UPDATE**

```java
User user = userRepository.findByIdForUpdate(userId);  // 락 잡고 읽기
```

조회 시점에 행에 락을 걸어 다른 트랜잭션이 같은 행을 못 읽게 합니다. 충돌이 잦은 시나리오에 적합합니다.

**낙관적 락 — 버전 컬럼**

```java
@Entity
class User {
    @Version
    private Long version;
}
```

UPDATE 시 `WHERE version = ?` 조건이 자동으로 붙고, 다른 트랜잭션이 먼저 커밋해서 버전이 바뀌었다면 업데이트가 실패합니다. 충돌이 드문 시나리오에 적합합니다.

당시 우리는 비관적 락을 선택했습니다. 결제 흐름은 같은 사용자의 동시 요청 확률이 낮지 않다고 판단했기 때문입니다.

## 정리하며

- ACID는 네 가지 다른 보장을 한 묶음으로 부르는 이름이고, 각각이 막아주는 문제가 다릅니다.
- 격리수준은 동시성 이상 현상을 어디까지 막을지를 결정합니다. 기본값이 무엇인지는 DB마다 다릅니다.
- 격리수준만으로 모든 동시성 문제가 풀리지는 않습니다. **Lost Update 같은 패턴은 락 전략을 따로 선택**해야 합니다.

`@Transactional`은 시작점일 뿐이고, 그 안에서 어떤 일이 벌어지는지를 알아야 비로소 운영 환경에서 무너지지 않는 코드를 짤 수 있다는 걸 다시 한번 느낀 학습이었습니다.
