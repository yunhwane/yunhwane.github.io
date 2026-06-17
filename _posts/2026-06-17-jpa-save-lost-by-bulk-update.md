---
layout: post
title: "save() 했는데 왜 DB에 안 남았을까 — bulk update가 삼킨 변경"
date: 2026-06-17 18:00:00 +0900
categories: [backend]
tags: [jpa, hibernate, persistence-context, bulk-update, flush]
---

## 들어가며: save 했는데 왜 DB에 안 남았을까?

분명히 `save()`를 호출했습니다. 예외도 없었고, 로그상으로도 그 라인은 멀쩡히 지나갔습니다. 그런데 트랜잭션이 끝나고 DB를 열어보면 그 변경만 쏙 빠져 있었습니다.

이런 버그가 고약한 이유는 **재현이 들쭉날쭉**하기 때문입니다. 어떤 요청에서는 정상이고, 어떤 요청에서는 사라집니다. NPE처럼 스택트레이스를 남겨주지도 않습니다. "분명히 코드는 맞는데" 하면서 며칠을 헤맬 수 있습니다.

결론부터 말하면 범인은 **같은 트랜잭션 안에서 뒤따라 실행된 bulk update**였습니다. 이 글은 그 변경이 어디서, 어떤 순서로 증발했는지를 영속성 컨텍스트 관점에서 처음부터 추적해 본 기록입니다.

## 익명화된 문제 상황

상황을 도메인만 바꿔 익명화하면 이렇습니다. 어떤 작업 배치가 끝나면, 처리한 주문 한 건의 상태를 `DONE`으로 바꾸고, **동시에** 같은 사용자의 오래된 주문들을 한꺼번에 `EXPIRED`로 정리하는 로직이었습니다.

```java
@Transactional
public void finishAndCleanup(Long orderId, Long userId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.markDone();              // (1) 더티 체킹으로 변경 의도
    orderRepository.save(order);   // (2) "저장했다"고 믿은 지점

    // (3) 같은 사용자의 오래된 주문 일괄 만료 처리
    orderRepository.expireOldOrders(userId, LocalDate.now().minusDays(30));
}
```

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = 'EXPIRED' " +
           "where o.userId = :userId and o.createdAt < :threshold and o.status = 'PAID'")
    int expireOldOrders(@Param("userId") Long userId,
                        @Param("threshold") LocalDate threshold);
}
```

기대한 결과는 "방금 처리한 주문은 `DONE`, 오래된 주문들은 `EXPIRED`"였습니다. 그런데 실제로는 **방금 처리한 주문의 `DONE`이 사라지고**, DB에는 만료 처리만 반영돼 있었습니다.

코드만 보면 도무지 이유가 없어 보입니다. `save()`는 분명히 (3)보다 먼저 호출됐으니까요.

## 기존 흐름을 순서로 보기

처음엔 저도 코드를 적힌 순서 그대로, 즉 **"각 줄이 곧 DB 명령"**이라고 읽었습니다. 그 관점에서 흐름은 이렇습니다.

```
(1) order.markDone()                 → 주문 상태를 DONE으로
(2) save(order)                       → UPDATE order SET status='DONE'  ← DB 반영(이라고 믿음)
(3) expireOldOrders(...)             → UPDATE order SET status='EXPIRED' WHERE ...
```

이 그림대로라면 (2)에서 이미 `DONE`이 DB에 박혔고, (3)의 `WHERE` 절은 `status = 'PAID'`만 잡으니 방금 `DONE`이 된 주문은 건드리지 않아야 합니다. 둘은 서로 다른 행이거나, 최소한 서로 간섭하지 않아야 맞습니다.

그런데 결과가 다릅니다. 이 모순은 **(2)가 DB 반영이 아니었다**는 사실을 인정하는 순간 풀리기 시작합니다.

## 영속성 컨텍스트 관점에서 다시 보기

JPA를 쓸 때 우리는 DB를 직접 만지는 게 아니라 **영속성 컨텍스트(Persistence Context)**라는 1차 캐시를 사이에 두고 일합니다. 트랜잭션이 살아있는 동안, 조회한 엔티티는 이 컨텍스트 안에 **관리 상태(managed)**로 보관됩니다.

핵심 동작은 세 가지입니다.

- **더티 체킹**: 관리 상태인 엔티티의 필드를 바꾸면, 그 변경은 일단 컨텍스트 안에만 기록됩니다. 즉시 SQL이 나가지 않습니다.
- **쓰기 지연**: 변경을 모아뒀다가 **flush 시점**에 한꺼번에 SQL로 내보냅니다.
- **flush 트리거**: flush는 (a) 트랜잭션 커밋 직전, (b) JPQL/native 쿼리 실행 직전, (c) `flush()` 직접 호출 시 자동으로 일어납니다.

이 관점으로 (1)~(3)을 다시 읽으면 그림이 완전히 달라집니다. 특히 (3)의 bulk update가 **JPQL이라 그 직전에 flush를 강제로 트리거한다**는 점, 그리고 `clearAutomatically = true`가 **그 직후 컨텍스트를 비운다**는 점이 사건의 두 축입니다.

하나씩 분해해 보겠습니다.

## save()는 DB 반영이 아니다

가장 먼저 깨야 할 믿음은 `save()`입니다.

이미 영속성 컨텍스트에 올라온(관리 상태) 엔티티에 대해 `save()`를 호출하면, Spring Data JPA는 내부적으로 `EntityManager.merge()`를 부르긴 하지만 **그 자리에서 곧바로 `UPDATE`를 날리지 않습니다.** 변경은 여전히 컨텍스트 안에 "더티" 표시로만 남아 있고, 실제 SQL은 다음 flush까지 미뤄집니다.

```java
order.markDone();              // 컨텍스트 안의 엔티티가 dirty 상태가 됨
orderRepository.save(order);   // 여전히 dirty. UPDATE는 아직 안 나감
```

사실 이 경우엔 `save()` 호출 자체가 군더더기입니다. 이미 관리 상태인 엔티티는 `markDone()`만 해도 더티 체킹 대상이 되기 때문에, flush 때 알아서 `UPDATE`가 나갑니다. `save()`가 있든 없든 결과는 같습니다.

문제는 그 다음입니다. "save = DB 반영"이라고 믿으면, **그 변경이 flush되기 전에 누군가 컨텍스트를 비워버릴 수 있다는 위험**을 보지 못합니다.

## bulk update는 영속성 컨텍스트를 우회한다

(3)의 `expireOldOrders`는 `@Modifying @Query`로 작성된 **bulk update**입니다. 이건 JPQL이 곧장 DB로 번역돼 나가는 연산으로, 영속성 컨텍스트를 **거치지 않고** DB의 행들을 직접 갱신합니다.

여기서 두 가지 일이 동시에 벌어집니다.

**첫째, 실행 직전에 자동 flush가 일어납니다.** Hibernate의 기본 flush 모드(`AUTO`)에서는, 같은 테이블을 건드릴 수 있는 JPQL/native 쿼리를 실행하기 전에 "지금까지 쌓인 변경을 DB에 먼저 반영"합니다. 안 그러면 bulk update가 보는 DB 상태와 컨텍스트가 어긋나기 때문입니다. 그래서 이 시점에 (1)의 `DONE` 변경이 비로소 `UPDATE`로 나갑니다.

**둘째, bulk update 자체는 컨텍스트와 동기화되지 않습니다.** DB의 행은 `EXPIRED`로 바뀌었지만, 컨텍스트 안에 들고 있던 그 엔티티 객체는 여전히 옛날 값을 들고 있습니다. 이 불일치를 방치하면, 같은 트랜잭션에서 그 엔티티를 다시 읽었을 때 **DB 아닌 컨텍스트의 헌 값**을 보게 됩니다. 그래서 흔히 `clearAutomatically = true`로 "bulk update 후 컨텍스트를 비워라"를 함께 겁니다.

바로 이 "비워라"가 다음 절의 양날의 검입니다.

## clearAutomatically = true는 왜 양날의 검인가

`clearAutomatically = true`는 bulk update 실행 **직후** 영속성 컨텍스트를 `clear()`합니다. 의도는 정당합니다. 위에서 말한 불일치(DB는 `EXPIRED`, 컨텍스트는 헌 값)를 없애서, 이후 조회가 신선한 값을 보게 하려는 거죠.

문제는 `clear()`가 **무차별적**이라는 점입니다. 컨텍스트를 비운다는 건, 그 안에 들어 있던 **모든** 관리 엔티티를 준영속(detached) 상태로 떼어낸다는 뜻입니다. 아직 flush되지 않은 더티 변경이 남아 있었다면 그것까지 **추적 대상에서 사라집니다.**

다행히 이번 케이스에서는 (1)의 `DONE`이 bulk update의 **자동 flush 덕분에 clear 직전에 이미 DB로 나갔어야** 맞습니다. 그런데 왜 사라졌을까요? 여기서 한 가지 더 봐야 할 게 있습니다. flush 모드가 `AUTO`가 아니거나(예: `readOnly` 트랜잭션에서 `MANUAL`로 바뀐 경우), 혹은 변경이 Hibernate가 "이 쿼리와 무관하다"고 판단해 자동 flush 대상에서 제외되는 경계 케이스에서는, **flush 없이 곧바로 clear**가 일어날 수 있습니다. 그 순간 더티 변경은 SQL 한 줄 못 남기고 증발합니다.

정리하면 `clearAutomatically = true`는 "불일치를 막아주는 안전장치"인 동시에, **"아직 안 내보낸 변경을 같이 쓸어버릴 수 있는 지우개"**입니다. 그 사이에서 살아남으려면 순서를 내가 통제해야 합니다.

## 실제로 변경이 사라진 순서

문제 상황을 실제로 일어난 순서대로 다시 그리면 이렇습니다.

```
(1) order.markDone()
        → 컨텍스트: order = dirty(DONE), 아직 DB에 안 나감

(2) save(order)
        → 여전히 dirty. UPDATE 미발행

(3) expireOldOrders(...) 호출
     ├─ 3a. (정상이라면) JPQL 실행 직전 자동 flush
     │        → UPDATE order SET status='DONE'  ← 여기서 나갔어야 함
     ├─ 3b. UPDATE order SET status='EXPIRED' WHERE ... (bulk)
     └─ 3c. clearAutomatically=true → 컨텍스트 clear()
              → 남아 있던 dirty 변경이 있었다면 여기서 detached 처리되어 소멸

(커밋) 더티 체킹 대상이 비었으므로 추가 UPDATE 없음
        → DB에는 EXPIRED만, DONE은 없음
```

핵심은 **3a와 3c 사이의 타이밍**입니다. flush가 확실히 먼저 일어났다면 `DONE`은 살아남습니다. 하지만 그 보장이 깨지는 순간 — flush 모드 변경, 트랜잭션 경계 설정, Hibernate 버전별 동작 차이 — `clear()`가 변경을 통째로 가져갑니다. **자동 flush에 운명을 맡기는 구조 자체가 위험**한 겁니다.

그래서 해법은 "자동 flush가 제때 돌기를 기도하는 것"이 아니라, **중요한 변경을 내가 명시적으로 먼저 못 박는 것**이어야 합니다.

## 해결: 후속 bulk update 전에 중요한 변경을 flush하기

가장 직접적인 해법은, bulk update를 호출하기 **전에** 지켜야 할 변경을 명시적으로 flush하는 것입니다. 컨텍스트의 자동 동작에 기대지 않고, 순서를 코드로 고정합니다.

```java
@Transactional
public void finishAndCleanup(Long orderId, Long userId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.markDone();

    // 중요한 변경을 먼저 DB로 못 박는다.
    orderRepository.flush();   // UPDATE order SET status='DONE' 즉시 발행

    // 이제 bulk update가 clear를 하더라도 잃을 dirty 변경이 없다.
    orderRepository.expireOldOrders(userId, LocalDate.now().minusDays(30));
}
```

`flush()`를 명시적으로 호출하면, 그 시점에 `DONE` 변경이 확실히 `UPDATE`로 나갑니다. 그 뒤 bulk update가 `clearAutomatically`로 컨텍스트를 비워도, **이미 DB에 반영된 변경이므로 잃을 게 없습니다.**

순서를 보장한 흐름은 이렇게 됩니다.

```
(1) markDone()        → dirty(DONE)
(2) flush()           → UPDATE status='DONE'  ← 명시적으로 DB 반영. 더 이상 dirty 아님
(3) expireOldOrders() → UPDATE status='EXPIRED' / clear()  ← 비울 dirty가 없음
(커밋) 모든 변경 안전
```

더 근본적으로는 **bulk update와 더티 체킹 기반 변경을 한 트랜잭션, 한 메서드에 섞지 않는 것**이 좋습니다. 둘은 영속성 컨텍스트를 다루는 모델이 정반대라서, 같이 두면 항상 이런 순서 의존성이 생깁니다. 책임을 분리할 수 있다면 그게 가장 깔끔합니다.

## saveAndFlush를 남발하면 안 되는 이유

"그럼 그냥 다 `saveAndFlush()` 쓰면 되는 거 아냐?"가 자연스러운 반응입니다. 실제로 위 `flush()` 대신 `saveAndFlush(order)`를 써도 이 버그는 잡힙니다.

하지만 `saveAndFlush`를 **습관처럼 모든 저장에 붙이는 건** 다른 비용을 부릅니다.

- **쓰기 지연·배치의 이점을 버립니다.** JPA가 변경을 모아뒀다가 한 번에 내보내는 최적화(특히 `batch_size` 설정과 결합한 일괄 INSERT/UPDATE)가 무력화됩니다. 매번 flush하면 SQL이 건건이 나갑니다.
- **flush는 컨텍스트 전체를 훑습니다.** flush는 호출한 그 엔티티 하나만 내보내는 게 아니라, 그 시점에 dirty인 **모든** 엔티티의 변경을 함께 내보냅니다. 의도치 않은 중간 상태가 미리 DB로 새어 나갈 수 있습니다.
- **트랜잭션 롤백 가능성과 충돌하는 착각을 줍니다.** flush를 해도 커밋 전까지는 롤백되지만, "flush했으니 안전하다"는 잘못된 안도감을 주기 쉽습니다.

그래서 기준은 이렇습니다. **flush는 "지금 이 변경의 순서를 반드시 보장해야 한다"는 의도가 있을 때만** 명시적으로 씁니다. 이번처럼 뒤에 컨텍스트를 비우는 연산이 따라오는, **순서가 정답을 가르는 지점**이 정확히 그런 경우입니다. 그 외 일반 저장은 flush를 트랜잭션 커밋에 맡기는 게 맞습니다.

## 이 경험에서 얻은 기준

이 버그를 거치고 나서 제 안에 남은 판단 기준은 세 가지입니다.

1. **`save()`는 "예약"이지 "반영"이 아니다.** 관리 상태 엔티티에서 `save()`는 더티 체킹과 같은 줄에 있을 뿐, DB 명령이 아닙니다. "언제 flush되는가"를 항상 같이 떠올립니다.
2. **bulk update는 컨텍스트 밖에서 논다.** `@Modifying` 쿼리는 영속성 컨텍스트를 우회해 DB를 직접 친다는 걸 잊지 않습니다. 그래서 `clearAutomatically`가 거의 필수처럼 따라오고, 그 `clear()`는 무차별적입니다.
3. **순서가 정답을 가르는 지점에서는 자동 동작에 맡기지 않는다.** 자동 flush 타이밍에 의존하는 코드는 환경(flush 모드, 트랜잭션 설정, 라이브러리 버전)이 바뀌면 조용히 깨집니다. 중요한 변경은 내가 먼저 못 박습니다.

## 체크리스트

`@Modifying` bulk update가 등장하는 코드를 만나면, 아래를 점검합니다.

- [ ] 같은 트랜잭션에서 bulk update **이전에** 더티 체킹/`save()` 변경이 있는가?
- [ ] 그 변경은 bulk update 실행 전에 **확실히** flush되는가? (자동 flush에만 의존하고 있지 않은가)
- [ ] `clearAutomatically = true`가 비우게 될 컨텍스트 안에, 아직 안 내보낸 변경이 남을 여지가 있는가?
- [ ] bulk update **이후에** 같은 엔티티를 다시 조회·사용하는 코드가 있는가? (있다면 clear 후 신선한 값을 받는지 확인)
- [ ] bulk update와 더티 체킹 변경을 굳이 한 메서드에 섞어야 하는가? 분리할 수는 없는가?
- [ ] `flush()`/`saveAndFlush()`를 쓴다면, 그게 **순서 보장 의도**인가 아니면 습관인가?

`save()` 한 줄을 적을 때, 그 변경이 "지금 DB에 있다"가 아니라 **"flush를 기다리는 중"**이라고 읽힌다면, 이 글의 목적은 다 한 셈입니다.
