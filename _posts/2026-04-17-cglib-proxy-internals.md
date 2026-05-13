---
layout: post
title: "CGLIB 프록시는 무엇을 하고 있나"
date: 2026-04-17 18:00:00 +0900
categories: [backend]
tags: [spring, aop, cglib, proxy, java]
---

## 도입: "JDK 프록시랑 뭐가 달라요?"

지난 글에서 `@Transactional`이 프록시 위에서 동작한다고 했습니다. 그리고 그 프록시는 **기본적으로 CGLIB**라고 한 줄 적고 넘어갔습니다.

후배가 그 글을 읽고 물었습니다.

> "CGLIB 프록시랑 JDK 동적 프록시랑 뭐가 다른 거예요? 그리고 왜 Spring은 CGLIB를 기본으로 골랐어요?"

대답하려고 보니, 평소에 "그냥 CGLIB가 쓰인다더라" 정도로만 알고 있었지 **CGLIB가 정확히 어떻게 프록시를 만드는지**는 제대로 들여다본 적이 없더군요. 이번 글은 그 답을 정리한 학습 노트입니다.

## 두 가지 프록시 방식

Spring AOP가 프록시를 만드는 방법은 크게 두 가지입니다.

### JDK 동적 프록시

JDK가 표준으로 제공하는 `java.lang.reflect.Proxy`를 씁니다. 핵심 제약은 **인터페이스 기반**이라는 점입니다.

```java
interface PaymentService {
    void pay(Long orderId);
}

class PaymentServiceImpl implements PaymentService {
    public void pay(Long orderId) { ... }
}
```

이런 구조에서 JDK 프록시는 `PaymentService` 인터페이스를 구현하는 새 클래스를 런타임에 만들어냅니다. 프록시는 `PaymentServiceImpl`이 아니라 `PaymentService`만 알면 됩니다.

```java
PaymentService proxy = (PaymentService) Proxy.newProxyInstance(
    classLoader,
    new Class<?>[] { PaymentService.class },
    invocationHandler
);
```

장점은 표준 라이브러리만 쓴다는 점, 단점은 **인터페이스가 없으면 못 만든다**는 점입니다.

### CGLIB 프록시

CGLIB(Code Generation Library)는 바이트코드 조작 라이브러리입니다. ASM 위에서 동작합니다. 인터페이스 없이 **클래스를 상속하는 서브클래스**를 런타임에 생성해 프록시로 씁니다.

```
PaymentService           ← 원본 클래스
    ↑
PaymentService$$EnhancerBySpringCGLIB$$abc123   ← CGLIB가 만든 자식 클래스
```

이름이 익숙할 겁니다. 디버거에 한 번쯤 등장했을 그 길쭉한 클래스 이름이 바로 CGLIB가 만든 프록시입니다.

## CGLIB가 실제로 하는 일

CGLIB가 프록시를 만들 때 일어나는 일을 풀어 봅니다.

### 1. 원본 클래스를 상속한 서브클래스를 바이트코드로 생성한다

원본이 `PaymentService`라면 CGLIB는 대략 이런 클래스를 바이트코드 레벨에서 만들어냅니다.

```java
// 의사 코드 — 실제로는 컴파일 결과물 없이 바이트코드로 직접 생성
public class PaymentService$$EnhancerBySpringCGLIB$$abc123 extends PaymentService {

    private MethodInterceptor interceptor;

    @Override
    public void pay(Long orderId) {
        // 원본 메서드를 호출하기 전에 인터셉터로 분기
        interceptor.intercept(this, methodPay, new Object[]{ orderId }, methodProxy);
    }
}
```

상속 기반이라서 **원본 클래스의 public/protected 메서드를 모두 오버라이드**할 수 있습니다. 인터페이스가 없어도 됩니다.

### 2. 메서드 호출을 인터셉터로 라우팅한다

오버라이드된 각 메서드는 직접 비즈니스 로직을 실행하지 않습니다. 등록된 인터셉터(`MethodInterceptor`)로 호출을 위임합니다. Spring AOP의 `@Transactional` 어드바이스가 거기에 끼어들어 트랜잭션 시작/종료를 수행합니다.

### 3. 정의된 클래스를 클래스로더에 주입한다

생성된 바이트코드는 그냥 바이트 배열입니다. 이걸 `ClassLoader`에 정의해서 실제 사용 가능한 클래스로 만들어야 합니다. 옛 CGLIB는 `ClassLoader.defineClass`를 리플렉션으로 호출했는데, JDK 9 이후로는 `MethodHandles.Lookup.defineClass`나 `ClassLoader#defineClass`를 정식 경로로 씁니다. Spring 5.x에서 이 호환성 작업이 꽤 큰 비중을 차지했습니다.

## CGLIB가 못 잡는 것들

상속 기반이라는 점이 강력하지만, 동시에 한계도 만듭니다.

### final 클래스 / final 메서드

`final`은 "상속이나 오버라이드 금지" 선언입니다. CGLIB는 상속과 오버라이드로 동작하므로 **`final` 클래스는 프록시를 만들 수 없고, `final` 메서드는 어드바이스가 적용되지 않습니다.**

```java
@Service
public final class ReportService {           // final 클래스
    @Transactional
    public void generate() { ... }
}
```

이런 코드는 컨테이너 기동 시 예외가 나거나, 어드바이스가 조용히 무시됩니다. Kotlin 클래스가 기본적으로 `final`인 점도 같은 이유로 자주 문제가 됩니다(그래서 `kotlin-spring` 플러그인이 `@Component`류에 `open`을 자동으로 붙여줍니다).

### private 메서드

`private`는 오버라이드 자체가 불가능합니다. CGLIB가 만든 서브클래스에서 보이지 않으므로 어드바이스가 걸리지 않습니다.

### 생성자

CGLIB 프록시는 원본 클래스의 **생성자를 한 번 더 호출**합니다. 부모 클래스의 생성자가 어쩔 수 없이 실행되기 때문입니다.

```
1. Spring이 원본 PaymentService 인스턴스를 만든다  → 생성자 1회 실행
2. CGLIB가 서브클래스 인스턴스를 만든다            → super() 호출로 생성자 1회 더 실행
```

생성자에 부작용이 있으면 이 점이 함정이 됩니다(외부 API 호출이라든가). Spring 4.x 이후로는 `@Configuration`의 CGLIB 프록시에서 이 문제를 피하기 위해 `objenesis`로 **생성자 호출을 우회하는 기법**이 도입됐습니다. 일반 `@Service` 프록시는 그렇게 다루지 않습니다.

## 왜 Spring Boot의 기본값이 CGLIB일까

Spring 3.x 시절에는 JDK 동적 프록시가 기본이었습니다. 인터페이스만 있으면 JDK 프록시를 쓰고, 인터페이스가 없으면 CGLIB로 fallback하는 방식이었습니다.

Spring Boot 2.x부터는 **CGLIB가 기본값**이 됐습니다. 이유는 대략 이렇습니다.

- 인터페이스 없이 `@Service` 클래스를 바로 쓰는 패턴이 보편화됐습니다.
- JDK 프록시는 인터페이스 타입으로만 주입받을 수 있어, 구현 클래스 타입으로 주입할 때 캐스팅 오류가 자주 났습니다(`PaymentServiceImpl service = ...` 같은 코드가 깨짐).
- CGLIB로 통일하면 두 방식의 차이로 인한 미묘한 버그가 줄어듭니다.

설정에서 명시적으로 바꿀 수도 있습니다.

```properties
spring.aop.proxy-target-class=false   # JDK 동적 프록시 우선
```

대부분의 프로젝트에서는 기본값을 유지하는 게 무난합니다.

## 부수 효과 하나: 디버깅과 스택 트레이스

CGLIB 프록시가 끼어든 스택은 이렇게 보입니다.

```
PaymentService$$EnhancerBySpringCGLIB$$abc123.pay(<generated>)
    at ...TransactionInterceptor.invoke(...)
    at ...CglibAopProxy$DynamicAdvisedInterceptor.intercept(...)
    at PaymentService.pay(PaymentService.java:42)
```

처음 보면 "이게 뭐지?" 싶지만, 한 번 알아두면 스택만 보고 **"프록시가 끼었구나, 트랜잭션 인터셉터 거쳤구나"** 가 바로 읽힙니다. self-invocation 문제를 의심할 때도 스택을 보고 "원본 → 원본" 호출인지 "프록시 → 원본" 호출인지 확인할 수 있습니다.

## 정리하며

- JDK 동적 프록시는 **인터페이스 기반**, CGLIB 프록시는 **상속 기반**입니다.
- CGLIB는 원본 클래스를 상속한 서브클래스를 바이트코드로 만들어 클래스로더에 주입합니다. 인터페이스 없이도 프록시가 만들어집니다.
- **`final`과 `private`는 CGLIB가 못 잡습니다.** Kotlin 클래스 기본이 `final`인 점, `private` 메서드에 `@Transactional`을 붙이는 게 안 되는 점이 모두 같은 이유입니다.
- 생성자가 두 번 실행될 수 있습니다. 부작용이 있는 생성자를 피해야 하는 이유 중 하나입니다.
- Spring Boot 기본값이 CGLIB인 건 인터페이스 없는 빈이 보편적이라는 현실 반영입니다.

`@Transactional` → AOP → 프록시 → CGLIB 순으로 한 계단씩 내려와 보니, 평소 무심코 쓰던 어노테이션이 사실은 **바이트코드를 새로 찍어 클래스로더에 밀어 넣는 일**을 하고 있다는 게 새삼 신기했습니다.
