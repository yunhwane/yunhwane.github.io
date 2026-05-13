---
layout: post
title: "커넥션 풀을 직접 TDD로 짜보았다"
date: 2026-05-13 21:00:00 +0900
categories: [backend]
tags: [jdbc, connection-pool, tdd, java, concurrency]
---

## 도입: HikariCP를 쓴다는 게 무엇을 한다는 뜻일까

Spring Boot 프로젝트를 만들면 `spring-boot-starter-jdbc` 또는 `-jpa`가 자동으로 HikariCP를 데이터소스로 잡아줍니다. `application.yml`에 `spring.datasource.url`만 적으면 됩니다. 그게 끝입니다.

문제는, **그 한 줄이 가려주는 것이 너무 많다**는 점입니다.

- 커넥션을 미리 몇 개 만들어 두고 있는지
- 풀이 비었을 때 누가 블록되는지
- 죽은 커넥션은 어떻게 식별되는지
- 앱이 종료될 때 잡힌 커넥션은 어떻게 정리되는지

지난주에 후배에게 "DB 커넥션 풀이 뭐야?"라는 질문을 받았는데, 평소에 "비싼 자원을 미리 만들어 두고 재사용하는 것"이라는 한 줄 설명만 외고 다녔다는 사실을 깨달았습니다. 자료구조도, 동시성 처리도, 검증도 다 묻혀 있었습니다.

그래서 **JDBC 위에 가장 단순한 형태의 커넥션 풀을 TDD로 직접 짜보기로** 했습니다. Claude를 페어 파트너로 두고, 테스트는 Claude가 먼저 짜주고 저는 통과시키는 구현만 하는 식으로 진행했습니다. 이 글은 그 6개 단계와, 거기서 부딪힌 실수, 그리고 풀이라는 자료구조의 본질에 대한 정리입니다.

## 그래서 커넥션 풀이 뭐냐

JDBC `Connection`은 단순한 객체처럼 보이지만, 만들 때마다 다음이 벌어집니다.

1. DB 호스트로 TCP 핸드셰이크
2. 인증 (사용자/비번/SSL)
3. 세션 초기화 (timezone, charset, autocommit 등 협상)

빠른 DB도 한 자리 ms, 멀면 수십 ms씩 걸립니다. 매 요청마다 이걸 새로 한다면, 트래픽이 조금만 늘어도 DB가 핸드셰이크와 정리에 시간을 다 씁니다.

**커넥션 풀의 한 줄 정의**:

> 미리 N개의 커넥션을 만들어 두고, 사용자에게 빌려줬다가 돌려받는 것을 반복한다.

그 한 줄 안에 다음 결정들이 숨어 있습니다.

- **N개를 어떻게 보관할 것인가?** → 자료구조 선택
- **N개가 모두 빌려나간 상태에서 N+1번째 요청은 어떻게 할 것인가?** → 블록할지, 실패할지
- **얼마나 기다리게 할 것인가?** → 타임아웃 정책
- **빌려나간 사이에 커넥션이 죽으면 어떻게 알 것인가?** → 검증
- **앱이 종료될 때는?** → graceful shutdown

이 다섯 가지를 하나씩 테스트로 강제하면서 작은 풀을 키워가는 것이 이 글의 골격입니다.

## 환경

- Java 21 (Temurin)
- Gradle 9.5 (Kotlin DSL)
- JUnit 5 + AssertJ
- H2 in-memory DB (`jdbc:h2:mem:step;DB_CLOSE_DELAY=-1`)

H2를 고른 이유는 단순합니다. 외부 DB 없이 진짜 JDBC 커넥션을 만들 수 있고, `Connection#isValid` 같은 표준 API가 그대로 동작합니다.

전체 디렉터리는 `playground/connection-pool/`에 있습니다.

## Step 1 — borrow하면 쓸 수 있는 커넥션이 나와야 한다

**테스트**:

```java
@Test
void borrow_returnsUsableConnection() throws Exception {
    SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 2);

    Connection conn = pool.borrow();

    assertThat(conn).isNotNull();
    assertThat(conn.isValid(1)).isTrue();
}
```

첫 테스트의 책임은 작아야 합니다. 풀이 풀답게 동작하길 강요하지 않습니다. 그냥 **`borrow()`라는 메서드가 있고 그 결과가 살아있는 `Connection`이어야 한다**는 것만 정합니다.

그래서 첫 구현도 이렇게 한 줄로 끝나도 됩니다.

```java
public Connection borrow() throws SQLException {
    return DriverManager.getConnection(jdbcUrl, username, password);
}
```

풀링은 아직 없습니다. 매번 새 커넥션이 만들어집니다. 그래도 테스트는 통과합니다. **첫 그린의 목표는 풀의 동작 검증이 아니라 API 모양과 테스트 인프라의 동작 검증**이기 때문입니다.

> 학습 포인트: TDD에서 "이 코드는 곧 다음 테스트에서 깨질 텐데 왜 쓰지?"라는 의문이 자주 듭니다. 그게 정상입니다. 코드가 정말 필요한지를 다음 실패 테스트가 증명하기 전까지는 미리 짜지 않는 것이 YAGNI 원칙입니다.

여기서 한 가지 작은 실수를 했습니다. 생성자 파라미터 `int size`를 받았는데 필드 이름을 `int timeout`으로 적었습니다. 지금은 안 쓰는 필드라 동작은 멀쩡했지만, Step 3에서 진짜 타임아웃이 등장할 때 충돌할 뻔했습니다. **안 쓰는 이름도 의미가 어긋나면 즉시 고치는 게 낫다**는 걸 다시 확인했습니다.

## Step 2 — 풀은 size 이상으로 커넥션을 발급하지 않는다

**테스트**:

```java
@Test
void borrow_blocksWhenPoolExhausted() throws Exception {
    SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 2);
    pool.borrow();
    pool.borrow();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
        Future<Connection> third = executor.submit(
            (Callable<Connection>) pool::borrow);

        assertThatThrownBy(() -> third.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        third.cancel(true);
    } finally {
        executor.shutdownNow();
    }
}
```

이 테스트는 두 가지를 동시에 강제합니다.

1. **size만큼 발급한 뒤 더는 발급하지 않는다**
2. **남는 커넥션이 없으면 블록한다**

블록을 검증하려면 어쩔 수 없이 동시성이 들어옵니다. 별도 스레드에서 세 번째 `borrow()`를 실행하고, 메인 스레드는 `Future.get(timeout)`으로 "300ms 안에 끝나면 안 된다"를 확인합니다. `TimeoutException`이 나야 통과합니다.

이 단계에서 풀이 풀이 되는 자료구조를 도입합니다. **`java.util.concurrent.ArrayBlockingQueue`**입니다.

```java
public SimpleConnectionPool(String url, String user, String pw, int size) throws SQLException {
    ...
    this.queue = new ArrayBlockingQueue<>(size);
    for (int i = 0; i < size; i++) {
        queue.offer(DriverManager.getConnection(url, user, pw));
    }
}

public Connection borrow() throws InterruptedException {
    return queue.take();
}
```

- **생성자에서 eager 초기화** — size개를 미리 만들어 큐에 채워둡니다. 첫 트래픽이 와도 핸드셰이크 비용을 더 내지 않습니다. DB가 죽어 있다면 앱 시작 시점에 바로 발견됩니다(lazy 초기화는 첫 borrow 때 발견).
- **`take()`** — 큐가 비면 자동으로 블록합니다. 직접 `wait`/`notify`를 작성할 필요 없습니다. 동시성 자료구조에 책임을 위임하는 것이 핵심입니다.

여기서 두 개의 실수를 했습니다.

**실수 1 — 필드 초기화 순서를 잊었다**:

```java
private int size;
ArrayBlockingQueue<Connection> queue = new ArrayBlockingQueue<>(size);  // size는 0
```

필드 초기화는 생성자보다 먼저 실행됩니다. `size`는 아직 0이고 `ArrayBlockingQueue(0)`은 `IllegalArgumentException`을 던집니다. 큐 생성을 **생성자 본문 안**으로 옮겨야 합니다.

**실수 2 — eager 초기화 루프를 `borrow()` 안에 넣었다**:

```java
public Connection borrow() throws InterruptedException, SQLException {
    for (int i = 0; i < size; i++) {                              // ←
        queue.offer(DriverManager.getConnection(jdbcUrl, username, password));
    }
    return queue.take();
}
```

매 호출마다 size개를 새로 만들어 큐에 넣으려고 합니다. `ArrayBlockingQueue`는 capacity가 고정이라 차면 `offer`가 `false`를 반환하고 조용히 드롭됩니다. **만들어진 커넥션 객체는 큐에도 못 들어가고 어디서도 닫히지 않은 채 유실**됩니다. 전형적인 리소스 누수입니다.

> 학습 포인트: 풀의 정체성은 "**미리 정해진 N개**의 자원을 **재사용**하는 것"입니다. 생성 시점에 N이 확정되고 그 이후로는 절대 늘어나지 않습니다. `borrow`는 소비자, 생산자는 release(다음 step)뿐입니다.

## Step 3 — borrow는 영원히 기다리지 않는다

**테스트**:

```java
@Test
void borrowWithTimeout_throwsWhenPoolExhausted() throws Exception {
    SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 2);
    pool.borrow();
    pool.borrow();

    long start = System.nanoTime();
    assertThatThrownBy(() -> pool.borrow(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(PoolTimeoutException.class);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isBetween(150L, 600L);
}
```

Step 2의 `borrow()`는 풀이 비면 영원히 블록합니다. 운영 환경에서는 이게 가장 위험한 동작 중 하나입니다. DB가 한 번 느려지면 모든 톰캣 스레드가 풀 앞에서 멈춰 응답이 통째로 잠깁니다. 그래서 풀에는 **borrow 자체에 시간 제한**이 있어야 합니다.

구현은 `BlockingQueue.poll(timeout, unit)`로 간단합니다.

```java
public Connection borrow(long timeout, TimeUnit unit) throws InterruptedException {
    Connection conn = queue.poll(timeout, unit);
    if (conn == null) {
        throw new PoolTimeoutException(
            "borrow timed out after " + timeout + " " + unit);
    }
    return conn;
}
```

`poll(t, u)`는 지정 시간 안에 못 받으면 **null을 반환**합니다. null을 호출자에게 그대로 넘기면 호출부에서 NPE가 터질 위험이 있고, "성공 아니면 실패"라는 API 계약을 흐리게 만듭니다. **`null` 반환 대신 명시적 예외**로 바꾸는 것이 안전합니다.

`PoolTimeoutException`은 `RuntimeException`을 상속합니다. 풀 고갈은 비즈니스 분기가 아니라 운영상 예외 상황이라 호출부가 매번 catch 강제될 이유가 없습니다. HikariCP의 `SQLTransientConnectionException`도 같은 정신입니다.

여기서 컴파일러가 갑자기 화를 냈습니다.

```
error: reference to submit is ambiguous
  Future<Connection> third = executor.submit(pool::borrow);
  both method submit(Callable<T>) and method submit(Runnable) match
```

Step 2까지는 같은 코드가 멀쩡히 컴파일됐는데, Step 3에서 `borrow(long, TimeUnit)`를 오버로드한 순간 깨졌습니다.

원인은 메서드 레퍼런스의 오버로드 해석입니다. `pool::borrow`가 가리킬 수 있는 메서드가 둘이 됐고, `executor.submit` 자체도 `Callable`/`Runnable` 두 가지 오버로드를 가집니다. 컴파일러는 "어느 submit + 어느 borrow"의 조합이 가장 구체적인지 결정하지 못합니다. 해결은 명시적 캐스트입니다.

```java
Future<Connection> third = executor.submit(
    (Callable<Connection>) pool::borrow);
```

> 학습 포인트: 메서드 레퍼런스는 깔끔하지만 오버로드가 늘어나는 순간 모호성이 생깁니다. 같은 시그니처가 함수형 인터페이스 두 개에 매핑될 가능성이 보이면 캐스트나 람다(`() -> pool.borrow()`)로 의도를 명시하는 것이 안전합니다.

## Step 4 — 돌려준 커넥션은 다시 발급될 수 있어야 한다

**테스트**:

```java
@Test
void release_returnsConnectionToPool_allowingReborrowWithoutTimeout() throws Exception {
    SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 1);
    Connection first = pool.borrow();

    pool.release(first);

    Connection second = pool.borrow(100, TimeUnit.MILLISECONDS);
    assertThat(second).isSameAs(first);
}
```

여기서 처음으로 **풀이 풀답게 돈다**는 의미가 검증됩니다. size=1인 풀에서 한 번 빌려가고 돌려준 뒤, 100ms 안에 다시 빌릴 수 있어야 합니다. 그리고 빌린 객체는 **동일한 물리 커넥션(`isSameAs`)**이어야 합니다.

구현은 한 줄입니다.

```java
public void release(Connection conn) {
    if (conn != null) {
        queue.offer(conn);
    }
}
```

`offer` vs `put`에서 잠깐 고민했습니다.

- `put(e)` — 큐가 차면 호출 스레드가 블록
- `offer(e)` — 큐가 차면 즉시 `false` 반환

이 풀에서는 발급한 수보다 더 많이 release될 일이 없으므로 둘 다 동작합니다. 다만 **`release`가 블록하는 건 위험**합니다. 핸들러가 finally에서 release하다 멈추면 그 스레드 전체가 멈추기 때문입니다. 그래서 `offer`를 선택했습니다.

다만 이 단순한 구현에는 함정이 많이 남아 있습니다.

- **외부에서 만든 커넥션을 release하면?** 풀 capacity가 깨집니다.
- **이미 close된 커넥션을 release하면?** 죽은 객체가 큐에 들어가서 다음 사용자에게 발급됩니다. 이건 Step 5가 해결합니다.
- **release를 잊으면?** 영구 leak입니다. 진짜 풀은 leak detection threshold를 둬서 일정 시간 안에 안 돌아오면 경고를 띄웁니다.

> 학습 포인트: TDD는 모든 함정을 한 번에 잡지 않습니다. 다음 테스트가 다음 함정을 강제로 드러나게 합니다. 지금 이 release는 "정상 케이스만 처리"입니다. 비정상 케이스는 별도 step에서.

## Step 5 — 죽은 커넥션은 빌려주기 전에 갈아끼운다

**테스트**:

```java
@Test
void borrow_replacesInvalidConnectionWithFreshOne() throws Exception {
    SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 1);
    Connection dead = pool.borrow();
    dead.close();
    pool.release(dead);

    Connection fresh = pool.borrow();

    assertThat(fresh.isValid(1)).isTrue();
    assertThat(fresh).isNotSameAs(dead);
}
```

JDBC 커넥션은 풀이 모르는 사이에 죽을 수 있습니다. DB 측 idle timeout, 네트워크 단절, 사용자 코드가 실수로 `close()` 호출, 방화벽 timeout 등 이유는 다양합니다. 풀에 그런 커넥션이 남아 있다가 빌려나가면 호출자가 첫 SQL을 날리는 순간 깨집니다.

**검증 시점은 borrow**가 보수적인 선택입니다. release 시점이 성능상 더 좋지만(검증 비용을 평균적으로 덜 부담), 풀에 들어가 있는 동안 죽는 경우를 못 잡습니다. HikariCP도 borrow 시점 검증이 기본입니다.

구현은 `Connection.isValid(timeoutSec)`를 씁니다. JDBC 4.0 표준 API로, 드라이버가 가벼운 쿼리(보통 `SELECT 1` 또는 ping)로 살아있는지 확인합니다.

```java
private boolean isAlive(Connection conn) {
    try {
        return conn.isValid(1);
    } catch (SQLException e) {
        return false;
    }
}

private void closeQuietly(Connection conn) {
    if (conn != null) {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}

private Connection validateOrReplace(Connection conn) throws SQLException {
    if (isAlive(conn)) return conn;
    closeQuietly(conn);
    return DriverManager.getConnection(jdbcUrl, username, password);
}

public Connection borrow() throws InterruptedException, SQLException {
    Connection conn = queue.take();
    return validateOrReplace(conn);
}
```

`borrow(long, TimeUnit)` 쪽에도 같은 `validateOrReplace` 호출을 추가합니다. **검증 로직을 헬퍼로 추출**하면 두 borrow가 같은 의미를 공유한다는 것이 코드 모양으로 드러납니다.

여기서도 실수가 있었습니다. 헬퍼 메서드(`isAlive`, `closeQuietly`, `validateOrReplace`)는 다 만들었는데 **`borrow()`에서 호출하지 않고** 그대로 `queue.take()` 결과를 반환했습니다. 헬퍼가 호출부에 연결되지 않으면 코드는 그냥 죽은 살입니다. 테스트가 실패할 때 "왜 안 되지?"를 먼저 보지 말고 **"이 코드 경로가 실제로 실행되는가?"**를 먼저 보는 습관이 필요합니다.

> 학습 포인트: `isValid`가 `SQLException`을 던질 수 있고, 그 자체를 "죽었다"로 해석합니다. 살아있는 커넥션이라면 ping 쿼리가 정상적으로 돌아오지, 예외를 던지지 않습니다.

## Step 6 — close된 풀에서는 더 이상 borrow할 수 없다

**테스트**:

```java
@Test
void close_closesAllConnectionsAndPreventsFurtherBorrow() throws Exception {
    SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 2);
    Connection borrowed = pool.borrow();

    pool.close();

    assertThat(borrowed.isClosed()).isTrue();
    assertThatThrownBy(pool::borrow).isInstanceOf(IllegalStateException.class);
}
```

이 테스트가 두 가지를 동시에 강제합니다.

1. **이미 borrow된 커넥션도 풀 종료 시 닫혀야 한다** — 큐에 있는 것만 닫으면 부족합니다. 풀이 발급한 모든 커넥션을 추적해야 합니다.
2. **close 이후 borrow는 빠르게 실패해야 한다** — 사용자 실수를 침묵으로 받아주면 디버깅이 지옥이 됩니다.

추적을 위해 별도 리스트를 도입합니다.

```java
private final List<Connection> allConnections = new ArrayList<>();
private volatile boolean closed = false;
```

`volatile`은 다른 스레드가 풀을 닫는 동안에도 closed 플래그의 변화가 즉시 보이게 만듭니다(Java Memory Model의 happens-before 보장).

생성자에서 만들 때마다 둘 다 등록합니다.

```java
for (int i = 0; i < size; i++) {
    Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
    allConnections.add(connection);
    queue.offer(connection);
}
```

`validateOrReplace`에서 교체할 때도 추적 정보를 갱신합니다.

```java
private Connection validateOrReplace(Connection conn) throws SQLException {
    if (isAlive(conn)) return conn;
    allConnections.remove(conn);
    closeQuietly(conn);
    Connection freshConn = DriverManager.getConnection(jdbcUrl, username, password);
    allConnections.add(freshConn);
    return freshConn;
}
```

`borrow` 두 메서드 모두 진입 시점에 closed를 검사합니다.

```java
if (closed) {
    throw new IllegalStateException("pool is closed");
}
```

`close()`는 플래그를 먼저 세팅해 새 borrow를 차단한 다음, 추적 리스트의 모든 커넥션을 닫습니다.

```java
public void close() {
    closed = true;
    for (Connection c : allConnections) {
        closeQuietly(c);
    }
    allConnections.clear();
    queue.clear();
}
```

여기서 마지막 실수를 했습니다. **`DriverManager.getConnection`을 한 줄 안에서 두 번 호출**한 것입니다.

```java
allConnections.add(DriverManager.getConnection(url, user, pw));  // 커넥션 A
queue.offer(DriverManager.getConnection(url, user, pw));         // 커넥션 B (다른 객체)
```

리스트에는 A, 큐에는 B가 들어갑니다. `borrow()`는 B를 받습니다. `close()`는 A만 닫고, **B는 영영 닫히지 않습니다**. 테스트는 빌린 B가 `isClosed()` true이길 기대했는데 그러지 못합니다.

비슷한 실수가 `validateOrReplace`에도 있었습니다.

```java
Connection freshConn = DriverManager.getConnection(...);  // X (리스트 등록)
allConnections.add(freshConn);
return DriverManager.getConnection(...);                  // Y (반환, 추적 안 됨)
```

호출자에게는 Y가 가고 리스트에는 X가 남습니다. Y는 추적 밖이라 close 때 안 닫힙니다.

> 학습 포인트: `DriverManager.getConnection`은 매번 호출이 비싼 호출입니다(TCP+auth+세션 협상). 변수에 한 번 잡아두고 그 변수만 사용하는 습관은 누수와 비용 둘 다 막아줍니다. 같은 결과를 두 번 만들어내는 코드 패턴이 보이면 즉시 의심해야 합니다.

## 완성된 풀

```java
public class SimpleConnectionPool {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ArrayBlockingQueue<Connection> queue;
    private final List<Connection> allConnections = new ArrayList<>();
    private volatile boolean closed = false;

    public SimpleConnectionPool(String jdbcUrl, String username, String password, int size)
            throws SQLException {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.queue = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            Connection c = DriverManager.getConnection(jdbcUrl, username, password);
            allConnections.add(c);
            queue.offer(c);
        }
    }

    public Connection borrow() throws InterruptedException, SQLException {
        if (closed) throw new IllegalStateException("pool is closed");
        return validateOrReplace(queue.take());
    }

    public Connection borrow(long timeout, TimeUnit unit)
            throws InterruptedException, SQLException {
        if (closed) throw new IllegalStateException("pool is closed");
        Connection conn = queue.poll(timeout, unit);
        if (conn == null) {
            throw new PoolTimeoutException(
                "borrow timed out after " + timeout + " " + unit);
        }
        return validateOrReplace(conn);
    }

    public void release(Connection conn) {
        if (conn != null) queue.offer(conn);
    }

    public void close() {
        closed = true;
        for (Connection c : allConnections) closeQuietly(c);
        allConnections.clear();
        queue.clear();
    }

    private Connection validateOrReplace(Connection conn) throws SQLException {
        if (isAlive(conn)) return conn;
        allConnections.remove(conn);
        closeQuietly(conn);
        Connection fresh = DriverManager.getConnection(jdbcUrl, username, password);
        allConnections.add(fresh);
        return fresh;
    }

    private boolean isAlive(Connection conn) {
        try { return conn.isValid(1); }
        catch (SQLException e) { return false; }
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }
}
```

테스트는 6개 모두 통과합니다.

```
SimpleConnectionPoolTest > borrow_returnsUsableConnection() PASSED
SimpleConnectionPoolTest > borrow_blocksWhenPoolExhausted() PASSED
SimpleConnectionPoolTest > borrowWithTimeout_throwsWhenPoolExhausted() PASSED
SimpleConnectionPoolTest > release_returnsConnectionToPool_allowingReborrowWithoutTimeout() PASSED
SimpleConnectionPoolTest > borrow_replacesInvalidConnectionWithFreshOne() PASSED
SimpleConnectionPoolTest > close_closesAllConnectionsAndPreventsFurtherBorrow() PASSED
```

## 일부러 다루지 않은 것들

이 풀은 "공부용"이라 의도적으로 단순하게 두었습니다. 실서비스에서 쓰려면 다음이 필요합니다.

- **`allConnections`의 thread-safety** — 지금은 `ArrayList`라 동시 접근 시 깨집니다. `synchronized` 블록이나 `CopyOnWriteArrayList`로 보강해야 합니다.
- **borrow 도중 close 호출 시 대기자 깨우기** — `take()`로 무한 대기 중인 스레드는 close가 호출돼도 안 깨어납니다. sentinel 객체를 큐에 푸시하거나 스레드를 인터럽트해야 합니다.
- **Connection wrapper / 동적 프록시** — 사용자가 `conn.close()`를 호출하면 진짜로 닫히는 대신 풀로 release되도록 가로채야 합니다. 그래야 `try-with-resources` 패턴이 깨끗하게 동작합니다. HikariCP의 `ProxyConnection`이 정확히 이걸 합니다.
- **Leak detection** — borrow 후 N초 이상 release되지 않으면 누가 잡고 있는지 스택을 찍어 경고를 남깁니다.
- **Idle eviction** — DB 측 idle timeout보다 이전에 idle 커넥션을 미리 닫고 새로 만듭니다.
- **메트릭** — active/idle 개수, wait time histogram, borrow 실패 카운트 등.

이걸 다 합치면 HikariCP 한 패키지가 됩니다. **HikariCP가 빠른 이유는 마술이 아니라 이 함정들을 다 손으로 막아둔 것**이라는 사실이 직접 짜본 뒤에야 체감됩니다.

## 회고

직접 짜보고 나서 가장 크게 바뀐 부분은 **풀의 "size" 의미가 더 이상 추상이 아니게 됐다**는 점입니다. `spring.datasource.hikari.maximum-pool-size`라는 값을 볼 때, 그게 단순한 동시 처리량 캡이 아니라 **"DB와 동시에 열어둘 TCP 세션의 상한"**이라는 것, 그리고 **"이 값을 넘어선 요청은 borrow에서 블록되어 응답 시간이 늘어난다는 운영적 의미"**가 같이 보입니다.

테스트가 한 번에 한 가지만 강제하도록 쪼개니, 코드를 잘못 짤 때마다 정확히 어디가 틀렸는지가 그 단계 안에서 답이 났습니다. eager 초기화를 `borrow` 안에 넣은 실수, 헬퍼를 만들고 안 부른 실수, `getConnection`을 두 번 호출한 실수 모두 한 단계 안에서 끝났습니다. 이게 TDD의 진짜 효용입니다. 큰 코드를 한 번에 짜놓고 디버깅하는 것보다, 작은 실패 하나를 그 단계 안에서 처리하는 쪽이 인지 부담이 훨씬 적습니다.

다음으로 이어가고 싶은 주제는 두 가지입니다.

1. **`Connection`을 프록시로 감싸 `close()`를 release로 가로채기** — 동적 프록시(`java.lang.reflect.Proxy`)로 한 단계 더.
2. **HikariCP의 `ConcurrentBag` 분석** — `LinkedBlockingQueue` 대신 더 가벼운 자료구조를 쓰는 이유.

전체 코드는 `playground/connection-pool/`에 그대로 두었습니다.
