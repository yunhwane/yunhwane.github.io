---
layout: post
title: "@Transactional을 붙이면 실제로 일어나는 일"
date: 2026-04-08 18:00:00 +0900
categories: [backend]
tags: [spring, transaction, aop, proxy, jpa]
---

## 도입: 분명히 붙였는데 롤백이 안 됐다

지난번 트랜잭션 글에서 "@Transactional은 시작점일 뿐"이라고 적었습니다. 이번 글은 그 시작점이 **실제로 어떻게 동작하는지**에 관한 이야기입니다.

이 글을 쓰게 된 계기는 며칠 전에 마주친 한 줄짜리 버그였습니다. 결제 처리 중 예외가 발생했는데 DB에는 데이터가 그대로 남아 있었습니다. 분명히 `@Transactional`은 붙어 있었습니다.

```java
@Service
public class PaymentService {

    @Transactional
    public void pay(Long orderId) {
        // ... 처리 로직
        validate(orderId);
    }

    @Transactional
    public void validate(Long orderId) {
        if (notValid(orderId)) {
            throw new IllegalStateException("invalid");
        }
    }
}
```

이 코드에서 `pay()`가 내부적으로 `validate()`를 호출하고, `validate()`가 예외를 던집니다. 그런데 `pay()`의 작업은 롤백되지 않았습니다. 왜일까요?

답은 **프록시**에 있습니다. `@Transactional`이 어떻게 동작하는지 처음부터 풀어 봅니다.

## @Transactional은 어떻게 트랜잭션을 거는가

`@Transactional`이 붙은 메서드를 직접 호출한다고 해서, 그 메서드 본문 안에서 `connection.setAutoCommit(false)`가 마법처럼 실행되지는 않습니다. Spring은 AOP 기반의 **프록시 객체**로 이걸 처리합니다.

### 1단계: 빈을 등록할 때 프록시로 감싼다

Spring이 `PaymentService` 빈을 생성할 때, `@Transactional` 어노테이션이 있으면 원본 객체를 그대로 등록하지 않습니다. 대신 원본을 감싸는 **프록시 객체**를 만들어 등록합니다.

```
컨테이너에 등록된 빈:  PaymentService$$EnhancerBySpringCGLIB$$xxxx
                       └─ 내부에 진짜 PaymentService 인스턴스를 보유
```

기본적으로 CGLIB 기반 클래스 프록시가 쓰이고, 인터페이스가 있으면 JDK 동적 프록시가 쓰일 수도 있습니다(설정에 따라).

### 2단계: 프록시가 트랜잭션을 시작하고 위임한다

`paymentService.pay(orderId)`가 호출되면 사실은 프록시 객체의 `pay()`가 호출됩니다. 프록시는 대략 이런 일을 합니다.

```java
// 의사 코드
public void pay(Long orderId) {
    TransactionStatus tx = transactionManager.getTransaction(...);  // BEGIN
    try {
        target.pay(orderId);                                        // 원본 호출
        transactionManager.commit(tx);                              // COMMIT
    } catch (RuntimeException e) {
        transactionManager.rollback(tx);                            // ROLLBACK
        throw e;
    }
}
```

트랜잭션의 시작과 종료는 **프록시 레이어에서 일어납니다**. 원본 메서드 본문은 그 안쪽에서 평범하게 실행될 뿐입니다.

이 구조를 이해하는 순간, 흔한 함정 두 가지가 자연스럽게 풀립니다.

## 함정 1: self-invocation은 트랜잭션이 안 걸린다

다시 처음의 코드로 돌아갑니다.

```java
public void pay(Long orderId) {
    // ... 처리 로직
    validate(orderId);   // ← this.validate()
}
```

`pay()` 안에서 `validate()`를 호출하면 이건 **`this.validate()`** 입니다. `this`는 누구일까요? 프록시가 아니라 **원본 객체**입니다. 프록시는 외부에서만 보이고, 원본 입장에서 자기 자신을 호출할 때는 프록시를 거치지 않습니다.

```
외부 호출:   paymentService.pay()
             └→ 프록시 진입 → 트랜잭션 시작 → target.pay() 실행
                                                  └→ this.validate()  ← 프록시 안 거침
                                                       └→ 원본 validate() 직접 실행
                                                            └→ @Transactional 무시됨
```

`validate()`의 `@Transactional`은 무시되고, `pay()`가 이미 시작한 트랜잭션 안에서 그냥 평범한 메서드 호출로 끝납니다. 같은 트랜잭션이라는 점은 다행이지만, 만약 `validate()`에 `propagation = REQUIRES_NEW`를 걸어두고 새 트랜잭션을 기대했다면 그 의도는 무너집니다.

### 해결 방법

- **메서드를 다른 빈으로 분리한다.** 가장 흔하고 깔끔합니다. `ValidationService`로 빼면 자연스럽게 프록시를 거치게 됩니다.
- **자기 자신을 주입받는다.** `ApplicationContext`에서 `self` 빈을 받아 `self.validate()`로 호출합니다. 가능하지만 코드 냄새가 납니다.
- **AspectJ 위빙으로 전환한다.** 컴파일/로드타임 위빙은 프록시가 아니라 바이트코드 자체를 수정하므로 self-invocation도 잡힙니다. 운영 부담은 더 큽니다.

## 함정 2: 기본적으로는 RuntimeException만 롤백한다

Spring `@Transactional`의 기본 동작은 **unchecked exception(RuntimeException, Error)** 발생 시에만 롤백입니다. **checked exception은 잡고도 커밋**합니다.

```java
@Transactional
public void save() throws IOException {
    repository.save(entity);
    throw new IOException("file fail");   // checked → 커밋됨
}
```

왜 이렇게 설계되었는지에는 여러 의견이 있지만, 결과적으로 "Java에서 IOException 같은 걸 던졌다고 비즈니스 트랜잭션을 무조건 되돌리는 건 과한 가정"이라는 입장입니다. 동의 여부와 별개로 **현실 동작이 그렇기 때문에** 알고 있어야 합니다.

명시적으로 지정하려면 `rollbackFor`를 씁니다.

```java
@Transactional(rollbackFor = Exception.class)
public void save() throws IOException { ... }
```

## propagation: 트랜잭션이 이미 있을 때 어떻게 할까

`@Transactional` 메서드 A가 또 다른 `@Transactional` 메서드 B를 호출하면(외부 빈으로 분리되어 프록시를 거친다고 가정) B 입장에서는 "이미 트랜잭션이 진행 중"입니다. 이때 어떻게 행동할지가 `propagation` 옵션입니다.

자주 쓰는 것 위주로 정리하면 이렇습니다.

- **REQUIRED (기본값)**: 진행 중인 트랜잭션이 있으면 합류, 없으면 새로 시작. 가장 흔한 선택.
- **REQUIRES_NEW**: 진행 중인 트랜잭션이 있으면 잠시 중단(suspend)하고, 새 트랜잭션을 시작. 끝나면 원래 트랜잭션 재개. 로그 기록처럼 **부모와 운명을 분리**하고 싶을 때 씁니다.
- **NESTED**: 부모 트랜잭션 안의 **저장점(savepoint)**을 만들어 부분 롤백을 가능하게 합니다. DB와 드라이버 지원이 있어야 동작합니다.
- **SUPPORTS / NOT_SUPPORTED / MANDATORY / NEVER**: 덜 쓰지만 필요할 때 찾아보면 됩니다.

### REQUIRES_NEW의 자주 하는 실수

```java
@Transactional
public void process() {
    doMainWork();
    auditService.log(...);   // 항상 남기고 싶음
}

@Service
public class AuditService {
    @Transactional(propagation = REQUIRES_NEW)
    public void log(...) { ... }
}
```

목적은 "메인 작업이 롤백되더라도 감사 로그는 남기기"입니다. 의도는 좋지만 두 가지 주의가 필요합니다.

1. **커넥션이 2개 필요합니다.** 부모 트랜잭션을 suspend하고 새 커넥션으로 자식 트랜잭션을 시작합니다. 커넥션 풀이 빈약하면 데드락 직전까지 갑니다.
2. **자식 트랜잭션이 부모의 변경을 보지 않습니다.** 같은 DB라도 다른 트랜잭션입니다.

## readOnly = true의 진짜 효과

`@Transactional(readOnly = true)`는 "DB가 읽기만 한다고 가정하고 최적화하라"는 힌트입니다.

JPA를 쓰는 경우 효과가 큽니다.

- **flush 모드가 MANUAL로 변경**되어, 영속성 컨텍스트의 더티 체킹을 위한 스냅샷을 만들지 않습니다. 메모리/CPU 절약입니다.
- **Hibernate가 자동 flush를 건너뜁니다.** 트랜잭션 종료 시 변경 감지 비용이 사라집니다.

순수 JDBC 환경에서는 효과가 제한적이지만, JPA 환경에서는 조회 트랜잭션에 붙여 두는 게 거의 항상 이득입니다.

## 다시 그 사건의 결말

처음 이야기로 돌아가면, 우리가 한 일은 단순했습니다.

```java
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final ValidationService validationService;

    @Transactional
    public void pay(Long orderId) {
        // ...
        validationService.validate(orderId);   // 외부 빈 호출 → 프록시 경유
    }
}
```

`validate()`를 다른 빈으로 분리했을 뿐입니다. 이제 호출이 프록시를 거치고, propagation 기본값인 `REQUIRED`에 따라 부모 트랜잭션에 합류한 뒤, 예외가 던져지면 한 묶음으로 롤백됩니다.

## 정리하며

- `@Transactional`은 **프록시** 위에서 동작합니다. 프록시를 거치지 않는 호출(self-invocation)에는 적용되지 않습니다.
- 기본 롤백 대상은 **unchecked exception**입니다. checked 예외도 롤백시키려면 `rollbackFor`를 명시합니다.
- propagation은 "이미 트랜잭션이 있을 때의 행동"입니다. `REQUIRES_NEW`는 커넥션과 가시성을 분리하는 도구이지 무료 옵션이 아닙니다.
- JPA 환경에서 조회용 트랜잭션에는 `readOnly = true`를 붙여 두면 비용을 줄일 수 있습니다.

다음에 `@Transactional`을 붙일 때, 머릿속에 **빈 컨테이너 안의 프록시 객체 하나**가 떠오른다면 이 글의 목적은 다 한 셈입니다.
