---
layout: post
title: "AI가 짜준 테스트, \"통과\"는 하는데 뭘 검증하는 걸까"
date: 2026-05-13 18:00:00 +0900
categories: [backend]
tags: [testing, ai, junit, mockito, code-review]
---

## 도입: 초록불인데 뭔가 이상한 PR

며칠 전 PR 리뷰 중에 이런 테스트를 마주쳤습니다. 작성자는 "AI한테 짜달라고 했더니 한 방에 통과하더라"고 코멘트를 달아둔 상태였습니다. CI는 초록불, 커버리지도 올라갔습니다.

근데 코드를 한참 들여다봐도 이게 뭘 검증하는 건지 모르겠더라구요.

```java
@Test
void cancelOrder_success() {
  Order order = mock(Order.class);
  when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
  when(order.calculateRefund()).thenReturn(Money.of(10000));

  service.cancelOrder(1L);

  verify(refundClient).refund(any(), any());
  verify(notificationClient).send(any(), any());
}
```

테스트 대상은 주문 취소 서비스고, 본문은 대충 이렇게 생겼습니다.

```java
public void cancelOrder(Long orderId) {
  Order order = orderRepository.findById(orderId).orElseThrow();
  order.cancel();                                       // 상태 변경
  Money refund = order.calculateRefund();               // 환불 금액 계산
  refundClient.refund(order.getPayerId(), refund);
  notificationClient.send(order.getUserId(), CANCEL_TEMPLATE);
}
```

이 글은 이 테스트가 왜 통과해도 "테스트가 아닌지", 그리고 같은 메서드를 어떻게 다시 검증할 수 있는지에 대한 이야기입니다.

---

## 1. 한 줄씩 뜯어보기 — 왜 가짜인가

위 테스트의 단계별 행동을 다시 읽어보겠습니다.

**`Order order = mock(Order.class);`**
주문 객체 자체를 mock으로 만들었습니다. 이 순간부터 `order.cancel()`을 호출하든, `order.getStatus()`를 호출하든, 실제 도메인 로직은 한 줄도 실행되지 않습니다. mock은 "호출은 받지만 아무것도 안 하는 인형"이니까요.

**`when(orderRepository.findById(1L)).thenReturn(Optional.of(order));`**
"`findById(1L)`을 호출하면 위에서 만든 인형을 돌려줘라"는 시나리오를 깔아뒀습니다. 여기까진 자연스럽습니다.

**`when(order.calculateRefund()).thenReturn(Money.of(10000));`**
이 줄이 결정적입니다. `Order`가 자기 안에서 환불 금액을 어떻게 계산하는지 — 할인 적용은 됐는지, 부분 환불이면 얼마인지, 음수가 안 나오는지 — 이런 비즈니스 규칙을 통째로 우회합니다. "10000원이 나온다고 치자"로 시작하는 시나리오인 거죠.

**`verify(refundClient).refund(any(), any());`**
`refund`가 한 번 호출됐는지만 봅니다. **누구한테 얼마를 환불했는지는 묻지 않습니다.** `refund(null, Money.of(-9999))`가 흘러가도 통과합니다.

**`verify(notificationClient).send(any(), any());`**
마찬가지. 엉뚱한 유저한테 알림이 가도 이 테스트는 초록불을 켭니다.

이 테스트가 잡을 수 있는 버그를 한번 세어봅니다.

- `cancelOrder` 본문에서 `refundClient.refund(...)` 호출 라인을 통째로 지우면? → 잡힙니다
- `refund`에 넘기는 금액을 `Money.of(0)`으로 잘못 바꾸면? → **못 잡습니다**
- 알림 수신자를 `payerId` 대신 `orderId`로 잘못 넘기면? → **못 잡습니다**
- `order.cancel()` 호출을 빼먹어서 상태가 그대로 `PAID`로 남으면? → **못 잡습니다**

세 개를 못 잡는데 통과합니다. 이건 "호출 추적기"지 테스트가 아닙니다.

---

## 2. 진짜 검증해야 했던 것

다시 본문을 봅니다.

```java
order.cancel();
Money refund = order.calculateRefund();
refundClient.refund(order.getPayerId(), refund);
notificationClient.send(order.getUserId(), CANCEL_TEMPLATE);
```

이 메서드가 "정상 동작했다"는 것의 정의를 풀어쓰면 이렇습니다.

1. 주문 상태가 `CANCELLED`로 바뀐다
2. 환불 요청이 **올바른 결제자에게**, **올바른 금액으로** 나간다
3. 취소 알림이 **주문자에게**, **취소 템플릿으로** 발송된다

테스트가 이 세 가지 중 하나라도 직접 비교하지 않으면, 그건 그 가지를 안 보고 있는 겁니다.

여기서 AI가 자주 빠지는 함정이 보입니다. AI는 본문의 호출 시퀀스를 그대로 따라 `verify` 줄을 만듭니다. **"무엇을 검증해야 하는가"를 알려주지 않으면 "무엇이 호출되는가"를 베껴 옵니다.** 코드의 표면만 따라가는 거죠.

---

## 3. 다시 짠 테스트

같은 메서드, 다른 관점으로 다시 짭니다.

```java
@ExtendWith(MockitoExtension.class)
class OrderCancelServiceTest {

  @Mock OrderRepository orderRepository;
  @Mock RefundClient refundClient;
  @Mock NotificationClient notificationClient;
  @InjectMocks OrderCancelService service;

  @Test
  void 주문_취소시_상태가_CANCELLED_되고_환불금액과_수신자가_정확히_전달된다() {
    // given: 실제 Order 객체. cancel()/calculateRefund()가 도메인 로직대로 실행된다.
    Order order = OrderFixture.paid(
        /* orderId */ 1L,
        /* payerId */ 1000L,
        /* userId  */ 9999L,
        /* amount  */ 10000
    );
    when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

    // when
    service.cancelOrder(1L);

    // then: 상태 검증
    assertThat(order.getStatus()).isEqualTo(CANCELLED);

    // then: 환불 인자 검증 — any()가 아니라 실제 값
    ArgumentCaptor<Long> payerCaptor = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Money> moneyCaptor = ArgumentCaptor.forClass(Money.class);
    verify(refundClient).refund(payerCaptor.capture(), moneyCaptor.capture());
    assertThat(payerCaptor.getValue()).isEqualTo(1000L);
    assertThat(moneyCaptor.getValue()).isEqualTo(Money.of(10000));

    // then: 알림 수신자/템플릿 검증
    verify(notificationClient).send(eq(9999L), eq(CANCEL_TEMPLATE));
  }
}
```

바뀐 포인트만 정리합니다.

- **`Order`를 mock에서 실제 객체로**. `cancel()`이 진짜 실행되니 상태 전이를 검증할 수 있습니다. 환불 금액 계산도 도메인 로직을 그대로 통과시킵니다.
- **`any()`를 버리고 `ArgumentCaptor` 또는 `eq()` 사용**. 무엇이 넘어갔는지를 봅니다.
- **테스트명이 검증 대상을 한 문장으로 말함**. "성공한다"가 아니라 "상태가 CANCELLED 되고 환불금액과 수신자가 정확히 전달된다".

이제 앞에서 못 잡았던 세 가지 버그를 다시 대입해보면 전부 빨간불이 켜집니다.

---

## 4. 마무리: AI한테 테스트 시키기 전·후 체크리스트

AI를 안 쓰자는 얘기가 아닙니다. 위 "After" 코드도 AI한테 시키면 잘 짭니다 — **무엇을 검증할지 사람이 먼저 말해줬을 때**.

PR 리뷰하면서, 그리고 제 코드를 다시 보면서 쓰는 5줄짜리 점검표를 공유하면서 마칩니다.

1. `verify()`만 있고 `assertThat()`이 없으면 일단 의심합니다
2. Mock을 stub만 하고 끝나면, 그 객체를 실제로 만들 수 있는지 먼저 확인합니다
3. "이 테스트는 어떤 버그를 잡을 수 있나"를 자문해보고, 답을 못 하면 가짜입니다
4. AI가 실패하는 테스트를 보고 `expected`를 바꾸자고 하면 거절합니다. 원인 먼저
5. 커버리지 숫자 대신 mutation testing을 한 번이라도 돌려봅니다. 가짜는 mutant가 다 살아남습니다

테스트는 코드가 동작한다는 증명이지, 코드가 호출된다는 기록이 아닙니다. AI가 둘을 자주 헷갈리는 만큼, 그 차이를 챙기는 책임은 결국 사람에게 남아 있습니다.
