# JSONSpecs (Java)

[![CI](https://github.com/catindev/jsonspecs-java/actions/workflows/ci.yml/badge.svg)](https://github.com/catindev/jsonspecs-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ru.jsonspecs/jsonspecs)](https://central.sonatype.com/artifact/ru.jsonspecs/jsonspecs)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://openjdk.org/)

Java-рантайм для [jsonspecs](https://www.npmjs.com/package/jsonspecs): декларативный движок валидационных правил.

Правила описываются в JSON-файлах. Движок компилирует их, запускает на любом payload и возвращает структурированный результат с уровнями `ERROR`, `WARNING` и `EXCEPTION`, полным списком issues и трассировкой выполнения.

**Без runtime-зависимостей.**

```xml
<dependency>
  <groupId>ru.jsonspecs</groupId>
  <artifactId>jsonspecs</artifactId>
  <version>1.2.0</version>
</dependency>
```

## Что дает эта библиотека

- Правила как обычные JSON-артефакты
- Правила композируются в сценарий проверки, компилируются один раз на запуске и каждая проверка выполняется в снэпшоте скомпилированных правил
- Структурированный результат вместо одного `true` и `false`
- Семантика результатов `ERROR`, `WARNING`, `EXCEPTION` и `ABORT`
- Условные блоки для опциональных проверок в зависимости от бизнес-контекста
- Переиспользуемые проверки и сценарии проверок
- Обращение к вложенным полям элементов массива через `[*]`
- Runtime-контекст через `__context`
- Тот же формат JSON-артефактов, что и в Node.js-пакете
- Без runtime-зависимостей

## Как это работает

Правила это отдельные JSON-файлы. Условия определяют, нужно ли запускать тот или иной блок проверок, но самой логики проверок не содержат. Сценарии проверок собирают правила и условия в один бизнес-сценарий.

Движок компилирует артефакты один раз, а потом запускает сценарии проверок на любом payload.

Один и тот же JSON-набор артефактов работает и в Node.js (через `jsonspecs` npm), и в Java (через эту библиотеку). Это значит, что правила можно калибровать на стороне Node.js, а в production выполнять на Java, не меняя сами JSON-файлы.

## Совместимость npm / Java

Один и тот же JSON-набор артефактов (`rule`, `condition`, `pipeline`, `dictionary`) работает на обоих рантаймах без изменений.

Node-версия остаётся источником истины для DSL и runtime semantics. Java-версия — это **Java runtime для JSON DSL**, а не отдельный Java DSL с builders или собственной моделью правил. Java API может немного отличаться ради удобства Java-разработчиков, но входной snapshot и выходная семантика должны совпадать.

### Что уже приведено к parity с Node

- Все типы артефактов: `rule`, `condition`, `pipeline`, `dictionary`
- Все стандартные имена операторов и их семантика
- Структура issue: `level`, `code`, `message`, `field`, `ruleId`, `expected`, `actual`
- Статусы: `OK`, `OK_WITH_WARNINGS`, `ERROR`, `EXCEPTION`, `ABORT`
- Обращение к полям элементов массива через wildcard-пути вроде `items[*].qty`
- Check aggregate modes: `EACH`, `ALL`, `COUNT`, `MIN`, `MAX`
- Predicate aggregate modes: `ANY`, `ALL`, `COUNT`
- `onEmpty` semantics для check и predicate wildcard-агрегации
- Ссылки на `$context.*` через `__context` в payload
- Флаги `matches_regex`, например `"i"`, `"m"`, `"s"`
- Ошибки compile-time валидации: неверный regex, дубликаты кодов, неразрешённые ссылки, циклы DAG, неверные `aggregate`/`required_context`/`dictionary.entries`
- Контракт custom operators через `ctx.get(path)` / `ctx.has(path)` / `ctx.getDictionary(id)` / `ctx.payloadKeys()`

### Что не переносится между рантаймами

- Объекты `CompiledRules` нужно собирать отдельно на каждой стороне
- Кастомные операторы нужно реализовывать отдельно на каждом рантайме
- Java API может отличаться от npm API по форме, если это делает использование библиотеки в Java более естественным

### Версионное соответствие

| Node runtime        | Java runtime             |
| ------------------- | ------------------------ |
| `jsonspecs` `1.1.0` | `jsonspecs-java` `1.2.0` |

Политика простая: Java-линия следует за Node-линией по семантике snapshot и runtime contract. При несовпадении документации и поведения источником истины считается текущая Node-спецификация, а Java-реализация должна быть приведена к ней.

Parity теперь подтверждается не только Java-тестами, но и минимальным автоматическим cross-runtime parity suite: `scripts/parity-check.sh` прогоняет одни и те же golden cases через Node и Java, а затем сравнивает compile-fail и runtime результаты. Suite покрывает ключевые расхождения, исправленные в `1.2.0`, и может быть расширен новыми кейсами в каталоге `parity/`.

## Стабильный публичный API

Под semver в Java-линии сейчас подпадают следующие вещи:

- `Engine`, `CompiledRules`, `CompileOptions`, `RunOptions`
- `PipelineResult`, `Issue`, `TraceEntry`, `CompilationException`
- `OperatorPack`, `CheckOperator`, `PredicateOperator`, `CheckResult`, `PredicateResult`, `OperatorContext`, `DeepGet`
- JSON snapshot format и его runtime semantics, совпадающие с Node-линией

Не считаются публичным API и могут меняться без отдельного notice:

- `ru.jsonspecs.compiler.*`
- внутренняя форма compiled model
- внутренние trace payload детали, если сохраняется общий trace backbone

## Быстрый старт

### Шаг 1: описать правила

Правила это обычные `Map<String, Object>`. Загружайте их из JSON как угодно: через Jackson, Gson, org.json, свой loader или даже как inline-map в тестах.

```java
Map<String, Object> nameRequired = Map.of(
    "id",          "library.person.first_name_required",
    "type",        "rule",
    "description", "Имя должно быть заполнено",
    "role",        "check",
    "operator",    "not_empty",
    "level",       "ERROR",
    "code",        "PERSON.FIRST_NAME.REQUIRED",
    "message",     "Имя обязательно",
    "field",       "person.firstName"
);

Map<String, Object> emailFormat = Map.of(
    "id",          "library.person.email_format",
    "type",        "rule",
    "description", "Email должен содержать @",
    "role",        "check",
    "operator",    "contains",
    "level",       "WARNING",
    "code",        "PERSON.EMAIL.FORMAT",
    "message",     "Похоже, email указан некорректно",
    "field",       "person.email",
    "value",       "@"
);

Map<String, Object> docNotExpired = Map.of(
    "id",          "library.person.doc_not_expired",
    "type",        "rule",
    "description", "Документ не должен быть просрочен",
    "role",        "check",
    "operator",    "field_greater_or_equal_than_field",
    "level",       "EXCEPTION",
    "code",        "PERSON.DOC.EXPIRED",
    "message",     "Срок действия документа истек",
    "field",       "person.document.expireDate",
    "value_field", "$context.currentDate"
);
```

### Шаг 2: собрать пайплайн

```java
Map<String, Object> pipeline = Map.of(
    "id",               "registration.pipeline",
    "type",             "pipeline",
    "description",      "Проверка регистрации человека",
    "entrypoint",       true,
    "strict",           false,
    "required_context", List.of("currentDate"),
    "flow", List.of(
        Map.of("rule", "library.person.first_name_required"),
        Map.of("rule", "library.person.email_format"),
        Map.of("rule", "library.person.doc_not_expired")
    )
);
```

### Шаг 3: скомпилировать и запустить

```java
import ru.jsonspecs.Engine;
import ru.jsonspecs.PipelineResult;
import ru.jsonspecs.operators.OperatorPack;

Engine engine = Engine.create(OperatorPack.standard());

List<Map<String, Object>> artifacts = List.of(
    nameRequired,
    emailFormat,
    docNotExpired,
    pipeline
);

var compiled = engine.compile(artifacts);

Map<String, Object> payload = Map.of(
    "person", Map.of(
        "firstName", "",
        "email",     "not-an-email",
        "document",  Map.of("expireDate", "2099-01-01")
    ),
    "__context", Map.of("currentDate", "2024-01-01")
);

PipelineResult result = engine.runPipeline(compiled, "registration.pipeline", payload);

System.out.println("status:  " + result.getStatus());
System.out.println("control: " + result.getControl());

result.getIssues().forEach(issue ->
    System.out.printf("[%s] %s → %s%n", issue.getLevel(), issue.getCode(), issue.getMessage())
);
```

Ожидаемый вывод:

```text
status:  ERROR
control: STOP
[ERROR] PERSON.FIRST_NAME.REQUIRED → Имя обязательно
[WARNING] PERSON.EMAIL.FORMAT → Похоже, email указан некорректно
```

## Справка по API

### Engine

```java
Engine engine = Engine.create(OperatorPack.standard());
```

#### compile

```java
var compiled = engine.compile(artifacts);
var compiled = engine.compile(artifacts, CompileOptions.withSources(sourceMap));
```

Бросает `CompilationException`, если какой-либо артефакт невалиден. Исключение содержит полный список всех найденных ошибок на всех фазах компиляции, а не только первую.

Один раз скомпилировал потом можно использовать сколько угодно раз. `CompiledRules` immutable, поэтому его можно безопасно переиспользовать между запросами.

#### runPipeline

```java
PipelineResult result = engine.runPipeline(compiled, "my.pipeline", payload);
PipelineResult result = engine.runPipeline(compiled, "my.pipeline", payload, RunOptions.NO_TRACE);
```

Ошибки движка не выбрасываются наружу исключением, а возвращаются как `PipelineResult` со статусом `ABORT`.

Payload может быть:

- вложенным JSON-подобным map
- плоским map с dot-notation

Runtime-контекст передается под ключом `__context` и используется в правилах через `$context.fieldName`.

## Модель результата

```java
result.getStatus();
result.getControl();
result.getIssues();
result.getTrace();
result.getError();
result.isOk();
```

### Статусы

| Статус             | Значение                                                      |
| ------------------ | ------------------------------------------------------------- |
| `OK`               | Все проверки прошли                                           |
| `OK_WITH_WARNINGS` | Проверка прошла, но есть WARNING-issues                       |
| `ERROR`            | Есть один или несколько ERROR-issues; пайплайн дошел до конца |
| `EXCEPTION`        | EXCEPTION-issue остановил пайплайн досрочно                   |
| `ABORT`            | Ошибка движка, а не результат валидации                       |

### Уровни

| Уровень     | Значение                         | Поведение пайплайна                      |
| ----------- | -------------------------------- | ---------------------------------------- |
| `ERROR`     | Ошибка валидации                 | Накапливается; не останавливает пайплайн |
| `WARNING`   | Мягкая проверка / подсказка      | Накапливается; не останавливает пайплайн |
| `EXCEPTION` | Жесткий блок / нельзя продолжать | Немедленно останавливает пайплайн        |

### Поля issue

```java
issue.getLevel();
issue.getCode();
issue.getMessage();
issue.getField();
issue.getRuleId();
issue.getActual();
issue.getExpected();
```

## Загрузка JSON

Движок принимает `List<Map<String, Object>>`. Он не зависит от конкретной JSON-библиотеки.

### Jackson

```java
ObjectMapper mapper = new ObjectMapper();
List<Map<String, Object>> artifacts = Files.walk(rulesDir)
    .filter(p -> p.toString().endsWith(".json"))
    .map(p -> mapper.readValue(p.toFile(), new TypeReference<Map<String, Object>>() {}))
    .toList();
```

### Gson

```java
Gson gson = new Gson();
Map<String, Object> artifact = gson.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
```

## Кастомные операторы

Используйте кастомные операторы, когда встроенного набора недостаточно для твоей предметной области.

### Простой smoke-пример: `is_apple`

```java
import ru.jsonspecs.Engine;
import ru.jsonspecs.PipelineResult;
import ru.jsonspecs.operators.OperatorPack;
import ru.jsonspecs.operators.CheckResult;
import ru.jsonspecs.operators.PredicateResult;
import ru.jsonspecs.util.DeepGet;

import java.util.List;
import java.util.Map;

OperatorPack operators = OperatorPack.standard()
    .withCheck("is_apple", (rule, ctx) -> {
        DeepGet.Result r = ctx.get((String) rule.get("field"));
        if (!r.ok()) return CheckResult.fail();
        String actual = String.valueOf(r.value());
        return "apple".equalsIgnoreCase(actual)
            ? CheckResult.ok()
            : CheckResult.fail(actual);
    })
    .withPredicate("is_apple", (rule, ctx) -> {
        DeepGet.Result r = ctx.get((String) rule.get("field"));
        if (!r.ok()) return PredicateResult.UNDEFINED;
        String actual = String.valueOf(r.value());
        return "apple".equalsIgnoreCase(actual)
            ? PredicateResult.TRUE
            : PredicateResult.FALSE;
    });

Engine engine = Engine.create(operators);

Map<String, Object> rule = Map.of(
    "id",          "library.fruit.must_be_apple",
    "type",        "rule",
    "role",        "check",
    "description", "Fruit must be apple",
    "operator",    "is_apple",
    "field",       "fruit.name",
    "level",       "ERROR",
    "code",        "FRUIT.NOT_APPLE",
    "message",     "Fruit must be apple"
);

Map<String, Object> pipeline = Map.of(
    "id",          "fruit.pipeline",
    "type",        "pipeline",
    "description", "Fruit validation",
    "entrypoint",  true,
    "strict",      false,
    "flow",        List.of(
        Map.of("rule", "library.fruit.must_be_apple")
    )
);

var compiled = engine.compile(List.of(rule, pipeline));

PipelineResult ok = engine.runPipeline(
    compiled,
    "fruit.pipeline",
    Map.of("fruit", Map.of("name", "apple"))
);

PipelineResult fail = engine.runPipeline(
    compiled,
    "fruit.pipeline",
    Map.of("fruit", Map.of("name", "banana"))
);

System.out.println(ok.getStatus());
System.out.println(fail.getStatus());
System.out.println(fail.getIssues().get(0).getCode());
```

Ожидаемый вывод:

```text
OK
ERROR
FRUIT.NOT_APPLE
```

Зарегистрируйте имя оператора в rule-артефакте так же, как любой другой оператор:

```json
{
  "id": "library.fruit.must_be_apple",
  "type": "rule",
  "role": "check",
  "description": "Fruit must be apple",
  "operator": "is_apple",
  "field": "fruit.name",
  "level": "ERROR",
  "code": "FRUIT.NOT_APPLE",
  "message": "Fruit must be apple"
}
```

Старайтесь делать кастомные операторы простыми и детерминированными:

- читать значения из payload
- вычислять условие
- возвращать `CheckResult` или `PredicateResult`

## Поля внутри массивов

Используйте `[*]`, чтобы обращаться к полю в каждом элементе массива:

```json
{
  "operator": "not_empty",
  "field": "items[*].name"
}
```

### Режимы агрегации

```json
{
  "field": "beneficiary.tax.foreignResidencies[*].countryCode",
  "operator": "not_equals",
  "value": "US",
  "aggregate": { "mode": "ALL" }
}
```

| Режим  | Значение                                     |
| ------ | -------------------------------------------- |
| `EACH` | Проверить каждый элемент; собрать все ошибки |
| `ALL`  | Успех только если все элементы совпали       |
| `ANY`  | Успех если совпал хотя бы один элемент       |

## Условные блоки

```json
{
  "id": "library.fl.cond_foreign_block",
  "type": "condition",
  "description": "If foreign trigger, check tax block",
  "when": {
    "any": [
      "library.fl.pred_address_is_foreign",
      "library.fl.pred_doc_is_foreign"
    ]
  },
  "steps": [
    { "rule": "library.fl.foreign_tax_country_required" },
    { "condition": "library.fl.cond_us_block" }
  ]
}
```

`when` поддерживает `all`, `any` и вложенные комбинации.

## Публичный API (stable)

Следующие классы стабильны и покрываются semantic versioning

### Stable

| Класс                  | Пакет                    | Описание                                              |
| ---------------------- | ------------------------ | ----------------------------------------------------- |
| `Engine`               | `ru.jsonspecs`           | Основная точка входа: compile и run                   |
| `CompiledRules`        | `ru.jsonspecs`           | Публичный объект со скомпилированными правилами       |
| `CompilationException` | `ru.jsonspecs`           | Исключение из `compile()` с полным списком ошибок     |
| `PipelineResult`       | `ru.jsonspecs`           | Результат `runPipeline()`                             |
| `Issue`                | `ru.jsonspecs`           | Одна проблема валидации                               |
| `TraceEntry`           | `ru.jsonspecs`           | Одна запись трассировки                               |
| `CompileOptions`       | `ru.jsonspecs`           | Опции для `compile()`                                 |
| `RunOptions`           | `ru.jsonspecs`           | Опции для `runPipeline()`                             |
| `OperatorPack`         | `ru.jsonspecs.operators` | Коллекция операторов; можно расширять                 |
| `CheckOperator`        | `ru.jsonspecs.operators` | Интерфейс кастомных check-операторов                  |
| `PredicateOperator`    | `ru.jsonspecs.operators` | Интерфейс кастомных predicate-операторов              |
| `CheckResult`          | `ru.jsonspecs.operators` | Тип результата check-оператора                        |
| `PredicateResult`      | `ru.jsonspecs.operators` | Тип результата predicate-оператора                    |
| `OperatorContext`      | `ru.jsonspecs.operators` | Контекст, передаваемый операторам во время выполнения |
| `DeepGet`              | `ru.jsonspecs.util`      | Помощник поиска полей для кастомных операторов        |

### Internal (не использовать напрямую)

Следующее implementation details и может измениться без предупреждения:

| Пакет / класс                              | Содержимое                                              |
| ------------------------------------------ | ------------------------------------------------------- |
| `ru.jsonspecs.compiler.*`                  | Фазы компилятора и внутренние compiled-модели           |
| Внутренности рантайма                      | `Runner` и связанная логика выполнения                  |
| `ru.jsonspecs.operators.StandardOperators` | Реализации встроенных операторов                        |
| `ru.jsonspecs.util.*`                      | Внутренние утилиты вроде wildcard expansion и сравнений |

Если вам понадобилось импортировать это напрямую, скорее всего, в публичном API чего-то не хватает. Создайте issue.

## Встроенные операторы

| Оператор                            | Описание                                                            |
| ----------------------------------- | ------------------------------------------------------------------- |
| `not_empty`                         | Поле не должно быть null или пустой строкой                         |
| `is_empty`                          | Поле должно быть null или пустым                                    |
| `equals`                            | Поле равно `value`                                                  |
| `not_equals`                        | Поле не равно `value`                                               |
| `contains`                          | Строковое поле содержит `value`                                     |
| `matches_regex`                     | Поле совпадает с regex `value`; возможны `flags` типа `i`, `m`, `s` |
| `greater_than`                      | Поле > `value` (число или ISO-дата)                                 |
| `less_than`                         | Поле < `value` (число или ISO-дата)                                 |
| `length_equals`                     | Длина строки равна `value`                                          |
| `length_max`                        | Длина строки <= `value`                                             |
| `in_dictionary`                     | Значение поля входит в dictionary `entries`                         |
| `any_filled`                        | Хотя бы одно из `fields[]` не пусто                                 |
| `field_equals_field`                | Два поля равны                                                      |
| `field_not_equals_field`            | Два поля различаются                                                |
| `field_greater_than_field`          | `field` > `value_field`                                             |
| `field_less_than_field`             | `field` < `value_field`                                             |
| `field_greater_or_equal_than_field` | `field` >= `value_field`                                            |
| `field_less_or_equal_than_field`    | `field` <= `value_field`                                            |

Встроенные predicate-операторы доступны для использования в `condition.when`. Точный список смотри в JavaDocs или в исходниках.

## Примеры и тесты

- Примеры quick start из этого README покрываются `ReadmeSmokeTest`
- Дополнительные тесты живут в `EngineTest`
- Для JavaScript-стороны смотри Node.js-пакет с тем же JSON-форматом артефактов

## Требования

- Java 21+
- Без runtime-зависимостей

## Лицензия

MIT

## Parity suite

Минимальный cross-runtime parity suite лежит в каталоге `parity/` и запускается так:

```bash
bash scripts/parity-check.sh
```

Скрипт:

- компилирует Java test harness;
- запускает одни и те же кейсы через Node и Java;
- сравнивает compile-fail кейсы по обязательным подстрокам ошибок;
- сравнивает runtime кейсы по `status`, `control` и нормализованному набору `issues`.

По умолчанию Node-рантайм берётся из `JSONSPECS_NODE_PATH`, если переменная окружения задана. Если нет, скрипт автоматически ставит npm-пакет `jsonspecs@1.1.0` во временный каталог `.parity-node-runtime/`. Для явного пина можно передать `JSONSPECS_NODE_PACKAGE_SPEC`, например:

```bash
JSONSPECS_NODE_PACKAGE_SPEC=jsonspecs@1.1.0 bash scripts/parity-check.sh
```

Чтобы добавить новый кейс, создай подпапку в `parity/runtime/` или `parity/compile-fail/` с `artifacts.json` и соответствующим `expected.json` или `assert.json`.
