---
layout: post
title: "금지어 필터 만들기 — Aho-Corasick과 우회표기 정규화"
date: 2026-05-07 18:00:00 +0900
categories: [backend]
tags: [algorithm, aho-corasick, java, spring-boot]
---

## 시작: 그냥 `contains` 돌리면 안 되나?

리뷰랑 플랜 본문에 금지어가 들어오면 잡아내는 기능을 맡게 됐습니다. 욕설, 비방, 광고성 단어 같은 것들이죠. 금지어 사전은 운영팀이 관리자 페이지에서 DB에 직접 등록하고, 운영하면서 계속 늘어납니다.

요구사항은 단순했습니다.

- 본문 한 덩어리에 대해 등록된 모든 금지어를 한 번에 찾는다
- 매칭된 단어를 그대로 응답으로 돌려준다 (클라이언트가 하이라이팅해야 하니까)

처음엔 진짜 별거 아닌 줄 알았습니다. 이렇게 짜면 되는 거 아닌가?

```java
for (String keyword : bannedKeywords) {
  if (text.contains(keyword)) {
    hits.add(keyword);
  }
}
```

근데 막상 만들다 보니 두 가지가 발목을 잡더라구요.

1. 사전이 커지면 매 요청마다 N번 `contains`를 도는 게 부담된다
2. 유저들이 `시1발`, `시 발`, `시~발`, `ㅅㅂ` 같은 우회표기를 너무 자유롭게 쓴다

이 글은 그 두 문제를 어떻게 풀었는지에 대한 이야기입니다.

---

## 1. Aho-Corasick으로 갈아탄 이유

### 단순 `contains` 루프의 한계

위에 적은 루프의 시간 복잡도를 계산해봅니다. 금지어 N개, 본문 길이 M, 평균 keyword 길이 K라고 하면 **O(N · M · K)** 입니다.

본문은 짧습니다 (저희는 2000자 상한). 문제는 N입니다. 운영하면서 금지어가 100개, 500개, 1000개로 늘어나는데, 그게 그대로 latency에 박힙니다. 그리고 이 검사는 글 작성 API의 동기 경로에 있어서 늦어지면 바로 유저가 느낍니다.

### Aho-Corasick의 아이디어

Aho-Corasick은 **여러 패턴을 한꺼번에 찾는** 문자열 매칭 알고리즘입니다. 동작 방식을 한 줄로 요약하면 이렇습니다.

> 모든 keyword를 trie 하나에 합쳐 넣고, 본문을 단 한 번만 훑으면서 매칭되는 걸 전부 잡아낸다.

trie를 빌드하면서 "여기서 매칭이 실패하면 어디로 점프해야 다른 keyword 매칭이 이어지는지"를 미리 계산해두는데 (이걸 failure link라고 부릅니다), 그 덕분에 본문을 처음부터 끝까지 한 번 스캔하면 끝납니다.

비용을 다시 써보면

- trie 빌드: keyword 등록 시점에 1회
- 검색: **O(M + 매칭 개수)** — N이 사라졌습니다

저희 상황 — "본문은 짧고 사전은 점점 커진다" — 에 딱 맞는 모양이었습니다.

### 직접 구현하다 라이브러리로 갈아탄 사연

처음엔 공부 삼아 직접 구현했습니다. trie 노드 정의하고, BFS로 failure link 깔고… 다 짠 줄 알았는데 테스트에서 문제가 터졌습니다.

`she`와 `he`를 동시에 등록해두고 본문 `"she"`를 검색하면 둘 다 잡혀야 합니다. 근데 제 코드는 `she` 하나만 잡고 `he`를 놓쳤습니다. failure link를 따라가면서 suffix 매칭을 다 수집해야 하는데, 그 부분에 버그가 있었던 거죠.

자료구조 코어의 미묘한 버그를 도메인 코드에 끌고 갈 이유가 없어서 [`robert-bor/aho-corasick`](https://github.com/robert-bor/aho-corasick)로 갈아탔습니다.

```java
Trie.TrieBuilder builder = Trie.builder();
for (String keyword : keywords) {
  builder.addKeyword(normalize(keyword));
}
Trie trie = builder.build();

Collection<Emit> hits = trie.parseText(normalize(text));
```

핵심 알고리즘은 라이브러리에 맡기고, 저는 **"trie에 뭘 넣고, 입력을 어떻게 정규화할지"** 만 고민하기로 했습니다. 사실 이 글의 진짜 주제도 그쪽입니다.

---

## 2. 진짜 어려운 건 우회표기였습니다

### 시드 데이터가 없었다

기능을 만들기 시작한 시점에 **금지어 DB가 거의 비어 있었습니다.** 다른 서비스에서 받아올 시드도 없었고, 운영팀이 한 글자씩 채워줘야 하는 상황이었어요.

여기서 선택지가 갈렸습니다.

**선택지 A.** 운영팀이 우회표기까지 전부 등록한다.
`시발`, `시1발`, `시2발`, `시 발`, `시~발`, `시.발`, `시ㅋ발`, `시^발`, ...

**선택지 B.** DB에는 canonical 한 단어만 등록하고, 검사 단계에서 본문을 정규화해서 우회표기를 흡수한다.

A로 가면 단어 하나당 등록해야 하는 변종이 수십~수백 개로 늘어납니다. 운영팀이 못 따라옵니다. 캐시도 비대해지고요.

당연히 B로 갔습니다.

### 정규화 정책을 enum으로 빼기

우회표기를 잘 보면 결국 본문에 **"의미 없는 잡음 문자"** 가 섞여 있는 거였습니다. `시1발`의 `1`, `시 발`의 공백, `시~발`의 `~` 같은 것들이요. 이걸 떨어내고 나면 trie가 깔끔하게 `시발`을 매칭합니다.

근데 "잡음" 의 종류가 한 가지가 아니라서, 어떤 카테고리를 떨어낼지 정책 단위로 분리했습니다.

```java
public enum BannedWordFilterPolicy {
  NUMBERS("[\\p{N}]"),
  WHITESPACES("[\\s]"),
  JAMO("[ㄱ-ㅎㅏ-ㅣ]"),
  FOREIGN_LANGUAGES("[\\p{L}&&[^ㄱ-ㅎ가-힣ㅏ-ㅣa-zA-Z]]"),
  SPECIAL_CHARACTERS("[^\\p{L}\\p{N}\\s]");
  // ...
}
```

정규화는 본문을 한 글자씩 보면서 drop 대상이면 건너뛰고, 알파벳은 소문자로 통일한 결과 문자열을 만듭니다.

### 진짜 중요한 디테일: 원문 인덱스 매핑

여기서 한 번 실수했었습니다. 정규화한 문자열로 매칭하고 끝내면 안 됩니다. **응답에는 유저가 실제로 친 원문 그대로** 가 들어가야 합니다.

예를 들어 본문이 `"아 시1발 진짜"` 인 상황을 생각해봅니다.

1. 정규화: `"아 시1발 진짜"` → `"아시발진짜"`
2. 매칭: trie가 `"시발"`을 찾음. 위치는 정규화 문자열 기준 `[1, 2]`
3. 응답: ??? `"시발"`을 그대로 돌려주면 클라이언트가 원문에서 `"시1발"`을 찾아 하이라이팅할 수 없습니다

매칭은 정규화된 문자열에서 했지만, **결과는 원문에서 잘라내야** 합니다. 그래서 정규화할 때 "정규화 결과의 i번째 글자가 원문 어디서 왔는지" 를 같이 기록해뒀습니다.

```java
public record NormalizedText(String normalized, int[] originalIndex) {}
```

정규화 루프는 이렇게 생겼습니다.

```java
for (int i = 0; i < len; i++) {
  if (drop[i]) continue;
  sb.append(Character.toLowerCase(text.charAt(i)));
  indices[n++] = i;       // 정규화된 n번째 글자는 원문 i번째에서 왔다
}
```

매칭이 끝나면 `originalIndex` 배열로 원문 substring을 복원합니다.

```java
int originalStart = normalized.originalIndex()[emit.getStart()];
int originalEnd   = normalized.originalIndex()[emit.getEnd()] + 1;
out.add(text.substring(originalStart, originalEnd));
```

테스트가 이걸 못 박아둡니다.

```java
assertThat(m.search("시1발")).containsExactly("시1발");
assertThat(m.search("시 발")).containsExactly("시 발");
assertThat(m.search("시~발")).containsExactly("시~발");
assertThat(m.search("아 시1발 진짜")).containsExactly("시1발");
```

DB에는 `시발` 한 줄만 들어있는데 우회표기를 다 잡아내고, 응답은 유저가 친 그 문자열을 그대로 돌려줍니다.

### 자모 keyword 라는 함정

여기까지 만들고 한숨 돌리려는데, 운영팀이 `ㅅㅂ`, `ㄴㄱㅁ` 같은 자모 약어도 등록하기 시작했습니다. 그리고 작동이 안 됐어요.

원인을 추적해보니 뻔한 실수였습니다. 기본 정규화 정책에 `JAMO` 가 들어있어서, **자모 keyword 자체도 빌드 시점에 정규화되면서 자모가 다 사라져버렸던** 거죠. `ㅅㅂ` → `""`. 빈 keyword 는 trie 에 들어가지도 않습니다.

그렇다고 `JAMO` 정책을 빼버리면 `시ㅋ발` 같은 우회표기를 흡수 못 합니다. 자모는 본문에서는 잡음이지만, keyword 입장에서는 본문이 될 수도 있습니다.

해결한 방식은 **"입력을 두 가지 정책으로 두 번 정규화해서 같은 trie에 두 번 흘려보내는"** 것이었습니다.

- 빌드 단계: keyword 중 자모로만 이루어진 게 있는지 미리 검사. 있으면 `hasJamoKeyword = true`. 자모 keyword 는 `JAMO` 를 제외한 정책으로 정규화해서 trie에 등록.
- 검색 단계:
  1. 본문을 **기본 정책** (자모 drop 포함) 으로 정규화 → 매칭 → 일반 keyword 잡음
  2. `hasJamoKeyword` 이면 본문을 **자모 보존 정책** 으로 한 번 더 정규화 → 매칭 → 자모 keyword 잡음

```java
collectHits(text, BannedWordFilterPolicy.defaults(), found);
if (hasJamoKeyword) {
  collectHits(text, JAMO_PRESERVING, found);
}
```

trie는 그대로 하나, **입력 쪽만 두 번** 흘립니다. 결과는 `LinkedHashSet` 으로 중복 제거. 덕분에 자모 keyword 도 사이사이 공백/숫자 잡음을 흡수합니다.

```java
assertThat(m.search("ㄴ ㄱ ㅁ")).containsExactly("ㄴ ㄱ ㅁ");
assertThat(m.search("ㄴ1ㄱ2ㅁ")).containsExactly("ㄴ1ㄱ2ㅁ");
```

---

## 3. 캐시 — trie를 매번 빌드할 순 없으니까

매 요청마다 DB에서 keyword 를 읽고 trie를 새로 빌드하면 Aho-Corasick 으로 얻은 이득이 다 날아갑니다. 그렇다고 서버 시작할 때 한 번만 빌드하면 운영팀이 DB에 새로 등록한 단어가 반영이 안 됩니다.

처음엔 단순하게 1분 폴링으로 갔습니다.

```java
@Component
public class BannedWordCacheRepository {
  private final AtomicReference<AhoCorasickMatcher> matcherRef = new AtomicReference<>();

  @Scheduled(fixedRate = 60000)
  public void refresh() {
    List<String> keywords = bannedWordRepository.findAllActiveKeywords();
    matcherRef.set(AhoCorasickMatcher.build(keywords));
  }
}
```

`AtomicReference` 로 통째로 교체하니까 요청 스레드랑 리프레시 스레드 사이에 락이 필요 없습니다. 검색은 그냥 `matcherRef.get().search(text)`. 운영팀이 단어를 등록하면 최악 1분 뒤에 반영됩니다.

이후에 manager-api 가 단일 writer 가 되면서 Redis L2 캐시 + Pub/Sub 무효화 + version 폴링 + DB sanity check 까지 붙어서 구조가 좀 복잡해졌는데, **young-api 쪽의 핵심 추상화 — "AhoCorasickMatcher 를 AtomicReference 로 swap" — 는 끝까지 그대로** 였습니다. 알고리즘 레이어가 캐시 동기화 레이어랑 분리돼 있었던 게 두고두고 도움이 됐습니다.

---

## 4. 돌아보면

작은 기능인데 정리하다 보니 배운 게 꽤 있었습니다.

**자료구조는 워크로드 모양 보고 고르자.** "본문은 짧고 사전은 늘어난다" 가 명확했기 때문에 Aho-Corasick 으로 가는 결정이 어렵지 않았습니다. 반대 모양이었으면 (사전 작고 본문 김) 단순 `contains` 가 더 나았을 수도 있어요.

**검증된 라이브러리는 검증된 라이브러리다.** failure link 같은 자료구조 코어의 미묘한 버그는 도메인 코드에 끌고 오지 말고, 잘 만들어진 라이브러리에 위임하는 게 맞습니다. 그러느라 알고리즘 공부가 의미 없어지진 않습니다. 라이브러리를 제대로 쓰려면 결국 그 안에서 뭐가 도는지 알아야 하니까요.

**시드가 없으면 입력 측을 정규화해서 사전 크기를 흡수.** DB row 한 줄로 변종 수십 개를 잡는 게 운영팀에 훨씬 친화적입니다.

**정규화는 원문 인덱스 매핑이랑 한 쌍이다.** 응답이 유저 원문 그대로여야 하면, 정규화는 반드시 역추적 가능해야 합니다. 이거 빼먹으면 응답이 깎여나가서 클라이언트 하이라이팅이 깨집니다.

**예외 케이스(자모 keyword)는 정책을 분리해서 다루자.** 정책을 enum 으로 빼둔 덕분에 "자모 keyword 는 자모 보존 정책으로 한 번 더 매칭" 같은 분기를 자연스럽게 추가할 수 있었습니다.

평범해 보이는 문자열 검색이 알고리즘 한 번 잘 고르고, 입력 정규화 한 단계 잘 끼우니까 깔끔하게 닫혔습니다. 코드는 작은데 일은 많이 합니다. 만족스러운 작업이었어요.
