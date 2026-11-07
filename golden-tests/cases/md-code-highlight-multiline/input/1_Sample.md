# Multiline

A block comment and a multi-line string, both crossing lines:

```java
/*
 * The whole comment is one colour,
 * including the blank line below.

 */
class Sample {
    String text = """
        line one
        line two
        """;
    int after = 1;
}
```

The block after it must start uncoloured again:

```java
int plain = 0;
```
