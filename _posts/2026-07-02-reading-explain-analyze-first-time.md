---
layout: post
title: "126ms 쿼리 플랜을 처음으로 '제대로' 읽어봤다 — 병목 노드 30초 만에 짚기"
date: 2026-07-02 20:00:00 +0900
categories: [database]
tags: [postgresql, explain, performance, query-tuning]
---

## 들어가며: `Seq Scan`은 아는데, 어디가 병목인지는 모르겠더라

느린 쿼리를 만나면 습관처럼 `EXPLAIN ANALYZE`를 붙였습니다. 그런데 막상 나온 플랜을 보면 늘 같은 상태였습니다. `Seq Scan`, `Index Scan`, `Hash Join` 같은 노드 이름은 읽을 줄 아는데, **정작 이 100줄짜리 트리에서 어디가 느린 건지 짚지를 못했습니다.** cost 숫자가 커 보이는 데를 대충 찍고 "여기가 문제겠지" 하는 수준이었죠.

그래서 이번에는 실제로 느리게 돌던 쿼리 하나를 붙잡고, 플랜 읽는 법을 처음부터 정리했습니다. 대상은 리뷰 목록을 커서 페이지네이션으로 가져오는 쿼리였고, 실행 시간은 **126ms**. (표에 나오는 테이블·컬럼명은 회사 스키마라 리뷰 플랫폼 도메인으로 바꿔 익명화했습니다. 구조와 숫자는 실제 플랜 그대로입니다.)

이 글은 그 플랜을 놓고, 병목 노드를 짚기까지 제가 밟은 순서입니다.

## 1. cost는 잊는다. `actual time`만 본다

플랜 첫 줄이 이렇게 생겼습니다.

```text
Limit  (cost=21370.80..21370.88 rows=31 width=111) (actual time=125.805..125.828 rows=31 loops=1)
```

괄호가 두 개입니다. 처음엔 이 둘을 뭉뚱그려 봤는데, 성격이 완전히 다릅니다.

- **`cost=...`** — 플래너의 **추정치**입니다. 단위도 ms가 아니라 "임의의 비용 단위"예요. 플래너가 어떤 계획을 고를지 결정할 때 쓰는 내부 점수일 뿐, **실제로 얼마나 걸렸는지와는 무관**합니다.
- **`actual time=125.805..125.828`** — `ANALYZE`가 실제로 재본 **실측 시간(ms)**. 진짜 느린 데를 찾을 땐 **오직 이 값만** 봅니다.

`actual time`의 두 숫자도 뜻이 다릅니다.

| | 의미 |
|---|---|
| 첫 번째 (`125.805`) | 이 노드가 **첫 행**을 내보내기까지 걸린 시간 |
| 두 번째 (`125.828`) | 이 노드가 **마지막 행**까지 다 끝내는 데 걸린 시간 |

병목을 찾을 땐 두 번째 값(총 소요)을 봅니다. 그리고 이 값에는 두 가지 함정이 있어서, 이걸 모르면 숫자를 잘못 읽습니다.

1. **자식 시간 포함(inclusive).** 어떤 노드의 `actual time`은 그 아래 자식 노드들의 시간을 **전부 합친 값**입니다. 부모가 크다고 부모가 범인이 아니라, 대개 자식 중 하나가 진짜 범인입니다.
2. **1회당 시간(per-loop).** 뒤에 `loops=N`이 붙으면, `actual time`은 **한 번 돌 때의 시간**입니다. 실제 총합은 `actual time × loops`예요. 여기에 안 속으려면 `loops`를 꼭 같이 봐야 합니다.

이 두 개만 머리에 넣어도 플랜이 갑자기 읽히기 시작합니다.

## 2. 위에서 아래로, 큰 자식만 따라 내려간다

병목 노드는 이렇게 찾았습니다. 어렵지 않습니다.

1. **맨 위 노드**의 총 시간을 본다 → `Limit`, 125.8ms. 이게 전체.
2. 그 **자식들 중 `actual time`(×loops)이 제일 큰 놈**으로 내려간다.
3. 시간이 확 줄어드는 지점 바로 위, 즉 **자기 자식들로는 설명이 안 되는 시간을 혼자 먹고 있는 노드**가 범인이다.

이 쿼리에서 그렇게 따라 내려가니 이 노드에서 멈췄습니다.

```text
->  Seq Scan on review r  (cost=0.00..9662.85 rows=2399 width=61)
                          (actual time=54.373..108.828 rows=2800 loops=1)
      Filter: ((NOT is_deleted) AND (NOT is_temporary) AND ((review_status)::text = 'post'::text)
               AND ( ... 커서 비교 ... ) AND EXISTS(SubPlan 4))
      Rows Removed by Filter: 11756
```

`loops=1`이라 곱할 것도 없이 **108.828ms**. 전체 126ms 중 이 한 노드가 대부분을 먹고 있었습니다. 범인은 `review` 테이블의 **`Seq Scan`(순차 스캔)**.

> 여기서 작은 함정 하나. 이 `Seq Scan`의 시작 시간이 `54.373`으로 꽤 늦죠? 스캔 자체가 늦게 시작한 게 아니라, **이 노드가 첫 행을 내기 전에 다른 사전 계산(InitPlan)이 끝나길 기다렸기 때문**입니다. 뒤에서 다시 나옵니다.

## 3. 눈대중이 맞는지 도구로 검증했다

트리를 눈으로 따라가는 게 처음엔 헷갈려서, [explain.depesz.com](https://explain.depesz.com/)에 플랜을 붙여넣어 교차 검증했습니다. 이 도구는 각 노드의 **self time**(자식 시간을 뺀, 그 노드 혼자 쓴 시간)을 색으로 하이라이트해줍니다.

`actual time`은 자식 포함(inclusive)이라 부모가 부풀려 보이기 쉬운데, self time은 "이 노드가 **혼자** 얼마나 썼나"를 뽑아주니까 범인이 한눈에 벌겋게 뜹니다. 제가 눈으로 찍은 `Seq Scan on review`가 실제로 self time 1등이었고, 그제서야 "아 내가 제대로 읽었구나" 하고 확신이 섰습니다.

**손으로 읽는 법을 익히되, 결과는 도구로 확인한다.** 이 두 개를 같이 하니 훨씬 빨리 늘었습니다.

## 4. `Filter` vs `Index Cond` — 읽고 버린 행이 곧 낭비다

범인은 찾았으니, "왜 느린가"의 첫 단서를 그 노드 안에서 봤습니다. 스캔 노드에는 조건이 두 종류로 붙습니다.

- **`Index Cond:`** — 인덱스로 **필요한 행만 찾아간** 조건. 싸다.
- **`Filter:`** — 일단 행을 **읽어 들인 뒤 조건으로 버리는** 것. 비싸다.

그리고 결정적인 한 줄이 `Rows Removed by Filter`입니다.

```text
Filter: ( ... )
Rows Removed by Filter: 11756
```

이 노드는 결과로 `rows=2800`을 내보냈는데, 필터로 버린 게 **11,756행**입니다. 즉 실제로 디스크에서 읽어 들인 건 `2800 + 11756 = 14,556행`. 그중 **약 81%를 읽자마자 버린 겁니다.** 게다가 이 노드엔 `Index Cond`가 **하나도 없고 전부 `Filter`**였습니다. 인덱스를 전혀 못 타고, 테이블을 통째로 읽어 대부분을 버리는 중이었던 거죠.

> `Rows Removed by Filter`가 크다 = "읽었는데 안 쓴" 낭비가 크다. 이게 크면 십중팔구 인덱스로 좁힐 여지가 있다는 신호입니다.

## 5. 진짜 수확: 같은 테이블을 두 번 통째로 읽고 있었다

여기서 플랜을 더 위로 훑다가 진짜 문제를 봤습니다. `review`를 `Seq Scan`하는 노드가 **하나가 아니라 둘**이었습니다. 커서 위치를 계산하는 CTE 안에 하나 더 있었어요.

```text
CTE cur
  ...
  ->  Seq Scan on review r_1  (actual time=0.207..39.462 rows=3800 loops=1)
        Filter: ((NOT is_deleted) AND (NOT is_temporary)
                 AND ((review_status)::text = 'post'::text) AND EXISTS(SubPlan 2))
        Rows Removed by Filter: 10756
```

이 CTE(`cur`)가 먼저 실행되면서 `review`를 한 번 통째로 스캔하고(≈54ms), 그 결과를 기다렸다가 메인 쿼리가 `review`를 **또 한 번** 통째로 스캔합니다(≈54ms). 2번 섹션에서 메인 `Seq Scan`의 시작 시간이 `54.373`으로 늦었던 이유가 바로 이것 — **CTE가 끝나길 기다린 시간**이었습니다.

126ms의 정체가 이제 선명해졌습니다. **같은 테이블 풀스캔 두 번이 시간의 거의 전부.** cost 큰 데를 찍던 예전 방식이었으면 절대 못 봤을 구조입니다.

## 마치며

병목을 짚는 데 필요한 건 결국 규칙 몇 개였습니다.

- **cost는 무시하고 `actual time`(두 번째 값)만 본다. `loops`가 있으면 곱한다.**
- **위에서 아래로, 큰 자식만 따라간다.** 부모 시간은 자식 포함이니, 시간을 혼자 먹는 노드가 범인이다.
- **`Rows Removed by Filter`는 낭비 계량기다.** 크면 인덱스로 좁힐 여지를 의심한다.
- **눈으로 읽고 depesz로 검증한다.**

무엇보다, "느낌으로 찍기"에서 "**근거로 짚기**"로 바뀐 게 제일 큰 수확이었습니다. 이제 임의의 플랜을 받아도 30초 안에 "가장 오래 걸린 노드"를 손가락으로 가리킬 수 있습니다.

다음 글에서는 여기서 남은 진짜 질문 — **"병목은 알겠는데, 이 `Seq Scan`은 대체 왜 인덱스를 안 타는가"** — 를 파고듭니다. 답은 `SARGability`, 그리고 인덱스의 동작 원리에 있었습니다.

---

<details>
<summary>참고: 익명화한 전체 플랜 (펼치기)</summary>

```text
Limit  (cost=21370.80..21370.88 rows=31 width=111) (actual time=125.805..125.828 rows=31 loops=1)
  Buffers: shared hit=6391
  CTE match_ids
    ->  Unique  (cost=27.98..28.01 rows=6 width=8) (actual time=0.156..0.160 rows=4 loops=1)
          ->  Sort  (cost=27.98..27.99 rows=6 width=8) (actual time=0.155..0.157 rows=4 loops=1)
                Sort Key: rc.id
                ->  Nested Loop  (cost=0.14..27.90 rows=6 width=8) (actual time=0.063..0.152 rows=4 loops=1)
                      Join Filter: ((fc.path = rc.path) OR (rc.path <@ fc.path) OR (fc.path <@ rc.path))
                      Rows Removed by Join Filter: 239
                      ->  Index Scan using category_pkey on category fc  (actual time=0.014..0.015 rows=1 loops=1)
                            Index Cond: (id = ANY ('{46}'::bigint[]))
                            Filter: is_active
                      ->  Seq Scan on category rc  (actual time=0.005..0.063 rows=243 loops=1)
                            Filter: is_active
  CTE cur
    ->  Subquery Scan on t  (cost=10218.06..10510.36 rows=34 width=16) (actual time=54.275..54.284 rows=1 loops=1)
          Filter: (t.rn = 1000)
          Rows Removed by Filter: 999
          ->  WindowAgg  (actual time=53.860..54.208 rows=1000 loops=1)
                Run Condition: (row_number() OVER (?) <= 1000)
                ->  Sort  (actual time=53.852..53.915 rows=1001 loops=1)
                      Sort Key: (md5(((r_1.review_id)::text || '<salt>'::text))), r_1.review_id
                      ->  Hash Join  (cost=374.34..9779.65 rows=6878 width=48) (actual time=3.436..47.840 rows=3800 loops=1)
                            Hash Cond: (g_1.merchant_id = m1.merchant_id)
                            ->  Hash Join  (actual time=3.299..43.936 rows=3800 loops=1)
                                  Hash Cond: (r_1.review_group_id = g_1.review_group_id)
                                  ->  Seq Scan on review r_1  (actual time=0.207..39.462 rows=3800 loops=1)
                                        Filter: ((NOT is_deleted) AND (NOT is_temporary) AND ((review_status)::text = 'post'::text) AND EXISTS(SubPlan 2))
                                        Rows Removed by Filter: 10756
                                        SubPlan 2
                                          ->  Hash Semi Join  (actual time=0.002..0.002 rows=0 loops=14356)
                                                Hash Cond: (((sc.value)::text)::bigint = match_ids.id)
                                                ->  Function Scan on jsonb_array_elements sc  (actual time=0.001..0.001 rows=1 loops=14356)
                                                ->  Hash  (actual time=0.161..0.162 rows=4 loops=1)
                                                      ->  CTE Scan on match_ids  (actual time=0.157..0.159 rows=4 loops=1)
                                  ->  Hash  (actual time=3.084..3.085 rows=9305 loops=1)
                                        ->  Seq Scan on review_group g_1  (actual time=0.008..1.469 rows=9305 loops=1)
                                              Filter: (NOT is_deleted)
                                              Rows Removed by Filter: 94
                            ->  Hash  (actual time=0.119..0.120 rows=124 loops=1)
                                  ->  Seq Scan on merchant m1  (actual time=0.008..0.095 rows=124 loops=1)
                                        Filter: ((account_type IS NULL) OR ((account_type)::text <> 'INTERNAL'::text))
                                        Rows Removed by Filter: 3
  InitPlan 5
    ->  CTE Scan on cur  (actual time=54.278..54.279 rows=1 loops=1)
  InitPlan 6
    ->  CTE Scan on cur cur_1  (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 7
    ->  CTE Scan on cur cur_2  (actual time=0.001..0.001 rows=1 loops=1)
  ->  Sort  (cost=10830.40..10836.18 rows=2315 width=111) (actual time=125.803..125.811 rows=31 loops=1)
        Sort Key: (md5(((r.review_id)::text || '<salt>'::text))), r.review_id
        Sort Method: top-N heapsort  Memory: 32kB
        ->  Hash Left Join  (actual time=61.957..124.301 rows=2800 loops=1)
              Hash Cond: (r.review_id = cv.content_id)
              ->  Hash Join  (actual time=61.491..119.801 rows=2800 loops=1)
                    Hash Cond: (g.user_id = u.user_id)
                    ->  Nested Loop  (actual time=57.786..115.016 rows=2800 loops=1)
                          ->  Hash Join  (actual time=57.759..113.377 rows=2800 loops=1)
                                Hash Cond: (r.review_group_id = g.review_group_id)
                                ->  Seq Scan on review r  (cost=0.00..9662.85 rows=2399 width=61) (actual time=54.373..108.828 rows=2800 loops=1)
                                      Filter: ((NOT is_deleted) AND (NOT is_temporary) AND ((review_status)::text = 'post'::text) AND (((InitPlan 5).col1 IS NULL) OR (ROW(md5(((review_id)::text || '<salt>'::text)), review_id) > ROW(md5((((InitPlan 6).col1)::text || '<salt>'::text)), (InitPlan 7).col1))) AND EXISTS(SubPlan 4))
                                      Rows Removed by Filter: 11756
                                      SubPlan 4
                                        ->  Hash Semi Join  (actual time=0.002..0.002 rows=0 loops=10676)
                                              Hash Cond: (((sc_1.value)::text)::bigint = match_ids_1.id)
                                              ->  Function Scan on jsonb_array_elements sc_1  (actual time=0.001..0.001 rows=1 loops=10676)
                                              ->  Hash  (actual time=0.004..0.004 rows=4 loops=1)
                                                    ->  CTE Scan on match_ids match_ids_1  (actual time=0.001..0.001 rows=4 loops=1)
                                ->  Hash  (actual time=3.375..3.375 rows=9305 loops=1)
                                      ->  Seq Scan on review_group g  (actual time=0.020..1.545 rows=9305 loops=1)
                                            Filter: (NOT is_deleted)
                                            Rows Removed by Filter: 94
                          ->  Memoize  (actual time=0.000..0.000 rows=1 loops=2800)
                                Cache Key: g.merchant_id
                                Hits: 2770  Misses: 30  Evictions: 0  Overflows: 0  Memory Usage: 4kB
                                ->  Index Scan using merchant_pkey on merchant m  (actual time=0.005..0.005 rows=1 loops=30)
                                      Index Cond: (merchant_id = g.merchant_id)
                                      Filter: ((account_type IS NULL) OR ((account_type)::text <> 'INTERNAL'::text))
                    ->  Hash  (actual time=3.654..3.654 rows=9860 loops=1)
                          ->  Seq Scan on app_user u  (actual time=0.009..1.703 rows=9860 loops=1)
              ->  Hash  (actual time=0.442..0.443 rows=781 loops=1)
                    ->  Seq Scan on content_engagement cv  (actual time=0.014..0.292 rows=781 loops=1)
                          Filter: ((content_type)::text = 'review'::text)
                          Rows Removed by Filter: 588
Planning Time: 1.656 ms
Execution Time: 126.023 ms
```

</details>
