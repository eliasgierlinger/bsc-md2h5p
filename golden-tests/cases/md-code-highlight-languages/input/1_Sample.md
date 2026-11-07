# Languages

Kotlin, by its full name:

```kotlin
// Greets the reader.
fun greet(name: String, times: Int = 2) {
    repeat(times) { println("hello, $name") }
}
```

The same language under an alias, and with an argument after it:

```kt title=Build.kts
val version = 3   /* an inline comment */
```

XML, the only source of the markup token types:

```xml
<?xml version="1.0"?>
<library shelf="new">
  <!-- a comment -->
  <book id="1">Compilers &amp; Interpreters</book>
</library>
```

A shell script, for the variable colour:

```sh
#!/bin/sh
NAME="world"
echo "hello, $NAME"
```

JavaScript under an alias:

```js
const answer = 42;
```
