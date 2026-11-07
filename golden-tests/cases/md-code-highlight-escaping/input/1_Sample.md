# Escaping

Everything that needs escaping, inside coloured tokens:

```java
// A comment holding </span> and A & B
String markup = "<div class=\"box\">A & B</div>";
boolean ok = 1 < 2 && 3 > 2;
```

Markup inside markup:

```html
<a href="?a=1&b=2" title="A &quot;quoted&quot; title">link</a>
```
