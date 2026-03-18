---
layout: post
title: "메시지 큐에서 트랜잭션 문제 극복하기 — Transactional Outbox Pattern"
date: 2026-03-15 18:00:00 +0900
categories: [backend]
tags: [kafka, transaction, outbox-pattern, spring-boot, distributed-system]
---

## 문제 상황: 커밋은 됐는데 메시지가 사라진다

포스팅 예약 시스템에서 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`를 사용해 DB 커밋 이후 Kafka로 이벤트를 발행하고 있었습니다. 평소에는 문제없이 동작했지만, **Kafka 브로커 장애** 상황에서 치명적인 버그가 드러났습니다.

```
1. 애플리케이션 → DB 커밋 완료 ✅
2. TransactionalEventListener → 이벤트 위임
3. Kafka 전송 시도 → 브로커 장애 ❌
4. 메시지 유실 → 포스팅이 WORKING 상태에서 영원히 멈춤
```

DB에는 정상적으로 저장됐지만 Kafka 메시지가 유실되면서, 예약된 포스팅이 발행되지 않는 상태가 되었습니다. **DB 트랜잭션과 메시지 발행 사이의 원자성이 보장되지 않는 전형적인 분산 시스템 문제**였습니다.

---

## 해결 방법 후보: Two-Phase Commit vs Outbox Pattern

### Two-Phase Commit (2PC)

분산 시스템에서 원자성을 보장하는 전통적인 방법입니다.

| Phase | 동작 |
|-------|------|
| **Prepare** | 코디네이터가 각 참여자에게 "커밋 준비 완료?" 질의 → 참여자는 준비만 하고 실제 커밋은 하지 않음 |
| **Commit** | 모든 참여자가 OK이면 커밋, 하나라도 실패하면 전체 롤백 |

"모두 성공하거나 모두 실패"라는 강력한 일관성을 제공하지만, 실제 운영 환경에서는 몇 가지 단점이 있습니다.

- **성능 저하** — 모든 참여자가 응답할 때까지 락을 잡고 대기해야 합니다
- **단일 장애점** — 코디네이터가 다운되면 참여자들이 불확실한 상태로 남습니다
- **Kafka는 XA 트랜잭션을 지원하지 않음** — 사실상 DB + Kafka 조합에서 2PC 적용이 불가능합니다

### Transactional Outbox Pattern

이벤트를 외부 시스템에 직접 전송하지 않고, **Outbox 테이블에 먼저 저장**한 후 별도 프로세스가 읽어서 외부 시스템으로 전송하는 패턴입니다.

```
[비즈니스 로직]
    │
    ├─ 도메인 데이터 저장  ──┐
    │                        ├── 같은 DB 트랜잭션
    └─ Outbox 테이블 저장  ──┘

[Message Relay (별도 스케줄러)]
    │
    ├─ Outbox에서 PENDING 이벤트 조회
    ├─ Kafka로 전송
    └─ 전송 결과에 따라 상태 업데이트 (SUCCESS / FAIL)
```

핵심은 **도메인 데이터와 이벤트를 같은 DB 트랜잭션으로 묶는 것**입니다. DB 트랜잭션의 원자성을 활용하기 때문에 "데이터는 저장됐는데 이벤트는 없다"는 상황이 원천적으로 발생하지 않습니다.

---

## 왜 Outbox Pattern을 선택했는가

| 기준 | 2PC | Outbox Pattern |
|------|-----|----------------|
| Kafka 호환성 | XA 미지원으로 사실상 불가 | DB 트랜잭션만 사용하므로 문제없음 |
| 성능 | 분산 락으로 인한 지연 | 로컬 트랜잭션이라 빠름 |
| 장애 대응 | 코디네이터 장애 시 복구 어려움 | 재시도 + 상태 추적 가능 |
| 운영 가시성 | 별도 모니터링 필요 | Outbox 테이블 자체가 로그 역할 |
| 구현 난이도 | 높음 | 상대적으로 낮음 |

특히 기존 시스템에서 메시지 발행 상황에 대한 **로깅이 부족**했기 때문에, Outbox 테이블이 자연스럽게 이벤트 이력 역할까지 하는 점이 큰 장점이었습니다.

---

## Outbox 테이블 설계

```sql
CREATE TABLE outbox (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_type  ENUM('POSTING_EVENT', 'POSTING_RESERVATION_EVENT') NOT NULL,
    payload     JSON,
    created     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status      ENUM('PENDING', 'SUCCESS', 'FAIL', 'RETRY'),
    retry_count BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_status_created_id ON outbox (status, created, id);
```

### 인덱스 설계 의도

`(status, created, id)` 복합 인덱스를 사용한 이유는 Message Relay의 조회 쿼리 패턴 때문입니다.

```sql
-- Message Relay가 실행하는 쿼리
SELECT * FROM outbox
WHERE status = 'PENDING'
ORDER BY created, id
LIMIT 100;
```

- `status = 'PENDING'` — 인덱스 선두 컬럼으로 빠르게 필터링
- `ORDER BY created, id` — 인덱스 순서와 일치하여 **filesort 없이** 정렬 가능
- `LIMIT 100` — 인덱스 스캔을 100건에서 조기 종료

이 인덱스가 없으면 Outbox 테이블이 커질수록 전체 테이블 스캔이 발생해 성능이 급격히 저하됩니다.

---

## Message Relay 구현

```java
@Scheduled(fixedDelay = 5_000)
@Transactional
public void publishPendingPostingEvents() {
    List<Outbox> retryableEvents =
        outBoxRepository.findTop100PendingOrderByCreatedAndId();

    retryableEvents.forEach(outbox -> {
        try {
            PostingEventPayload payload =
                DataSerializer.deserialize(outbox.getPayload(),
                    PostingEventPayload.class);
            String postId = String.valueOf(payload.getPostId());

            kafkaTemplate.send(kafkaTopicConfig.getTopicName(),
                postId, postId).get();

            outbox.markSent();
            log.info("Kafka 전송 성공: postId={}", postId);

        } catch (Exception e) {
            outbox.markFailed();
            log.error("Kafka 전송 실패: outboxId={}, retryCount={}",
                outbox.getId(), outbox.getRetryCount(), e);
        }
    });

    outBoxRepository.saveAll(retryableEvents);
}
```

### 전체 흐름 정리

```
[사용자 포스팅 예약 요청]
    │
    ▼
[예약 스케줄러 - 1분 주기]
    ├─ 5분 전 예약 데이터 조회
    ├─ WORKING 상태로 마킹
    └─ Outbox 테이블에 이벤트 저장  ← 같은 트랜잭션
    │
    ▼
[Message Relay - 5초 주기]
    ├─ PENDING 상태 이벤트 100건 조회
    ├─ Kafka로 전송 시도
    ├─ 성공 → SUCCESS로 마킹
    └─ 실패 → FAIL로 마킹, retry_count 증가
         └─ 최대 3회 재시도 후 수동 처리 대상으로 분류
```

### 구현에서 주의한 점

**1. 동기 전송 사용 (`kafkaTemplate.send().get()`)**

`KafkaTemplate.send()`는 기본적으로 비동기입니다. `.get()`을 호출해 동기로 전환한 이유는, 전송 성공/실패를 확실히 확인한 후 Outbox 상태를 업데이트해야 하기 때문입니다. 비동기로 처리하면 전송 결과를 모르는 채로 상태를 변경할 위험이 있습니다.

**2. 배치 조회 + 개별 전송**

100건을 한 번에 조회하되 개별 건마다 try-catch로 감싸서, 한 건의 실패가 나머지 건의 전송을 막지 않도록 했습니다.

**3. 재시도 횟수 제한**

무한 재시도는 장애 상황에서 시스템 부하를 가중시킵니다. 최대 3회로 제한하고, 그 이후에는 운영자가 직접 확인할 수 있도록 별도 상태로 분류합니다.

---

## 적용 결과

| Before | After |
|--------|-------|
| Kafka 장애 시 메시지 유실 | Outbox에 보존되어 장애 복구 후 자동 재전송 |
| 이벤트 발행 이력 없음 | Outbox 테이블이 이벤트 이력 역할 수행 |
| DB와 Kafka 간 일관성 미보장 | 같은 트랜잭션으로 원자성 보장 |
| 장애 대응이 어려움 | 상태/재시도 횟수 기반으로 빠른 파악 가능 |

Outbox 패턴은 추가 테이블과 스케줄러라는 복잡성이 생기지만, **메시지 유실 방지, 운영 가시성 확보, 시스템 간 결합도 감소**라는 이점이 그 비용을 충분히 상쇄했습니다. 복잡성과 운영 효율성, 확장성을 고려했을 때 현재 서비스 환경에 가장 적합한 선택이었습니다.
