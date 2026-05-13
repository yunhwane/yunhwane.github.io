# AI가 짜준 테스트, "통과"는 하는데 뭘 검증하는 걸까 — 설계 문서

- 날짜: 2026-05-13
- 카테고리: backend
- 스택: Java, Spring Boot, JUnit5, Mockito, AssertJ
- 분량 목표: 1500~2500자 (Aho-Corasick 포스트와 동일 톤)

## 목적

AI 생성 테스트 코드가 "통과는 하지만 실제로 아무것도 검증하지 않는" 패턴을 한 가지 사례(주문 취소 서비스)로 깊게 해부한다. 비판으로 끝내지 않고 "그럼 무엇을 검증해야 했는가"까지 코드로 보여준다.

## 앵글

AI는 테스트 커버리지/품질을 자주 망친다. 가장 흔한 증상은 **Mock.when 도배 → verify 1줄로 끝나는 가짜 통과 테스트**. "메서드가 호출되었다"는 "올바른 일이 일어났다"가 아니다.

## 톤

- 1인칭 경험담, "PR 리뷰 중 마주친 한 토막" 도입
- Aho-Corasick 포스트와 동일한 문체 (구어체, 짧은 문장, 회고적)
- 비판은 명확히 하되 AI 자체를 부정하지 않음 — 사용자 책임 영역 강조

## 글 구조

```
제목: AI가 짜준 테스트, "통과"는 하는데 뭘 검증하는 걸까
도입: PR 리뷰 중 마주친 테스트 한 토막. 초록불인데 뭔가 이상함
1. 문제 코드 — AI가 짠 테스트 그대로
2. 한 줄씩 뜯어보기 — Mock.when 도배가 왜 가짜인가
3. 진짜 검증해야 했던 것 — 비즈니스 행동 vs 호출 추적
4. 다시 짠 테스트 — 같은 메서드, 다른 관점
5. 마무리 — AI한테 테스트 시키기 전 체크리스트
```

## 사례 시나리오 (가공)

도메인: 주문 취소 서비스.

```java
public class OrderCancelService {
  public void cancelOrder(Long orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.cancel();                              // 상태 변경
    Money refund = order.calculateRefund();      // 환불 금액 계산
    refundClient.refund(order.getPayerId(), refund);
    notificationClient.send(order.getUserId(), CANCEL_TEMPLATE);
  }
}
```

AI가 짠 테스트의 문제점:

- `when(orderRepository.findById(any())).thenReturn(Optional.of(order))` 도배
- `when(refundClient.refund(any(), any())).thenReturn(success)` 도배
- assert는 `verify(refundClient).refund(any(), any())` 1줄
- 주문 상태가 실제로 `CANCELLED`로 바뀌었는지 검증 없음
- 환불 금액 계산 로직 통째로 우회 (`any()`로 받음)
- 알림 발송 검증도 `verify` 1줄로 종료

## 코드 샘플 계획

### Before (AI 산출물)

```java
@ExtendWith(MockitoExtension.class)
class OrderCancelServiceTest {
  @Mock OrderRepository orderRepository;
  @Mock RefundClient refundClient;
  @Mock NotificationClient notificationClient;
  @InjectMocks OrderCancelService service;

  @Test
  void cancelOrder_success() {
    Order order = mock(Order.class);
    when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
    when(order.calculateRefund()).thenReturn(Money.of(10000));

    service.cancelOrder(1L);

    verify(refundClient).refund(any(), any());
    verify(notificationClient).send(any(), any());
  }
}
```

문제 진단:
- `Order` 자체를 mock — `cancel()` 호출이 상태에 영향을 주는지 알 수 없음
- `refund` 인자가 무엇이든 통과 (`any()`)
- 알림 수신자가 누군지 검증 안 함

### After (재작성)

```java
@ExtendWith(MockitoExtension.class)
class OrderCancelServiceTest {
  @Mock OrderRepository orderRepository;
  @Mock RefundClient refundClient;
  @Mock NotificationClient notificationClient;
  @InjectMocks OrderCancelService service;

  @Test
  void 주문_취소시_상태가_CANCELLED_되고_환불금액과_수신자가_정확히_전달된다() {
    Order order = OrderFixture.paid(1L, 1000L, 9999L, 10000); // 실제 객체
    when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

    service.cancelOrder(1L);

    assertThat(order.getStatus()).isEqualTo(CANCELLED);

    ArgumentCaptor<Long> payerCaptor = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Money> moneyCaptor = ArgumentCaptor.forClass(Money.class);
    verify(refundClient).refund(payerCaptor.capture(), moneyCaptor.capture());
    assertThat(payerCaptor.getValue()).isEqualTo(1000L);
    assertThat(moneyCaptor.getValue()).isEqualTo(Money.of(10000));

    verify(notificationClient).send(eq(9999L), eq(CANCEL_TEMPLATE));
  }
}
```

변화 포인트:
- `Order`를 실제 객체로 (`OrderFixture`) — `cancel()` 행동 검증 가능
- `ArgumentCaptor`로 실제 전달된 환불 금액·수령자 검증
- 알림 수신자 ID `eq()`로 명시
- 테스트명이 "무엇을 검증하는지" 한 문장으로

## 마무리 체크리스트

AI에게 테스트 짜라 하기 전·후 셀프 점검:

1. `verify()`만 있고 `assertThat()` 없으면 의심
2. Mock이 stub만 하고 끝나면 → 실제 객체 가능한지 먼저 확인
3. "이 테스트는 어떤 버그를 잡아낼 수 있나" 자문 — 답 못 하면 가짜
4. AI가 실패하는 테스트 보고 expected 바꾸자 하면 거절
5. coverage 숫자 대신 mutation testing 한 번이라도 돌려보기

## 비범위 (Out of Scope)

- 통합 테스트·E2E 영역 (단위 테스트로 한정)
- AI 도구별 비교 (Copilot vs Claude vs Cursor)
- 테스트 자동 생성 도구 (EvoSuite 등) 비교
- 다른 언어/프레임워크 (Java/Spring 한정)

## Frontmatter 초안

```yaml
---
layout: post
title: "AI가 짜준 테스트, \"통과\"는 하는데 뭘 검증하는 걸까"
date: 2026-05-13 18:00:00 +0900
categories: [backend]
tags: [testing, ai, junit, mockito, code-review]
---
```

## 파일 경로

`_posts/2026-05-13-ai-generated-test-quality.md`
